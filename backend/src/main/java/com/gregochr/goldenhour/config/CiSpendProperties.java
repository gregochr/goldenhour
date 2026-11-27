package com.gregochr.goldenhour.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Bound to the {@code photocast.ci-spend} section of {@code application.yml}: the hourly {@code ci_spend_poll} job
 * that reads the CI prompt-regression usage artifacts from GitHub Actions (docs/engineering/ci-spend-plan.md, C2).
 *
 * <p>Off by default. The feature is <em>active</em> only when it is both enabled and has a token
 * ({@link #isActive()}), so a build without a token never calls GitHub.
 *
 * <p>{@link #githubToken} is a secret: a fine-grained token with Actions: read on the one repository, sent only as a
 * Bearer header, excluded from {@link #toString()} and never logged or served.
 */
@Component
@ConfigurationProperties(prefix = "photocast.ci-spend")
@Validated
@Getter
@Setter
public class CiSpendProperties {

    private static final java.util.regex.Pattern TOKEN_SHAPE = java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** Default look-back window in days. */
    static final int DEFAULT_LOOKBACK_DAYS = 14;

    /** Default cap on GitHub API calls in one poll. */
    static final int DEFAULT_MAX_CALLS_PER_POLL = 300;

    /** The master switch. Defaults to {@code false}; whether the job runs is {@link #isActive()}. */
    private boolean enabled = false;

    /** The GitHub token (Actions: read). Never logged, never served. */
    private String githubToken = "";

    /** The repository as {@code owner/name}. */
    @NotBlank
    @Pattern(regexp = "(?!\\.{1,2}/)[A-Za-z0-9_.-]{1,100}/(?!\\.{1,2}$)[A-Za-z0-9_.-]{1,100}",
            message = "must be owner/name")
    private String repository = "gregochr/goldenhour";

    /** The workflow file whose runs are read. */
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_.-]+\\.ya?ml", message = "must be a workflow file name ending .yml or .yaml")
    private String workflowFile = "prompt-regression.yml";

    /** How many days back to look for workflow runs. Set 90 once to backfill. */
    @Min(1)
    @Max(90)
    private int lookbackDays = DEFAULT_LOOKBACK_DAYS;

    /** The most GitHub API calls one poll may make; the next poll resumes where it stopped. */
    @Min(1)
    private int maxCallsPerPoll = DEFAULT_MAX_CALLS_PER_POLL;

    /** The GitHub API root. */
    @NotBlank
    @Pattern(regexp = "https://[^/?#@]+(/.*)?", message = "must be an https URL with no credentials")
    private String apiBaseUrl = "https://api.github.com";

    /**
     * Whether a token is set (non-blank).
     *
     * @return true when a token is present
     */
    public boolean hasToken() {
        return githubToken != null && TOKEN_SHAPE.matcher(githubToken).matches();
    }

    /**
     * Sets the token, trimmed. A value that is not letters, digits and underscores is kept but never counts as a
     * token ({@link #hasToken()} is false), so it can neither activate the job nor reach a header, and no message
     * ever carries it.
     *
     * @param githubToken the raw configured value
     */
    public void setGithubToken(String githubToken) {
        this.githubToken = githubToken == null ? "" : githubToken.trim();
    }

    /**
     * Whether the poll should run: the switch is on <em>and</em> a token is set.
     *
     * @return true when the job may call GitHub
     */
    public boolean isActive() {
        return enabled && hasToken();
    }

    /**
     * The repository owner, the part of {@link #repository} before the slash.
     *
     * @return the owner
     */
    public String owner() {
        return repository.substring(0, repository.indexOf('/'));
    }

    /**
     * The repository name, the part of {@link #repository} after the slash.
     *
     * @return the name
     */
    public String repoName() {
        return repository.substring(repository.indexOf('/') + 1);
    }

    /**
     * Excludes the token: a config dump or a logged properties object must never reveal it.
     *
     * @return a description without the secret
     */
    @Override
    public String toString() {
        return "CiSpendProperties[enabled=" + enabled + ", tokenConfigured=" + hasToken()
                + ", repository=" + repository + ", workflowFile=" + workflowFile
                + ", lookbackDays=" + lookbackDays + ", maxCallsPerPoll=" + maxCallsPerPoll + "]";
    }
}
