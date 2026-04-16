package com.gregochr.goldenhour.service.evaluation;

/**
 * Shared utility methods for prompt construction and Claude response parsing.
 *
 * <p>Stateless, no Spring dependencies. All methods are static.
 */
public final class PromptUtils {

    private PromptUtils() {
        // utility class
    }

    /** 16-point compass directions, indexed by (degrees / 22.5) rounded. */
    private static final String[] CARDINAL_DIRECTIONS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    /**
     * Inserts a content block immediately before the prompt suffix in a
     * completed prompt string. If the suffix is not found, appends the
     * block at the end.
     *
     * @param base   the completed prompt string containing the suffix
     * @param suffix the prompt suffix to insert before
     * @param block  the content block to insert
     * @return the prompt with the block inserted before the suffix
     */
    public static String insertBeforeSuffix(String base, String suffix, String block) {
        int suffixIdx = base.lastIndexOf(suffix);
        if (suffixIdx < 0) {
            return base + "\n" + block + suffix;
        }
        return base.substring(0, suffixIdx) + block + base.substring(suffixIdx);
    }

    /**
     * Converts a wind direction in degrees (0-360) to a 16-point compass cardinal.
     *
     * @param degrees wind direction in degrees (meteorological convention)
     * @return compass cardinal (e.g. "N", "SW", "ENE")
     */
    public static String toCardinal(int degrees) {
        int normalised = ((degrees % 360) + 360) % 360;
        int index = (int) Math.round(normalised / 22.5) % 16;
        return CARDINAL_DIRECTIONS[index];
    }

    /**
     * Truncates the given text to at most {@code maxWords} words.
     *
     * @param text     the text to truncate
     * @param maxWords maximum number of words
     * @return truncated text, or the original if already within the limit
     */
    public static String truncateToWords(String text, int maxWords) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] words = text.strip().split("\\s+");
        if (words.length <= maxWords) {
            return text.strip();
        }
        return String.join(" ", java.util.Arrays.copyOf(words, maxWords));
    }

    /**
     * Strips {@code ```json} and {@code ```} code fence wrappers from a
     * Claude response, then trims whitespace.
     *
     * @param raw the raw Claude response text
     * @return cleaned text with code fences removed
     */
    public static String stripCodeFences(String raw) {
        if (raw == null) {
            return null;
        }
        return raw
                .replaceAll("(?s)^```json\\s*", "")
                .replaceAll("(?s)^```\\s*", "")
                .replaceAll("(?s)```\\s*$", "")
                .trim();
    }

    /**
     * Extracts the first balanced JSON object from a string, ignoring any
     * surrounding preamble or postamble text. Returns the original string if
     * no balanced object is found (caller will then fail fast with a clear
     * Jackson error).
     *
     * <p>Handles the common case where Claude emits conversational text like
     * "I'll analyze..." before the JSON, despite system-prompt instructions
     * to return JSON only.
     *
     * @param raw the raw response text, possibly with preamble/postamble
     * @return the first balanced JSON object, or the original string
     */
    public static String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start == -1) {
            return raw;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        // Unbalanced — return the original so Jackson reports a clear error
        return raw;
    }

    /**
     * Returns the median of a sorted int array, or 0 if empty.
     *
     * @param sorted a sorted int array
     * @return the median value
     */
    public static int median(int[] sorted) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[sorted.length / 2];
    }
}
