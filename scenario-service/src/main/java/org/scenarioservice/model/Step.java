package org.scenarioservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.scenarioservice.enums.StepArgumentType;
import org.scenarioservice.enums.StepKeyword;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A step of the current version (ERD {@code steps}) with at most one argument (ERD-D6).
 */
@Entity
@Table(name = "steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Step extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID scenarioId;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StepKeyword keyword;

    @Column(nullable = false, columnDefinition = "text")
    private String keywordText;

    @Column(nullable = false, columnDefinition = "text")
    private String stepText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StepArgumentType argumentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<List<String>> dataTable;

    @Column(columnDefinition = "text")
    private String docString;

    @Column(columnDefinition = "text")
    private String docStringMediaType;

    @Column(nullable = false)
    private int line;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Without text or argument (P16).
     */
    @Override
    public String toString() {
        return "Step[id=" + id + ", keyword=" + keyword + ", position=" + position + "]";
    }
}
