package com.gregochr.goldenhour.service.ask;

/**
 * The first, minimal step of cleaning a question before it goes anywhere near an engine (plan §2.5
 * step 2): strip control characters, collapse whitespace, trim, and refuse a blank or over-long
 * question.
 *
 * <p>This is the part the admin dry-run needs now. B4 builds the typed endpoint's full step 2 on
 * top of it — the character allow-list, the normalised cache-key form, the filler list — and should
 * <em>extend this class</em> rather than clean a question a second way: the text Claude receives
 * must be exactly the text the cache key is derived from, so there can be only one sanitiser.
 *
 * <p>What it strips: every ISO control character (tab and newline become a single space, the rest
 * vanish) and every Unicode <em>format</em> character (zero-width space and joiner, the bidi
 * overrides, the byte-order mark) — invisible characters a reader cannot see in the box and that
 * could otherwise smuggle a different question past a cache key — plus unpaired surrogates, which
 * are not text at all. B4's allow-list is stricter still and may choose to <em>reject</em> what this
 * strips; stripping is the safe minimum for a tool an admin types into.
 */
public final class AskQuestionSanitiser {

    /** The longest question accepted, in characters (code points) after cleaning. */
    public static final int MAX_LENGTH = 200;

    private AskQuestionSanitiser() {
    }

    /**
     * The outcome of sanitising: the cleaned text, or why there is none.
     *
     * @param sanitised the cleaned question, or null when it was refused
     * @param error     a sentence saying why it was refused, or null when it was accepted
     */
    public record Result(String sanitised, String error) {

        /**
         * Whether the question was accepted.
         *
         * @return true when there is a cleaned question
         */
        public boolean ok() {
            return sanitised != null;
        }
    }

    /**
     * Cleans a question.
     *
     * @param raw the question as received; may be null
     * @return the cleaned question, or the reason it was refused (blank, or over
     *         {@value #MAX_LENGTH} characters once cleaned)
     */
    public static Result sanitise(String raw) {
        if (raw == null) {
            return new Result(null, "The question must not be blank.");
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        int length = 0;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = cleaned.length() > 0;
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.SURROGATE) {
                continue;
            }
            if (pendingSpace) {
                cleaned.append(' ');
                length++;
                pendingSpace = false;
            }
            cleaned.appendCodePoint(cp);
            length++;
            if (length > MAX_LENGTH) {
                return new Result(null,
                        "The question must be at most " + MAX_LENGTH + " characters.");
            }
        }
        if (cleaned.length() == 0) {
            return new Result(null, "The question must not be blank.");
        }
        return new Result(cleaned.toString(), null);
    }
}
