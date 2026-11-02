package org.scenarioservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;

/**
 * frontend spec SE1 (G2, D12): {@code parseErrors[].column} counts Unicode code points, 1-based. Each line has an
 * error after non-ASCII text: a tag with whitespace ({@code @x y}), which the parser reports at the tag's column.
 * Before it come an accented letter (one UTF-16 unit, two UTF-8 bytes), an emoji outside the BMP (one code point, two
 * UTF-16 units, four UTF-8 bytes) and a combining mark (two code points, one user-perceived character), so code
 * points, UTF-16 units, UTF-8 bytes and user-perceived characters each give a different set of columns.
 */
@ScenarioServiceIntegrationTest
class ParseErrorColumnTest {

    private static final String ACCENT = "@caf\u00e9 ";
    private static final String EMOJI = "@\uD83D\uDCB3 ";
    private static final String COMBINING = "@e\u0301te ";
    private static final String BAD_TAG = "@x y";
    private static final String TAG_ERROR = "A tag may not contain whitespace";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @BeforeEach
    void reset() {
        data.reset();
        AccessCheckStub.get().reset();
    }

    @Test
    @Tag("SE1")
    @DisplayName("SE1 parseErrors column counts code points on lines with accents, emoji and combining marks")
    void columnsCountCodePoints() throws Exception {
        String source = ACCENT + BAD_TAG + "\n"
                + EMOJI + BAD_TAG + "\n"
                + COMBINING + BAD_TAG + "\n"
                + "Feature: Payment\n";

        MvcResult result = create(mockMvc, GUS, CHECKOUT, fields("path", "features/se1.feature", "source", source));

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        // The parser also reports each tag line at column 1; the tag errors are the ones with a column inside the line
        List<Map<String, Object>> all = JsonPath.read(body(result), "$.parseErrors[*]");
        List<Map<String, Object>> errors = all.stream()
                .filter(error -> String.valueOf(error.get("message")).contains(TAG_ERROR)).toList();
        assertThat(errors).extracting(error -> error.get("line"), error -> error.get("column"))
                .containsExactly(
                        tuple(1, 7),
                        tuple(2, 4),
                        tuple(3, 7));
        // The same columns from each prefix's code point count; UTF-16 units and UTF-8 bytes would differ
        List<String> prefixes = List.of(ACCENT, EMOJI, COMBINING);
        for (int i = 0; i < prefixes.size(); i++) {
            String prefix = prefixes.get(i);
            int column = (Integer) errors.get(i).get("column");
            assertThat(column).isEqualTo(prefix.codePointCount(0, prefix.length()) + 1);
            assertThat(column).as("UTF-8 byte column").isNotEqualTo(
                    prefix.getBytes(StandardCharsets.UTF_8).length + 1);
        }
        assertThat((Integer) errors.get(1).get("column")).as("UTF-16 column of the emoji line")
                .isNotEqualTo(EMOJI.length() + 1);
        assertThat((Integer) errors.get(2).get("column")).as("user-perceived column of the combining line")
                .isNotEqualTo(6);
    }
}
