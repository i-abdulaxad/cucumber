package org.scenarioservice.configuration;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditorSupport;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict, untrimmed binding of path ids, {@code {version}} and {@code archived} (D16, D19 #6, D20 #4; auth-service
 * T13's mechanism). Binder-only advice: {@code PropertyEditor}s registered for each type, because a registered
 * {@code Converter} would let Spring fall back to its lenient default editors. A value is matched exactly as received;
 * anything else fails the binding, which is 400 MALFORMED_REQUEST. No editor message contains the value.
 */
@ControllerAdvice
public class StrictParameterBinding {

    @InitBinder
    public void strictParameters(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new CanonicalUuidEditor());
        binder.registerCustomEditor(VersionNumber.class, new VersionNumberEditor());
        binder.registerCustomEditor(ArchivedFilter.class, new ArchivedFilterEditor());
    }

    /**
     * 8-4-4-4-12 hexadecimal digits, either case; the value is the UUID (printed in lower case).
     */
    static final class CanonicalUuidEditor extends PropertyEditorSupport {

        private static final Pattern CANONICAL =
                Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        @Override
        public void setAsText(String text) {
            if (text == null || !CANONICAL.matcher(text).matches()) {
                throw new IllegalArgumentException("Not a canonical UUID");
            }
            setValue(UUID.fromString(text));
        }
    }

    /**
     * {@code ^[1-9][0-9]{0,9}$} and at most 2147483647.
     */
    static final class VersionNumberEditor extends PropertyEditorSupport {

        private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]{0,9}");

        @Override
        public void setAsText(String text) {
            if (text == null || !POSITIVE.matcher(text).matches() || Long.parseLong(text) > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Not a version number from 1 to 2147483647");
            }
            setValue(new VersionNumber(Integer.parseInt(text)));
        }
    }

    /**
     * Exactly {@code true} or {@code false}, in lower case.
     */
    static final class ArchivedFilterEditor extends PropertyEditorSupport {

        @Override
        public void setAsText(String text) {
            if ("true".equals(text)) {
                setValue(new ArchivedFilter(true));
            } else if ("false".equals(text)) {
                setValue(new ArchivedFilter(false));
            } else {
                throw new IllegalArgumentException("Not true or false");
            }
        }
    }
}
