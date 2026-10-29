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
import org.reportservice.enums.StepResultKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A step or hook of a test case result, in execution order (ERD {@code step_results}, D9, D10). Written once. No
 * {@code toString} shows step texts, arguments or error messages.
 */
@Entity
@Table(name = "step_results")
@Getter
@Setter
@NoArgsConstructor
public class StepResult extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID testCaseResultId;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StepResultKind kind;

    private UUID stepId;

    @Column(columnDefinition = "text")
    private String keywordText;

    @Column(columnDefinition = "text")
    private String stepText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<List<String>> dataTable;

    @Column(columnDefinition = "text")
    private String docString;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExecutionStatus status;

    private Long durationMs;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
