package com.tgyozalyan.channelmanager.err;

import com.tgyozalyan.channelmanager.db.EntityNotFoundException;
import com.tgyozalyan.channelmanager.db.PgErrorParser;
import com.tgyozalyan.channelmanager.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.tgyozalyan.channelmanager.err.ValidationError.error;
import static com.tgyozalyan.channelmanager.err.ValidationError.fromHandlerMethodValidationException;
import static com.tgyozalyan.channelmanager.err.ValidationError.parseFieldErrors;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONTENT_TOO_LARGE;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_ACCEPTABLE;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@ControllerAdvice
@ResponseBody
@ResponseStatus(BAD_REQUEST)
class RestExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RestExceptionHandler.class);

    private static final Map<String, ValidationError> UNIQUE_NAME_CONSTRAINT_MAP = Map.of(
            "uq_labels__board_name", error("name", "A label with this name already exists on this board")
    );

    @ExceptionHandler(SQLException.class)
    ResponseEntity<String> handleDbErrors(SQLException e) {
        LOGGER.warn("PG Error", e);
        String m = e.getMessage();
        if (PgErrorParser.isAuthenticationRequiredException(e))
            return createResponse(UNAUTHORIZED, "Oops. Please log in to see this.");
        if (PgErrorParser.isAccessDeniedException(e)) {
            if (m.contains("not a member of the board"))
                return createResponse(FORBIDDEN, "Oops. You don't have permissions to see this. Ask the board admin to give you an invite link.");
            if (m.contains("don't have permission"))
                return createResponse(FORBIDDEN, PgErrorParser.getDbErrorMessageForUi(e));
        }
        if (PgErrorParser.isEntityNotFoundException(e)) {
            String errorMsg = m.contains("Board")
                    ? "Oops. Resource not found ¯\\_(ツ)_/¯ You're sure the link is correct?"
                    : PgErrorParser.getDbErrorMessageForUi(e);
            return createResponse(NOT_FOUND, errorMsg);
        }
        if (PgErrorParser.isValidationException(e))
            return createResponse(BAD_REQUEST, PgErrorParser.getDbErrorMessageForUi(e));
        if (PgErrorParser.isValidationException(e)) {
            return createResponse(BAD_REQUEST, new ErrorResponse(PgErrorParser.getDbErrorMessageForUi(e)));
        }
        if (PgErrorParser.isUniqueViolation(e)) {
            String constraintName = PgErrorParser.extractConstraintName(e);
            ValidationError error = UNIQUE_NAME_CONSTRAINT_MAP.get(constraintName);
            if (error != null)
                return createResponse(BAD_REQUEST, new ErrorResponse("Unique constraint violation", List.of(error)));
            LOGGER.warn("Unique violation on constraint [{}] that has no error mapping", constraintName);
        }
        return createResponse(INTERNAL_SERVER_ERROR, new ErrorResponse(m));
    }

    /**
     * Since version 6.1, spring throws {@link NoHandlerFoundException} and a response status of
     * {@link HttpStatus#INTERNAL_SERVER_ERROR} if the requested endpoint is not found. This is undesired, as we
     * still intend to indicate this with {@link HttpStatus#NOT_FOUND}.
     */
    @ExceptionHandler({EntityNotFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<String> handle404(Exception ex, HttpServletRequest request) {
        return createResponse(NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @ResponseStatus(NOT_ACCEPTABLE)
    ResponseEntity<String> handleBadMediaType(HttpMediaTypeNotAcceptableException e, HttpServletRequest request) {
        String acceptHeader = request.getHeader("Accept");
        LOGGER.warn("Unsupported MimeType in Accept header '{}' supplied at URL: {} ", acceptHeader, request.getRequestURL());
        return createResponse(NOT_ACCEPTABLE,
                "Unsupported MimeType in Accept header ["+acceptHeader+"], the supported headers are " +
                e.getHeaders().getAccept());
    }

    @ExceptionHandler({ValidationException.class})
    ResponseEntity<String> handleValidationException(ValidationException ex, HttpServletRequest request) {
        return createResponse(BAD_REQUEST, new ErrorResponse(null, ex.getMessage(), ex.getErrors(), "validation_error"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<String> handleBadRequestBody(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        return switch (e.getCause()) {
            case InputCoercionException ice -> createResponse(BAD_REQUEST, parseInputCoercion(ice));
            case InvalidFormatException ife -> createResponse(BAD_REQUEST, parseInvalidFormatException(ife));
            case MismatchedInputException mie -> createResponse(BAD_REQUEST, parseMismatched(mie));
            case null -> createResponse(BAD_REQUEST, "Request body is missing or malformed");
            default -> createResponse(BAD_REQUEST, Objects.requireNonNullElse(cause.getCause(), e).getMessage());
        };
    }

    //
    // In SpringMVC 6.1 they changed the exception from MethodArgumentNotValidException in order to unify SpringMVC
    // and SpringFlux.
    // https://github.com/spring-projects/spring-framework/issues/30644
    @ExceptionHandler({HandlerMethodValidationException.class})
    ResponseEntity<String> handleBadRequestBody(HandlerMethodValidationException e, HttpServletRequest request) {
        return createResponse(BAD_REQUEST, new ErrorResponse("Validation error", fromHandlerMethodValidationException(e)));
    }

    /**
     * Used to missing request params that are required to be present
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(BAD_REQUEST)
    public ResponseEntity<String> handleBadRequest(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return createResponse(BAD_REQUEST, new ErrorResponse("Invalid Request",
                Collections.singletonList(error(ex.getParameterName(), ex.getMessage()))));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<String> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        Class<?> type = ex.getRequiredType();
        String message = (type == Boolean.class || type == boolean.class)
                ? "must be true or false"
                : "invalid value for type " + (type != null ? type.getSimpleName() : "unknown");
        return createResponse(BAD_REQUEST, new ErrorResponse("Validation error",
                List.of(error(paramName, message))));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<String> handleMissingPart(MissingServletRequestPartException ex) {
        return createResponse(BAD_REQUEST, "Missing required request part: " + ex.getRequestPartName());
    }

    // Seems like some cases still return the old exception
    // https://github.com/spring-projects/spring-framework/issues/31887
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<String> handleBadRequestBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        return createResponse(BAD_REQUEST, new ErrorResponse(null, "Validation error",
                parseFieldErrors(e.getBindingResult()), "validation_error"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<String> handleBadRequestBody(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        // we need to drill two levels to get tomcat exception since spring doesn't contain the max allowed file size
        // for some reason
        // ex -> org.apache.tomcat.util.http.InvalidParameterException -> org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException
        FileSizeLimitExceededException tomcatEx = (FileSizeLimitExceededException) ex.getCause().getCause();

        String errorMessage = "File size limit exceeded. Maximum allowed size is " + tomcatEx.getPermittedSize() / 1000 / 1000 + " MB(s).";
        return createResponse(CONTENT_TOO_LARGE, errorMessage);
    }

    @ExceptionHandler
    @ResponseStatus(NOT_ACCEPTABLE)
    ResponseEntity<String> handleMediaTypeNotSupportedRepresentation(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        LOGGER.warn("Unsupported MediaType '{}' supplied at URL: {} ", ex.getContentType(), request.getRequestURL());
        return createResponse(NOT_ACCEPTABLE, ex.getMessage());
    }

    @ExceptionHandler({Exception.class})
    @ResponseStatus(INTERNAL_SERVER_ERROR)
    ResponseEntity<String> handleAllExceptions(Exception ex, HttpServletRequest request) {
         /*
          <p>At the moment, the only use case where we expect an out of memory error to occur would be during injection uploads
          where we have huge file size (multiple {@code GBs}). This is a complex issue and for the moment we just make a pretty error
          message for UI until we think of a better way to handle those issues. When we are certain we handle files of arbitrary size,
          this handler can be removed.</p>
         */
        if (ex.getCause() instanceof OutOfMemoryError error) {
            LOGGER.error("Server ran out of memory", error);
            return createResponse(INTERNAL_SERVER_ERROR, "We are out of memory, refresh the page. If this happens often, please report.");
        }
        LOGGER.error("", ex);
        return createResponse(INTERNAL_SERVER_ERROR, new ErrorResponse(ex));
    }

    private static ResponseEntity<String> createResponse(HttpStatus status, String errorMsg) {
        return createResponse(status, new ErrorResponse(errorMsg));
    }
    private static ResponseEntity<String> createResponse(HttpStatus status, ErrorResponse resp) {
        return jsonResponseWithHeaders(resp, status);
    }

    private static ResponseEntity<String> jsonResponseWithHeaders(ErrorResponse body, HttpStatus status) {
        HttpHeaders headers = new HttpHeaders();
        // The original Request Mapping may have declared that it produces a text/plain or a binary response,
        // and the client may have requested that type too (using Accept). But if an error happened, we want to
        // force a JSON content type.
        //
        // Note, that for this to be able to override the headers that you set in the XxxApi classes, you should
        // use SpringMVC capabilities like ResponseEntity, and not set headers directly via HttpServletResponse.
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noCache());
        return new ResponseEntity<>(JsonUtil.writeValueAsString(body), headers, status);
    }

    private static String parseInputCoercion(InputCoercionException ice) {
        String fieldName = getFieldName(ice);
        Class<?> type = ice.getTargetType();
        String msg = "Value out of range for field '%s': must be between %d and %d";
        if (type == Byte.TYPE || type == Byte.class) return msg.formatted(fieldName, Byte.MIN_VALUE, Byte.MAX_VALUE);
        if (type == Short.TYPE || type == Short.class) return msg.formatted(fieldName, Short.MIN_VALUE, Short.MAX_VALUE);
        if (type == Integer.TYPE || type == Integer.class) return msg.formatted(fieldName, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (type == Long.TYPE || type == Long.class) return msg.formatted(fieldName, Long.MIN_VALUE, Long.MAX_VALUE);
        return ice.getOriginalMessage();
    }

    private static String parseInvalidFormatException(InvalidFormatException ife) {
        String fieldName = getFieldName(ife);
        Object value = ife.getValue();
        Class<?> targetType = ife.getTargetType();
        if (targetType != null && targetType.isEnum()) {
            String enumValues = extractEnumVals(targetType);
            return "Invalid value '%s' for field '%s': must be one of %s".formatted(value, fieldName, enumValues);
        }
        if (targetType != null && targetType.isArray()) {
            return "Invalid value '%s' type for field '%s': expected an array".formatted(value, fieldName);
        }
        return "Invalid value '%s' for field '%s'".formatted(value, fieldName);
    }

    private static String parseMismatched(MismatchedInputException mie) {
        String fieldName = getFieldName(mie);
        Class<?> targetType = mie.getTargetType();
        if (targetType != null && targetType.isEnum()) {
            String enumValues = extractEnumVals(targetType);
            return "Invalid value for field '%s': must be one of %s".formatted(fieldName, enumValues);
        }
        if (targetType != null && targetType.isArray()) {
            return "Invalid type for field '%s': expected an array".formatted(fieldName);
        }
        return "Invalid type for field '%s'.".formatted(fieldName);
    }

    private static String getFieldName(JacksonException je) {
        List<JacksonException.Reference> path = je.getPath();
        if (path.isEmpty()) {
            return "unknown";
        }
        JacksonException.Reference ref = path.getFirst();
        return (ref.getPropertyName() != null) ? ref.getPropertyName() : "unknown";
    }

    private static String extractEnumVals(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
    }
}