package com.example.authservice.configuration;

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
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Locale;
import java.util.Map;

import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-9.13 (E58): under {@code bootRun}, devtools turns {@code spring.mvc.log-resolved-exception} on, and the MVC
 * exception resolver would then log WARN "Resolved [...EmailAlreadyExistsException: ... &lt;email&gt;]".
 * <p>
 * The setting is forced the way devtools applies it: {@code DevToolsPropertyDefaultsPostProcessor} adds a
 * {@code MapPropertySource} named "devtools" with {@code addLast}, so it ranks below {@code application.yaml}. A
 * {@code @TestPropertySource} would rank above {@code application.yaml} and hide the fix.
 */
@AuthServiceIntegrationTest
@ContextConfiguration(initializers = ResolvedExceptionLoggingTest.DevToolsPropertyDefaults.class)
@ExtendWith(OutputCaptureExtension.class)
class ResolvedExceptionLoggingTest {

    private static final String EMAIL = "dev-dup@example.com";
    private static final String REGISTER_BODY =
            "{\"email\":\"" + EMAIL + "\",\"password\":\"Secret123!\",\"fullName\":\"Dev Dup\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private ConfigurableEnvironment environment;

    @BeforeEach
    void cleanDatabase() {
        fixture.reset();
    }

    @Test
    @Tag("AC-9.13")
    @DisplayName("AC-9.13 A duplicate registration doesn't log the email when resolved exceptions are logged")
    void aDuplicateRegistrationDoesNotLogTheEmail(CapturedOutput output) throws Exception {
        // Given
        assertThat(environment.getPropertySources().stream().reduce((first, second) -> second))
                .as("the devtools defaults are the last property source, as under bootRun")
                .map(PropertySource::getName).contains("devtools");
        assertThat(register().getResponse().getStatus()).isEqualTo(201);
        int start = output.getAll().length();

        // When
        MvcResult result = register();

        // Then
        assertProblem(result, 409, "EMAIL_ALREADY_REGISTERED", "/api/auth/register");
        assertThat(output.getAll().substring(start).toLowerCase(Locale.ROOT)).doesNotContain(EMAIL);
    }

    private MvcResult register() throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY))
                .andReturn();
    }

    /**
     * Adds devtools' web default exactly as devtools does: lowest precedence, below {@code application.yaml}.
     */
    static class DevToolsPropertyDefaults implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addLast(
                    new MapPropertySource("devtools", Map.of("spring.mvc.log-resolved-exception", "true")));
        }
    }
}
