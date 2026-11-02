package org.reportservice.support;

import org.reportservice.support.ReportsFixture.User;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Manual run planning in tests (US-3): {@code POST /api/projects/{p}/runs}. */
public final class Plans {

    private Plans() {
    }

    public static String runs(UUID projectId) {
        return "/api/projects/" + projectId + "/runs";
    }

    public static MvcResult plan(MockMvc mockMvc, User caller, UUID projectId, String json) throws Exception {
        return mockMvc.perform(post(runs(projectId)).header(HttpHeaders.AUTHORIZATION, caller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    /** {"name":name,"featureIds":[ids…]} plus an optional extra member text (for example {@code ,"tag":"@smoke"}). */
    public static String body(String name, String extra, UUID... featureIds) {
        StringBuilder ids = new StringBuilder();
        for (UUID id : featureIds) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        return "{\"name\":\"" + name + "\",\"featureIds\":[" + ids + "]" + extra + "}";
    }
}
