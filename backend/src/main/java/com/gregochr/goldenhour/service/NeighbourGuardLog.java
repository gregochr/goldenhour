package com.gregochr.goldenhour.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The neighbour guard's INFO lines (docs/engineering/neighbour-guard-plan.md §4, Logging). Two kinds,
 * both ids and integers only, never a name:
 * <ul>
 *   <li>one line the first time a (location id, date, type, sky rating) is flagged in this JVM, so a
 *       place that stays flagged for hours is not logged on every serve; the set of keys seen is
 *       bounded at {@value #MAX_KEYS} and <b>FIFO</b>: first-time-per-JVM, and a key evicted by the
 *       bound is logged again if it is flagged afterwards;</li>
 *   <li>one summary whenever the briefing's {@code generatedAt}, the flagged count or the coverage
 *       percent changes: flagged N of M rated sky slots over W windows, sky-rating coverage K%. This
 *       is the figure the rollout reads.</li>
 * </ul>
 * The sink is injectable so tests read the lines without a logging framework (the
 * {@code AskDenialCounter} shape).
 */
@Component
public class NeighbourGuardLog {

    private static final Logger LOG = LoggerFactory.getLogger(NeighbourGuardLog.class);

    /** The most per-slot keys remembered. */
    static final int MAX_KEYS = 1_024;

    /** One flagged slot at one sky rating: the identity of a per-slot line. */
    private record Flagged(NeighbourOutlierGuard.Key key, int skyRating) {
    }

    /** The last summary's identity: the build, the flagged count and the coverage it reported. */
    private record Basis(LocalDateTime generatedAt, int flagged, int coveragePercent) {
    }

    private final Consumer<String> sink;
    private final Set<Flagged> seen = new LinkedHashSet<>();
    private Basis lastSummary;

    /** Creates the log, writing through the INFO logger. */
    @Autowired
    public NeighbourGuardLog() {
        this(LOG::info);
    }

    /**
     * Creates the log with its own sink (tests).
     *
     * @param sink receives each finished line
     */
    NeighbourGuardLog(Consumer<String> sink) {
        this.sink = sink;
    }

    /**
     * Records one pass: a line for each slot not flagged before, and the summary when it changed.
     *
     * @param generatedAt the briefing's generation stamp
     * @param result      what the pass found
     */
    public void record(LocalDateTime generatedAt, NeighbourOutlierGuard.Result result) {
        synchronized (seen) {
            for (Map.Entry<NeighbourOutlierGuard.Key, Integer> flagged
                    : result.flaggedRatings().entrySet()) {
                if (!alreadySeen(flagged.getKey(), flagged.getValue())) {
                    sink.accept("[NEIGHBOUR GUARD] Location " + flagged.getKey().locationId()
                            + " flagged as a neighbour outlier for " + flagged.getKey().date() + " "
                            + flagged.getKey().targetType() + " (sky rating " + flagged.getValue() + ")");
                }
            }
            Basis basis = new Basis(generatedAt, result.flagged().size(), result.coveragePercent());
            if (!Objects.equals(basis, lastSummary)) {
                lastSummary = basis;
                sink.accept("[NEIGHBOUR GUARD] Flagged " + result.flagged().size() + " of "
                        + result.ratedSkySlots() + " rated sky slots over " + result.windows()
                        + " windows, sky-rating coverage " + result.coveragePercent() + "%");
            }
        }
    }

    /**
     * How many per-slot keys are remembered.
     *
     * @return the number of keys held, at most {@value #MAX_KEYS}
     */
    int rememberedKeys() {
        synchronized (seen) {
            return seen.size();
        }
    }

    /** True when this key and rating were already logged; otherwise remembers them, evicting the oldest. */
    private boolean alreadySeen(NeighbourOutlierGuard.Key key, int skyRating) {
        if (!seen.add(new Flagged(key, skyRating))) {
            return true;
        }
        if (seen.size() > MAX_KEYS) {
            var oldest = seen.iterator();
            oldest.next();
            oldest.remove();
        }
        return false;
    }
}
