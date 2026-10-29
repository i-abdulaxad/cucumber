package org.reportservice.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reportservice.support.AdjustableClock;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database pattern item 11: one UTC clock with microsecond ticks; tests get the adjustable clock in the shared
 * context.
 */
@ReportServiceIntegrationTest
class ServiceClockTest {

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("Item 11: the production clock is UTC and ticks in microseconds")
    void theProductionClockIsUtcWithMicroseconds() {
        Clock production = new ClockConfiguration().clock();

        assertThat(production.getZone()).isEqualTo(ZoneOffset.UTC);
        for (int i = 0; i < 50; i++) {
            assertThat(production.instant().getNano() % 1000).isZero();
        }
    }

    @Test
    @DisplayName("D14: the shared test context injects the adjustable clock, which tests set and reset")
    void theTestContextUsesTheAdjustableClock() {
        assertThat(clock).isInstanceOf(AdjustableClock.class);
        AdjustableClock adjustable = (AdjustableClock) clock;
        try {
            adjustable.set(Instant.parse("2026-10-14T09:30:00.123456Z"));
            assertThat(Instant.now(clock)).isEqualTo(Instant.parse("2026-10-14T09:30:00.123456Z"));
        } finally {
            adjustable.reset();
        }
        assertThat(Instant.now(clock).getNano() % 1000).isZero();
    }
}
