package com.gregochr.goldenhour.service.evaluation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the sky-rating user-message trims to a stored production prompt.
 *
 * <p>The files under {@code prompt-regression/} record what production sent before the trims
 * (see {@code docs/engineering/sky-user-message-trim-plan.md}). The fidelity tests compare
 * {@code buildUserMessage(fixture)} with that text after this helper has removed the same three
 * pieces the builder no longer prints. Each step must match exactly once, so a drifted pattern
 * fails loudly instead of silently doing nothing.
 */
final class PromptTrims {

    private static final Pattern PRECIP_PROBABILITY = Pattern.compile(", Precip probability: [^%\\n]*%");
    private static final Pattern SHORTWAVE = Pattern.compile(", Shortwave: [^\\n]*? W/m²");
    private static final Pattern ORIENTATION_RESTATEMENT =
            Pattern.compile(" \\(this location is best suited for [a-z]+ photography\\)");

    private PromptTrims() {
    }

    /**
     * Removes the precipitation probability, the shortwave reading and the orientation
     * restatement from a stored production prompt.
     *
     * @param storedPrompt the prompt text production sent before the trims
     * @return the same text as the trimmed builder prints it
     * @throws IllegalStateException if any step does not match exactly once
     */
    static String apply(String storedPrompt) {
        String result = removeOnce(storedPrompt, PRECIP_PROBABILITY, "precip probability");
        result = removeOnce(result, SHORTWAVE, "shortwave");
        return removeOnce(result, ORIENTATION_RESTATEMENT, "orientation restatement");
    }

    private static String removeOnce(String text, Pattern pattern, String step) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        if (count != 1) {
            throw new IllegalStateException("Trim step '" + step + "' matched " + count + " times, expected 1");
        }
        return pattern.matcher(text).replaceAll("");
    }
}
