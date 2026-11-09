-- V4 (phase-4 D8 item 3, E71): enum columns are varchar(32) with a named CHECK listing the Java constants, never
-- PostgreSQL ENUM types (ERD-D12). V1 already created each CHECK as ck_<table>_<column>_values, with exactly the values
-- of the Java enums, so this migration only narrows the type. Assumption: every stored value is one of those
-- constants (the CHECKs guarantee it), and the longest, PENDING_VERIFICATION, has 20 characters.

ALTER TABLE users
ALTER
COLUMN status TYPE varchar(32);

ALTER TABLE organizations
ALTER
COLUMN plan TYPE varchar(32);

ALTER TABLE org_memberships
ALTER
COLUMN role TYPE varchar(32);

ALTER TABLE project_memberships
ALTER
COLUMN role TYPE varchar(32);

ALTER TABLE invitations
ALTER
COLUMN role TYPE varchar(32),
    ALTER
COLUMN status TYPE varchar(32);
