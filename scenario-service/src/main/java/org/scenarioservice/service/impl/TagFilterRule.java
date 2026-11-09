package org.scenarioservice.service.impl;

import org.scenarioservice.exception.InvalidTagFilterException;

import java.util.List;

/**
 * The {@code tag} filter rule (D12, D19 #5), in one place: given at most once (checked on the raw values, which Spring
 * would otherwise join or split on commas); {@code @} followed by at least one character; at most 1,000 code points;
 * no whitespace (ECMAScript {@code \s}: Unicode White_Space and U+FEFF) and no control character. Anything else, such
 * as U+200B, is part of the tag and matched exactly. Messages never contain the value.
 */
final class TagFilterRule {

    private static final int MAX_CODE_POINTS = 1000;

    private TagFilterRule() {
    }

    /**
     * The one tag to filter by, or {@code null} when the parameter is absent.
     */
    static String tag(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return null;
        }
        if (rawValues.size() > 1) {
            throw new InvalidTagFilterException("Tag must be given at most once");
        }
        String tag = rawValues.get(0);
        if (!tag.startsWith("@") || tag.length() < 2) {
            throw new InvalidTagFilterException("Tag must be @ followed by at least one character");
        }
        if (tag.codePointCount(0, tag.length()) > MAX_CODE_POINTS) {
            throw new InvalidTagFilterException("Tag must be at most 1000 characters long");
        }
        if (tag.codePoints().anyMatch(TagFilterRule::isWhitespaceOrControl)) {
            throw new InvalidTagFilterException("Tag must not contain whitespace or control characters");
        }
        return tag;
    }

    private static boolean isWhitespaceOrControl(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0xFEFF
                || Character.isISOControl(codePoint);
    }
}
