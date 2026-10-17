package com.gregochr.goldenhour.service.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 fingerprints of prompt text, as lower-case hex, for {@code forecast_evaluation.prompt_hash}
 * (V182).
 *
 * <p>The fingerprinted text is the SKY <i>system</i> prompt a request carries, never the user
 * message: the user message embeds the slot's own weather, so its hash would differ on every row and
 * could group nothing. The system prompt changes only when someone edits the prompt, which is the
 * event the calibration report's per-prompt buckets exist to pin a drift to.
 */
public final class PromptHash {

    /** Length of the hex digest, the width of the {@code prompt_hash} column. */
    public static final int HEX_LENGTH = 64;

    private PromptHash() {
    }

    /**
     * Fingerprints prompt text.
     *
     * @param text the prompt text, must not be null
     * @return the SHA-256 of its UTF-8 bytes, as {@value #HEX_LENGTH} lower-case hex characters
     */
    public static String sha256Hex(String text) {
        return digestHex("SHA-256", text);
    }

    /**
     * Hex digest of the text's UTF-8 bytes under a named algorithm. Package-private so a test can
     * reach the one failure path (an algorithm the platform lacks) with a name it does not have.
     *
     * @param algorithm a {@link MessageDigest} algorithm name
     * @param text      the text to digest
     * @return the digest as lower-case hex
     * @throws IllegalStateException when the platform has no such algorithm
     */
    static String digestHex(String algorithm, String text) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm)
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform, so for the one caller this cannot happen.
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
