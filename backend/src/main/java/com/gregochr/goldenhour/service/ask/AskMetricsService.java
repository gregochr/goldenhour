package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.AskLogRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the owner reads to judge Ask PhotoCast (plan §2.9, {@code GET /api/admin/ask/metrics}): how
 * answered requests split by outcome, how often a question was free, what the forecast could not
 * answer, and what it cost.
 *
 * <p><b>It never returns a question.</b> The only free text it reads is the {@code missing} phrase
 * ("car park information") of a can't-answer — what PhotoCast lacks, from the pre-filter's own fixed
 * table or the engine's word-capped field — and the aggregate queries it calls select no question
 * column at all.
 *
 * <p>The window is the last {@code days} UK civil days <b>including today</b>: {@code days = 1} is
 * since UK midnight, {@code days = 7} since UK midnight six days ago. {@code days} is clamped to
 * {@value #MIN_DAYS}..{@value #MAX_DAYS} (the log keeps 90 days by default, so a wider window could
 * only be wrong).
 *
 * <p><b>The rates</b> (null when the denominator is zero, never a made-up zero):
 * <ul>
 *   <li>{@code cacheHitRate} = {@code CACHE_HIT} / ({@code CACHE_HIT} + engine runs), where an engine
 *       run is {@code CLAUDE_OK}, {@code CLAUDE_CANT} or {@code CLAUDE_FAILED}: of the questions that
 *       got as far as the cache, how many it answered.</li>
 *   <li>{@code readyMatchRate} = {@code READY_MATCH} / every answered typed request (plan Q5: Ready
 *       hits are measured for typed questions only; a tap on a Ready question makes no request).</li>
 *   <li>{@code cantRate} = ({@code PREFILTER_CANT} + {@code CLAUDE_CANT}) / answered requests, where
 *       answered excludes {@code CLAUDE_FAILED}: the share of answers that said "not in the
 *       forecast", the owner's guide to what readers want that PhotoCast does not hold.</li>
 * </ul>
 * <b>Spend</b> is the recorded cost of every call logged against runs of the type that started in the
 * window ({@code ApiCallLogRepository.sumCostMicroDollarsByRunTypeStartedSince}, the figure the
 * spend cap reads for today): typed is {@code ASK} (it includes an admin's dry-run with the Claude
 * engine), Ready is {@code ASK_READY}. Cost the engine could not record (the in-memory holder of
 * {@link AskJobRunService}) is not in a multi-day figure.
 */
@Service
public class AskMetricsService {

    /** The narrowest window. */
    public static final int MIN_DAYS = 1;

    /** The widest window. */
    public static final int MAX_DAYS = 90;

    /** The window when none is given. */
    public static final int DEFAULT_DAYS = 7;

    /** How many missing phrases are returned. */
    static final int TOP_MISSING = 10;

    private static final double MICRO_PER_DOLLAR = 1_000_000.0;

    /**
     * One missing phrase and how often it was the reason.
     *
     * @param phrase what PhotoCast does not have
     * @param count  how many answers named it
     */
    public record MissingPhrase(String phrase, long count) {
    }

    /**
     * The metrics.
     *
     * @param days           the window, after clamping
     * @param from           the first UK civil date in the window
     * @param total          every answered request in the window
     * @param outcomes       the count of each outcome, every outcome present (zero when none)
     * @param cacheHitRate   see the class documentation; null with no denominator
     * @param readyMatchRate see the class documentation; null with no requests
     * @param cantRate       see the class documentation; null with no answered requests
     * @param topMissing     the most common missing phrases, most common first, at most ten
     * @param typedSpendUsd  recorded cost of typed (and dry-run) questions in the window, US dollars
     * @param readySpendUsd  recorded cost of Ready precompute in the window, US dollars
     */
    public record Metrics(int days, LocalDate from, long total, Map<String, Long> outcomes,
            Double cacheHitRate, Double readyMatchRate, Double cantRate, List<MissingPhrase> topMissing,
            double typedSpendUsd, double readySpendUsd) {
    }

    private final AskLogRepository logRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param logRepository        the question log
     * @param apiCallLogRepository the recorded cost of every call
     * @param clock                the application clock (the UK civil day)
     */
    public AskMetricsService(AskLogRepository logRepository, ApiCallLogRepository apiCallLogRepository,
            Clock clock) {
        this.logRepository = logRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.clock = clock;
    }

    /**
     * Clamps a requested window to {@value #MIN_DAYS}..{@value #MAX_DAYS}.
     *
     * @param days what was asked for
     * @return the window to use
     */
    public static int clampDays(int days) {
        return Math.min(MAX_DAYS, Math.max(MIN_DAYS, days));
    }

    /**
     * Clamps a window given as text, however large: "99999999999" is a window of {@value #MAX_DAYS}
     * days, not an error.
     *
     * @param days a whole number, optionally signed
     * @return the window to use
     * @throws NumberFormatException when the text is not a whole number
     */
    public static int clampDays(String days) {
        BigInteger parsed = new BigInteger(days);
        if (parsed.compareTo(BigInteger.valueOf(MIN_DAYS)) < 0) {
            return MIN_DAYS;
        }
        if (parsed.compareTo(BigInteger.valueOf(MAX_DAYS)) > 0) {
            return MAX_DAYS;
        }
        return parsed.intValue();
    }

    /**
     * Builds the metrics for the last {@code days} UK civil days.
     *
     * @param requestedDays the window, clamped to {@value #MIN_DAYS}..{@value #MAX_DAYS}
     * @return the metrics
     */
    public Metrics metrics(int requestedDays) {
        int days = clampDays(requestedDays);
        LocalDate from = ForecastHorizon.today(clock).minusDays(days - 1L);
        LocalDateTime startUtc = ForecastHorizon.ukDayStartUtc(from);
        Instant since = startUtc.toInstant(ZoneOffset.UTC);

        Map<AskLog.Outcome, Long> counts = new EnumMap<>(AskLog.Outcome.class);
        for (AskLog.Outcome outcome : AskLog.Outcome.values()) {
            counts.put(outcome, 0L);
        }
        for (AskLogRepository.OutcomeCount row : logRepository.countByOutcomeSince(since)) {
            outcomeOf(row.getOutcome()).ifPresent(outcome -> counts.merge(outcome, row.getTotal(),
                    Long::sum));
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long engineRuns = countOf(counts, AskLog.Outcome.CLAUDE_OK) + countOf(counts, AskLog.Outcome.CLAUDE_CANT)
                + countOf(counts, AskLog.Outcome.CLAUDE_FAILED);
        long answered = total - countOf(counts, AskLog.Outcome.CLAUDE_FAILED);
        long cant = countOf(counts, AskLog.Outcome.PREFILTER_CANT) + countOf(counts, AskLog.Outcome.CLAUDE_CANT);
        long hits = countOf(counts, AskLog.Outcome.CACHE_HIT);

        Map<String, Long> byName = new LinkedHashMap<>();
        counts.forEach((outcome, count) -> byName.put(outcome.name(), count));
        List<MissingPhrase> missing = logRepository.topMissingSince(since, PageRequest.of(0, TOP_MISSING))
                .stream().map(row -> new MissingPhrase(row.getMissing(), row.getTotal())).toList();

        return new Metrics(days, from, total, byName, rate(hits, hits + engineRuns),
                rate(countOf(counts, AskLog.Outcome.READY_MATCH), total), rate(cant, answered), missing,
                apiCallLogRepository.sumCostMicroDollarsByRunTypeStartedSince(RunType.ASK, startUtc)
                        / MICRO_PER_DOLLAR,
                apiCallLogRepository.sumCostMicroDollarsByRunTypeStartedSince(RunType.ASK_READY, startUtc)
                        / MICRO_PER_DOLLAR);
    }

    private static Optional<AskLog.Outcome> outcomeOf(String name) {
        try {
            return Optional.of(AskLog.Outcome.valueOf(name));
        } catch (IllegalArgumentException e) {
            // A row from a newer build (a rollback): not counted, never an error for the owner.
            return Optional.empty();
        }
    }

    /** One outcome's count; zero when absent, so no caller unboxes a missing entry. */
    private static long countOf(Map<AskLog.Outcome, Long> counts, AskLog.Outcome outcome) {
        return counts.getOrDefault(outcome, 0L);
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
