package org.scenarioservice.support;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The spec's Scenario fixture (glossary): users with fixed ids and emails, the projects Checkout, Billing and Legacy
 * (archived) of the organization Acme, and each user's effective role per project, as the access check stub answers.
 * "none" is absent from the table: the stub answers 403.
 */
public final class ScenarioFixture {

    public static final UUID ACME = UUID.fromString("a0000000-0000-4000-8000-00000000ac3e");
    public static final UUID CHECKOUT = UUID.fromString("c0000000-0000-4000-8000-0000000c4ec0");
    public static final UUID BILLING = UUID.fromString("b0000000-0000-4000-8000-0000000b1110");
    public static final UUID LEGACY = UUID.fromString("10000000-0000-4000-8000-00000001e9ac");

    public static final User ANA = new User("ana", UUID.fromString("00000000-0000-4000-8000-0000000000a1"));
    public static final User CAI = new User("cai", UUID.fromString("00000000-0000-4000-8000-0000000000c2"));
    public static final User GUS = new User("gus", UUID.fromString("00000000-0000-4000-8000-0000000000e3"));
    public static final User HAL = new User("hal", UUID.fromString("00000000-0000-4000-8000-0000000000f4"));
    public static final User DAN = new User("dan", UUID.fromString("00000000-0000-4000-8000-0000000000d5"));
    public static final User IVY = new User("ivy", UUID.fromString("00000000-0000-4000-8000-0000000000b6"));
    public static final User EVE = new User("eve", UUID.fromString("00000000-0000-4000-8000-0000000000e7"));

    private static final Map<UUID, Map<UUID, String>> ROLES = Map.of(
            ANA.id(), Map.of(CHECKOUT, "ADMIN", BILLING, "ADMIN", LEGACY, "ADMIN"),
            CAI.id(), Map.of(CHECKOUT, "ADMIN"),
            GUS.id(), Map.of(CHECKOUT, "MEMBER", LEGACY, "MEMBER"),
            HAL.id(), Map.of(CHECKOUT, "TESTER"),
            DAN.id(), Map.of(CHECKOUT, "READER", LEGACY, "READER"),
            IVY.id(), Map.of(),
            EVE.id(), Map.of());

    private ScenarioFixture() {
    }

    /**
     * Whether the user exists (auth-service answers 401 for a token of an unknown user).
     */
    public static boolean isKnown(UUID userId) {
        return ROLES.containsKey(userId);
    }

    /**
     * The user's effective role in the project, or empty for "none".
     */
    public static Optional<String> role(UUID userId, UUID projectId) {
        return Optional.ofNullable(ROLES.getOrDefault(userId, Map.of()).get(projectId));
    }

    public static boolean archived(UUID projectId) {
        return LEGACY.equals(projectId);
    }

    /**
     * A fixture user: {@code name@example.com} and a fixed id.
     */
    public record User(String name, UUID id) {

        public String email() {
            return name + "@example.com";
        }

        /**
         * {@code {token of X}}.
         */
        public String token() {
            return TestTokens.valid(id, email());
        }

        public String bearer() {
            return "Bearer " + token();
        }
    }
}
