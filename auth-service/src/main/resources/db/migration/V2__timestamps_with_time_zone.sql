-- V2 (phase-4 D6, E69): every timestamp column becomes timestamp(6) with time zone (timestamptz), the type Hibernate
-- maps java.time.Instant to on PostgreSQL. Assumption: existing values were written as UTC wall-clock times, so
-- "AT TIME ZONE 'UTC'" keeps each instant. Every database starts empty (spec A1); only dev databases from Batch A can
-- hold rows, and the user may drop and recreate those.

ALTER TABLE users
ALTER
COLUMN created_at TYPE timestamp(6) with time zone USING created_at AT TIME ZONE 'UTC',
    ALTER
COLUMN updated_at TYPE timestamp(6) with time zone USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE organizations
ALTER
COLUMN created_at TYPE timestamp(6) with time zone USING created_at AT TIME ZONE 'UTC';

ALTER TABLE org_memberships
ALTER
COLUMN created_at TYPE timestamp(6) with time zone USING created_at AT TIME ZONE 'UTC';

ALTER TABLE projects
ALTER
COLUMN created_at TYPE timestamp(6) with time zone USING created_at AT TIME ZONE 'UTC',
    ALTER
COLUMN archived_at TYPE timestamp(6) with time zone USING archived_at AT TIME ZONE 'UTC';

ALTER TABLE project_memberships
ALTER
COLUMN created_at TYPE timestamp(6) with time zone USING created_at AT TIME ZONE 'UTC';

ALTER TABLE invitations
ALTER
COLUMN expires_at TYPE timestamp(6) with time zone USING expires_at AT TIME ZONE 'UTC',
    ALTER
COLUMN created_at TYPE timestamp(6) with time zone USING created_at AT TIME ZONE 'UTC';

ALTER TABLE audit_log
ALTER
COLUMN created_at TYPE timestamp(6) with time zone USING created_at AT TIME ZONE 'UTC';
