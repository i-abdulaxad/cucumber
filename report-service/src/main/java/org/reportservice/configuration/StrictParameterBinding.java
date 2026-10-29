package org.reportservice.configuration;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditorSupport;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict, untrimmed binding of path ids (AC-6.5, standards §3; scenario-service's mechanism). Binder-only advice: a
 * {@code PropertyEditor}, because a registered {@code Converter} would let Spring fall back to its lenient default
 * editors. A value is matched exactly as received and must be a canonical UUID in lower case, the only form
 * report-service emits (contract C1); anything else fails the binding, which is 400 MALFORMED_REQUEST. No editor
 * message contains the value.
 */
@ControllerAdvice
public class StrictParameterBinding {

    @InitBinder
    public void strictParameters(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new LowerCaseUuidEditor());
    }

    /** 8-4-4-4-12 lower-case hexadecimal digits. */
    static final class LowerCaseUuidEditor extends PropertyEditorSupport {

        private static final Pattern CANONICAL_LOWER_CASE =
                Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        @Override
        public void setAsText(String text) {
            if (text == null || !CANONICAL_LOWER_CASE.matcher(text).matches()) {
                throw new IllegalArgumentException("Not a lower-case canonical UUID");
            }
            setValue(UUID.fromString(text));
        }
    }
}
