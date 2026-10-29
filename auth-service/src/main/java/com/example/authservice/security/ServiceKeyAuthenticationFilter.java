package com.example.authservice.security;

import com.example.authservice.configuration.InternalServiceKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates a calling service on {@code /internal/service/**} by its {@code X-Service-Key} header (C7,
 * report-service spec D5). Only the right key authenticates; anything else leaves the request anonymous, so the
 * chain answers 401. Created by the security chain itself, never a bean, so the servlet container doesn't register it
 * a second time. Neither the key nor the header is ever logged.
 */
public class ServiceKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Key";

    static final String SERVICE_PRINCIPAL = "internal-service";

    private final InternalServiceKey serviceKey;

    public ServiceKeyAuthenticationFilter(InternalServiceKey serviceKey) {
        this.serviceKey = serviceKey;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (serviceKey.matches(request.getHeader(HEADER))) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    SERVICE_PRINCIPAL, null, AuthorityUtils.createAuthorityList("ROLE_INTERNAL_SERVICE")));
        }
        filterChain.doFilter(request, response);
    }
}
