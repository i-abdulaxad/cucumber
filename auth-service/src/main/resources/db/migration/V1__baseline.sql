-- V1 baseline (phase-4 D4, E63): exactly the schema that the entities at HEAD map to on PostgreSQL, as Hibernate's
-- own PostgreSQL DDL creates it: the same tables, columns, types, nullability, unique constraints, foreign keys (with
-- PostgreSQL's default delete rule) and enum checks. Only the constraint names differ: they're readable (D4 "Names").
-- Every later difference is its own migration (V2 onwards).

CREATE TABLE users
(
    id            uuid         NOT NULL,
    email         varchar(255) NOT NULL,
    password_hash varchar(255),
    full_name     varchar(255),
    status        varchar(255),
    mfa_enabled   boolean      NOT NULL,
    created_at    timestamp(6),
    updated_at    timestamp(6),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_status_values CHECK (status IN ('ACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION'))
);

CREATE TABLE organizations
(
    id         uuid NOT NULL,
    name       varchar(255),
    plan       varchar(255),
    seat_limit integer,
    created_at timestamp(6),
    CONSTRAINT pk_organizations PRIMARY KEY (id),
    CONSTRAINT ck_organizations_plan_values CHECK (plan IN ('FREE', 'TEAM', 'ENTERPRISE'))
);

CREATE TABLE org_memberships
(
    id         uuid NOT NULL,
    user_id    uuid NOT NULL,
    org_id     uuid NOT NULL,
    role       varchar(255),
    created_at timestamp(6),
    CONSTRAINT pk_org_memberships PRIMARY KEY (id),
    CONSTRAINT uk_org_memberships_user_id_org_id UNIQUE (user_id, org_id),
    CONSTRAINT ck_org_memberships_role_values CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'READER')),
    CONSTRAINT fk_org_memberships_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_org_memberships_org_id FOREIGN KEY (org_id) REFERENCES organizations (id)
);

CREATE TABLE projects
(
    id          uuid NOT NULL,
    org_id      uuid NOT NULL,
    name        varchar(255),
    created_at  timestamp(6),
    archived_at timestamp(6),
    CONSTRAINT pk_projects PRIMARY KEY (id),
    CONSTRAINT fk_projects_org_id FOREIGN KEY (org_id) REFERENCES organizations (id)
);

CREATE TABLE project_memberships
(
    id         uuid NOT NULL,
    user_id    uuid NOT NULL,
    project_id uuid NOT NULL,
    role       varchar(255),
    created_at timestamp(6),
    CONSTRAINT pk_project_memberships PRIMARY KEY (id),
    CONSTRAINT uk_project_memberships_user_id_project_id UNIQUE (user_id, project_id),
    CONSTRAINT ck_project_memberships_role_values CHECK (role IN ('ADMIN', 'MEMBER', 'TESTER', 'READER')),
    CONSTRAINT fk_project_memberships_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_project_memberships_project_id FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE invitations
(
    id         uuid NOT NULL,
    email      varchar(255),
    org_id     uuid NOT NULL,
    role       varchar(255),
    invited_by uuid NOT NULL,
    token      varchar(255),
    status     varchar(255),
    expires_at timestamp(6),
    created_at timestamp(6),
    CONSTRAINT pk_invitations PRIMARY KEY (id),
    CONSTRAINT uk_invitations_token UNIQUE (token),
    CONSTRAINT ck_invitations_role_values CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'READER')),
    CONSTRAINT ck_invitations_status_values CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT fk_invitations_org_id FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id)
);

CREATE TABLE audit_log
(
    id             uuid NOT NULL,
    actor_id       uuid NOT NULL,
    action         varchar(255),
    org_id         uuid,
    project_id     uuid,
    target_user_id uuid,
    metadata       json,
    created_at     timestamp(6),
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_actor_id FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT fk_audit_log_org_id FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_audit_log_project_id FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_audit_log_target_user_id FOREIGN KEY (target_user_id) REFERENCES users (id)
);
