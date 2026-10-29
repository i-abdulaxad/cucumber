package org.reportservice.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * D14 (as scenario-service): writes in one project are serialised by a transaction-scoped advisory lock, and the
 * connection's {@code lock_timeout} (1 s in tests) bounds the wait: a wait beyond it fails as a pessimistic-locking
 * failure.
 */
@ReportServiceIntegrationTest
class ProjectLockTest {

    @Autowired
    private TestRunRepository runs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("D14: a second write in the project waits for the first transaction to end")
    void theLockSerialisesTwoTransactions() throws Exception {
        UUID projectId = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction().executeWithoutResult(s -> {
            runs.lockProject(projectId);
            locked.countDown();
            sleep(400);
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        long start = System.nanoTime();

        transaction().executeWithoutResult(status -> runs.lockProject(projectId));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofMillis(250));
        holder.get(5, TimeUnit.SECONDS);
        // Another project's lock is independent
        long other = System.nanoTime();
        transaction().executeWithoutResult(status -> runs.lockProject(UUID.randomUUID()));
        assertThat(Duration.ofNanos(System.nanoTime() - other)).isLessThan(Duration.ofMillis(250));
    }

    @Test
    @DisplayName("D14: lock_timeout bounds the advisory lock wait, so a wait beyond 1 s fails in about 1 s")
    void lockTimeoutBoundsTheAdvisoryLock() throws Exception {
        UUID projectId = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction().executeWithoutResult(s -> {
            runs.lockProject(projectId);
            locked.countDown();
            await(release);
        }));
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();

            Throwable failure = catchThrowable(() -> transaction()
                    .executeWithoutResult(status -> runs.lockProject(projectId)));

            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(waited).isGreaterThan(Duration.ofMillis(900)).isLessThan(Duration.ofMillis(2500));
        } finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
