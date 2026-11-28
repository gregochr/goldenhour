package com.gregochr.goldenhour.model.runs;

import java.util.Arrays;
import java.util.Optional;

/**
 * The paid CI test suites, in stack order (sky, ask, then the daily real-API smoke). Never a {@link RunDeepType}: CI
 * spend is counted apart from the app's.
 */
public enum CiSuite {

    /** The sky-rating prompt regression. */
    SKY("sky", "CI tests · sky"),
    /** The Ask PhotoCast prompt regression. */
    ASK("ask", "CI tests · ask"),
    /** The daily real-API smoke (one Batch request a day). The poller prices it at the Batch rate. */
    SMOKE("smoke", "CI tests · smoke");

    private final String code;
    private final String label;

    CiSuite(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * The value stored in {@code ci_spend_run.suite}.
     *
     * @return {@code sky}, {@code ask} or {@code smoke}
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
