package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Assembles the "Coming up" almanac feed from every {@link AlmanacSource}.
 *
 * <p><strong>Why this does not go through {@code HotTopicAggregator}.</strong> Three reasons, any
 * one of which would be enough. Ten of its thirteen strategies are bounded well short of ninety
 * days whatever range they are handed. It applies a travel-day filter, which is a "should you go
 * out tonight" concern that would silently delete a solstice. And it honours a simulation override
 * that exists for demoing the Plan tab — a feed quietly serving simulated tides months out is
 * exactly the failure the degrade rule is written to prevent.
 *
 * <p><strong>The cache is a whole-value swap, and it is not the briefing's carrier pattern.</strong>
 * CLAUDE.md's warning about build-time enrichment does not apply here — that trap is specific to
 * {@code HotTopic} fields written into {@code daily_briefing_cache} and then discarded when the
 * serve path recomputes topics live. This feed has no separate build path and nothing persisted:
 * one {@link AtomicReference} holds a fully-formed day's answer, and a request either finds today's
 * or rebuilds the whole thing. There is no read-modify-write and therefore no way for half a
 * payload to survive into the next day.
 *
 * <p>Everything here is ephemeris arithmetic and DB reads, so a cold rebuild is cheap and a miss is
 * not worth defending against with a stampede lock — two concurrent requests on a new day both
 * build, both write, and the answers are identical because they are pure functions of the date.
 *
 * <p><strong>Eligibility (plan §2 D1).</strong> An almanac event becomes a {@link ComingUpEntry}
 * only once it ends beyond Plan's four-day window ({@link PlanHorizon#lastPlanDate}) — an entry
 * wholly inside that window is strip material, not chronology material, and a straddling run stays
 * eligible because its dates say so. {@code build} itself stays raw and unfiltered: it is the
 * per-source merge every existing test asserts on directly.
 */
@Service
public class AlmanacService {

    private static final Logger LOG = LoggerFactory.getLogger(AlmanacService.class);

    /** Default feed length, and the horizon the tide fetch window is sized to serve. */
    public static final int DEFAULT_DAYS = 90;

    /** Shortest feed a caller may ask for. */
    public static final int MIN_DAYS = 1;

    /**
     * Longest feed a caller may ask for.
     *
     * <p>Above the tide fetch horizon the extra days are honest but thin — dates with no figures —
     * and the ceiling exists so a caller cannot turn a cheap read into a year of day-by-day lunar
     * arithmetic by passing a large number.
     */
    public static final int MAX_DAYS = 365;

    private final List<AlmanacSource> sources;
    private final Clock clock;

    /** Today's fully-built feed, or null before the first build of the day. */
    private final AtomicReference<CachedFeed> cache = new AtomicReference<>();

    /**
     * One day's built feed, keyed by the day it was built for and the length asked for.
     *
     * @param builtFor the date the feed starts at
     * @param days     the number of days requested
     * @param response the assembled response
     */
    private record CachedFeed(LocalDate builtFor, int days, ComingUpResponse response) { }

    /**
     * Constructs an {@code AlmanacService}.
     *
     * @param sources every almanac source Spring can find
     * @param clock   supplies "today" — the feed's first day and its cache key — resolved in
     *                {@code Europe/London} by {@link ForecastHorizon}
     */
    public AlmanacService(List<AlmanacSource> sources, Clock clock) {
        this.sources = List.copyOf(sources);
        this.clock = clock;
    }

    /**
     * Returns the feed for the default horizon starting today.
     *
     * @return the assembled response
     */
    public ComingUpResponse getFeed() {
        return getFeed(DEFAULT_DAYS);
    }

    /**
     * Returns the feed for the given number of days starting today.
     *
     * @param days feed length, clamped to {@value #MIN_DAYS}..{@value #MAX_DAYS}
     * @return the assembled response
     */
    public ComingUpResponse getFeed(int days) {
        int clamped = Math.clamp(days, MIN_DAYS, MAX_DAYS);
        // The UK civil date, for the same reason the rest of the app counts days that way: every
        // entry in this feed is a UK-dated event — a spring tide run, an equinox, an NLC season.
        // On a UTC anchor, for the hour *after* UK midnight in summer — 23:00–00:00 UTC, which is
        // 00:00–01:00 BST — the feed opened on the UK's *yesterday*, so something that had already
        // happened could lead a list headed "Coming up".
        // It also keys the cache, which therefore turned over an hour late for the same reason.
        LocalDate today = ForecastHorizon.today(clock);

        CachedFeed cached = cache.get();
        if (cached != null && cached.builtFor().isEqual(today) && cached.days() == clamped) {
            return cached.response();
        }

        ComingUpResponse built = assemble(today, today.plusDays(clamped - 1L));
        cache.set(new CachedFeed(today, clamped, built));
        return built;
    }

    /**
     * Builds the raw almanac events for an explicit range, bypassing the cache and the eligibility
     * filter.
     *
     * @param from first day, inclusive
     * @param to   last day, inclusive
     * @return every source's events, merged and sorted ascending — unfiltered
     */
    List<AlmanacEvent> build(LocalDate from, LocalDate to) {
        List<AlmanacEvent> all = new ArrayList<>();
        for (AlmanacSource source : sources) {
            try {
                all.addAll(source.events(from, to));
            } catch (RuntimeException e) {
                // One source failing must not blank the feed. A missing tide run is a smaller
                // problem than an empty Coming-up tab, and the alternative — letting it propagate —
                // would make the whole feed hostage to the one source that touches the database.
                LOG.warn("Almanac source {} failed for {}..{}: {}",
                        source.getClass().getSimpleName(), from, to, e.toString());
            }
        }
        return all.stream().sorted().toList();
    }

    /**
     * Builds the response for an explicit range: the raw merge, filtered to eligible entries and
     * enriched with {@code enteredWindow}.
     *
     * @param builtFor the date the feed starts at — also the response's {@code builtFor}
     * @param to       last day, inclusive
     * @return the assembled response
     */
    private ComingUpResponse assemble(LocalDate builtFor, LocalDate to) {
        LocalDate cutoff = PlanHorizon.lastPlanDate(builtFor);
        List<ComingUpEntry> entries = build(builtFor, to).stream()
                .filter(event -> event.endDate().isAfter(cutoff))
                .map(event -> ComingUpEntry.from(
                        event, event.startDate().minusDays(DEFAULT_DAYS - 1L)))
                .toList();
        return new ComingUpResponse(builtFor, null, null, List.of(), entries);
    }

    /**
     * Drops the cached feed so the next request rebuilds.
     *
     * <p>Exists for the admin refresh path and for tests; the feed otherwise turns over on its own
     * at the date roll.
     */
    public void evict() {
        cache.set(null);
    }
}
