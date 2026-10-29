package org.reportservice.service.impl;

import org.reportservice.dto.request.CreateUploadTokenRequest;
import org.reportservice.dto.response.CreatedUploadTokenResponse;
import org.reportservice.dto.response.UploadTokenResponse;
import org.reportservice.exception.InvalidFieldException;
import org.reportservice.exception.UploadTokenLimitException;
import org.reportservice.exception.UploadTokenNotFoundException;
import org.reportservice.model.UploadToken;
import org.reportservice.repository.TestRunRepository;
import org.reportservice.repository.UploadTokenRepository;
import org.reportservice.security.ProjectAccessChecker;
import org.reportservice.security.ProjectRole;
import org.reportservice.service.UploadTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The upload token use cases (US-1, D4, D7, D13, D14). Each one validates what needs the clock first (400 before the
 * access check, A3), then checks access outside any transaction, then writes in one transaction whose first statement
 * takes the project's advisory lock; under the lock it re-reads the token state. The token text exists only in the
 * create answer; only its hash is stored.
 */
@Service
public class UploadTokenServiceImpl implements UploadTokenService {

    static final int MAX_ACTIVE_TOKENS = 10;

    private final UploadTokenRepository tokens;
    private final TestRunRepository runs;
    private final ProjectAccessChecker accessChecker;
    private final UploadTokenCodec codec;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public UploadTokenServiceImpl(UploadTokenRepository tokens, TestRunRepository runs,
                                  ProjectAccessChecker accessChecker, UploadTokenCodec codec, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.runs = runs;
        this.accessChecker = accessChecker;
        this.codec = codec;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public CreatedUploadTokenResponse create(UUID projectId, UUID userId, CreateUploadTokenRequest request) {
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now(clock))) {
            throw new InvalidFieldException("expiresAt", "Expiry must be in the future");
        }
        accessChecker.requireWrite(projectId, ProjectRole.ADMIN);
        String token = codec.newToken();
        UploadToken created = writeTransaction.execute(status -> {
            runs.lockProject(projectId);
            Instant now = Instant.now(clock);
            if (tokens.countActive(projectId, now) >= MAX_ACTIVE_TOKENS) {
                throw new UploadTokenLimitException();
            }
            UploadToken row = new UploadToken();
            row.setId(UUID.randomUUID());
            row.setProjectId(projectId);
            row.setName(request.name());
            row.setTokenHash(codec.hash(token));
            row.setTokenPrefix(codec.prefix(token));
            row.setCreatedBy(userId);
            row.setExpiresAt(request.expiresAt());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            return tokens.save(row);
        });
        return new CreatedUploadTokenResponse(created.getId(), created.getName(), token, created.getTokenPrefix(),
                created.getCreatedBy(), created.getCreatedAt(), created.getExpiresAt(), created.getLastUsedAt(),
                created.getRevokedAt(), created.getRevokedBy());
    }

    @Override
    public List<UploadTokenResponse> list(UUID projectId) {
        accessChecker.requireRead(projectId, ProjectRole.ADMIN);
        return readTransaction.execute(status -> tokens.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(UploadTokenServiceImpl::response).toList());
    }

    @Override
    public UploadTokenResponse revoke(UUID projectId, UUID tokenId, UUID userId) {
        accessChecker.requireRead(projectId, ProjectRole.ADMIN);
        return writeTransaction.execute(status -> {
            runs.lockProject(projectId);
            UploadToken token = tokens.findByIdAndProjectId(tokenId, projectId)
                    .orElseThrow(UploadTokenNotFoundException::new);
            if (token.getRevokedAt() == null) {
                Instant now = Instant.now(clock);
                token.setRevokedAt(now);
                token.setRevokedBy(userId);
                token.setUpdatedAt(now);
            }
            return response(token);
        });
    }

    private static UploadTokenResponse response(UploadToken token) {
        return new UploadTokenResponse(token.getId(), token.getName(), token.getTokenPrefix(), token.getCreatedBy(),
                token.getCreatedAt(), token.getExpiresAt(), token.getLastUsedAt(), token.getRevokedAt(),
                token.getRevokedBy());
    }
}
