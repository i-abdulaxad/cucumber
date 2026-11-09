package com.example.authservice.qa;

import com.example.authservice.enums.InvitationStatus;
import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.model.Invitation;
import com.example.authservice.model.Organization;
import com.example.authservice.model.User;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.authservice.support.ApiCalls.bearer;

/**
 * qa helpers shared by the qa test classes. They only build requests, seed rows through repositories and run calls
 * concurrently; they never change the code under test.
 */
final class QaSupport {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private QaSupport() {
    }

    /**
     * A valid HS512 token with the TEST-ONLY shared secret for this user, exp in 1 hour. It is accepted exactly like
     * a login token, because auth-service loads the user and its roles from the database on every request (D3). Used
     * in the large matrices to avoid one BCrypt login per row.
     */
    static String tokenFor(User user) {
        return TestJwt.valid(user.getId(), user.getEmail());
    }

    static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        return user == null ? request : request.header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(user)));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    /**
     * A token signed HS512 with the shared secret over exactly this header and payload JSON.
     */
    static String signedRaw(String headerJson, String payloadJson) {
        try {
            String signingInput = base64Url(headerJson) + "." + base64Url(payloadJson);
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(TestJwt.key());
            return signingInput + "." + BASE64_URL.encodeToString(
                    mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String base64Url(String text) {
        return BASE64_URL.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static Invitation seedInvitation(InvitationRepository repository, Organization organization, User invitedBy,
                                     String email, OrganizationRole role, InvitationStatus status,
                                     Instant expiresAt, String token) {
        return repository.save(Invitation.builder()
                .email(email)
                .organization(organization)
                .role(role)
                .invitedBy(invitedBy)
                .token(token)
                .status(status)
                .createdAt(expiresAt.minus(7, ChronoUnit.DAYS))
                .expiresAt(expiresAt)
                .build());
    }

    static Invitation seedPendingInvitation(InvitationRepository repository, Organization organization,
                                            User invitedBy, String email, OrganizationRole role, String token) {
        return seedInvitation(repository, organization, invitedBy, email, role, InvitationStatus.PENDING,
                Instant.now().plus(7, ChronoUnit.DAYS), token);
    }

    /**
     * Starts every call at the same moment and returns the results in call order.
     */
    @SafeVarargs
    static List<MvcResult> concurrently(Callable<MvcResult>... calls) throws Exception {
        return concurrently(List.of(calls));
    }

    static List<MvcResult> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(executor.submit(() -> {
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
            executor.shutdownNow();
        }
    }

    static List<Integer> statuses(List<MvcResult> results) {
        return results.stream().map(result -> result.getResponse().getStatus()).toList();
    }

    static MockMvcCall call(MockMvc mockMvc, MockHttpServletRequestBuilder request) {
        return () -> mockMvc.perform(request).andReturn();
    }

    /**
     * A MockMvc call that can run on another thread.
     */
    @FunctionalInterface
    interface MockMvcCall extends Callable<MvcResult> {
    }
}
