package org.reportservice.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D17 (as scenario-service, against Spring Boot 4.1's {@code SecurityFilterProperties}): the request-id filter runs
 * before the security chains, so lines logged inside them carry the request id; the user-id filter runs after them,
 * once the caller is known. {@code aop/RequestCorrelationTest} shows the lines of a request.
 */
class MdcFilterOrderTest {

    @Test
    @DisplayName("D17: request-id filter < security chain < user-id filter")
    void theMdcFiltersSurroundTheSecurityChain() {
        assertThat(RequestIdMdcFilter.ORDER).isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
        assertThat(UserIdMdcFilter.ORDER).isGreaterThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
    }
}
