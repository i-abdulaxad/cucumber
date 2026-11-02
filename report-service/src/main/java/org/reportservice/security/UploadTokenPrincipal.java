package org.reportservice.security;

import java.util.UUID;

/**
 * The authenticated CI upload (D4, D5): the upload token's id and its project. Never the token, its prefix or hash.
 */
public record UploadTokenPrincipal(UUID tokenId, UUID projectId) {
}
