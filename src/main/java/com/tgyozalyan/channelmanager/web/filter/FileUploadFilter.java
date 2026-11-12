package com.tgyozalyan.channelmanager.web.filter;

import com.tgyozalyan.channelmanager.err.ErrorResponse;
import com.tgyozalyan.channelmanager.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Translates Tomcat's multipart parse failures into JSON ErrorResponses.
 * Spring's {@link org.springframework.web.bind.annotation.ControllerAdvice} can't see these because Tomcat raises
 * them inside the servlet container's multipart parser, before/around the dispatcher servlet — so the default
 * Tomcat HTML error page is returned otherwise. Handles two cases:
 * <ul>
 *   <li>Total request size exceeded ({@link SizeLimitExceededException} wrapped in {@link InvalidParameterException})
 *       → 413 with a human-readable MB limit. Per-file size violations are unaffected; they still flow through
 *       Spring's {@link org.springframework.web.multipart.MaxUploadSizeExceededException} handler.</li>
 *   <li>Missing multipart body ({@link FileUploadException} as direct cause) → 400.</li>
 * </ul>
 */
public class FileUploadFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileUploadFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | InvalidParameterException ex) {
            if (ex.getCause() instanceof InvalidParameterException paramEx) {
                if (paramEx.getCause() instanceof SizeLimitExceededException sizeEx) {
                    writeSizeLimitJsonError((HttpServletResponse) response, sizeEx);
                    return;
                }
            }
            if (ex.getCause() instanceof FileUploadException fileUploadEx) {
                writeMultipartNotFoundJsonError((HttpServletResponse) response, fileUploadEx);
                return;
            }
            throw ex;
        }
    }

    private void writeSizeLimitJsonError(HttpServletResponse response, SizeLimitExceededException sizeEx) throws IOException {
        if (response.isCommitted()) {
            LOGGER.warn("Can't write JSON error for request size violation - response already committed", sizeEx);
            return;
        }
        response.reset();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        long permittedMb = sizeEx.getPermittedSize() / 1000 / 1000;
        String message = "Request size limit exceeded. Maximum allowed size is " + permittedMb + " MB(s).";
        response.getWriter().write(JsonUtil.writeValueAsString(new ErrorResponse(message)));
    }

    private void writeMultipartNotFoundJsonError(HttpServletResponse response, FileUploadException fileUploadEx) throws IOException {
        if (response.isCommitted()) {
            LOGGER.warn("Can't write JSON error for multipart not found - response already committed", fileUploadEx);
            return;
        }
        response.reset();
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = "Multipart attachment not found.";
        response.getWriter().write(JsonUtil.writeValueAsString(new ErrorResponse(message)));
    }
}
