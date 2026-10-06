package com.gregochr.goldenhour.service.ask;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * The one place a question is cleaned before it goes anywhere near an engine (plan §2.5 step 2).
 * Two entry points share one pass: {@link #sanitise} (the admin dry-run: strip what cannot be seen,
 * collapse whitespace, trim, refuse a blank or over-long question) and {@link #sanitiseTyped} (the
 * reader-facing {@code POST /api/ask}: the same pass, but invisible characters and anything outside
 * the character allow-list are <em>refused</em> rather than stripped, and the cache-key
 * <em>normalised</em> form is derived from the cleaned text).
 *
 * <p>There is deliberately only one sanitiser: the text Claude receives must be exactly the text the
 * cache key is derived from, so a question whose visible form differs from its key cannot plant an
 * answer for everyone (plan §1 #16).
 *
 * <p>What the lenient pass strips: every ISO control character (tab and newline become a single
 * space, the rest vanish) and every Unicode <em>format</em> character (zero-width space and joiner,
 * the bidi overrides, the byte-order mark) — invisible characters a reader cannot see in the box and
 * that could otherwise smuggle a different question past a cache key — plus unpaired surrogates,
 * which are not text at all. The strict pass refuses all of those instead.
 */
public final class AskQuestionSanitiser {

    /** The longest question accepted, in characters (code points) after cleaning. */
    public static final int MAX_LENGTH = 200;

    /**
     * The longest raw input {@link #sanitiseTyped} will look at, in UTF-16 units. A cap taken before
     * any normalising so a megabyte body costs nothing: a question this long is never a question
     * (the cleaned limit is {@value #MAX_LENGTH}, and whitespace collapses).
     */
    static final int MAX_RAW_LENGTH = 1_000;

    /**
     * The punctuation a typed question may contain (plan §2.5 step 2), besides letters, digits and
     * spaces: {@code ? ! ' ’ , . - : / & ( )}. The apostrophe's typographic twin is U+2019.
     */
    static final String ALLOWED_PUNCTUATION = "?!'’,.-:/&()";

    /** The typographic apostrophe, which the normalised form treats as the plain one. */
    private static final char RIGHT_SINGLE_QUOTE = '’';

    /**
     * The words the normalised (cache-key) form drops: politeness and filler that carries no part of
     * the question, so "Please, could you tell me the best spot tonight?" and "best spot tonight"
     * share a key. <b>Deliberately conservative and defined only here</b>: a word that changes what
     * is asked (a day, a place, a negation, a modal like "will" or "should", "my", "near") must
     * never be on it, because two different questions sharing a key would share an answer.
     */
    public static final Set<String> FILLER_WORDS = Set.of("please", "pls", "plz", "thanks", "thank",
            "kindly", "hey", "hi", "hello", "um", "uh", "the", "a", "an", "could", "would", "can",
            "you", "tell", "me", "i");

    private AskQuestionSanitiser() {
    }

    /**
     * The outcome of sanitising: the cleaned text, or why there is none.
     *
     * @param sanitised  the cleaned question, or null when it was refused
     * @param normalised the lower-cased, punctuation-free, filler-free cache-key form of
     *                   {@code sanitised}; null from {@link #sanitise} (the dry-run has no cache) and
     *                   when the question was refused
     * @param error      a sentence saying why it was refused, or null when it was accepted
     */
    public record Result(String sanitised, String normalised, String error) {

        /**
         * A result with no normalised form.
         *
         * @param sanitised the cleaned question, or null when refused
         * @param error     why it was refused, or null
         */
        public Result(String sanitised, String error) {
            this(sanitised, null, error);
        }

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
     * Cleans a question leniently, for the admin dry-run: invisible characters are stripped, not
     * refused, and nothing outside an allow-list is rejected.
     *
     * @param raw the question as received; may be null
     * @return the cleaned question, or the reason it was refused (blank, or over
     *         {@value #MAX_LENGTH} characters once cleaned)
     */
    public static Result sanitise(String raw) {
        return clean(raw, false);
    }

    /**
     * Cleans a question strictly, for the reader-facing typed endpoint (plan §2.5 step 2): trims,
     * collapses whitespace, caps at {@value #MAX_LENGTH} code points, and <em>refuses</em> — never
     * strips — any control or format character (zero-width space and joiner, bidi overrides, the
     * byte-order mark) and anything but letters (accented included), digits, spaces and
     * {@code ? ! ' ’ , . - : / & ( )}. Emoji, symbols and combining marks are therefore refused. The
     * text is composed to NFC first, so an accent typed as two code points is the same visible
     * letter as one typed as one. The result carries the {@linkplain #normalise normalised} form.
     *
     * @param raw the question as received; may be null
     * @return the cleaned question with its normalised form, or the sentence saying why it was refused
     */
    public static Result sanitiseTyped(String raw) {
        if (raw != null && raw.length() > MAX_RAW_LENGTH) {
            return new Result(null, tooLong());
        }
        Result cleaned = clean(raw == null ? null : Normalizer.normalize(raw, Normalizer.Form.NFC), true);
        if (!cleaned.ok()) {
            return cleaned;
        }
        String normalised = normalise(cleaned.sanitised());
        if (normalised.isEmpty()) {
            return new Result(null, "The question needs some words in it.");
        }
        return new Result(cleaned.sanitised(), normalised, null);
    }

    /**
     * The cache-key form of an already-cleaned question: lower-cased (root locale), apostrophes
     * removed ("what's" and "whats" agree), every other piece of punctuation a word break, the
     * {@linkplain #FILLER_WORDS filler words} dropped and the rest joined by single spaces. A question
     * made of nothing but filler keeps its filler (so two such questions still differ from "").
     *
     * @param sanitised the cleaned question
     * @return the normalised form; empty only when the text holds no letter or digit at all
     */
    public static String normalise(String sanitised) {
        StringBuilder spaced = new StringBuilder(sanitised.length());
        sanitised.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (cp == '\'' || cp == RIGHT_SINGLE_QUOTE) {
                return;
            }
            spaced.appendCodePoint(Character.isLetter(cp) || isDigit(cp) ? cp : ' ');
        });
        StringBuilder kept = new StringBuilder();
        StringBuilder everything = new StringBuilder();
        for (String word : spaced.toString().split(" +")) {
            if (word.isEmpty()) {
                continue;
            }
            append(everything, word);
            if (!FILLER_WORDS.contains(word)) {
                append(kept, word);
            }
        }
        return kept.length() > 0 ? kept.toString() : everything.toString();
    }

    private static void append(StringBuilder words, String word) {
        if (words.length() > 0) {
            words.append(' ');
        }
        words.append(word);
    }

    private static String tooLong() {
        return "The question must be at most " + MAX_LENGTH + " characters.";
    }

    private static boolean isDigit(int cp) {
        return Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER;
    }

    private static boolean allowed(int cp) {
        return Character.isLetter(cp) || isDigit(cp) || ALLOWED_PUNCTUATION.indexOf(cp) >= 0;
    }

    /** The one cleaning pass. {@code strict} refuses what the lenient pass strips or lets through. */
    private static Result clean(String raw, boolean strict) {
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
            boolean invisible = type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.SURROGATE;
            if (invisible && !strict) {
                continue;
            }
            if (strict && (invisible || !allowed(cp))) {
                return new Result(null, "Questions can only use letters, numbers and ordinary "
                        + "punctuation (? ! ' , . - : / & and brackets).");
            }
            if (pendingSpace) {
                cleaned.append(' ');
                length++;
                pendingSpace = false;
            }
            cleaned.appendCodePoint(cp);
            length++;
            if (length > MAX_LENGTH) {
                return new Result(null, tooLong());
            }
        }
        if (cleaned.length() == 0) {
            return new Result(null, "The question must not be blank.");
        }
        return new Result(cleaned.toString(), null);
    }
}
