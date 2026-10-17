package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.util.Rewind;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The application's {@link Clock}: the system clock, except on a thread that carries a
 * {@link Rewind} instant, where it answers with that instant instead.
 *
 * <p>Every serve-time "now" in the app — {@code PlanWindowProjector}'s elapsed test through
 * {@code ServedBriefingAssembler}, {@code SolarEventFreshness} for every hot-topic strategy,
 * {@code BriefingRegionEvaluationRollup}'s horizon, {@code ForecastController}'s serve window,
 * {@code AlmanacService}'s anchor — reads the injected {@code Clock} bean at call time. Making
 * that bean rewind-aware is what lets one request header turn the whole payload back to an
 * earlier moment without threading an instant through a dozen signatures and nine strategies.
 *
 * <p>{@link #withZone(ZoneId)} returns another rewind-aware clock, never the bare system one: the
 * serve path reads {@code LocalDate.now(clock.withZone(LONDON))} and
 * {@code LocalDateTime.now(clock.withZone(UTC))} everywhere, and a zoned copy that forgot the
 * rewind would silently split "today" from "now".
 */
public final class RewindAwareClock extends Clock {

    private final Clock system;

    /**
     * Wraps a system clock.
     *
     * @param system the clock to answer with when no rewind is set
     */
    public RewindAwareClock(Clock system) {
        this.system = system;
    }

    @Override
    public ZoneId getZone() {
        return system.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (zone.equals(system.getZone())) {
            return this;
        }
        return new RewindAwareClock(system.withZone(zone));
    }

    @Override
    public Instant instant() {
        Instant rewound = Rewind.current();
        return rewound != null ? rewound : system.instant();
    }

    @Override
    public long millis() {
        return instant().toEpochMilli();
    }

    @Override
    public String toString() {
        return "RewindAwareClock[" + system + "]";
    }
}
