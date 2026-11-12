package com.tgyozalyan.channelmanager.err;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;

public class ValidationError {
    public String field, message, detailedMessage;

    public ValidationError(String field, String detailedMessage, String message) {
        this.detailedMessage = detailedMessage;
        this.field = field;
        this.message = message;
    }

    @JsonCreator
    public ValidationError() {}

    public static ValidationError error(String field, String message) {
        return error(field, message, message);
    }

    public static ValidationError error(String field, String message, String detailedMessage) {
        return new ValidationError(field, message, detailedMessage);
    }

    public ValidationError(FieldError fieldError) {
        this(null, fieldError.getField(), fieldError.getDefaultMessage());
    }

    public static List<ValidationError> fromHandlerMethodValidationException(HandlerMethodValidationException e) {
        return e.getParameterValidationResults().stream()
                .flatMap(ValidationError::toValidationErrors)
                .toList();
    }

    private static Stream<ValidationError> toValidationErrors(ParameterValidationResult result) {
        return result.getResolvableErrors().stream()
                .map(error -> {
                    String field = error instanceof FieldError fieldError ? toField(fieldError, result) : toField(error, result);

                    return new ValidationError(field, error.getDefaultMessage(), error.getDefaultMessage());
                });
    }

    public static List<ValidationError> parseFieldErrors(Errors errors) {
        if (errors == null || !errors.hasErrors()) {
            return emptyList();
        }

        List<ValidationError> result = new ArrayList<>(errors.getFieldErrors()
                .stream()
                .map(ValidationError::fromFieldError)
                .toList()
        );
        result.addAll(
                errors.getGlobalErrors().stream()
                        .map(e -> new ValidationError(null, e.getDefaultMessage(), e.getDefaultMessage()))
                        .toList()
        );
        return result;
    }

    public static ValidationError fromFieldError(FieldError error) {
        return new ValidationError(error);
    }

    private static String toField(FieldError fieldError, ParameterValidationResult result) {
        String field = "";
        if (result.getContainerIndex() != null) {
            Object container = result.getContainerKey();

            field += container != null ? container.toString() : fieldError.getObjectName();
            field += "[" + result.getContainerIndex() + "]." + fieldError.getField();
        } else {
            field += fieldError.getObjectName();
        }
        return field;
    }

    private static String toField(MessageSourceResolvable error, ParameterValidationResult result) {
        String field = (error instanceof ObjectError objectError ?
                objectError.getObjectName() :
                ((MessageSourceResolvable) error.getArguments()[0]).getDefaultMessage());

        if (result.getContainerIndex() != null) {
            field = field + "[" + result.getContainerIndex() + "]";
        }
        return field;
    }
}
