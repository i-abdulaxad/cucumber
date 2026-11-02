package org.reportservice.support;

import org.reportservice.support.ReportsFixture.User;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Run operations in tests (US-4, US-5): record, complete, cancel and reads, as a fixture user. */
public final class Runs {

    private Runs() {
    }

    public static MvcResult record(MockMvc mockMvc, User caller, UUID projectId, UUID runId, UUID resultId,
                                   String json) throws Exception {
        return mockMvc.perform(put(Plans.runs(projectId) + "/" + runId + "/results/" + resultId)
                .header(HttpHeaders.AUTHORIZATION, caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    public static MvcResult complete(MockMvc mockMvc, User caller, UUID projectId, UUID runId) throws Exception {
        return mockMvc.perform(post(Plans.runs(projectId) + "/" + runId + "/complete")
                .header(HttpHeaders.AUTHORIZATION, caller.bearer())).andReturn();
    }

    public static MvcResult cancel(MockMvc mockMvc, User caller, UUID projectId, UUID runId) throws Exception {
        return mockMvc.perform(post(Plans.runs(projectId) + "/" + runId + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, caller.bearer())).andReturn();
    }

    /** {@code GET} of a path relative to the project's runs ({@code ""} for the list). */
    public static MvcResult read(MockMvc mockMvc, User caller, UUID projectId, String relative) throws Exception {
        return mockMvc.perform(get(Plans.runs(projectId) + relative)
                .header(HttpHeaders.AUTHORIZATION, caller.bearer())).andReturn();
    }
}
