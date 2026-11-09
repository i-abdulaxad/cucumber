package org.scenarioservice.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4 (as auth-service E30): roles compare by explicit rank, ADMIN 4 > MEMBER 3 > TESTER 2 > READER 1.
 */
class ProjectRoleTest {

    @Test
    @DisplayName("D4: ranks are explicit")
    void ranksAreExplicit() {
        assertThat(ProjectRole.ADMIN.rank()).isEqualTo(4);
        assertThat(ProjectRole.MEMBER.rank()).isEqualTo(3);
        assertThat(ProjectRole.TESTER.rank()).isEqualTo(2);
        assertThat(ProjectRole.READER.rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("D3: a role is at least itself and every lower role, never a higher one")
    void isAtLeastComparesRanks() {
        assertThat(ProjectRole.ADMIN.isAtLeast(ProjectRole.MEMBER)).isTrue();
        assertThat(ProjectRole.MEMBER.isAtLeast(ProjectRole.MEMBER)).isTrue();
        assertThat(ProjectRole.TESTER.isAtLeast(ProjectRole.MEMBER)).isFalse();
        assertThat(ProjectRole.READER.isAtLeast(ProjectRole.READER)).isTrue();
        assertThat(ProjectRole.READER.isAtLeast(ProjectRole.TESTER)).isFalse();
    }
}
