package org.reportservice.service.impl;

import org.reportservice.dto.response.BuildResponse;
import org.reportservice.dto.response.EnvironmentResponse;
import org.reportservice.model.Build;
import org.reportservice.model.Environment;
import org.reportservice.repository.BuildRepository;
import org.reportservice.repository.EnvironmentRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Environments and builds of manual runs (D11): found by exact, case-sensitive name or created on first use. Call it
 * inside the write transaction, after the project lock. A build created here has no branch, commit or CI URL.
 */
@Component
class RunAttributes {

    private final EnvironmentRepository environments;
    private final BuildRepository builds;

    RunAttributes(EnvironmentRepository environments, BuildRepository builds) {
        this.environments = environments;
        this.builds = builds;
    }

    Environment environment(UUID projectId, String name, Instant now) {
        return environments.findByProjectIdAndName(projectId, name).orElseGet(() -> {
            Environment created = new Environment();
            created.setId(UUID.randomUUID());
            created.setProjectId(projectId);
            created.setName(name);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            return environments.save(created);
        });
    }

    Build build(UUID projectId, String name, Instant now) {
        return builds.findByProjectIdAndName(projectId, name).orElseGet(() -> {
            Build created = new Build();
            created.setId(UUID.randomUUID());
            created.setProjectId(projectId);
            created.setName(name);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            return builds.save(created);
        });
    }

    static EnvironmentResponse response(Environment environment) {
        return environment == null ? null : new EnvironmentResponse(environment.getId(), environment.getName());
    }

    static BuildResponse response(Build build) {
        return build == null ? null : new BuildResponse(build.getId(), build.getName(), build.getBranch(),
                build.getCommitSha(), build.getCiUrl());
    }
}
