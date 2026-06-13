package com.gregochr.goldenhour.model;

/**
 * Explicit outcome discriminator for a best-bet advisor run.
 *
 * <p>The API must tell the UI <em>what happened</em>, not leave it to infer intent from an
 * empty pick list. Without this, an empty Best Bet has two opposite meanings the frontend
 * cannot distinguish — an honest "nothing stands out today" versus a machinery failure that
 * lost a genuinely good pick — and they demand opposite UX (show the empty state vs fall back
 * to the last good pick). This enum is set by the advisor layer (the only layer that knows
 * which path was taken) and reports it; it never changes which path is taken.
 */
public enum BestBetStatus {

    /** The advisor produced one or more usable picks (including picks salvaged from a
     *  partially-truncated response). */
    SUCCESS_WITH_PICKS,

    /** The advisor ran and evaluated, and honestly found nothing worth crowning — a
     *  genuinely flat week. NOT a failure; the honest empty state is the correct output. */
    SUCCESS_NO_PICKS,

    /** The advisor did not produce a usable result: an exception, a parse failure, or a
     *  truncated response from which no valid pick could be salvaged. There may have been a
     *  good pick; the machinery lost it. This is the case that warrants the stale fallback. */
    FAILED
}
