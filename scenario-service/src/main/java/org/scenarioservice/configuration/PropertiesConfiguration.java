package org.scenarioservice.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the service's typed settings records.
 */
@Configuration
@EnableConfigurationProperties({DatabaseProperties.class, JwtProperties.class, LoggingProperties.class,
        AuthServiceProperties.class})
public class PropertiesConfiguration {
}
