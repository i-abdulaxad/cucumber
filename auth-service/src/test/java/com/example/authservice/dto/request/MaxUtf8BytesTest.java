package com.example.authservice.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A6: the 72-byte password limit counts UTF-8 bytes, not characters (AC-2.9 uses ASCII; this covers multi-byte input).
 */
class MaxUtf8BytesTest {

    /**
     * The euro sign is 3 bytes in UTF-8.
     */
    private static final String EURO = "€";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    @Tag("AC-2.9")
    void aPasswordOfExactly72BytesIsValid() {
        assertThat(passwordViolations(EURO.repeat(24))).isEmpty();
        assertThat(passwordViolations("a".repeat(72))).isEmpty();
    }

    @Test
    @Tag("AC-2.9")
    void aPasswordOver72BytesIsInvalidEvenWithFewerThan72Characters() {
        assertThat(passwordViolations(EURO.repeat(25)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("Password must be at most 72 bytes long");
    }

    @Test
    void theAcceptInvitationPasswordHasTheSameLimit() {
        Set<ConstraintViolation<AcceptInvitationRequest>> violations =
                validator.validate(new AcceptInvitationRequest("a".repeat(73), "Fay Lin"));

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("password");
    }

    private Set<ConstraintViolation<RegisterRequest>> passwordViolations(String password) {
        return validator.validate(new RegisterRequest("fay@example.com", password, "Fay Lin"));
    }
}
