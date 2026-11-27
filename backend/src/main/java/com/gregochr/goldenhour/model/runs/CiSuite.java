package com.gregochr.goldenhour.model.runs;

import java.util.Arrays;
import java.util.Optional;

/**
 * The two paid CI prompt-regression suites, in stack order (sky, then ask). Never a {@link RunDeepType}: CI spend is
 * counted apart from the app's.
 */
public enum CiSuite {

    /** The sky-rating prompt regression. */
    SKY("sky", "Prompt tests · sky"),
    /** The Ask PhotoCast prompt regression. */
    ASK("ask", "Prompt tests · ask");

    private final String code;
    private final String label;

    CiSuite(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * The value stored in {@code ci_spend_run.suite}.
     *
     * @return {@code sky} or {@code ask}
     */
    public String code() {
        return code;
    }

    /**
     * The words to show.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * The suite for a stored code.
     *
     * @param code the stored value
     * @return the suite, or empty for an unknown code
     */
    public static Optional<CiSuite> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
