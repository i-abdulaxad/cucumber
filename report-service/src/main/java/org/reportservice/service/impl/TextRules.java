package org.reportservice.service.impl;

/**
 * The name rule of D9 and D10 in one place (standards §3 strict input; scenario-service's definitions): 1 to
 * {@code max} code points, no control character (U+0000–U+001F, U+007F–U+009F), no lone surrogate, and not
 * Unicode-blank (only whitespace, space characters or U+200B, U+200C, U+200D, U+2060, U+FEFF).
 */
final class TextRules {

    private TextRules() {
    }

    static boolean isName(String text, int maxCodePoints) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean visible = false;
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (Character.isLowSurrogate(c)
                    || Character.isHighSurrogate(c) && (i + 1 == text.length()
                    || !Character.isLowSurrogate(text.charAt(i + 1)))) {
                return false;
            }
            int codePoint = text.codePointAt(i);
            if (Character.isISOControl(codePoint)) {
                return false;
            }
            visible |= !isBlank(codePoint);
            count++;
            i += Character.charCount(codePoint);
        }
        return visible && count <= maxCodePoints;
    }

    private static boolean isBlank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x200B
                || codePoint == 0x200C || codePoint == 0x200D || codePoint == 0x2060 || codePoint == 0xFEFF;
    }
}
