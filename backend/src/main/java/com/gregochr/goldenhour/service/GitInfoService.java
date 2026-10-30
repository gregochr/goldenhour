package com.gregochr.goldenhour.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Properties;

/**
 * Exposes git commit metadata from {@code git.properties} generated at build time.
 *
 * <p>Falls back gracefully if the file is missing (e.g. Docker builds without {@code .git}).
 */
@Service
public class GitInfoService {

    private static final Logger LOG = LoggerFactory.getLogger(GitInfoService.class);

    private String commitHash;
    private String commitAbbrev;
    private LocalDateTime commitDate;
    private boolean dirty;
    private String branch;

    /**
     * Loads git properties from the classpath at startup.
     */
    @PostConstruct
    void init() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            if (is == null) {
                LOG.info("git.properties not found on classpath — git info unavailable");
                return;
            }
            load(is);
        } catch (IOException e) {
            LOG.warn("Failed to load git.properties: {}", e.getMessage());
        }
    }

    /**
     * Reads the git fields from an open {@code git.properties} stream.
     *
     * @param is the stream to read
     * @throws IOException if the stream cannot be read
     */
    void load(InputStream is) throws IOException {
        Properties props = new Properties();
        props.load(is);

        commitHash = props.getProperty("git.commit.id", "");
        commitAbbrev = props.getProperty("git.commit.id.abbrev", "");
        dirty = Boolean.parseBoolean(props.getProperty("git.dirty", "false"));
        branch = props.getProperty("git.branch", "");

        String commitTimeStr = props.getProperty("git.commit.time", "");
        if (!commitTimeStr.isEmpty()) {
            try {
                commitDate = LocalDateTime.ofInstant(
                        Instant.parse(commitTimeStr), ZoneOffset.UTC);
            } catch (Exception e) {
                LOG.warn("Failed to parse git.commit.time '{}': {}", commitTimeStr, e.getMessage());
            }
        }

        LOG.info("Git info loaded — branch={}, commit={}{}, date={}",
                branch, commitAbbrev, dirty ? " (dirty)" : "", commitDate);
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
