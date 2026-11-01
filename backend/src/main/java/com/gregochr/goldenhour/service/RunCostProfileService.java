package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.ApiCallCostRow;
import com.gregochr.goldenhour.model.RunCostProfile;
import com.gregochr.goldenhour.model.RunCostProfile.ModelCost;
import com.gregochr.goldenhour.model.RunCostProfile.TokenRates;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.Rewind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Measures what each Run Config tab costs from the logged Anthropic calls of the last
 * {@value #WINDOW_DAYS} UK days, so the admin screen renders served figures instead of constants.
 *
 * <p>Rows are classified into tabs, bucketed by {@code days_ahead} and aggregated in Java (no date
 * arithmetic in SQL: H2 and Postgres disagree). Models that did not run on a tab are estimated by
 * pricing the tab's average token mix through {@link CostCalculator}. Costs are calculated from
 * logged token counts, not read from an Anthropic bill; the cache-priming call and admin aurora
 * runs log nothing and are not included.
 *
 * <p>The result is memoised for {@link #MEMO_TTL} against the injected clock, and not memoised at
 * all while an admin rewind is set. The service never throws to its caller: a failure is logged at
 * WARN and yields empty figures.
 */
@Service
public class RunCostProfileService {

    private static final Logger LOG = LoggerFactory.getLogger(RunCostProfileService.class);

    /** The number of UK civil days (ending today) the figures are measured over. */
    public static final int WINDOW_DAYS = 30;

    /** How long a computed profile is reused. */
    public static final Duration MEMO_TTL = Duration.ofMinutes(10);

    /** The request label {@code BriefingBestBetAdvisor} logs its calls under. */
    static final String BEST_BET_URL = "briefing-best-bet";

    private static final String AURORA_BATCH_PREFIX = "au-";
    private static final List<String> FORECAST_BATCH_PREFIXES = List.of("fc-", "bb-", "wd-");

    /** A request this many days ahead or further belongs to the far-term model. */
    private static final int FAR_TERM_FROM_DAYS_AHEAD = 2;

    private static final double MICRO = 1_000_000.0;

    private record Snapshot(Map<String, RunCostProfile> profiles, Double gbpPerUsd) {
    }

    private record Memo(Instant computedAt, Snapshot snapshot) {
    }

    private final ApiCallLogRepository apiCallLogRepository;
    private final JobRunRepository jobRunRepository;
    private final CostCalculator costCalculator;
    private final CostProperties costProperties;
    private final Clock clock;
    private final AtomicReference<Memo> memo = new AtomicReference<>();

    /**
     * Constructs the service.
     *
     * @param apiCallLogRepository source of the logged calls
     * @param jobRunRepository     source of the latest exchange rate
     * @param costCalculator       prices the estimates
     * @param costProperties       the configured token prices
     * @param clock                the application clock
     */
    public RunCostProfileService(ApiCallLogRepository apiCallLogRepository, JobRunRepository jobRunRepository,
            CostCalculator costCalculator, CostProperties costProperties, Clock clock) {
        this.apiCallLogRepository = apiCallLogRepository;
        this.jobRunRepository = jobRunRepository;
        this.costCalculator = costCalculator;
        this.costProperties = costProperties;
        this.clock = clock;
    }

    /**
     * The cost profile of each Run Config tab, keyed by run type name.
     *
     * @return the profiles; empty when the figures could not be computed; never null
     */
    public Map<String, RunCostProfile> costProfiles() {
        return snapshot().profiles();
    }

    /**
     * The newest exchange rate recorded on a job run.
     *
     * @return GBP per USD, or empty when no run has recorded one or the lookup failed
     */
    public Optional<Double> gbpPerUsd() {
        return Optional.ofNullable(snapshot().gbpPerUsd());
    }

    /**
     * The configured token prices of every selectable model, in USD per million tokens.
     *
     * @return rates keyed by {@code EvaluationModel} name
     */
    public Map<String, TokenRates> tokenRates() {
        Map<String, TokenRates> rates = new LinkedHashMap<>();
        for (EvaluationModel model : EvaluationModel.selectable()) {
            rates.put(model.name(), ratesFor(model));
        }
        return rates;
    }

    private TokenRates ratesFor(EvaluationModel model) {
        return switch (model.pricingTier()) {
            case HAIKU -> new TokenRates(costProperties.getHaikuInputUsdPerMtok(),
                    costProperties.getHaikuOutputUsdPerMtok());
            case SONNET -> new TokenRates(costProperties.getSonnetInputUsdPerMtok(),
                    costProperties.getSonnetOutputUsdPerMtok());
            case SONNET_55 -> new TokenRates(costProperties.getSonnet55InputUsdPerMtok(),
                    costProperties.getSonnet55OutputUsdPerMtok());
            case HAIKU_55 -> new TokenRates(costProperties.getHaiku55InputUsdPerMtok(),
                    costProperties.getHaiku55OutputUsdPerMtok());
            case OPUS -> new TokenRates(costProperties.getOpusInputUsdPerMtok(),
                    costProperties.getOpusOutputUsdPerMtok());
            case FREE -> new TokenRates(0, 0);
        };
    }

    private Snapshot snapshot() {
        // A rewound clock must neither read a memo built for the real today nor write one.
        boolean rewound = Rewind.isActive();
        Instant now = clock.instant();
        if (!rewound) {
            Memo cached = memo.get();
            if (cached != null && Duration.between(cached.computedAt(), now).compareTo(MEMO_TTL) < 0) {
                return cached.snapshot();
            }
        }
        Snapshot computed = compute();
        if (!rewound) {
            memo.set(new Memo(now, computed));
        }
        return computed;
    }

    private Snapshot compute() {
        Map<String, RunCostProfile> profiles = new LinkedHashMap<>();
        try {
            LocalDate today = ForecastHorizon.today(clock);
            List<ApiCallCostRow> rows = apiCallLogRepository.findCostRowsSince(
                    ForecastHorizon.ukDayStartUtc(today.minusDays(WINDOW_DAYS - 1L)));
            Map<RunType, List<ApiCallCostRow>> byTab = classify(rows);
            for (RunType tab : List.of(RunType.BRIEFING_BEST_BET, RunType.AURORA_EVALUATION,
                    RunType.BATCH_NEAR_TERM, RunType.BATCH_FAR_TERM)) {
                profiles.put(tab.name(), profile(byTab.getOrDefault(tab, List.of()), isBatchTab(tab)));
            }
        } catch (RuntimeException e) {
            LOG.warn("Run Config cost profiles could not be computed: {}", e.toString());
            profiles.clear();
        }
        Double rate = null;
        try {
            rate = jobRunRepository.findLatestExchangeRates(PageRequest.of(0, 1)).stream()
                    .filter(r -> r != null && r > 0).findFirst().orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("Run Config exchange rate could not be read: {}", e.toString());
        }
        return new Snapshot(Map.copyOf(profiles), rate);
    }

    private static boolean isBatchTab(RunType tab) {
        return tab == RunType.BATCH_NEAR_TERM || tab == RunType.BATCH_FAR_TERM;
    }

    private Map<RunType, List<ApiCallCostRow>> classify(List<ApiCallCostRow> rows) {
        Map<RunType, List<ApiCallCostRow>> byTab = new EnumMap<>(RunType.class);
        for (ApiCallCostRow row : rows) {
            RunType tab = tabOf(row);
            if (tab != null) {
                byTab.computeIfAbsent(tab, t -> new ArrayList<>()).add(row);
            }
        }
        return byTab;
    }

    private RunType tabOf(ApiCallCostRow row) {
        if (row.runType() == RunType.BRIEFING && BEST_BET_URL.equals(row.requestUrl())) {
            return RunType.BRIEFING_BEST_BET;
        }
        if (row.runType() == RunType.AURORA_EVALUATION) {
            return RunType.AURORA_EVALUATION;
        }
        String customId = row.customId();
        if (!Boolean.TRUE.equals(row.isBatch()) || customId == null) {
            return null;
        }
        if (row.runType() == RunType.SCHEDULED_BATCH && customId.startsWith(AURORA_BATCH_PREFIX)) {
            return RunType.AURORA_EVALUATION;
        }
        if (row.pipelineRunId() == null || row.targetDate() == null || row.jobRunStartedAt() == null
                || FORECAST_BATCH_PREFIXES.stream().noneMatch(customId::startsWith)) {
            return null;
        }
        LocalDate submitted = ForecastHorizon.civilDate(row.jobRunStartedAt().toInstant(ZoneOffset.UTC));
        long daysAhead = row.targetDate().toEpochDay() - submitted.toEpochDay();
        return daysAhead >= FAR_TERM_FROM_DAYS_AHEAD ? RunType.BATCH_FAR_TERM : RunType.BATCH_NEAR_TERM;
    }

    private RunCostProfile profile(List<ApiCallCostRow> rows, boolean batchTab) {
        Set<Long> runs = new HashSet<>();
        Map<EvaluationModel, List<ApiCallCostRow>> byModel = new EnumMap<>(EvaluationModel.class);
        Map<EvaluationModel, Long> spend = new EnumMap<>(EvaluationModel.class);
        for (ApiCallCostRow row : rows) {
            runs.add(batchTab ? row.pipelineRunId() : row.jobRunId());
            if (row.evaluationModel() == null) {
                continue;
            }
            spend.merge(row.evaluationModel(), nz(row.costMicroDollars()), Long::sum);
            if (Boolean.TRUE.equals(row.succeeded())) {
                byModel.computeIfAbsent(row.evaluationModel(), m -> new ArrayList<>()).add(row);
            }
        }
        long succeeded = byModel.values().stream().mapToLong(List::size).sum();
        if (succeeded == 0) {
            return RunCostProfile.empty(WINDOW_DAYS);
        }
        double runsPerDay = runs.size() / (double) WINDOW_DAYS;
        double callsPerRun = succeeded / (double) runs.size();

        Map<String, ModelCost> measured = new LinkedHashMap<>();
        for (Map.Entry<EvaluationModel, List<ApiCallCostRow>> e : byModel.entrySet()) {
            long cost = e.getValue().stream().mapToLong(r -> nz(r.costMicroDollars())).sum();
            measured.put(e.getKey().name(), new ModelCost(e.getValue().size(),
                    cost / MICRO / e.getValue().size(), spend.get(e.getKey()) / MICRO / WINDOW_DAYS));
        }
        return new RunCostProfile(WINDOW_DAYS, runsPerDay, callsPerRun, measured,
                estimate(byModel, runsPerDay * callsPerRun));
    }

    /** Prices the average token mix of the tab's most-used model at every selectable model not measured. */
    private Map<String, ModelCost> estimate(Map<EvaluationModel, List<ApiCallCostRow>> byModel,
            double callsPerDay) {
        List<ApiCallCostRow> top = byModel.values().stream()
                .max(Comparator.comparingInt(List::size)).orElse(List.of());
        int n = top.size();
        boolean batch = top.stream().filter(r -> Boolean.TRUE.equals(r.isBatch())).count() * 2 > n;
        long input = average(top, ApiCallCostRow::inputTokens);
        long output = average(top, ApiCallCostRow::outputTokens);
        long write = average(top, ApiCallCostRow::cacheCreationTokens);
        long read = average(top, ApiCallCostRow::cacheReadTokens);
        // Batch requests write 1-hour caches; api_call_log keeps one write column, so price all of it
        // at the 1-hour rate on the batch tabs.
        TokenUsage usage = new TokenUsage(input, output, write, read, batch ? write : 0);

        Map<String, ModelCost> estimates = new LinkedHashMap<>();
        for (EvaluationModel model : EvaluationModel.selectable()) {
            if (byModel.containsKey(model)) {
                continue;
            }
            double perCall = costCalculator.calculateCostMicroDollars(model, usage, batch) / MICRO;
            estimates.put(model.name(), new ModelCost(0, perCall, perCall * callsPerDay));
        }
        return estimates;
    }

    private static long average(List<ApiCallCostRow> rows, Function<ApiCallCostRow, Long> column) {
        return Math.round(rows.stream().mapToLong(r -> nz(column.apply(r))).sum() / (double) rows.size());
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
