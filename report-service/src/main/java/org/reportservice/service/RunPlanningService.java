package org.reportservice.service;

import org.reportservice.dto.request.PlanRunRequest;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.security.CallerPrincipal;

import java.util.UUID;

/**
 * Manual run planning (US-3, D6, D10): a PLANNED run from scenario-service's current test cases.
 */
public interface RunPlanningService {

    TestRunResponse plan(UUID projectId, CallerPrincipal caller, PlanRunRequest request);
}
