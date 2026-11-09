-- V3 (phase-4 D8 items 1, 2 and 4, E70): NOT NULL on every business column the application always sets, column
-- lengths that match the API limits (PostgreSQL counts characters), and a positive seat limit.
-- Stay nullable on purpose: users.password_hash (accounts without a password later), organizations.seat_limit (null
-- means unlimited) and projects.archived_at (null means active).
-- Assumption: no existing row has a null in these columns or a value longer than the new length. Every database starts
-- empty (spec A1), and the application has always set these values within these limits.

ALTER TABLE users
ALTER
COLUMN email TYPE varchar(254),
    ALTER
COLUMN full_name TYPE varchar(100),
    ALTER
COLUMN full_name SET NOT NULL,
    ALTER
COLUMN status SET NOT NULL,
    ALTER
COLUMN created_at SET NOT NULL,
    ALTER
COLUMN updated_at SET NOT NULL;

ALTER TABLE organizations
ALTER
COLUMN name TYPE varchar(100),
    ALTER
COLUMN name SET NOT NULL,
    ALTER
COLUMN plan SET NOT NULL,
    ALTER
COLUMN created_at SET NOT NULL,
    ADD CONSTRAINT ck_organizations_seat_limit_positive CHECK (seat_limit IS NULL OR seat_limit > 0);

ALTER TABLE org_memberships
    ALTER COLUMN role SET NOT NULL,
ALTER
COLUMN created_at SET NOT NULL;

ALTER TABLE projects
ALTER
COLUMN name TYPE varchar(100),
    ALTER
COLUMN name SET NOT NULL,
    ALTER
COLUMN created_at SET NOT NULL;

ALTER TABLE project_memberships
    ALTER COLUMN role SET NOT NULL,
ALTER
COLUMN created_at SET NOT NULL;

ALTER TABLE invitations
ALTER
COLUMN email TYPE varchar(254),
    ALTER
COLUMN email SET NOT NULL,
    ALTER
COLUMN role SET NOT NULL,
    ALTER
COLUMN token SET NOT NULL,
    ALTER
COLUMN status SET NOT NULL,
    ALTER
COLUMN expires_at SET NOT NULL,
    ALTER
COLUMN created_at SET NOT NULL;
