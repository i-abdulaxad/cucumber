package com.example.authservice.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, OrganizationProperties.class, LoggingProperties.class,
        DatabaseProperties.class, InternalServiceProperties.class})
public class PropertiesConfiguration {
}
