package org.reportservice.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** The service clock (database pattern item 11): UTC with a microsecond tick, injected wherever "now" is needed. */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000));
    }
}
