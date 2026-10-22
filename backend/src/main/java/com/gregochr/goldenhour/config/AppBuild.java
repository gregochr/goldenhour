package com.gregochr.goldenhour.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The identity of the running build, stamped on every scored forecast and every reader's rating so
 * the calibration gate can say which release a drift began in.
 *
 * <p>The value is the {@code APP_VERSION} the deploy workflow bakes into the image from the git tag
 * ({@code v2.28.0}); a run with none set (local development, tests) reads {@value #UNSET}. Production
 * is only ever built from a tag, so the tag names exactly one commit and carries more meaning to a
 * reader of the report than a bare sha would. It is the same value {@code job_run.app_version}
 * already records (V80), so the two agree for one process.
 *
 * <p>The value is cut to {@value #MAX_LENGTH} characters, the width of the {@code app_build}
 * columns, so a long override can never fail a write that merely carries provenance.
 */
@Component
public class AppBuild {

    /** Width of the {@code app_build} columns. */
    public static final int MAX_LENGTH = 40;

    /** What a process with no {@code APP_VERSION} reports. */
    public static final String UNSET = "dev";

    private final String value;

    /**
     * Creates the bean from the {@code APP_VERSION} environment variable.
     *
     * @param appVersion the application version, blank when unset
     */
    public AppBuild(@Value("${APP_VERSION:dev}") String appVersion) {
        String trimmed = appVersion == null ? "" : appVersion.strip();
        if (trimmed.isEmpty()) {
            trimmed = UNSET;
        }
        this.value = trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
    }

    /**
     * The build identifier to stamp on a row.
     *
     * @return a non-blank string of at most {@value #MAX_LENGTH} characters
     */
    public String value() {
        return value;
    }
}
