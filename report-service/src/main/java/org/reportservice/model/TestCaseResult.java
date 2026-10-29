package org.reportservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.reportservice.enums.ExecutionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The result of one test case in one run (ERD {@code test_case_results}, D6): a snapshot of what ran. Automated
 * results are unlinked in phase 1 (feature, version, scenario and example-row ids null). No {@code toString} shows
 * names, tags or the comment.
 */
@Entity
@Table(name = "test_case_results")
@Getter
@Setter
@NoArgsConstructor
public class TestCaseResult extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID testRunId;

    @Column(nullable = false)
    private int position;

    private UUID featureId;

    private Integer featureVersion;

    private UUID scenarioId;

    private UUID exampleRowId;

    @Column(nullable = false, columnDefinition = "text")
    private String featurePath;

    @Column(nullable = false, columnDefinition = "text")
    private String featureName;

    @Column(nullable = false, columnDefinition = "text")
    private String scenarioName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> tags;

    private Integer line;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExecutionStatus status;

    @Column(nullable = false)
    private int attempt;

    private UUID executedBy;

    @Column(columnDefinition = "text")
    private String comment;

    private Instant startedAt;

    private Instant finishedAt;

    private Long durationMs;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
