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

/**
 * The build under test (ERD {@code builds}, D11): matched by exact name; branch, commit and CI URL come from the first
 * run that names it and never change.
 */
@Entity
@Table(name = "builds")
@Getter
@Setter
@NoArgsConstructor
public class Build extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String branch;

    @Column(length = 64)
    private String commitSha;

    @Column(length = 2048)
    private String ciUrl;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
