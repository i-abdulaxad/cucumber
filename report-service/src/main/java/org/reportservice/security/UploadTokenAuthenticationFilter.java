package org.reportservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.reportservice.model.UploadToken;
import org.reportservice.repository.UploadTokenRepository;
import org.reportservice.service.impl.UploadTokenCodec;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The upload token authenticator of the CI chain (D4, D5): the only place that looks tokens up. {@code
 * Authorization: Bearer <token>} (scheme in any letter case) with a well-formed token ({@code rct_} plus 43 base64url
 * characters) is hashed with {@link UploadTokenCodec#hash} and looked up by hash; an active token (not revoked, no
 * expiry or one in the future) authenticates the request as its {@link UploadTokenPrincipal}, and its id goes into the
 * logging MDC as {@code tokenId} (D17). Anything else leaves the request anonymous, so the chain answers 401
 * UPLOAD_TOKEN_INVALID. The upload re-reads the token under the project lock (AC-2.14). Created by the security chain,
 * never a servlet filter of its own; it never logs the header, the token, its prefix or its hash.
 */
public class UploadTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "tokenId";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern TOKEN_FORMAT = Pattern.compile("rct_[A-Za-z0-9_-]{43}");

    private final UploadTokenRepository tokens;
    private final UploadTokenCodec codec;
    private final Clock clock;

    public UploadTokenAuthenticationFilter(UploadTokenRepository tokens, UploadTokenCodec codec, Clock clock) {
        this.tokens = tokens;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UploadToken> token = presented(request.getHeader(HttpHeaders.AUTHORIZATION))
                .flatMap(text -> tokens.findByTokenHash(codec.hash(text)))
                .filter(this::isActive);
        if (token.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        UploadToken found = token.get();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UploadTokenPrincipal(found.getId(), found.getProjectId()), null, List.of()));
        MDC.put(MDC_KEY, found.getId().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private boolean isActive(UploadToken token) {
        return token.getRevokedAt() == null
                && (token.getExpiresAt() == null || token.getExpiresAt().isAfter(Instant.now(clock)));
    }

    private static Optional<String> presented(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length());
        return TOKEN_FORMAT.matcher(token).matches() ? Optional.of(token) : Optional.empty();
    }
}
