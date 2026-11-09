package com.example.authservice.configuration;

import com.example.authservice.support.AdjustableClock;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectCalls;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.TimeZone;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-4 AC-4.2 and AC-4.3 (phase 4, T5, D7): auth-service reads the current time only from the service clock, so the
 * JVM's time zone changes nothing and an invitation expires exactly 7 days after the clock's creation time. The clock
 * is replaced by an {@link AdjustableClock}, so this class has its own context.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class ServiceClockTest {

    private static final String ACCEPT_BODY = "{\"password\":\"Secret123!\",\"fullName\":\"Fay Lowe\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private Projects projects;
    private ProjectCalls calls;
    private TimeZone defaultZone;

    @BeforeEach
    void seedProjects() {
        defaultZone = TimeZone.getDefault();
        projects = fixture.seed();
        calls = new ProjectCalls(mockMvc);
    }

    @AfterEach
    void restoreClockAndZone() {
        clock.reset();
        TimeZone.setDefault(defaultZone);
    }

    @ParameterizedTest(name = "AC-4.2 JVM time zone {0}")
    @ValueSource(strings = {"UTC", "America/New_York", "Asia/Tashkent"})
    @Tag("AC-4.2")
    void theServersTimeZoneChangesNothing(String zone) throws Exception {
        // Given
        Instant now = Instant.parse("2026-10-14T09:30:00.123456Z");
        clock.set(now);
        calls.login("ana");
        TimeZone.setDefault(TimeZone.getTimeZone(zone));

        // When
        MvcResult result = calls.as("ana", post("/api/orgs"), "{\"name\":\"Zone Org QA\"}");

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.createdAt"))
                .isEqualTo("2026-10-14T09:30:00.123456Z");
        OffsetDateTime stored = jdbc.queryForObject(
                "SELECT created_at FROM organizations WHERE name = 'Zone Org QA'", OffsetDateTime.class);
        assertThat(stored.toInstant()).isEqualTo(now);
    }

    @ParameterizedTest(name = "AC-4.3 accepted at {0}: {1}")
    @CsvSource({"2026-10-21T08:59:59.999999Z, 201", "2026-10-21T09:00:00Z, 410"})
    @Tag("AC-4.3")
    void anInvitationExpiresExactlySevenDaysAfterItWasCreated(String now, int status) throws Exception {
        // Given
        clock.set(Instant.parse("2026-10-14T09:00:00Z"));
        MvcResult invited = calls.as("ben", post("/api/orgs/" + projects.acme().orgId() + "/invitations"),
                "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        String token = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
        clock.set(Instant.parse(now));

        // When
        String accept = "/api/auth/invitations/" + token + "/accept";
        MvcResult result = mockMvc.perform(post(accept).contentType(MediaType.APPLICATION_JSON).content(ACCEPT_BODY))
                .andReturn();

        // Then
        if (status == 201) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            return;
        }
        assertProblem(result, 410, "INVITATION_EXPIRED", accept);
    }

    @TestConfiguration
    static class ServiceClockConfiguration {

        /**
         * Replaces the service {@code Clock} bean by type.
         */
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }
}
