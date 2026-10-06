package com.gregochr.goldenhour.service.ask;

/**
 * The question log (plan §2.5, {@code ask_log}): one row per request that was <em>answered</em>.
 * Denied requests write nothing. <b>B5 implements it</b>; until then {@link NoOpAskLog} (a
 * {@code @Fallback} bean) is wired, which records nothing. A real {@code @Component} wins over it.
 */
public interface AskLog {

    /** How a request was answered. */
    enum Outcome {
        /** A Ready answer matched the question. */
        READY_MATCH,
        /** The pre-filter said the forecast can never answer it. */
        PREFILTER_CANT,
        /** A cached typed answer was served. */
        CACHE_HIT,
        /** The engine answered. */
        CLAUDE_OK,
        /** The engine said it is not in the forecast. */
        CLAUDE_CANT,
        /** The engine failed. */
        CLAUDE_FAILED
    }

    /**
     * One log row.
     *
     * @param userId             the asker
     * @param scopeKey           {@code ALL} or the single region's id as text
     * @param view               {@code map}, {@code plan} or {@code coming-up}
     * @param outcome            how it was answered
     * @param normalisedQuestion the normalised question; B5 stores it only for {@code CLAUDE_OK} and
     *                           {@code CLAUDE_CANT}
     * @param missing            what the answer said was missing, or null
     * @param durationMs         how long the request took
     */
    record Entry(long userId, String scopeKey, String view, Outcome outcome,
            String normalisedQuestion, String missing, long durationMs) {
    }

    /**
     * Records one answered request. Must never throw into the request: a log failure is not a reason
     * to lose an answer the reader has paid for.
     *
     * @param entry the row
     */
    void record(Entry entry);
}
