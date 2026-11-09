package org.scenarioservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.controller.CreateFeatureTest.parseErrors;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.save;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;

/**
 * AC-1.19 (D21 #1, BUG-1; end to end): only UTF-8 encoding pragmas are accepted.
 */
@ScenarioServiceIntegrationTest
class EncodingPragmaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @BeforeEach
    void reset() {
        data.reset();
        AccessCheckStub.get().reset();
    }

    @ParameterizedTest(name = "AC-1.19 {0}: {1}")
    @CsvSource(delimiter = '|', value = {"# encoding: foo|422", "# encoding: -|422", "# encoding: ISO-8859-1|422",
            "# encoding: UTF-8|201", "# encoding: utf-8|201"})
    @Tag("AC-1.19")
    void onlyUtf8PragmasAreAccepted(String pragma, int status) throws Exception {
        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/e.feature", pragma + "\nFeature: Caf\u00E9\n");

        if (status == 201) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(JsonPath.<String>read(body(result), "$.name")).isEqualTo("Caf\u00E9");
        } else {
            Map<String, Object> problem = assertProblem(result, 422, "GHERKIN_INVALID");
            assertThat(parseErrors(problem)).singleElement().satisfies(error -> {
                assertThat(error).containsEntry("line", 1).containsEntry("column", 1);
                assertThat((String) error.get("message")).isEqualTo("Only UTF-8 encoding is supported");
            });
            assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
        }
    }

    @Test
    @Tag("AC-1.19")
    @DisplayName("AC-1.19 (save) a non-UTF-8 pragma is 422, and the feature is unchanged")
    void aSaveWithANonUtf8Pragma() throws Exception {
        UUID id = created(mockMvc, GUS, CHECKOUT, "features/e.feature", GherkinSources.ADDRESS);

        MvcResult result = save(mockMvc, GUS, CHECKOUT, id, fields("path", "features/e.feature", "source",
                "# encoding: foo\n" + GherkinSources.ADDRESS, "baseVersion", 1));

        assertThat(parseErrors(assertProblem(result, 422, "GHERKIN_INVALID"))).hasSize(1);
        assertThat(data.count("feature_versions")).isEqualTo(1);
    }
}
