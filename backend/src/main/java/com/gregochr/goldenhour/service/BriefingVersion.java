package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/**
 * A content hash of the ratings and dispositions a served briefing was assembled from: the
 * {@code briefingVersion} on {@code /api/briefing}, echoed by {@code GET /api/user/settings/reach-verdicts}
 * (reach-scoped-card-plan R2 step 5).
 *
 * <p><b>Why a hash of content and not an epoch.</b> {@link ServedBriefingEpoch} is an in-memory counter that never
 * leaves the process, so it cannot tell a client that two responses were computed from one briefing. The hash can:
 * two responses carrying the same version were assembled from the same enriched ratings, dispositions and verdicts,
 * and the reach computation reads nothing else. It is computed once per memoised served briefing, in
 * {@link ServedBriefingAssembler#assembleForPlan}, never per request.
 *
 * <p><b>What it covers</b> is exactly what {@link ReachVerdictService} reads: per region its weather verdict,
 * display verdict, gloss headline, sample flags, mean and confidence; per slot its location, rating, canopy flag,
 * neighbour mark and the two reach stamps; and the UK civil date, because confidence is horizon-dependent. It
 * deliberately omits prose, tide and weather figures, which the reach computation does not read, so a change to them
 * does not make a client refetch for nothing.
 */
final class BriefingVersion {

    /** Hex characters kept from the digest: 64 bits is far more than a collision between two builds needs. */
    private static final int HEX_LENGTH = 16;

    private BriefingVersion() {
    }

    /**
     * Hashes the ratings and dispositions of a served briefing.
     *
     * @param days  the served day hierarchy, enriched, filtered and projected
     * @param today the UK civil date the response was assembled for
     * @return a short stable hex string; the same inputs always give the same string
     */
    static String of(List<BriefingDay> days, LocalDate today) {
        MessageDigest digest = sha256();
        put(digest, today);
        for (BriefingDay day : days) {
            put(digest, day.date());
            for (BriefingEventSummary summary : day.eventSummaries()) {
                put(digest, summary.targetType());
                for (BriefingRegion region : summary.regions()) {
                    putRegion(digest, region);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, HEX_LENGTH);
    }

    private static void putRegion(MessageDigest digest, BriefingRegion region) {
        put(digest, region.regionName());
        put(digest, region.verdict());
        put(digest, region.displayVerdict());
        put(digest, region.glossHeadline());
        put(digest, region.sampleSufficient());
        put(digest, region.forcedSample());
        put(digest, region.meanRating());
        put(digest, region.confidence());
        for (BriefingSlot slot : region.slots()) {
            put(digest, slot.locationId());
            put(digest, slot.locationName());
            put(digest, slot.claudeRating());
            put(digest, slot.canopy());
            put(digest, slot.neighbourOutlier());
            put(digest, slot.forced());
            put(digest, slot.triaged());
        }
    }

    /** Feeds one value, null-safe and unambiguous: every value is terminated, and null is not the string "null". */
    private static void put(MessageDigest digest, Object value) {
        digest.update((value == null ? "\u0000" : String.valueOf(value)).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
    }

    private static MessageDigest sha256() {
        return digest("SHA-256");
    }

    /**
     * A digest by name. Separate from {@link #sha256()} only so the failure arm can be exercised: every Java platform
     * is required to provide SHA-256, so with the real name it cannot happen.
     *
     * @param algorithm the algorithm name
     * @return a fresh digest
     * @throws IllegalStateException if the platform has no such algorithm
     */
    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is unavailable", e);
        }
    }
}
