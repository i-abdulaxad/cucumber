package com.example.authservice.service;

import com.example.authservice.dto.response.ProjectStatusResponse;

import java.util.UUID;

/** C7, for other services (report-service spec D5): a project's organization and archived state, without a user. */
public interface ProjectStatusService {

    ProjectStatusResponse projectStatus(UUID projectId);
}
