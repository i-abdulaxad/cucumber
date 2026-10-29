package com.example.authservice.service.impl;

import com.example.authservice.dto.response.ProjectStatusResponse;
import com.example.authservice.exception.ProjectNotFoundException;
import com.example.authservice.model.Project;
import com.example.authservice.repository.ProjectRepository;
import com.example.authservice.service.ProjectStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectStatusServiceImpl implements ProjectStatusService {

    private final ProjectRepository projectRepository;

    @Override
    @Transactional(readOnly = true)
    public ProjectStatusResponse projectStatus(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow(ProjectNotFoundException::new);
        return new ProjectStatusResponse(project.getId(), project.getOrganization().getId(),
                project.getArchivedAt() != null);
    }
}
