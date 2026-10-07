package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.comingup.ComingUpCondition;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.comingup.ComingUpAssembler;
import com.gregochr.goldenhour.service.comingup.ComingUpConditionsBuilder;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.Rewind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
 * <p>Everything here is ephemeris arithmetic and DB reads, so a miss is not worth defending against
 * with a stampede lock — two concurrent requests on a new day both build, both write, and the
 * answers are identical because they are pure functions of the date and the stored data. A build is
 * not free, though (the tide-magnitude history is a three-year scan), so the cache is also
 * <b>warmed</b> rather than left to the first reader of the day: {@link #refresh()} rebuilds and
 * swaps it in at the tail of every pipeline cycle (where fresh forecast data has just been written,
 * which the feed's conditions strip reads) and once at startup (a restart drops the in-memory cache).
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
    private final ComingUpAssembler assembler;
    private final ComingUpConditionsBuilder conditionsBuilder;
    private final Executor warmExecutor;

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
     * @param sources   every almanac source Spring can find
     * @param clock     supplies "today" — the feed's first day and its cache key — resolved in
     *                  {@code Europe/London} by {@link ForecastHorizon}
     * @param assembler         grows each eligible event to the full chronology entry shape
     *                          (plan §5 P2)
     * @param conditionsBuilder builds the standing-conditions strip (plan §7 P4)
     */
    @Autowired
    public AlmanacService(List<AlmanacSource> sources, Clock clock, ComingUpAssembler assembler,
            ComingUpConditionsBuilder conditionsBuilder) {
        this(sources, clock, assembler, conditionsBuilder, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Constructs an {@code AlmanacService} with an explicit executor for the startup warm, so a test
     * can run it inline.
     *
     * @param sources           every almanac source Spring can find
     * @param clock             supplies "today"
     * @param assembler         grows each eligible event to the full chronology entry shape
     * @param conditionsBuilder builds the standing-conditions strip
     * @param warmExecutor      where the startup warm runs — never the main thread
     */
    AlmanacService(List<AlmanacSource> sources, Clock clock, ComingUpAssembler assembler,
            ComingUpConditionsBuilder conditionsBuilder, Executor warmExecutor) {
        this.sources = List.copyOf(sources);
        this.clock = clock;
        this.assembler = assembler;
        this.conditionsBuilder = conditionsBuilder;
        this.warmExecutor = warmExecutor;
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

        // An admin's rewind (X-Rewind-To) turns the clock bean back for one request. That request
        // must neither read the day cache — built for the real today — nor write into it, or the
        // rewound feed would be served to everyone until the next real request happened to miss.
        if (Rewind.isActive()) {
            return assemble(today, today.plusDays(clamped - 1L));
        }

        CachedFeed cached = cache.get();
        if (cached != null && cached.builtFor().isEqual(today) && cached.days() == clamped) {
            return cached.response();
        }

        ComingUpResponse built = assemble(today, today.plusDays(clamped - 1L));
        // Fill the slot only if it is still the one this reader found wanting. A refresh() that
        // landed while this build ran was built from newer data (the pipeline tail runs it after a
        // cycle has written), so it must win; a plain set would put this older answer over it until
        // the next cycle. A lost race still returns what was built — correct when it started.
        cache.compareAndSet(cached, new CachedFeed(today, clamped, built));
        return built;
    }

    /**
     * Rebuilds today's default-horizon feed and swaps it into the cache.
     *
     * <p><b>Build first, then replace.</b> The new response is fully assembled before the one
     * {@code cache.set}, so a reader arriving during the build is served the previous answer rather
     * than finding an empty slot and paying for a build of its own; contrast {@link #evict()}, which
     * empties it. A failed build throws and leaves the previous cache exactly as it was.
     *
     * <p>Called after every pipeline cycle's run has finished (the standing conditions are read from
     * the data the cycle has just written, and until now stayed as they were at the day's first
     * request) and once at application start. Refuses to run on a thread with an admin rewind set —
     * the same guard {@link #getFeed(int)} has, since a rewound clock would build the wrong day's
     * feed and put it where everyone reads — although neither caller can have one.
     *
     * @return true when the cache was replaced, false when the warm was refused for a rewind
     */
    public boolean refresh() {
        if (Rewind.isActive()) {
            LOG.warn("Almanac refresh skipped: an admin rewind is set on this thread");
            return false;
        }
        LocalDate today = ForecastHorizon.today(clock);
        long started = System.nanoTime();
        ComingUpResponse built = assemble(today, today.plusDays(DEFAULT_DAYS - 1L));
        cache.set(new CachedFeed(today, DEFAULT_DAYS, built));
        LOG.info("Almanac feed refreshed for {} ({} days, {} entries) in {} ms", today, DEFAULT_DAYS,
                built.entries().size(), (System.nanoTime() - started) / 1_000_000L);
        return true;
    }

    /**
     * Warms the cache once the application is up, off the main thread.
     *
     * <p>A restart drops the in-memory feed, so without this the first reader after every deploy pays
     * for the whole build. A failure is logged and ignored: the feed then builds on first request
     * exactly as it did before the warm existed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        try {
            warmExecutor.execute(() -> {
                try {
                    refresh();
                } catch (RuntimeException e) {
                    LOG.warn("Almanac startup warm failed — the feed will build on first request: {}",
                            e.toString());
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Almanac startup warm could not be dispatched: {}", e.toString());
        }
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
     * grown to the full chronology shape by {@link ComingUpAssembler}, plus the standing-conditions
     * strip (plan §7 P4) — which needs the <b>unfiltered</b> merge too, since a run wholly inside
     * Plan's window is strip material even though it never becomes a chronology entry (D11).
     *
     * @param builtFor the date the feed starts at — also the response's {@code builtFor}
     * @param to       last day, inclusive
     * @return the assembled response
     */
    private ComingUpResponse assemble(LocalDate builtFor, LocalDate to) {
        List<AlmanacEvent> all = build(builtFor, to);
        LocalDate cutoff = PlanHorizon.lastPlanDate(builtFor);
        List<AlmanacEvent> eligible = all.stream()
                .filter(event -> event.endDate().isAfter(cutoff))
                .toList();
        ComingUpResponse core = assembler.assemble(builtFor, eligible);
        List<ComingUpCondition> conditions = conditionsBuilder.build(builtFor, all, core.entries());
        return new ComingUpResponse(core.builtFor(), core.bands(), core.counts(), conditions, core.entries());
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
