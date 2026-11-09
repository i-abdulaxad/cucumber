package org.scenarioservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * TEST-ONLY use case behind {@code controller/ProbeController} (kept after T8; see there). It is a {@code @Service}
 * in {@code service}, so the logging aspect advises it like a real use case.
 */
@Service
public class ProbeService {

    private static final Logger log = LoggerFactory.getLogger(ProbeService.class);

    /**
     * Fails the way the named database failure would reach the advice from a real use case.
     */
    public void fail(String kind) {
        switch (kind) {
            case "unique" -> throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"uk_features_project_id_path_lower\" "
                            + "for features/secret-path-QA.feature");
            case "lock" -> throw new CannotAcquireLockException("canceling statement due to lock timeout QA");
            case "deadlock" -> throw new PessimisticLockingFailureException("deadlock detected QA");
            default -> throw new IllegalArgumentException("unknown probe failure");
        }
    }

    /**
     * A transactional use case that logs when its transaction commits, to show where the aspect sits (T4).
     */
    @Transactional
    public boolean transactional() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Probe transaction committed");
            }
        });
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
