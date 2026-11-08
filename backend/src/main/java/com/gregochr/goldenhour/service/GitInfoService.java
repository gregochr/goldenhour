package com.gregochr.goldenhour.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Exposes git commit metadata from {@code git.properties} generated at build time.
 *
 * <p>Falls back gracefully if the file is missing (e.g. Docker builds without {@code .git}): the production
 * image is built from a context with no {@code .git}, so the deploy workflow bakes the commit into the
 * {@code GIT_COMMIT_SHA}, {@code GIT_COMMIT_TIME} and {@code GIT_COMMIT_MESSAGE} environment variables, and
 * those are read when {@code git.properties} is absent or carries no commit id.
 */
@Service
public class GitInfoService {

    private static final Logger LOG = LoggerFactory.getLogger(GitInfoService.class);

    private static final int ABBREV_LENGTH = 7;

    /** Environment variable carrying the full commit sha when {@code git.properties} is unavailable. */
    static final String ENV_COMMIT_SHA = "GIT_COMMIT_SHA";

    /** Environment variable carrying the commit time (ISO-8601 with offset). */
    static final String ENV_COMMIT_TIME = "GIT_COMMIT_TIME";

    /** Environment variable carrying the commit subject line. */
    static final String ENV_COMMIT_MESSAGE = "GIT_COMMIT_MESSAGE";

    private final UnaryOperator<String> environment;
    private String commitHash;
    private String commitMessage;
    private String commitAbbrev;
    private LocalDateTime commitDate;
    private boolean dirty;
    private String branch;

    /**
     * Constructs the service reading the process environment for the fallback.
     */
    @Autowired
    public GitInfoService() {
        this(System::getenv);
    }

    /**
     * Constructs the service with an injectable environment lookup (tests pass a map-backed one).
     *
     * @param environment returns the value of a named environment variable, or null when unset
     */
    public GitInfoService(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    /**
     * Loads git properties from the classpath at startup.
     */
    @PostConstruct
    void init() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            if (is == null) {
                LOG.info("git.properties not found on classpath — trying the GIT_COMMIT_* environment");
                applyEnvironmentFallback();
                return;
            }
            load(is);
        } catch (IOException e) {
            LOG.warn("Failed to load git.properties: {}", e.getMessage());
            applyEnvironmentFallback();
        }
    }

    /**
     * Reads the git fields from an open {@code git.properties} stream.
     *
     * @param is the stream to read
     * @throws IOException if the stream cannot be read
     */
    public void load(InputStream is) throws IOException {
        Properties props = new Properties();
        props.load(is);

        commitHash = props.getProperty("git.commit.id", "");
        commitAbbrev = props.getProperty("git.commit.id.abbrev", "");
        dirty = Boolean.parseBoolean(props.getProperty("git.dirty", "false"));
        branch = props.getProperty("git.branch", "");

        commitMessage = blankToNull(props.getProperty("git.commit.message.short"));
        commitDate = parseCommitTime(props.getProperty("git.commit.time", ""));

        if (commitHash.isBlank()) {
            applyEnvironmentFallback();
            return;
        }
        LOG.info("Git info loaded — branch={}, commit={}{}, date={}",
                branch, commitAbbrev, dirty ? " (dirty)" : "", commitDate);
    }

    /** Reads the commit from the {@code GIT_COMMIT_*} environment variables; blank values stay unknown. */
    private void applyEnvironmentFallback() {
        String sha = blankToNull(environment.apply(ENV_COMMIT_SHA));
        if (sha == null) {
            LOG.info("No GIT_COMMIT_SHA in the environment — git info unavailable");
            return;
        }
        commitHash = sha;
        commitAbbrev = sha.length() > ABBREV_LENGTH ? sha.substring(0, ABBREV_LENGTH) : sha;
        commitMessage = blankToNull(environment.apply(ENV_COMMIT_MESSAGE));
        commitDate = parseCommitTime(environment.apply(ENV_COMMIT_TIME));
        LOG.info("Git info from the environment — commit={}, date={}", commitAbbrev, commitDate);
    }

    /** Parses an ISO-8601 instant with offset to naive UTC; null when blank or unparseable. */
    private static LocalDateTime parseCommitTime(String text) {
        String trimmed = blankToNull(text);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(trimmed).toInstant(), ZoneOffset.UTC);
        } catch (Exception e) {
            LOG.warn("Failed to parse git commit time '{}': {}", trimmed, e.getMessage());
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Returns the full commit hash.
     *
     * @return full SHA-1 hash, or empty string if unavailable
     */
    public String getCommitHash() {
        return commitHash != null ? commitHash : "";
    }

    /**
     * Returns the commit's subject line.
     *
     * @return the subject line, or null when unknown (never blank)
     */
    public String getCommitMessage() {
        return commitMessage;
    }

    /**
     * Returns the commit date in UTC.
     *
     * @return commit date, or null if unavailable
     */
    public LocalDateTime getCommitDate() {
        return commitDate;
    }

    /**
     * Returns whether the working tree was dirty at build time.
     *
     * @return true if uncommitted changes existed at build time
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Returns the branch name at build time.
     *
     * @return branch name, or empty string if unavailable
     */
    public String getBranch() {
        return branch != null ? branch : "";
    }
}
