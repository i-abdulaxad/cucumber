package com.example.authservice.model;

import com.example.authservice.enums.OrganizationRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org_memberships", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "org_id"})
})
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OrgMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OrganizationRole role;

    @Column(nullable = false)
    private Instant createdAt;

}
