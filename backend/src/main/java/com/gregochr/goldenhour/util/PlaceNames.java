package com.gregochr.goldenhour.util;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The one way a place name is turned into comparable words. Ask's place matching and the admin
 * name-namespace guard both use it, so "Hadrians Wall" and "Hadrian's Wall" are the same name everywhere.
 */
public final class PlaceNames {

    private static final int MAX_EXACT_ONLY_LETTERS = 3;
    private static final int MAX_ONE_EDIT_LETTERS = 5;
    private static final int MAX_TWO_EDIT_LETTERS = 8;

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

    /**
     * How close a query is to the start of a name: the edits to the closest leading run of the name, and how many
     * of the name's words that run holds.
     *
     * @param distance the edits, or {@link Integer#MAX_VALUE} when the first letters differ or either side is empty
     * @param words    how many of the name's leading words the closest run holds; 0 when there is no match
     */
    public record PrefixMatch(int distance, int words) {
    }

    /**
     * Edits from the query's letters to the closest leading run of the name's words (words 1..k for every k),
     * spaces removed on both sides, so "dunstan burgh" and "Dunstanburgh" are the same and "Bamburgh" is close to
     * "Bamburgh Castle". The first letters must match, so a misspelling is never matched inside a longer name
     * ("bamborough" is not near "Flamborough Head"). The distance is optimal string alignment: Levenshtein plus a
     * swap of two adjacent letters.
     *
     * @param name  the name's words
     * @param query the query's words
     * @return the least distance, or {@link Integer#MAX_VALUE} when the first letters differ or either is empty
     */
    public static int prefixDistance(List<String> name, List<String> query) {
        return prefixMatch(name, query).distance();
    }

    /**
     * {@link #prefixDistance} together with the length of the leading run that gave it (the shortest such run
     * when two tie).
     *
     * @param name  the name's words
     * @param query the query's words
     * @return the closest leading run's distance and word count
     */
    public static PrefixMatch prefixMatch(List<String> name, List<String> query) {
        String wanted = String.join("", query);
        if (wanted.isEmpty() || name.isEmpty() || name.getFirst().charAt(0) != wanted.charAt(0)) {
            return new PrefixMatch(Integer.MAX_VALUE, 0);
        }
        int best = Integer.MAX_VALUE;
        int bestWords = 0;
        StringBuilder run = new StringBuilder();
        for (int k = 1; k <= name.size(); k++) {
            run.append(name.get(k - 1));
            int distance = editDistance(run.toString(), wanted);
            if (distance < best) {
                best = distance;
                bestWords = k;
            }
        }
        return new PrefixMatch(best, bestWords);
    }

    /**
     * The most edits allowed for a query of this many letters: none up to 3, one for 4 to 5, two for 6 to 8, three
     * from 9.
     *
     * @param letters the query's letters, spaces not counted
     * @return the allowed edits
     */
    public static int allowedEdits(int letters) {
        if (letters <= MAX_EXACT_ONLY_LETTERS) {
            return 0;
        }
        if (letters <= MAX_ONE_EDIT_LETTERS) {
            return 1;
        }
        return letters <= MAX_TWO_EDIT_LETTERS ? 2 : 3;
    }

    /** Optimal string alignment distance: insert, delete, substitute, or swap two adjacent letters. */
    private static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, d[i - 2][j - 2] + 1);
                }
                d[i][j] = value;
            }
        }
        return d[a.length()][b.length()];
    }
}
