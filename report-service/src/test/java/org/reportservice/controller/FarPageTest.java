package org.reportservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.RequestLog;
import org.reportservice.support.RunData;
import org.reportservice.support.Runs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.body;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;

/**
 * T16 (RE13, QA-3; AC-5.1, AC-5.3): a page far beyond the data, even one whose offset exceeds the int range, is an
 * empty page: 200 with {@code items} {@code []}, the requested {@code page} and {@code size}, the real
 * {@code totalItems}, and no ERROR line.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class FarPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetData() {
        AuthServiceStub.get().reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
    }

    @ParameterizedTest(name = "RE13 GET {0}: 200, empty page {1}/{2}, totalItems {3}")
    @CsvSource({
            "'?page=21474837&size=100',                    21474837,   100, 2",
            "'?page=2147483647',                           2147483647, 20,  2",
            "'/{run}/results?page=2147483647&size=500',    2147483647, 500, 3",
            "'/{run}/results?page=4294968&size=500',       4294968,    500, 3",
            "'?page=21474836&size=100',                    21474836,   100, 2"})
    @Tag("AC-5.1")
    @Tag("AC-5.3")
    void aFarPageIsAnEmptyPage(String relative, int page, int size, long total, CapturedOutput output)
            throws Exception {
        RunData.Run run = RunData.manual(jdbc, CHECKOUT, "PLANNED", 3);
        RunData.manual(jdbc, CHECKOUT, "PLANNED", 1);
        int logStart = output.getAll().length();

        MvcResult result = Runs.read(mockMvc, DAN, CHECKOUT, relative.replace("{run}", run.id().toString()));

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(body(result), "$.items")).isEmpty();
        assertThat((Integer) JsonPath.read(body(result), "$.page")).isEqualTo(page);
        assertThat((Integer) JsonPath.read(body(result), "$.size")).isEqualTo(size);
        assertThat(((Number) JsonPath.read(body(result), "$.totalItems")).longValue()).isEqualTo(total);
        assertThat(RequestLog.since(output, logStart).count("ERROR")).isZero();
    }
}
