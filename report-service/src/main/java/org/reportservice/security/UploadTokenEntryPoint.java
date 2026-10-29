package org.reportservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.reportservice.exception.UploadTokenInvalidException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Answers an unauthenticated request on {@code /api/ci/**} (D5): 401 UPLOAD_TOKEN_INVALID, rendered by the advice
 * with {@code WWW-Authenticate: Bearer}. A user's JWT is no credential there, so it gets the same answer.
 */
@Component
public class UploadTokenEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public UploadTokenEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (handlerExceptionResolver.resolveException(request, response, null,
                new UploadTokenInvalidException()) == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
