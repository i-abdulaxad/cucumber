package org.scenarioservice.dto.request;

/**
 * "Unicode-blank" in one place (persistence rule T2, spec D8): a code point that is whitespace
 * ({@link Character#isWhitespace(int)} or {@link Character#isSpaceChar(int)}, which includes NBSP U+00A0, U+2007 and
 * U+202F) or one of the invisible format characters U+200B, U+200C, U+200D, U+2060 and U+FEFF.
 */
public final class UnicodeBlank {

    private UnicodeBlank() {
    }

    public static boolean isBlank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D || codePoint == 0x2060
                || codePoint == 0xFEFF;
    }

    /**
     * True when every code point is Unicode-blank (so also for the empty string).
     */
    public static boolean isBlank(CharSequence text) {
        return text.codePoints().allMatch(UnicodeBlank::isBlank);
    }

    /**
     * The text without Unicode-blank code points at either end (D19 #9).
     */
    public static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isBlank(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isBlank(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }
}
