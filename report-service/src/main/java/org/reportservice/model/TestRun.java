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
import org.reportservice.enums.TestRunSource;
import org.reportservice.enums.TestRunStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One execution of a set of test cases (ERD {@code test_runs}, D8). References to environments, builds and tokens are
 * plain ids; the foreign keys live in the database.
 */
@Entity
@Table(name = "test_runs")
@Getter
@Setter
@NoArgsConstructor
public class TestRun extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TestRunSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TestRunStatus status;

    private UUID environmentId;

    private UUID buildId;

    @Column(length = 1000)
    private String tagExpression;

    private UUID uploadTokenId;

    private UUID createdBy;

    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
