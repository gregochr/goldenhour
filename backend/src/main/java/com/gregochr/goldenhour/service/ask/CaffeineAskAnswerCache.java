package com.gregochr.goldenhour.service.ask;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The typed-answer cache (plan §2.5 step 6, §1 #16, D-6): an answer already paid for is served again
 * to the next reader who asks the same question, free ({@code kind: own}, {@code charged: false}),
 * so long as it is still true.
 *
 * <h2>The key</h2>
 * {@code (sorted region ids | ALL, UK civil date, briefing generatedAt, normalised question,
 * windowId | none, userId | shared)}, exactly §2.5 step 6:
 * <ul>
 *   <li><b>Scope</b>: {@link AskScope#key}, the question's region ids de-duplicated and sorted
 *       ({@code 3,7}, never {@code 7,3}); none means {@code ALL}. Two scopes never share an
 *       answer.</li>
 *   <li><b>UK civil date</b> ({@link ForecastHorizon#today}, the injected clock): at 00:30 BST on
 *       6 October, which is still 5 October in UTC, the key says 6 October. A day word in the question
 *       ("tomorrow") means something different the next day, so the date is part of the key.</li>
 *   <li><b>Briefing {@code generatedAt}</b>: a new build is a new key, so an answer built on an old
 *       forecast is not looked up once a new one is served (it ages out by TTL).</li>
 *   <li><b>Normalised question</b> and <b>window id</b> (null when the question had none).</li>
 *   <li><b>User</b>: {@code shared} — no user in the key — unless the conversation was
 *       <b>personal</b>.</li>
 *   <li><b>Favourites fingerprint</b> (find-and-favourites-plan F4b): a personal answer's key also carries a hash
 *       of the asker's favourite set, so starring or un-starring a place can never serve an answer made under the
 *       old set. A shared key has none.</li>
 * </ul>
 *
 * <p>When the asker's favourites touch the question's scope the shared key is <em>not</em> tried: a shared answer
 * was made in the favourite-blind order, and that asker's order may differ, so they ask the engine (or hit their
 * own key) instead of being handed another asker's order. So that their repeat question still hits, a shared answer
 * stored for such an asker is also kept under their own key and fingerprint.
 *
 * <h2>Personal answers never cross users</h2>
 * {@link AskOutcome#personal()} is set by the tools themselves, the moment a conversation asks for
 * {@code maxDriveMinutes} (drive times are the asker's own, stored against their home): the server
 * decides it, never the model. A personal answer is stored under the asker's id, and a lookup tries
 * the shared key and then <em>only that user's own</em> key — there is no way to name another user's
 * key. So user B asking the question user A asked "within an hour of home" can never be served A's
 * answer: B's lookup finds nothing under the shared key or B's key and goes to the engine. A shared
 * answer is stored with no user, and is the same for everyone. (A personal answer is not re-keyed on
 * a change of home within its 30 minutes: a residual, bounded by the TTL.)
 *
 * <h2>A hit is re-checked against live data, every time</h2>
 * Never trusting the key's {@code generatedAt} (ratings are re-enriched on every serve and hot topics
 * recomputed live, plan §2.1), a hit passes {@link AskReadyFreshness#recheck} — the very test a Ready
 * answer passes, shared not copied: every pick's window still in the window set, its slot still
 * pick-eligible in scope with the <em>same rating and verdict</em> it was answered under, every event
 * still live. Fail it and the entry is evicted and the lookup is a miss. What passes is re-decorated
 * from the live snapshot, so names, dates and safety notes are always today's.
 *
 * <h2>What is stored</h2>
 * Only an {@code OK} answer that has a pick or an event to re-check, and never a night answer
 * ({@link AskOutcome#uncacheable()}: a conversation that called {@code rank_night_spots}). An answer with neither
 * ("nothing is worth it today") is prose about the whole forecast with nothing the freshness test can
 * hold it to, so it is not cached: serving it later could contradict the forecast. Nothing is stored
 * while a simulation is active (plan §1 #21): the data was not real.
 *
 * <p>Bounded: {@code photocast.ask.cache.max-entries} entries and
 * {@code photocast.ask.cache.ttl-minutes} minutes from the write. In memory, per process (D-8): a
 * restart empties it. Maintenance runs on the calling thread, so the bound holds the moment an
 * entry is written and tests are deterministic. The cache's time is the injected clock's.
 */
@Component
public class CaffeineAskAnswerCache implements AskAnswerCache {

    private static final Logger LOG = LoggerFactory.getLogger(CaffeineAskAnswerCache.class);

    /**
     * The cache key (plan §2.5 step 6).
     *
     * @param scope       {@link AskScope#key}: sorted region ids joined with commas, or {@code ALL}
     * @param date        the UK civil date
     * @param generatedAt the briefing build the answer was made from, or null
     * @param question    the normalised question
     * @param windowId    the context window, or null
     * @param userId      the asker for a personal answer; null for a shared one
     * @param favourites  the asker's favourites fingerprint for a personal answer; null for a shared one, and for
     *                    an asker with no favourites
     */
    record Key(String scope, LocalDate date, LocalDateTime generatedAt, String question,
            String windowId, Long userId, String favourites) {
    }

    /**
     * A stored answer with the scope it was made for.
     *
     * @param answer the validated answer, picks carrying their rating and verdict at answer time
     * @param scope  the question's scope, kept so the freshness test needs no database read
     */
    private record Stored(AskAnswer answer, AskScope scope) {
    }

    private final Cache<Key, Stored> entries;
    private final HotTopicSimulationService hotTopicSimulation;
    private final AuroraStateCache auroraStateCache;
    private final Clock clock;

    /**
     * Creates the cache.
     *
     * @param properties         the Ask settings ({@code cache.max-entries}, {@code cache.ttl-minutes})
     * @param hotTopicSimulation the hot-topic simulation switch
     * @param auroraStateCache   the aurora state, whose simulated data marks an aurora simulation
     * @param clock              the application clock: the key's UK date and the entries' time
     */
    public CaffeineAskAnswerCache(AskProperties properties, HotTopicSimulationService hotTopicSimulation,
            AuroraStateCache auroraStateCache, Clock clock) {
        this.hotTopicSimulation = hotTopicSimulation;
        this.auroraStateCache = auroraStateCache;
        this.clock = clock;
        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        this.entries = Caffeine.newBuilder()
                .maximumSize(properties.getCache().getMaxEntries())
                .expireAfterWrite(Duration.ofMinutes(properties.getCache().getTtlMinutes()))
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
    }

    @Override
    public Optional<AskAnswer> lookup(AskQuestion question, AskSnapshot snapshot, AskUserContext user) {
        List<Key> keys = new ArrayList<>(2);
        if (!favouritesTouchScope(question, snapshot, user)) {
            keys.add(key(question, snapshot, null, null));
        }
        if (user != null && user.hasUser()) {
            keys.add(key(question, snapshot, user.userId(), user.favouritesFingerprint()));
        }
        for (Key key : keys) {
            Stored stored = entries.getIfPresent(key);
            if (stored == null) {
                continue;
            }
            AskReadyFreshness.Verdict verdict = AskReadyFreshness.recheck(stored.answer(), snapshot,
                    stored.scope(), user != null && user.canSeeAurora());
            if (verdict.fresh()) {
                return Optional.of(verdict.answer());
            }
            // Only this entry is dropped, and only when the live data says it is no longer true.
            entries.invalidate(key);
            LOG.debug("[ASK] Cached answer withheld: {}", verdict.reason());
        }
        return Optional.empty();
    }

    @Override
    public void store(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
            AskOutcome outcome) {
        if (outcome == null || outcome.status() != AskOutcome.Status.OK || outcome.answer() == null
                || !outcome.answer().answerable()) {
            return;
        }
        if (outcome.uncacheable()) {
            // A night answer is never stored (ask-night plan §2.2): the key carries no role, so one asker's
            // figures could reach another, and the facts behind it move under the TTL.
            return;
        }
        AskAnswer answer = outcome.answer();
        if (answer.picks().isEmpty() && answer.events().isEmpty()) {
            return;
        }
        // A shared answer has no owner; a personal one is kept for its asker alone.
        Long owner = null;
        String favourites = null;
        if (outcome.personal()) {
            if (user == null || !user.hasUser()) {
                // A personal answer with nobody to keep it for cannot be shared: drop it.
                return;
            }
            owner = user.userId();
            favourites = user.favouritesFingerprint();
        }
        if (AskSimulation.active(hotTopicSimulation, auroraStateCache)) {
            return;
        }
        Stored stored = new Stored(answer, question.scope());
        entries.put(key(question, snapshot, owner, favourites), stored);
        if (owner == null && user != null && user.hasUser() && favouritesTouchScope(question, snapshot, user)) {
            // A shared answer this asker will never be handed (their favourites touch the scope, so the shared key
            // is skipped for them): keep it under their own key too, so their repeat question still hits.
            entries.put(key(question, snapshot, user.userId(), user.favouritesFingerprint()), stored);
        }
    }

    /**
     * How many entries the cache holds, after pending maintenance.
     *
     * @return the entry count
     */
    long size() {
        entries.cleanUp();
        return entries.estimatedSize();
    }

    private Key key(AskQuestion question, AskSnapshot snapshot, Long userId, String favourites) {
        return new Key(question.scope().key(), ForecastHorizon.today(clock),
                snapshot.generatedAt(), question.normalised(), question.windowId(), userId, favourites);
    }

    /** Whether any of the asker's favourites is a place in the question's scope. */
    private static boolean favouritesTouchScope(AskQuestion question, AskSnapshot snapshot, AskUserContext user) {
        if (user == null || !user.hasFavourites()) {
            return false;
        }
        return snapshot.windows().stream()
                .flatMap(w -> w.regions().stream())
                .filter(r -> question.scope().contains(r.name()))
                .flatMap(r -> r.slots().stream())
                .anyMatch(slot -> user.isFavourite(slot.locationId()));
    }
}
