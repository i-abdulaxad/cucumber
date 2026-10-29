package org.reportservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates a request from its bearer token (D5). The scheme is matched in any letter case. An invalid
 * token leaves the request anonymous, so the entry point answers 401. It never logs the token or a claim value, and it
 * never reads {@code X-User-Id}. It is created by the security chain, not registered as a servlet filter of its own.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenValidator tokenValidator;

    public JwtAuthenticationFilter(JwtTokenValidator tokenValidator) {
        this.tokenValidator = tokenValidator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            authenticate(header.substring(BEARER_PREFIX.length()));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        if (token.isEmpty()) {
            return;
        }
        UUID userId;
        try {
            userId = tokenValidator.userIdOf(token);
        } catch (InvalidTokenException ex) {
            log.debug("Bearer token rejected: {}", ex.getMessage());
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CallerPrincipal(userId, token), null, List.of()));
    }
}
