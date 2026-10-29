package com.example.authservice.controller;

import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.example.authservice.support.TestServiceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * report-service spec AC-8.2, last row (D5): in the shared test context, {@code app.internal-service-key} is unset
 * (its default is empty), so C7 answers 401 to everything, even to a well-formed key.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class InternalServiceKeyUnsetTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Test
    @Tag("AC-8.2")
    @DisplayName("AC-8.2 The right key, but auth-service runs with INTERNAL_SERVICE_KEY unset: 401 UNAUTHENTICATED")
    void withTheKeyUnsetNothingIsAnswered() throws Exception {
        Projects projects = fixture.seed();
        String path = "/internal/service/projects/" + projects.checkout();

        MvcResult result = mockMvc.perform(get(path)
                .header(InternalProjectStatusTest.SERVICE_KEY_HEADER, TestServiceKey.KEY)).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED", path);
    }
}
