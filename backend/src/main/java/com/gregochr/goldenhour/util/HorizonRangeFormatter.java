package com.gregochr.goldenhour.util;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Formats a set of integer days-ahead offsets into a compact horizon label.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code 0} → {@code "T"}, {@code 1} → {@code "T+1"}, {@code -1} → {@code "T-1"}</li>
 *   <li>A contiguous range renders as {@code "T to T+2"}.</li>
 *   <li>Two or more non-contiguous offsets render as a comma-separated list of labels
 *       in ascending order (e.g. {@code "T, T+2"}).</li>
 * </ul>
 */
public final class HorizonRangeFormatter {

    private HorizonRangeFormatter() {
    }

    /**
     * Formats the given days-ahead offsets into a compact horizon label.
     *
     * @param daysAhead set of signed day offsets relative to the run-start date
     * @return compact horizon label, or an empty string if {@code daysAhead} is null or empty
     */
    public static String format(Collection<Integer> daysAhead) {
        if (daysAhead == null || daysAhead.isEmpty()) {
            return "";
        }
        TreeSet<Integer> sorted = new TreeSet<>(daysAhead);
        int min = sorted.first();
        int max = sorted.last();
        if (min == max) {
            return label(min);
        }
        if (isContiguous(sorted, min, max)) {
            return label(min) + " to " + label(max);
        }
        StringBuilder sb = new StringBuilder();
        for (Integer d : sorted) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(label(d));
        }
        return sb.toString();
    }

    private static boolean isContiguous(Set<Integer> sorted, int min, int max) {
        return sorted.size() == (max - min + 1);
    }

    private static String label(int daysAhead) {
        if (daysAhead == 0) {
            return "T";
        }
        if (daysAhead > 0) {
            return "T+" + daysAhead;
        }
        return "T" + daysAhead;
    }
}
