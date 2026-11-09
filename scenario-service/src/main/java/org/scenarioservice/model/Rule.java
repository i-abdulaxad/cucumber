package org.scenarioservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A Gherkin Rule of the current version (ERD {@code rules}); {@code position} is among the Feature's children.
 */
@Entity
@Table(name = "rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rule extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID featureId;

    @Column(nullable = false, columnDefinition = "text")
    private String keywordText;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private int line;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Override
    public String toString() {
        return "Rule[id=" + id + ", position=" + position + "]";
    }
}
