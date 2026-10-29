package org.reportservice.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The shared full-context test configuration (D16, standards §5): the whole application with MockMvc, the shared
 * PostgreSQL test database ({@link TestDatabase}, through {@code @ServiceConnection}) and the TEST-ONLY settings
 * ({@link TestSettings}). No profile is active, so no {@code .env} is ever loaded. Tests reuse this one configuration
 * unless they need different properties, so the number of cached contexts stays small.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ImportTestcontainers({TestDatabase.class, TestSettings.class})
public @interface ReportServiceIntegrationTest {
}
