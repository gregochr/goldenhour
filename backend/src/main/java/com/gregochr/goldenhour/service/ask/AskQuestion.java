package com.gregochr.goldenhour.service.ask;

import java.util.List;

/**
 * A typed question after sanitising (plan §2.5 step 2).
 *
 * <p>The question carries its {@link AskScope}, resolved once where the question is validated, so
 * nothing downstream (the engines, the typed cache, the Ready precompute) goes back to the database
 * for it. The normalised form comes from {@link AskQuestionSanitiser} for every producer.
 *
 * @param sanitised  the trimmed, whitespace-collapsed text Claude receives
 * @param normalised the lower-cased, filler-free form used as the cache key
 * @param windowId   the context window, or null
 * @param scope      the regions asked about; {@link AskScope#ALL} means all (null reads as that)
 * @param view       {@code map}, {@code plan} or {@code coming-up}
 */
public record AskQuestion(String sanitised, String normalised, String windowId, AskScope scope,
        String view) {

    /** Canonical constructor: a null scope reads as every region. */
    public AskQuestion {
        scope = scope == null ? AskScope.ALL : scope;
    }

    /**
     * A question from the sanitiser's output. The normalised form is the sanitiser's own: the one
     * {@link AskQuestionSanitiser#sanitiseTyped} derived, or, from the lenient pass that derives none,
     * {@link AskQuestionSanitiser#normalise} of the cleaned text.
     *
     * @param cleaned  the sanitiser's result, which must be {@code ok}
     * @param windowId the context window, or null
     * @param scope    the resolved scope
     * @param view     {@code map}, {@code plan} or {@code coming-up}
     * @return the question
     */
    public static AskQuestion of(AskQuestionSanitiser.Result cleaned, String windowId, AskScope scope,
            String view) {
        return cleaned.normalised() != null
                ? new AskQuestion(cleaned.sanitised(), cleaned.normalised(), windowId, scope, view)
                : of(cleaned.sanitised(), windowId, scope, view);
    }

    /**
     * A question from text that needs no cleaning (a catalogue question), sent exactly as written,
     * with the sanitiser's normalised form.
     *
     * @param text     the question text Claude receives
     * @param windowId the context window, or null
     * @param scope    the resolved scope
     * @param view     {@code map}, {@code plan} or {@code coming-up}
     * @return the question
     */
    public static AskQuestion of(String text, String windowId, AskScope scope, String view) {
        return new AskQuestion(text, AskQuestionSanitiser.normalise(text), windowId, scope, view);
    }

    /**
     * The regions asked about, as ids.
     *
     * @return the scope's ids; empty means every region
     */
    public List<Long> regionIds() {
        return scope.regionIds();
    }
}
