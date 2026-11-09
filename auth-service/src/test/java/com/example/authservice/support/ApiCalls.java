package com.example.authservice.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Small helpers for calls the scenarios repeat, such as obtaining {token of X} by logging in.
 */
public final class ApiCalls {

    private ApiCalls() {
    }

    /**
     * {token of X}: the accessToken returned by POST /api/auth/login for the given email and the fixture password.
     */
    public static String tokenOf(MockMvc mockMvc, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, AcmeFixture.PASSWORD)))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("Login of " + email + " failed with status " + result.getResponse().getStatus());
        }
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
