package org.reportservice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * {@code POST .../upload-tokens} (D4, AC-1.4). The name rule is one pattern over code points: 1 to 100 of them, none a
 * control character (U+0000–U+001F, U+007F–U+009F) or a lone surrogate, and at least one that isn't Unicode-blank
 * (whitespace, a space character, or U+200B, U+200C, U+200D, U+2060, U+FEFF; scenario-service's definition). A JSON
 * number or boolean as the name is 400 MALFORMED_REQUEST (strict JSON). {@code expiresAt} must be in the future; the
 * service checks it with the service clock.
 */
public record CreateUploadTokenRequest(
        @NotNull(message = "Name is required")
        @Pattern(regexp = NAME_RULE, message = NAME_MESSAGE)
        String name,

        Instant expiresAt) {

    /** 1–100 code points, no control character or lone surrogate, not Unicode-blank. */
    public static final String NAME_RULE = "(?=[\\s\\S]*[^\\p{javaWhitespace}\\p{javaSpaceChar}"
            + "\\u200B\\u200C\\u200D\\u2060\\uFEFF])[^\\p{Cc}\\p{Cs}]{1,100}";

    static final String NAME_MESSAGE = "Name must be 1 to 100 characters, not blank, without control characters";
}
