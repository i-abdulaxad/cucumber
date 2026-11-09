package com.example.authservice.exception;

import com.example.authservice.service.OrganizationService;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AuthServiceIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AC-1.3: an unexpected failure is a 500 Problem without internal details, logged once at ERROR.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class UnexpectedErrorTest {

    private static final String INTERNAL_MESSAGE = "SELECT secret FROM x at db-host:5432";
    /**
     * Spring Boot's console pattern: timestamp, then the level.
     */
    private static final Pattern ERROR_ENTRY = Pattern.compile("(?m)^\\S+\\s+ERROR\\s");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @MockitoBean
    private OrganizationService organizationService;

    @BeforeEach
    void seedAcme() {
        fixture.seed();
    }

    @Test
    @Tag("AC-1.3")
    @DisplayName("AC-1.3 An unexpected failure is a 500 Problem without internal details")
    void anUnexpectedFailureIsA500ProblemWithoutInternalDetails(CapturedOutput output) throws Exception {
        // Given
        String token = tokenOf(mockMvc, "ana@example.com");
        doThrow(new IllegalStateException(INTERNAL_MESSAGE)).when(organizationService).findByUser(any());
        int logLengthBefore = output.getAll().length();

        // When
        MvcResult result = mockMvc.perform(get("/api/orgs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn();

        // Then
        assertProblem(result, 500, "INTERNAL_ERROR", "/api/orgs");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("SELECT", "db-host", "5432", "Exception", "at com.example");
        String requestLog = output.getAll().substring(logLengthBefore);
        assertThat(countErrorEntries(requestLog)).as("ERROR entries for the request").isEqualTo(1);
    }

    private static int countErrorEntries(String log) {
        Matcher matcher = ERROR_ENTRY.matcher(log);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
