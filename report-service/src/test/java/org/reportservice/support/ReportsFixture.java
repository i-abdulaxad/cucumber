package org.reportservice.support;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The spec's Reports fixture (glossary): users with fixed ids, the projects Checkout and Billing, and each user's
 * effective role, as the auth-service stub answers C5. Checkout: Ana and Cai ADMIN, Gus MEMBER, Hal TESTER, Dan
 * READER; Ivy and Eve no access. Billing: Ana ADMIN. A test can archive a project ({@link #archive}) and must
 * {@link #reset()} it afterwards.
 */
public final class ReportsFixture {

    public static final UUID ACME = UUID.fromString("a0000000-0000-4000-8000-00000000ac3e");
    public static final UUID CHECKOUT = UUID.fromString("c0000000-0000-4000-8000-0000000c4ec0");
    public static final UUID BILLING = UUID.fromString("b0000000-0000-4000-8000-0000000b1110");

    public static final User ANA = new User("ana", UUID.fromString("00000000-0000-4000-8000-0000000000a1"));
    public static final User CAI = new User("cai", UUID.fromString("00000000-0000-4000-8000-0000000000c2"));
    public static final User GUS = new User("gus", UUID.fromString("00000000-0000-4000-8000-0000000000e3"));
    public static final User HAL = new User("hal", UUID.fromString("00000000-0000-4000-8000-0000000000f4"));
    public static final User DAN = new User("dan", UUID.fromString("00000000-0000-4000-8000-0000000000d5"));
    public static final User IVY = new User("ivy", UUID.fromString("00000000-0000-4000-8000-0000000000b6"));
    public static final User EVE = new User("eve", UUID.fromString("00000000-0000-4000-8000-0000000000e7"));

    private static final Map<UUID, Map<UUID, String>> ROLES = Map.of(
            ANA.id(), Map.of(CHECKOUT, "ADMIN", BILLING, "ADMIN"),
            CAI.id(), Map.of(CHECKOUT, "ADMIN"),
            GUS.id(), Map.of(CHECKOUT, "MEMBER"),
            HAL.id(), Map.of(CHECKOUT, "TESTER"),
            DAN.id(), Map.of(CHECKOUT, "READER"),
            IVY.id(), Map.of(),
            EVE.id(), Map.of());

    private static final Set<UUID> ARCHIVED = ConcurrentHashMap.newKeySet();

    private ReportsFixture() {
    }

    public static boolean isKnown(UUID userId) {
        return ROLES.containsKey(userId);
    }

    /** The user's effective role in the project, or empty for "none". */
    public static Optional<String> role(UUID userId, UUID projectId) {
        return Optional.ofNullable(ROLES.getOrDefault(userId, Map.of()).get(projectId));
    }

    public static boolean archived(UUID projectId) {
        return ARCHIVED.contains(projectId);
    }

    /** From now on, C5 answers the project with {@code archived: true}. */
    public static void archive(UUID projectId) {
        ARCHIVED.add(projectId);
    }

    /** No project is archived. */
    public static void reset() {
        ARCHIVED.clear();
    }

    /** A fixture user: {@code name@example.com} and a fixed id. */
    public record User(String name, UUID id) {

        public String email() {
            return name + "@example.com";
        }

        /** {@code {token of X}}. */
        public String token() {
            return TestTokens.valid(id, email());
        }

        public String bearer() {
            return "Bearer " + token();
        }
    }
}
