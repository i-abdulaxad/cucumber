package org.reportservice.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the service's typed settings records (D15). */
@Configuration
@EnableConfigurationProperties({DatabaseProperties.class, JwtProperties.class, InternalServiceProperties.class,
        AuthServiceProperties.class, ScenarioServiceProperties.class, LoggingProperties.class})
public class PropertiesConfiguration {
}
