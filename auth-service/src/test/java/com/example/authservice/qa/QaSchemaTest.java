package com.example.authservice.qa;

import com.example.authservice.support.AuthServiceIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qa ERD check: the schema Flyway's migrations create on PostgreSQL (the same schema in every profile), read from
 * PostgreSQL's catalog, matches the as-built auth_service group of docs/erd/cucumber.dbml: columns with type, length
 * and nullability, enum values (from their check constraints), unique constraints and foreign keys. Expected values
 * are transcribed from the ERD file, in PostgreSQL type names. Flyway's own flyway_schema_history table isn't part of
 * the ERD.
 */
@AuthServiceIntegrationTest
class QaSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("qa ERD: every auth_service column has the documented type, length and nullability")
    void columnsMatchTheErd() {
        Set<String> expected = new TreeSet<>(List.of(
                // users
                "USERS.ID UUID NOT NULL", "USERS.EMAIL CHARACTER VARYING(254) NOT NULL",
                "USERS.PASSWORD_HASH CHARACTER VARYING(255) NULL", "USERS.FULL_NAME CHARACTER VARYING(100) NOT NULL",
                "USERS.STATUS CHARACTER VARYING(32) NOT NULL", "USERS.MFA_ENABLED BOOLEAN NOT NULL",
                "USERS.CREATED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL",
                "USERS.UPDATED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL",
                // organizations
                "ORGANIZATIONS.ID UUID NOT NULL", "ORGANIZATIONS.NAME CHARACTER VARYING(100) NOT NULL",
                "ORGANIZATIONS.PLAN CHARACTER VARYING(32) NOT NULL", "ORGANIZATIONS.SEAT_LIMIT INTEGER NULL",
                "ORGANIZATIONS.CREATED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL",
                // org_memberships
                "ORG_MEMBERSHIPS.ID UUID NOT NULL", "ORG_MEMBERSHIPS.USER_ID UUID NOT NULL",
                "ORG_MEMBERSHIPS.ORG_ID UUID NOT NULL", "ORG_MEMBERSHIPS.ROLE CHARACTER VARYING(32) NOT NULL",
                "ORG_MEMBERSHIPS.CREATED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL",
                // projects
                "PROJECTS.ID UUID NOT NULL", "PROJECTS.ORG_ID UUID NOT NULL", "PROJECTS.NAME CHARACTER VARYING(100) NOT NULL",
                "PROJECTS.CREATED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL",
                "PROJECTS.ARCHIVED_AT TIMESTAMP(6) WITH TIME ZONE NULL",
                // project_memberships
                "PROJECT_MEMBERSHIPS.ID UUID NOT NULL", "PROJECT_MEMBERSHIPS.USER_ID UUID NOT NULL",
                "PROJECT_MEMBERSHIPS.PROJECT_ID UUID NOT NULL", "PROJECT_MEMBERSHIPS.ROLE CHARACTER VARYING(32) NOT NULL",
                "PROJECT_MEMBERSHIPS.CREATED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL",
                // invitations
                "INVITATIONS.ID UUID NOT NULL", "INVITATIONS.EMAIL CHARACTER VARYING(254) NOT NULL",
                "INVITATIONS.ORG_ID UUID NOT NULL",
                "INVITATIONS.ROLE CHARACTER VARYING(32) NOT NULL", "INVITATIONS.INVITED_BY UUID NOT NULL",
                "INVITATIONS.TOKEN CHARACTER VARYING(255) NOT NULL", "INVITATIONS.STATUS CHARACTER VARYING(32) NOT NULL",
                "INVITATIONS.EXPIRES_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL",
                "INVITATIONS.CREATED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL"));

        Set<String> actual = new TreeSet<>(jdbc.query("""
                SELECT upper(table_name) AS table_name, upper(column_name) AS column_name,
                       upper(data_type) AS data_type, is_nullable, character_maximum_length, datetime_precision
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'""", (rs, n) -> {
            String type = rs.getString("data_type");
            if (type.equals("CHARACTER VARYING")) {
                type += "(" + rs.getLong("character_maximum_length") + ")";
            } else if (type.startsWith("TIMESTAMP ")) {
                type = type.replaceFirst("TIMESTAMP", "TIMESTAMP(" + rs.getInt("datetime_precision") + ")");
            }
            return rs.getString("table_name") + "." + rs.getString("column_name") + " " + type + " "
                    + (rs.getString("is_nullable").equals("YES") ? "NULL" : "NOT NULL");
        }));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("qa ERD: the documented enums have exactly the documented values")
    void enumValuesMatchTheErd() {
        Set<String> actual = new TreeSet<>(jdbc.query("""
                        SELECT upper(t.relname), upper(a.attname), v.value[1]
                        FROM pg_constraint c
                        JOIN pg_class t ON t.oid = c.conrelid
                        JOIN pg_namespace s ON s.oid = t.relnamespace
                        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                        CROSS JOIN LATERAL regexp_matches(pg_get_constraintdef(c.oid), '''([^'']+)''', 'g') AS v(value)
                        WHERE s.nspname = 'public' AND c.contype = 'c' AND cardinality(c.conkey) = 1""",
                (rs, n) -> rs.getString(1) + "." + rs.getString(2) + "=" + rs.getString(3)));

        assertThat(actual).containsExactlyInAnyOrder(
                "USERS.STATUS=ACTIVE", "USERS.STATUS=SUSPENDED", "USERS.STATUS=PENDING_VERIFICATION",
                "ORGANIZATIONS.PLAN=FREE", "ORGANIZATIONS.PLAN=TEAM", "ORGANIZATIONS.PLAN=ENTERPRISE",
                "ORG_MEMBERSHIPS.ROLE=OWNER", "ORG_MEMBERSHIPS.ROLE=ADMIN", "ORG_MEMBERSHIPS.ROLE=MEMBER",
                "ORG_MEMBERSHIPS.ROLE=READER",
                "PROJECT_MEMBERSHIPS.ROLE=ADMIN", "PROJECT_MEMBERSHIPS.ROLE=MEMBER", "PROJECT_MEMBERSHIPS.ROLE=TESTER",
                "PROJECT_MEMBERSHIPS.ROLE=READER",
                "INVITATIONS.STATUS=PENDING", "INVITATIONS.STATUS=ACCEPTED", "INVITATIONS.STATUS=REVOKED", "INVITATIONS.STATUS=EXPIRED",
                "INVITATIONS.ROLE=OWNER", "INVITATIONS.ROLE=ADMIN", "INVITATIONS.ROLE=MEMBER", "INVITATIONS.ROLE=READER");
    }

    @Test
    @DisplayName("qa ERD: unique constraints and foreign keys are exactly the documented ones")
    void constraintsMatchTheErd() {
        // Each unique index as TABLE(key 1,key 2,...), keys in index order and as PostgreSQL prints them, so an
        // expression key such as lower(name COLLATE "und-x-icu") is checked in full, not just its plain columns.
        Set<String> uniques = new TreeSet<>(jdbc.query("""
                SELECT upper(t.relname), string_agg(pg_get_indexdef(i.indexrelid, k, true), ',' ORDER BY k)
                FROM pg_index i
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace s ON s.oid = t.relnamespace
                CROSS JOIN LATERAL generate_series(1, i.indnkeyatts) AS k
                WHERE s.nspname = 'public' AND i.indisunique AND NOT i.indisprimary AND i.indpred IS NULL
                  AND t.relname <> 'flyway_schema_history'
                GROUP BY t.relname, i.indexrelid""", (rs, n) -> rs.getString(1) + "(" + rs.getString(2) + ")"));
        Set<String> foreignKeys = new TreeSet<>(jdbc.query("""
                        SELECT upper(k1.table_name), upper(k1.column_name), upper(k2.table_name), upper(k2.column_name)
                        FROM information_schema.referential_constraints rc
                        JOIN information_schema.key_column_usage k1
                          ON k1.constraint_name = rc.constraint_name AND k1.constraint_schema = rc.constraint_schema
                        JOIN information_schema.key_column_usage k2
                          ON k2.constraint_name = rc.unique_constraint_name
                         AND k2.constraint_schema = rc.unique_constraint_schema
                        WHERE rc.constraint_schema = 'public'""",
                (rs, n) -> rs.getString(1) + "." + rs.getString(2) + ">" + rs.getString(3) + "." + rs.getString(4)));

        assertThat(uniques).containsExactlyInAnyOrder("USERS(email)", "INVITATIONS(token)",
                "ORG_MEMBERSHIPS(user_id,org_id)", "PROJECT_MEMBERSHIPS(user_id,project_id)",
                "PROJECTS(org_id,lower(name::text COLLATE \"und-x-icu\"))");
        assertThat(foreignKeys).containsExactlyInAnyOrder(
                "ORG_MEMBERSHIPS.USER_ID>USERS.ID", "ORG_MEMBERSHIPS.ORG_ID>ORGANIZATIONS.ID",
                "PROJECTS.ORG_ID>ORGANIZATIONS.ID", "PROJECT_MEMBERSHIPS.USER_ID>USERS.ID",
                "PROJECT_MEMBERSHIPS.PROJECT_ID>PROJECTS.ID", "INVITATIONS.ORG_ID>ORGANIZATIONS.ID",
                "INVITATIONS.INVITED_BY>USERS.ID");
    }
}
