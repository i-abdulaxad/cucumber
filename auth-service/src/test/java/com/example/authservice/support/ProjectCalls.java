package com.example.authservice.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.authservice.support.ApiCalls.bearer;

/**
 * Helpers for the project API tests: paths under {@code {acme-projects}}, calls as a fixture user (each user logs
 * in once per test; not thread-safe, so call {@link #login} before simultaneous calls), JSON bodies and simultaneous
 * calls.
 */
public final class ProjectCalls {

    private final MockMvc mockMvc;
    private final Map<String, String> tokens = new HashMap<>();

    public ProjectCalls(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /**
     * {@code /api/orgs/{orgId}/projects}.
     */
    public static String projects(Object orgId) {
        return "/api/orgs/" + orgId + "/projects";
    }

    /**
     * {@code /api/orgs/{orgId}/projects/{projectId}}.
     */
    public static String project(Object orgId, Object projectId) {
        return projects(orgId) + "/" + projectId;
    }

    /**
     * {@code /api/orgs/{orgId}/projects/{projectId}/members}.
     */
    public static String members(Object orgId, Object projectId) {
        return project(orgId, projectId) + "/members";
    }

    /**
     * {@code /api/orgs/{orgId}/projects/{projectId}/members/{userId}}.
     */
    public static String member(Object orgId, Object projectId, UUID userId) {
        return members(orgId, projectId) + "/" + userId;
    }

    /**
     * {token of X}, where {@code user} is the part of the fixture email before {@code @example.com}.
     */
    public String tokenOf(String user) throws Exception {
        String token = tokens.get(user);
        if (token == null) {
            token = ApiCalls.tokenOf(mockMvc, user + "@example.com");
            tokens.put(user, token);
        }
        return token;
    }

    /**
     * Performs the request as the given fixture user.
     */
    public MvcResult as(String user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(user)))).andReturn();
    }

    /**
     * Performs the request as the given fixture user, with a JSON body.
     */
    public MvcResult as(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        return as(user, request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * Performs the request with an already issued token.
     */
    public MvcResult withToken(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn();
    }

    /**
     * Runs the calls at the same moment and returns their results in submission order.
     */
    public static List<MvcResult> simultaneously(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Pre-issues the tokens of these users, so simultaneous calls don't race to log in.
     */
    public void login(String... users) throws Exception {
        for (String user : users) {
            tokenOf(user);
        }
    }
}
