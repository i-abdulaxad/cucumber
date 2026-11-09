package org.reportservice.dto.response;

import java.util.UUID;

/**
 * A run's build (D11): branch, commit and CI URL as the run that created it named them.
 */
public record BuildResponse(UUID id, String name, String branch, String commitSha, String ciUrl) {
}
