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
import org.scenarioservice.enums.TagTargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * Which element of a feature's current version carries which tag (ERD {@code tag_assignments}).
 */
@Entity
@Table(name = "tag_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagAssignment extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tagId;

    @Column(nullable = false)
    private UUID featureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TagTargetType targetType;

    @Column(nullable = false)
    private UUID targetId;

    @Column(nullable = false)
    private Instant createdAt;

    @Override
    public String toString() {
        return "TagAssignment[id=" + id + ", targetType=" + targetType + "]";
    }
}
