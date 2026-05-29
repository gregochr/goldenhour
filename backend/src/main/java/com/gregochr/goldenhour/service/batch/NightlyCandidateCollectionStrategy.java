package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Nightly cycle's candidate-collection strategy: accept every future event slot.
 *
 * <p>The nightly batch's role is to refresh the full forward window; it does not
 * trim to a subset. Past-date filtering happens inside the collector before this
 * predicate is consulted (see {@link CandidateCollectionStrategy}), so this
 * always returns {@code true} for the inputs it actually sees.
 *
 * <p>This is the default strategy and the only concrete impl until the intraday
 * cycle adds its own.
 */
public final class NightlyCandidateCollectionStrategy implements CandidateCollectionStrategy {

    /**
     * Shared instance — the strategy is stateless so a singleton is correct.
     */
    public static final NightlyCandidateCollectionStrategy INSTANCE =
            new NightlyCandidateCollectionStrategy();

    /**
     * Private constructor — the strategy is stateless, so all callers share
     * {@link #INSTANCE}.
     */
    private NightlyCandidateCollectionStrategy() {
    }

    @Override
    public boolean includes(LocalDate date, TargetType targetType) {
        return true;
    }
}
