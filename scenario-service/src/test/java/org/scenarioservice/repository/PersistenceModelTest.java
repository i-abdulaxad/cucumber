package org.scenarioservice.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.scenarioservice.enums.ScenarioKind;
import org.scenarioservice.enums.StepArgumentType;
import org.scenarioservice.enums.StepKeyword;
import org.scenarioservice.enums.TagTargetType;
import org.scenarioservice.model.ExampleRow;
import org.scenarioservice.model.Examples;
import org.scenarioservice.model.Feature;
import org.scenarioservice.model.FeatureVersion;
import org.scenarioservice.model.Rule;
import org.scenarioservice.model.Scenario;
import org.scenarioservice.model.Step;
import org.scenarioservice.model.Tag;
import org.scenarioservice.model.TagAssignment;
import org.scenarioservice.support.AdjustableClock;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7: every entity round-trips through V1 with {@code ddl-auto: validate}: {@code jsonb} columns (Hibernate 7 with
 * the classpath's Jackson), {@code timestamptz} as {@code Instant} equal to the clock's microsecond value, enums as
 * strings, {@code text} keywords (A10: the 53-code-point {@code en-tx} Examples keyword). Each test runs in a
 * transaction that is rolled back, and reads back from the database after clearing the persistence context. The
 * transaction is a {@code TransactionTemplate}: a class-level {@code @Transactional} gave the class its own context.
 */
@ScenarioServiceIntegrationTest
class PersistenceModelTest {

    /**
     * The longest keyword of gherkin 28's dialects (en-tx Examples), 53 code points.
     */
    private static final String EN_TX_EXAMPLES = "Now that's a story longer than a cattle drive in July";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private FeatureRepository features;

    @Autowired
    private FeatureVersionRepository versions;

    @Autowired
    private RuleRepository rules;

    @Autowired
    private ScenarioRepository scenarios;

    @Autowired
    private StepRepository steps;

    @Autowired
    private ExamplesRepository examples;

    @Autowired
    private ExampleRowRepository exampleRows;

    @Autowired
    private TagRepository tags;

    @Autowired
    private TagAssignmentRepository tagAssignments;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void resetTheClock() {
        clock.reset();
    }

    @Test
    @DisplayName("T7: every table round-trips, with the clock's microsecond instants")
    void everyTableRoundTrips() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            roundTripEveryTable();
        });
    }

    private void roundTripEveryTable() {
        clock.set(Instant.parse("2026-10-14T09:30:00.123456Z"));
        Instant now = Instant.now(clock);
        UUID projectId = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Feature feature = features.save(Feature.builder().id(UUID.randomUUID()).projectId(projectId)
                .path("features/checkout/payment.feature").name("Payment").description("  Customers pay.")
                .language("en-tx").currentVersion(1).createdBy(user).updatedBy(user).createdAt(now).updatedAt(now)
                .build());
        FeatureVersion version = versions.save(FeatureVersion.builder().id(UUID.randomUUID())
                .featureId(feature.getId()).version(1).source("Feature: Payment\r\n").contentHash("ab".repeat(32))
                .message(null).createdBy(user).createdAt(now).build());
        Rule rule = rules.save(Rule.builder().id(UUID.randomUUID()).featureId(feature.getId()).keywordText("Rule")
                .name("Vouchers").description("").position(2).line(19).createdAt(now).updatedAt(now).build());
        Scenario outline = scenarios.save(Scenario.builder().id(UUID.randomUUID()).featureId(feature.getId())
                .ruleId(rule.getId()).kind(ScenarioKind.SCENARIO_OUTLINE).keywordText("Scenario Outline")
                .name("Pay with a voucher").description("").position(0).line(21).createdAt(now).updatedAt(now)
                .build());
        Step step = steps.save(Step.builder().id(UUID.randomUUID()).scenarioId(outline.getId()).position(0)
                .keyword(StepKeyword.GIVEN).keywordText("Given").stepText("a basket with these items:")
                .argumentType(StepArgumentType.DATA_TABLE)
                .dataTable(List.of(List.of("item", "price"), List.of("Mug", "8.00"))).line(6).createdAt(now)
                .updatedAt(now).build());
        Step docStep = steps.save(Step.builder().id(UUID.randomUUID()).scenarioId(outline.getId()).position(1)
                .keyword(StepKeyword.THEN).keywordText("Then").stepText("the receipt is:")
                .argumentType(StepArgumentType.DOC_STRING).docString("Total: 28.00").docStringMediaType("text")
                .line(14).createdAt(now).updatedAt(now).build());
        Examples block = examples.save(Examples.builder().id(UUID.randomUUID()).scenarioId(outline.getId())
                .keywordText(EN_TX_EXAMPLES).name("Valid vouchers").description("").position(0)
                .header(List.of("code", "result")).line(26).createdAt(now).updatedAt(now).build());
        ExampleRow row = exampleRows.save(ExampleRow.builder().id(UUID.randomUUID()).examplesId(block.getId())
                .position(0).cells(List.of("SAVE10", "confirmed")).line(28).createdAt(now).updatedAt(now).build());
        Tag tag = tags.save(Tag.builder().id(UUID.randomUUID()).projectId(projectId).name("@regression")
                .createdAt(now).updatedAt(now).build());
        TagAssignment assignment = tagAssignments.save(TagAssignment.builder().id(UUID.randomUUID())
                .tagId(tag.getId()).featureId(feature.getId()).targetType(TagTargetType.EXAMPLES)
                .targetId(block.getId()).createdAt(now).build());
        entityManager.flush();
        entityManager.clear();

        Feature readFeature = features.findById(feature.getId()).orElseThrow();
        assertThat(readFeature.getCreatedAt()).isEqualTo(now);
        assertThat(readFeature.getUpdatedAt()).isEqualTo(now);
        assertThat(readFeature.getArchivedAt()).isNull();
        assertThat(readFeature.getLanguage()).isEqualTo("en-tx");
        assertThat(readFeature.getDescription()).isEqualTo("  Customers pay.");
        assertThat(versions.findById(version.getId()).orElseThrow()).satisfies(read -> {
            assertThat(read.getSource()).isEqualTo("Feature: Payment\r\n");
            assertThat(read.getMessage()).isNull();
            assertThat(read.getCreatedAt()).isEqualTo(now);
        });
        assertThat(rules.findById(rule.getId()).orElseThrow().getLine()).isEqualTo(19);
        assertThat(scenarios.findById(outline.getId()).orElseThrow()).satisfies(read -> {
            assertThat(read.getKind()).isEqualTo(ScenarioKind.SCENARIO_OUTLINE);
            assertThat(read.getRuleId()).isEqualTo(rule.getId());
        });
        assertThat(steps.findById(step.getId()).orElseThrow()).satisfies(read -> {
            assertThat(read.getKeyword()).isEqualTo(StepKeyword.GIVEN);
            assertThat(read.getArgumentType()).isEqualTo(StepArgumentType.DATA_TABLE);
            assertThat(read.getDataTable()).containsExactly(List.of("item", "price"), List.of("Mug", "8.00"));
            assertThat(read.getDocString()).isNull();
        });
        assertThat(steps.findById(docStep.getId()).orElseThrow()).satisfies(read -> {
            assertThat(read.getDataTable()).isNull();
            assertThat(read.getDocString()).isEqualTo("Total: 28.00");
            assertThat(read.getDocStringMediaType()).isEqualTo("text");
        });
        assertThat(examples.findById(block.getId()).orElseThrow()).satisfies(read -> {
            assertThat(read.getKeywordText()).isEqualTo(EN_TX_EXAMPLES).hasSize(53);
            assertThat(read.getHeader()).containsExactly("code", "result");
        });
        assertThat(exampleRows.findById(row.getId()).orElseThrow().getCells()).containsExactly("SAVE10", "confirmed");
        assertThat(tags.findById(tag.getId()).orElseThrow().getName()).isEqualTo("@regression");
        assertThat(tagAssignments.findById(assignment.getId()).orElseThrow()).satisfies(read -> {
            assertThat(read.getTargetType()).isEqualTo(TagTargetType.EXAMPLES);
            assertThat(read.getCreatedAt()).isEqualTo(now);
        });
        String jsonType = (String) entityManager.createNativeQuery(
                        "SELECT jsonb_typeof(data_table) FROM steps WHERE id = :id").setParameter("id", step.getId())
                .getSingleResult();
        assertThat(jsonType).isEqualTo("array");
    }

    @Test
    @DisplayName("P16: entities never print user content")
    void entitiesDoNotPrintContent() {
        Feature feature = Feature.builder().id(UUID.randomUUID()).path("features/secret-QA.feature")
                .name("Secret QA").description("text QA").build();
        FeatureVersion version = FeatureVersion.builder().source("Feature: Secret QA").message("msg QA").build();
        Step step = Step.builder().stepText("step QA").docString("doc QA").dataTable(List.of(List.of("cell QA")))
                .build();

        assertThat(feature + " " + version + " " + step).doesNotContain("QA");
    }
}
