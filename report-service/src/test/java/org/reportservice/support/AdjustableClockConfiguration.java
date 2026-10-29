package org.reportservice.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Gives every test context the {@link AdjustableClock} as its service clock (database pattern item 11). It lives in
 * test sources under {@code org.reportservice}, so the application's component scan finds it in every test context,
 * and no context configuration changes; {@code @Primary} makes it win over the production {@code Clock} bean.
 */
@Configuration
public class AdjustableClockConfiguration {

    @Bean
    @Primary
    public AdjustableClock adjustableClock() {
        return new AdjustableClock();
    }
}
