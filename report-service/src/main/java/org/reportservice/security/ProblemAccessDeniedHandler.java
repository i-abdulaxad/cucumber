package org.reportservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/** Answers a request the security chain denies with the 403 Problem (D13). */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ProblemAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (handlerExceptionResolver.resolveException(request, response, null, accessDeniedException) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
