package org.reportservice.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.reportservice.security.CallerPrincipal;
import org.slf4j.MDC;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the authenticated caller's user id into the logging MDC (D17), after the security chain has validated the
 * token. Anonymous requests carry none (the pattern prints {@code -}). Removed in {@code finally}.
 */
@Component
@Order(UserIdMdcFilter.ORDER)
public class UserIdMdcFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "userId";

    static final int ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CallerPrincipal caller) {
            MDC.put(MDC_KEY, caller.userId().toString());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
