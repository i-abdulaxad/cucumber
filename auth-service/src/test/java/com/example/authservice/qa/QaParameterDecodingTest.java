package com.example.authservice.qa;

import com.example.authservice.aop.RequestLog;
import com.example.authservice.support.HttpCalls;
import com.example.authservice.support.HttpCalls.Answer;
import com.example.authservice.support.RunningAuthService;
import com.example.authservice.support.TestServiceKey;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qa, platform-hardening FU-3 / T9 (E17's condition): malformed query percent-encoding on every auth-service route
 * family, on a running server (only Tomcat decodes parameters). Each row must give the endpoint's normal answer, or
 * 400 MALFORMED_REQUEST; never a 500. No captured line may contain the marker, an ERROR level or a stack trace.
 */
@ExtendWith(OutputCaptureExtension.class)
class QaParameterDecodingTest {

    private static final String MARKER = "QaLeakFu3";
    private static final String PASSWORD = "Secret123!";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static RunningAuthService server;
    private static HttpCalls http;
    private static String ana;
    private static String org;
    private static String project;

    @BeforeAll
    static void start() throws Exception {
        server = RunningAuthService.start("--app.internal-service-key=" + TestServiceKey.KEY);
        http = server.http();
        assertThat(http.send("POST", "/api/auth/register", registration("ana.qa9@example.com")).status())
                .isEqualTo(201);
        Answer login = http.send("POST", "/api/auth/login", login("ana.qa9@example.com"));
        ana = "Bearer " + JsonPath.read(login.body(), "$.accessToken");
        Answer created = http.send("POST", "/api/orgs", "{\"name\":\"QA9 Org\"}", "Authorization", ana);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        org = JsonPath.read(created.body(), "$.id");
        Answer createdProject = http.send("POST", "/api/orgs/" + org + "/projects", "{\"name\":\"QA9\"}",
                "Authorization", ana);
        assertThat(createdProject.status()).as(createdProject.body()).isEqualTo(201);
        project = JsonPath.read(createdProject.body(), "$.id");
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest(name = "FU-3 qa: {0} {1}{2} [{3}] -> {4} or 400, never 500")
    @CsvSource(delimiter = '|', value = {
            "POST   | /api/auth/login                              | ?x=QaLeakFu3%FF        | login    | 200",
            "POST   | /api/auth/register                           | ?x=QaLeakFu3%           | register | 201",
            "POST   | /api/auth/invitations/QaLeakFu3tok/accept   | ?x=QaLeakFu3%G1         | accept   | 404",
            "GET    | /api/orgs                                    | ?QaLeakFu3%FF=1         | jwt      | 200",
            "GET    | /api/orgs                                    | ?x=QaLeakFu3%FF         | none     | 401",
            "POST   | /api/orgs                                    | ?x=QaLeakFu3%C0%AF      | org      | 201",
            "GET    | /api/orgs/{org}                              | ?x=QaLeakFu3%F          | jwt      | 200",
            "GET    | /api/orgs/{org}/members                      | ?x=QaLeakFu3%ED%A0%80   | jwt      | 200",
            "POST   | /api/orgs/{org}/invitations                  | ?x=QaLeakFu3%E0%A4      | invite   | 201",
            "GET    | /api/orgs/{org}/invitations                  | ?x=QaLeakFu3%FF         | jwt      | 200",
            "GET    | /api/orgs/{org}/projects                     | ?x=QaLeakFu3%FF         | jwt      | 200",
            "POST   | /api/orgs/{org}/projects                     | ?x=QaLeakFu3%FF         | project  | 201",
            "GET    | /api/orgs/{org}/projects/{project}           | ?x=QaLeakFu3%FF         | jwt      | 200",
            "GET    | /api/orgs/{org}/projects/{project}/members   | ?x=QaLeakFu3%FF         | jwt      | 200",
            "GET    | /api/orgs/00000000-0000-4000-8000-000000000000 | ?x=QaLeakFu3%FF       | jwt      | 403",
            "GET    | /internal/projects/{project}/access          | ?x=QaLeakFu3%FF         | jwt      | 200",
            "GET    | /internal/service/projects/{project}         | ?x=QaLeakFu3%FF         | key      | 200",
            "GET    | /internal/service/projects/{project}         | ?x=QaLeakFu3%FF         | none     | 401",
            "GET    | /internal/service/projects/{project}         | ?x=QaLeakFu3%FF         | jwt      | 401"})
    @Tag("T9")
    void neverA500(String method, String path, String query, String kind, int normal, CapturedOutput output)
            throws Exception {
        String target = path.replace("{org}", org).replace("{project}", project) + query;
        int n = SEQUENCE.incrementAndGet();
        String body = switch (kind) {
            case "login" -> login("ana.qa9@example.com");
            case "register" -> registration("reg" + n + ".qa9@example.com");
            case "accept" -> "{\"password\":\"" + PASSWORD + "\",\"fullName\":\"Q A\"}";
            case "org" -> "{\"name\":\"QA9 Org " + n + "\"}";
            case "invite" -> "{\"email\":\"inv" + n + ".qa9@example.com\",\"role\":\"MEMBER\"}";
            case "project" -> "{\"name\":\"QA9 P" + n + "\"}";
            default -> null;
        };
        List<String> headers = new ArrayList<>();
        switch (kind) {
            case "jwt", "org", "invite", "project" -> headers.addAll(List.of("Authorization", ana));
            case "key" -> headers.addAll(List.of("X-Service-Key", TestServiceKey.KEY));
            default -> {
            }
        }
        int logStart = output.getAll().length();

        int status;
        String responseBody;
        if (query.matches(".*%(?![0-9A-Fa-f]{2}).*")) {
            // A broken escape: the JDK client refuses to send it, so it goes over a raw socket as written
            String[] raw = raw(method, target, body, headers);
            status = Integer.parseInt(raw[0]);
            responseBody = raw[1];
        } else {
            Answer answer = http.send(method, target, body, headers.toArray(String[]::new));
            status = answer.status();
            responseBody = answer.body();
            if (status == 400) {
                answer.assertProblem(400, "MALFORMED_REQUEST");
            }
        }

        assertThat(status).as(target + " " + responseBody).isNotEqualTo(500).isIn(normal, 400);
        assertThat(responseBody).doesNotContain(MARKER + "%").doesNotContain("Character decoding")
                .doesNotContain("Exception");
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.count("ERROR")).as("ERROR lines").isZero();
        assertThat(log.hasStackTraceLine()).as("a stack trace line").isFalse();
        assertThat(log.lines()).noneMatch(line -> line.contains(MARKER));
    }

    /**
     * Sends the request line exactly as written; returns {status, body}.
     */
    private static String[] raw(String method, String target, String body, List<String> headers) throws Exception {
        int port = Integer.parseInt(server.bean(org.springframework.core.env.Environment.class)
                .getProperty("local.server.port"));
        byte[] content = body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + content.length
                + "\r\n");
        for (int i = 0; i < headers.size(); i += 2) {
            request.append(headers.get(i)).append(": ").append(headers.get(i + 1)).append("\r\n");
        }
        request.append("\r\n");
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(request.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(content);
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String status = response.substring(9, 12);
            int bodyStart = response.indexOf("\r\n\r\n");
            return new String[]{status, bodyStart < 0 ? "" : response.substring(bodyStart + 4)};
        }
    }

    private static String registration(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"QA Nine\"}";
    }

    private static String login(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }
}
