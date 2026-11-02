package org.reportservice.dto.response;

import java.util.UUID;

/** A run's environment (D11). */
public record EnvironmentResponse(UUID id, String name) {
}
