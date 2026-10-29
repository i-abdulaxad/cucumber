package com.example.authservice.dto.response;

import java.util.UUID;

/** C7's answer (report-service spec D5): whether a project exists in which organization, and whether it's archived. */
public record ProjectStatusResponse(UUID projectId, UUID orgId, boolean archived) {
}
