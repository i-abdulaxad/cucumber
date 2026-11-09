-- V6 (phase-4 D8 items 8 and 9, E73): every foreign-key column leads some index, and every foreign key restricts
-- deletes explicitly.
-- Already covered: org_memberships.user_id and project_memberships.user_id lead their unique constraints (V1), and
-- projects.org_id leads uk_projects_org_id_name_lower (V5).
-- No operation hard-deletes users, organizations or projects; memberships and invitations are deleted directly and
-- the test fixtures delete children first. A cascade would silently remove history once audit exists, so a deletion
-- must be designed with the feature that introduces it.
-- audit_log keeps its four foreign keys unchanged: V7 drops the table (E74).

CREATE INDEX ix_org_memberships_org_id ON org_memberships (org_id);
CREATE INDEX ix_project_memberships_project_id ON project_memberships (project_id);
CREATE INDEX ix_invitations_org_id_email ON invitations (org_id, email);
CREATE INDEX ix_invitations_invited_by ON invitations (invited_by);

ALTER TABLE org_memberships
DROP
CONSTRAINT fk_org_memberships_user_id,
    DROP
CONSTRAINT fk_org_memberships_org_id,
    ADD CONSTRAINT fk_org_memberships_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE
RESTRICT,
    ADD CONSTRAINT fk_org_memberships_org_id FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE
RESTRICT;

ALTER TABLE projects
DROP
CONSTRAINT fk_projects_org_id,
    ADD CONSTRAINT fk_projects_org_id FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE
RESTRICT;

ALTER TABLE project_memberships
DROP
CONSTRAINT fk_project_memberships_user_id,
    DROP
CONSTRAINT fk_project_memberships_project_id,
    ADD CONSTRAINT fk_project_memberships_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE
RESTRICT,
    ADD CONSTRAINT fk_project_memberships_project_id FOREIGN KEY (project_id) REFERENCES projects (id)
        ON DELETE
RESTRICT;

ALTER TABLE invitations
DROP
CONSTRAINT fk_invitations_org_id,
    DROP
CONSTRAINT fk_invitations_invited_by,
    ADD CONSTRAINT fk_invitations_org_id FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE
RESTRICT,
    ADD CONSTRAINT fk_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE
RESTRICT;
