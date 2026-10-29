package org.reportservice.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.reportservice.exception.Problems;
import org.slf4j.MDC;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts the request ID into the logging MDC (D17) before the security chains runs, so every line of the request carries
 * it: the valid {@code X-Request-Id} header, or a generated UUID when it is missing or invalid (never the invalid
 * value). The access checker forwards the same value to auth-service (C4). Removed in {@code finally}.
 */
@Component
@Order(RequestIdMdcFilter.ORDER)
public class RequestIdMdcFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";

    static final int ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        MDC.put(MDC_KEY, Problems.requestId(request).orElseGet(() -> UUID.randomUUID().toString()));
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
