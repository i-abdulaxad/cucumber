package org.reportservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Where a run executed (ERD {@code environments}, D11): matched by exact name, created on first use. */
@Entity
@Table(name = "environments")
@Getter
@Setter
@NoArgsConstructor
public class Environment extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
