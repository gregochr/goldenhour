package com.gregochr.goldenhour.util;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The one way a place name is turned into comparable words. Ask's place matching and the admin
 * name-namespace guard both use it, so "Hadrians Wall" and "Hadrian's Wall" are the same name everywhere.
 */
public final class PlaceNames {

    private PlaceNames() {
    }

    /**
     * A place name as words: lower case, apostrophes removed, every other non-letter-or-digit a gap.
     *
     * @param text a place name or a query
     * @return its words, possibly empty
     */
    public static List<String> words(String text) {
        String folded = text.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
        StringBuilder spaced = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            spaced.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        String joined = spaced.toString().strip();
        return joined.isEmpty() ? List.of() : List.of(joined.split("\\s+"));
    }

    /**
     * Whether {@code words} holds {@code run} as a contiguous run of whole words.
     *
     * @param words the place's words
     * @param run   the run to look for
     * @return true when the run appears; false for an empty run
     */
    public static boolean containsRun(List<String> words, List<String> run) {
        return !run.isEmpty() && Collections.indexOfSubList(words, run) >= 0;
    }
}
