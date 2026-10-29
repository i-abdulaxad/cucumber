package org.reportservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Answers an unauthenticated request on a user path with 401 UNAUTHENTICATED and {@code WWW-Authenticate: Bearer}
 * (D5).
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String BEARER_CHALLENGE = "Bearer";

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ProblemAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
        AuthenticationException required =
                new InsufficientAuthenticationException("Authentication is required", authException);
        if (handlerExceptionResolver.resolveException(request, response, null, required) == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
