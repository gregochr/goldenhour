package com.gregochr.goldenhour.service;

import java.util.Map;

/**
 * A fixed ordinal over hot-topic kinds, rarest first — the tie-break for which coincidence gets
 * promoted to the Plan tab's full-width strip when two land on the same window.
 *
 * <p><b>The ordering is a product decision, not a fact in the tree.</b> The basis is the expected
 * number of nights per year on which a <em>kind</em> can fire at all — the kind, not tonight's
 * conditions. A supermoon is fixed by orbital mechanics and happens a few times a year; spring
 * tides run for two or three nights around every new and full moon, so roughly a third of the
 * calendar. Argue with the table by arguing with that sentence.
 *
 * <p><b>Why not {@code HotTopic.priority}.</b> Priority is condition-dependent for at least three
 * kinds — aurora is 1 or 2 tonight but a hard-coded 3 for tomorrow, bluebell moves between 1 and 3
 * — so a strip keyed on it would change which coincidence it promotes for reasons that have
 * nothing to do with rarity, and would do so between two serves of the same night.
 *
 * <p>The rank is <em>advice</em>: this class ranks, and nothing here enforces the one-strip rule.
 * The projection publishes the lowest rank on a window and the client decides.
 */
public final class TopicRarity {

    /**
     * Rank given to a kind with no entry below — sorts last, so a new strategy cannot silently
     * outrank an established one by being unknown.
     *
     * <p>A test asserts every kind any strategy emits has an explicit entry, so reaching this in
     * production means a kind was added without a rarity decision being taken.
     */
    public static final int UNKNOWN_RANK = Integer.MAX_VALUE;

    private static final Map<String, Integer> RANK_BY_TYPE = Map.ofEntries(
            Map.entry("SUPERMOON", 1),
            Map.entry("EQUINOX", 2),
            Map.entry("KING_TIDE", 3),
            Map.entry("AURORA", 4),
            Map.entry("METEOR", 5),
            Map.entry("STORM_SURGE", 6),
            Map.entry("INVERSION", 7),
            Map.entry("SNOW_TOPS", 8),
            Map.entry("SNOW_MIST", 9),
            Map.entry("SNOW_FRESH", 10),
            Map.entry("DUST", 11),
            Map.entry("CLEARANCE", 12),
            Map.entry("BLUEBELL", 13),
            Map.entry("NLC", 14),
            Map.entry("SPRING_TIDE", 15));

    private TopicRarity() {
    }

    /**
     * The rarity rank of a hot-topic kind — 1 is rarest.
     *
     * @param type the {@code HotTopic.type} string, or null
     * @return the kind's rank, or {@link #UNKNOWN_RANK} when the kind has no entry
     */
    public static int rankOf(String type) {
        return type == null ? UNKNOWN_RANK : RANK_BY_TYPE.getOrDefault(type, UNKNOWN_RANK);
    }

    /**
     * The kinds with an explicit rank, for the test that pins the vocabulary.
     *
     * @return an unmodifiable view of the ranked kinds
     */
    public static Map<String, Integer> rankedTypes() {
        return RANK_BY_TYPE;
    }
}
