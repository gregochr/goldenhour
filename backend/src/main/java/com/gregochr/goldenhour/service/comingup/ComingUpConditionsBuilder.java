package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.comingup.ComingUpCondition;
import com.gregochr.goldenhour.model.comingup.ComingUpConditionOccurrence;
import com.gregochr.goldenhour.model.comingup.ComingUpConditionPeak;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.DustHotTopicStrategy;
import com.gregochr.goldenhour.service.InversionHotTopicStrategy;
import com.gregochr.goldenhour.service.PlanHorizon;
import com.gregochr.goldenhour.service.SurvivorSignalReader;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the standing-conditions strip's {@code conditions[]} (plan §7 P4, §13, D11) — the topics
 * that occur too often to announce, with every occurrence in the window available on request.
 *
 * <p>Three rows at first ship, always present even with an empty occurrence list (D11/§11.20):
 * <b>Coastal tides</b> (one row for both spring and king runs), <b>Saharan dust</b> and
 * <b>Valley inversions</b>. Aurora, NLC and snow are excluded — no rate history exists for them yet
 * (plan §1.4).
 *
 * <h2>Magnitude is config-interim for dust and inversion, forever, at first ship</h2>
 *
 * <p>Plan D4: "Magnitude is interim: a config-defined bucketed mapping from the topic's intensity
 * scale to bits — never labelled {@code median} or {@code p90}." Dust is scored on its own AOD
 * reading (the same scale {@code DustHotTopicStrategy}/{@code TopicDailyLogJob} already measure it
 * on, never an invented 0–10 "load" figure with no reading behind it); inversion reuses the 0–10
 * score {@code InversionScoreCalculator} already produces. Neither ever draws a real distribution —
 * {@link ComingUpScoringProperties.RecurrentTopics} is a threshold, not a percentile table — so
 * both conditions carry {@code interim: true} unconditionally, and the quant line's "distribution
 * half" (README §3) is always omitted for them, replaced by the threshold phrase D4 asks for
 * ({@code "promoted above AOD 0.50 (interim)"}).
 *
 * <h2>Rarity: dust is upgraded from observed arrivals, inversion is not</h2>
 *
 * <p>Dust rarity is computed from the trailing {@link ComingUpScoringProperties.RecurrentTopics
 * #getTrailingWindowDays()}-day arrival count, replayed over the <b>complete</b>
 * {@code forecast_evaluation} population — never a survivor surface, which would understate
 * presence and inflate rarity toward over-promotion (plan D4, external-review finding §14 round 3).
 * Inversion rarity stays on the config fallback until P7's {@code topic_daily_log} accrues an
 * unbiased population; the historical occurrences shown for it come from the survivor-only
 * {@code forecast_score} table for DISPLAY only ("nothing is discarded" — README §2.1) and never
 * feed the rarity number.
 *
 * <h2>Coastal tides reuses P2's scoring machinery, never a second formula</h2>
 *
 * <p>Every run in the 90-day window — not just the ones eligible for the chronology — needs a
 * bits/status row here, so this class stages each raw tide-type {@link AlmanacEvent} through the
 * same {@link TideRunBuilder}/{@link TideRunPeakHistory}/{@link SurpriseScore} calls
 * {@code ComingUpAssembler.enrichTide} uses, rather than inventing an alternate scoring path (plan
 * §7: "do not rescore" — read as "the same machinery", since an inside-Plan run has no entry to
 * read a score back from at all).
 *
 * <h2>Status precedence, and the coincidence-merge seam it depends on</h2>
 *
 * <p>{@code promoted} is derived from membership of the already-built {@code entries[]} list — an
 * exact id match ({@code type:startDate:endDate}) for the common case, plus a fallback for the one
 * case an exact match cannot cover: a run absorbed into a supermoon's coincidence line under D10's
 * max rule keeps its own id at staging time, but the surviving chronology entry's id is the
 * WINNER's — the supermoon's, whenever the supermoon's own score was higher. {@link
 * #matchingEntry} closes that gap by date overlap plus a coincidence line carrying this run's own
 * family ({@code "coastal"}), which is also what makes D10's {@code reason} tag reachable at all:
 * without it, a run that lost a merge could never be found, so the occurrence naming which topic
 * carried the score could never fire on the one branch it exists for.
 */
@Component
public class ComingUpConditionsBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ComingUpConditionsBuilder.class);

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.UK);

    private static final List<TargetType> EVENT_TARGET_TYPES = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    static final String STATUS_PROMOTED = "promoted";
    static final String STATUS_INSIDE_PLAN = "insidePlan";
    static final String STATUS_HELD_BACK = "heldBack";

    private static final String LABEL_SPRING_TIDE = "Spring tide";
    private static final String LABEL_KING_TIDE = "King tide";

    private final LocationRepository locationRepository;
    private final TideRunBuilder tideRunBuilder;
    private final TideRunPeakHistory tideRunPeakHistory;
    private final TideService tideService;
    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final SurvivorSignalReader survivorSignalReader;
    private final ComingUpScoringProperties scoringProperties;

    /**
     * Constructs a {@code ComingUpConditionsBuilder}.
     *
     * @param locationRepository           supplies the coastal roster tide-run scoring is measured
     *                                      against
     * @param tideRunBuilder                supplies a run's numeric peak range and representative
     * @param tideRunPeakHistory             supplies the representative's own historical run-peak
     *                                      distribution
     * @param tideService                    supplies each representative's stored range statistics
     * @param forecastEvaluationRepository the complete-population source for dust's observed
     *                                      arrival rate
     * @param survivorSignalReader          the survivor surface for both topics' forward (T+0..T+3)
     *                                      peak and inversion's display-only historical occurrences
     * @param scoringProperties              every knob the surprise model uses
     */
    public ComingUpConditionsBuilder(LocationRepository locationRepository, TideRunBuilder tideRunBuilder,
            TideRunPeakHistory tideRunPeakHistory, TideService tideService,
            ForecastEvaluationRepository forecastEvaluationRepository,
            SurvivorSignalReader survivorSignalReader, ComingUpScoringProperties scoringProperties) {
        this.locationRepository = locationRepository;
        this.tideRunBuilder = tideRunBuilder;
        this.tideRunPeakHistory = tideRunPeakHistory;
        this.tideService = tideService;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.survivorSignalReader = survivorSignalReader;
        this.scoringProperties = scoringProperties;
    }

    /**
     * Builds every standing condition for the strip.
     *
     * @param builtFor   the UK civil date the feed is built for
     * @param allEvents  every almanac event in the 90-day window, unfiltered by eligibility — the
     *                   coastal-tides condition needs runs wholly inside Plan's window too
     * @param entries    the already-assembled chronology entries, for the promoted/insidePlan/
     *                   heldBack derivation (D11)
     * @return the three conditions, always present even with an empty occurrence list
     */
    public List<ComingUpCondition> build(LocalDate builtFor, List<AlmanacEvent> allEvents,
            List<ComingUpEntry> entries) {
        List<ComingUpCondition> conditions = new ArrayList<>(3);
        conditions.add(buildCoastalTides(builtFor, allEvents, entries));
        conditions.add(buildDust(builtFor));
        conditions.add(buildInversion(builtFor));
        return conditions;
    }

    // ── Coastal tides (D11) ──────────────────────────────────────────────

    private ComingUpCondition buildCoastalTides(LocalDate builtFor, List<AlmanacEvent> allEvents,
            List<ComingUpEntry> entries) {
        List<AlmanacEvent> tideEvents = allEvents.stream()
                .filter(e -> ComingUpAssembler.TYPE_SPRING_TIDE.equals(e.type())
                        || ComingUpAssembler.TYPE_KING_TIDE.equals(e.type()))
                .sorted(Comparator.comparing(AlmanacEvent::startDate))
                .toList();

        List<LocationEntity> coastalRoster = coastalRoster();
        LocalDate lastPlanDate = PlanHorizon.lastPlanDate(builtFor);
        Map<String, ComingUpEntry> entriesById = new LinkedHashMap<>();
        for (ComingUpEntry entry : entries) {
            entriesById.putIfAbsent(entry.id(), entry);
        }

        double rarityBits = SurpriseScore.rarity(scoringProperties.getRarity().getSpringTideMeanGapDays());
        List<ComingUpConditionOccurrence> occurrences = new ArrayList<>();
        List<Double> rangeValues = new ArrayList<>();
        boolean anyColdStart = false;
        double bestRange = Double.NEGATIVE_INFINITY;
        ComingUpConditionOccurrence bestOccurrence = null;

        for (AlmanacEvent event : tideEvents) {
            TideRunScore score = scoreTideRunSafely(builtFor, event, coastalRoster, rarityBits);
            anyColdStart = anyColdStart || score.coldStart();
            if (!Double.isNaN(score.rangeMetres())) {
                rangeValues.add(score.rangeMetres());
            }

            ComingUpConditionOccurrence occurrence =
                    tideOccurrence(event, score, lastPlanDate, entriesById, entries);
            occurrences.add(occurrence);
            if (!Double.isNaN(score.rangeMetres()) && score.rangeMetres() > bestRange) {
                bestRange = score.rangeMetres();
                bestOccurrence = occurrence;
            }
        }

        String rateLabel = "a run every " + fmt1(scoringProperties.getRarity().getSpringTideMeanGapDays())
                + " days · fixed by the ephemeris";
        String quantLabel = coastalTideQuantLabel(rarityBits, tideEvents.size(), rangeValues);
        ComingUpConditionPeak peak = bestOccurrence == null ? null
                : new ComingUpConditionPeak(bestOccurrence.dateLabel(), bestOccurrence.valueLabel(),
                        bestOccurrence.bits());

        return new ComingUpCondition("COASTAL_TIDES", "Coastal tides",
                scoringProperties.getCadence().getCoastalTides(), anyColdStart,
                rateLabel, quantLabel, peak, occurrences);
    }

    /**
     * The run's own kind, read straight from the source event's type — never re-derived — so a
     * mixed condition row (D11: "one row for both spring and king runs") can still tell its
     * occurrences apart. Null for a type this method does not recognise, which cannot occur for a
     * tide-type event but keeps the method total rather than throwing.
     */
    private static String runLabelOf(AlmanacEvent event) {
        if (ComingUpAssembler.TYPE_KING_TIDE.equals(event.type())) {
            return LABEL_KING_TIDE;
        }
        if (ComingUpAssembler.TYPE_SPRING_TIDE.equals(event.type())) {
            return LABEL_SPRING_TIDE;
        }
        return null;
    }

    /** One tide run's own (never merge-adjusted) scoring, computed once and shared by every reader. */
    private record TideRunScore(double rangeMetres, double bits, boolean coldStart, String valueLabel) {
    }

    /**
     * Scores one run, degrading to the cold-start default on any DB failure rather than aborting
     * the whole strip — matching {@link #buildDust}/{@link #buildInversion}'s own per-condition
     * isolation. Without this, one bad tide row propagated uncaught through {@code build()} would
     * 500 the entire {@code /api/almanac} response and, since coastal tides is built first, take
     * the dust and inversion conditions down with it even though nothing was wrong with either.
     */
    private TideRunScore scoreTideRunSafely(LocalDate builtFor, AlmanacEvent event,
            List<LocationEntity> coastalRoster, double rarityBits) {
        try {
            return scoreTideRun(builtFor, event, coastalRoster, rarityBits);
        } catch (RuntimeException e) {
            LOG.warn("Tide run scoring failed for {} — this occurrence will score without a "
                    + "distribution rather than the whole feed failing: {}", event, e.toString());
            return new TideRunScore(Double.NaN, rarityBits + SurpriseScore.DEFAULT_MAGNITUDE_BITS, true, null);
        }
    }

    private TideRunScore scoreTideRun(LocalDate builtFor, AlmanacEvent event,
            List<LocationEntity> coastalRoster, double rarityBits) {
        Optional<TideRunBuilder.RunPeak> peakOpt = tideRunBuilder.peakRange(datesOf(event), coastalRoster);
        if (peakOpt.isEmpty()) {
            return new TideRunScore(Double.NaN, rarityBits + SurpriseScore.DEFAULT_MAGNITUDE_BITS, true, null);
        }
        TideRunBuilder.RunPeak peak = peakOpt.get();
        String valueLabel = fmt1(peak.rangeMetres()) + " m";

        Optional<TideStats> stats = tideService.getTideStats(peak.representative().getId());
        if (stats.isEmpty()) {
            return new TideRunScore(peak.rangeMetres(), rarityBits + SurpriseScore.DEFAULT_MAGNITUDE_BITS,
                    true, valueLabel);
        }
        List<Double> history = tideRunPeakHistory.peakRanges(
                peak.representative(), coastalRoster, builtFor, event.startDate());
        SurpriseScore.MagnitudeResult result =
                SurpriseScore.magnitudeFromHistory(history, peak.rangeMetres(), scoringProperties.getMagnitude());
        return new TideRunScore(peak.rangeMetres(), rarityBits + result.bits(), result.coldStart(), valueLabel);
    }

    private ComingUpConditionOccurrence tideOccurrence(AlmanacEvent event, TideRunScore score,
            LocalDate lastPlanDate, Map<String, ComingUpEntry> entriesById, List<ComingUpEntry> entries) {
        String ownId = event.type() + ":" + event.startDate() + ":" + event.endDate();
        LocalDate representativeDate = peakDateOf(event);
        String valueLabel = score.valueLabel() != null ? score.valueLabel() : event.meta().get("range");

        ComingUpEntry matchedEntry = matchingEntry(event, ownId, entriesById, entries);
        double displayBits = score.bits();
        String reason = null;
        if (matchedEntry != null && matchedEntry.bits() != null) {
            displayBits = matchedEntry.bits();
            if (!matchedEntry.coincidence().isEmpty()) {
                // D10: "the reason label is mandatory wherever the max was taken" — both
                // directions, not only when the OTHER topic overrode this run's own score. Which
                // field names the other topic depends on which one actually won the merge:
                //   - exact id match  → this run's own entry survived (it won), so its own
                //     coincidence line names the LOSER — the other topic.
                //   - fallback match  → the OTHER entry survived (it won), so ITS OWN title names
                //     the other topic, and its coincidence line would name this run instead.
                String otherTopic = matchedEntry.id().equals(ownId)
                        ? matchedEntry.coincidence().getFirst().name()
                        : matchedEntry.title();
                reason = "max w/ " + otherTopic.toLowerCase(Locale.UK);
            }
        }

        String status;
        String entryId;
        if (matchedEntry != null) {
            status = STATUS_PROMOTED;
            entryId = matchedEntry.id();
        } else if (!event.endDate().isAfter(lastPlanDate)) {
            // No entry, and this run's dates never reach beyond Plan's boundary: wholly inside
            // Plan's window (D11). Eligibility (D1) guarantees the complement — endDate beyond
            // the boundary — always resolves to a real entry (itself or a merge winner via
            // matchingEntry's fallback), so a run that reaches here with endDate > lastPlanDate
            // cannot happen; deliberately NOT also requiring startDate to be on/after builtFor,
            // since TideAlmanacSource's backward walk (see the P2 phase-log row) routinely
            // produces an in-progress run whose start already lags behind today — that run is
            // still live on the Plan tab right now, and requiring startDate >= builtFor mislabelled
            // it heldBack instead.
            status = STATUS_INSIDE_PLAN;
            entryId = null;
        } else {
            status = STATUS_HELD_BACK;
            entryId = null;
        }

        return new ComingUpConditionOccurrence(representativeDate, DATE_LABEL.format(representativeDate),
                valueLabel, runLabelOf(event), round1(displayBits), reason, status, entryId);
    }

    /**
     * Finds the chronology entry this run belongs to — either as itself, or as the loser of a D10
     * coincidence merge.
     *
     * <p>An exact id match covers every case except one: a run absorbed into a supermoon's
     * coincidence line under {@code ComingUpAssembler.mergeEntries}'s max rule keeps its OWN id at
     * staging time, but the surviving entry's id is the WINNER's — the supermoon's, when the
     * supermoon's score was higher, which is precisely the case D10's {@code reason} tag exists to
     * name. Without this fallback, {@code reason} could never fire in production: if the tide run
     * itself won the merge, {@code matchedEntry.bits()} already equals this run's own score (no
     * override to name); if the supermoon won, the exact-id lookup misses entirely and the run
     * silently drops to {@code insidePlan}/{@code heldBack} despite having a real home in the
     * chronology. The fallback finds that entry by date overlap plus a coincidence line carrying
     * this run's own family ({@code "coastal"}).
     *
     * <p><b>This heuristic is sound only under four invariants that hold today but are not enforced
     * by any type here — a future change to the merge machinery must keep them, or re-derive this
     * method:</b>
     * <ol>
     *   <li>Tide runs in one window are maximal and disjoint — {@code TideAlmanacSource} never
     *       emits two overlapping runs, so date overlap alone identifies at most one candidate.</li>
     *   <li>A merge's surviving entry keeps the WINNER's own {@code startDate}/{@code endDate} —
     *       {@code ComingUpAssembler.mergeEntries} never touches the winner's dates, so "this
     *       run's dates overlap the entry's dates" is a reliable link back to the merge.</li>
     *   <li>The coincidence merge only ever pairs a tide run with a supermoon — matching on the
     *       family {@code "coastal"} alone (rather than also checking the OTHER side is
     *       {@code sun-moon}) is safe only because no other pairing exists to confuse it with.</li>
     *   <li>A merge only ever involves eligible events — {@code ComingUpAssembler} merges before
     *       the eligibility filter is even applied to the pre-P4 pipeline's own entries, but by
     *       the time this class runs, every merge candidate it can observe already cleared it, so
     *       {@code entries} never contains a merge whose overlap could match an unrelated,
     *       still-in-Plan tide run by coincidence.</li>
     * </ol>
     * A future phase that widens the merge to another topic pair, or lets two tide runs overlap,
     * must revisit this method rather than assume it still holds.
     */
    private static ComingUpEntry matchingEntry(AlmanacEvent event, String ownId,
            Map<String, ComingUpEntry> entriesById, List<ComingUpEntry> entries) {
        ComingUpEntry exact = entriesById.get(ownId);
        if (exact != null) {
            return exact;
        }
        return entries.stream()
                .filter(entry -> !entry.coincidence().isEmpty() && overlaps(event, entry))
                .filter(entry -> entry.coincidence().stream().anyMatch(c -> "coastal".equals(c.family())))
                .findFirst()
                .orElse(null);
    }

    private static boolean overlaps(AlmanacEvent event, ComingUpEntry entry) {
        return !event.endDate().isBefore(entry.startDate()) && !entry.endDate().isBefore(event.startDate());
    }

    private static String coastalTideQuantLabel(double rarityBits, int runCount, List<Double> rangeValues) {
        StringBuilder label = new StringBuilder("rarity ").append(fmt1(rarityBits));
        label.append(" · ").append(runCount).append(runCount == 1 ? " run in 90 days" : " runs in 90 days");
        if (!rangeValues.isEmpty()) {
            List<Double> sorted = rangeValues.stream().sorted().toList();
            label.append(" · range median ").append(fmt1(percentile(sorted, 0.50))).append(" m, p90 ")
                    .append(fmt1(percentile(sorted, 0.90))).append(" m");
        }
        return label.toString();
    }

    /** Nearest-rank percentile over an already-sorted list. */
    private static double percentile(List<Double> sorted, double p) {
        int index = Math.max(0, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private List<LocationEntity> coastalRoster() {
        try {
            return locationRepository.findCoastalLocations();
        } catch (RuntimeException e) {
            LOG.warn("Coastal roster fetch failed — coastal-tides condition will score without a "
                    + "distribution rather than the whole feed failing: {}", e.toString());
            return List.of();
        }
    }

    // ── Saharan dust (D4, D11) ───────────────────────────────────────────

    private ComingUpCondition buildDust(LocalDate builtFor) {
        int windowDays = scoringProperties.getRecurrent().getTrailingWindowDays();
        LocalDate yesterday = builtFor.minusDays(1);
        LocalDate windowStart = yesterday.minusDays(windowDays - 1L);

        Map<LocalDate, BigDecimal> maxAodByPresentDate = new LinkedHashMap<>();
        boolean trailingReadFailed = false;
        try {
            for (ForecastEvaluationEntity row : forecastEvaluationRepository
                    .findByTargetDateBetweenAndTargetTypeIn(windowStart, yesterday, EVENT_TARGET_TYPES)) {
                if (DustHotTopicStrategy.isDustEnhanced(
                        row.getAerosolOpticalDepth(), row.getDust(), row.getPm25())) {
                    recordDustPresence(maxAodByPresentDate, row.getTargetDate(), row.getAerosolOpticalDepth());
                }
            }
        } catch (RuntimeException e) {
            // An empty map here is indistinguishable from "genuinely no arrivals" further down
            // unless this flag says otherwise — without it, a transient DB failure would publish
            // (and, since AlmanacService caches the built response for the whole civil day, RETAIN
            // until the next rebuild) a false "none in the last N days" claim rather than the
            // honest "we do not know" this actually is.
            trailingReadFailed = true;
            LOG.warn("Dust trailing-window read failed — the condition will degrade to the config "
                    + "fallback rather than the whole feed failing: {}", e.toString());
        }

        List<Burst> bursts = groupBursts(maxAodByPresentDate);
        double rarityBits;
        String rateLabel;
        ComingUpScoringProperties.Dust dustConfig = scoringProperties.getRecurrent().getDust();
        int arrivals = bursts.size();
        int evidentiaryBar = scoringProperties.getRecurrent().getEvidentiaryBarArrivals();
        if (arrivals >= evidentiaryBar) {
            double meanGapDays = (double) windowDays / arrivals;
            rarityBits = SurpriseScore.rarity(meanGapDays);
            rateLabel = arrivals + (arrivals == 1 ? " plume" : " plumes") + " since "
                    + DATE_LABEL.format(bursts.getFirst().start()) + " · about " + describeGap(meanGapDays);
        } else {
            rarityBits = SurpriseScore.rarity(dustConfig.getFallbackMeanGapDays());
            if (trailingReadFailed) {
                rateLabel = "history unavailable right now";
            } else {
                rateLabel = arrivals == 0
                        ? "none in the last " + windowDays + " days"
                        : arrivals + (arrivals == 1 ? " plume" : " plumes") + " since "
                                + DATE_LABEL.format(bursts.getFirst().start());
            }
        }

        List<ComingUpConditionOccurrence> occurrences = new ArrayList<>();
        for (Burst burst : bursts) {
            double bits = rarityBits + dustMagnitude(burst.peakValue(), dustConfig);
            occurrences.add(new ComingUpConditionOccurrence(burst.peakDate(), DATE_LABEL.format(burst.peakDate()),
                    "AOD " + fmt2(burst.peakValue()), null, round1(bits), null, STATUS_HELD_BACK, null));
        }

        ComingUpConditionPeak peak = null;
        SurvivorSignals forwardPeak = null;
        try {
            forwardPeak = survivorSignalReader.read(builtFor, PlanHorizon.lastPlanDate(builtFor)).stream()
                    .filter(s -> passesPeakGate(s.eventType()))
                    .filter(s -> DustHotTopicStrategy.isDustEnhanced(s.readings().aerosolOpticalDepth(),
                            s.readings().dust(), s.readings().pm25()))
                    .max(Comparator.comparing(s -> s.readings().aerosolOpticalDepth(),
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("Dust forward-peak read failed — the peak cell will say so rather than the "
                    + "whole feed failing: {}", e.toString());
        }
        if (forwardPeak != null) {
            BigDecimal aod = forwardPeak.readings().aerosolOpticalDepth();
            double bits = rarityBits + dustMagnitude(aod, dustConfig);
            peak = new ComingUpConditionPeak(DATE_LABEL.format(forwardPeak.date()), "AOD " + fmt2(aod),
                    round1(bits));
            occurrences.add(new ComingUpConditionOccurrence(forwardPeak.date(), DATE_LABEL.format(forwardPeak.date()),
                    "AOD " + fmt2(aod), null, round1(bits), null, STATUS_INSIDE_PLAN, null));
        }

        String quantLabel = "rarity " + fmt1(rarityBits) + " · promoted above AOD "
                + fmt2(BigDecimal.valueOf(dustConfig.getMagnitudeThresholdAod())) + " (interim)";
        return new ComingUpCondition("DUST", "Saharan dust", scoringProperties.getCadence().getDust(), true,
                rateLabel, quantLabel, peak, occurrences);
    }

    private static double dustMagnitude(BigDecimal aod, ComingUpScoringProperties.Dust config) {
        if (aod != null && aod.doubleValue() >= config.getMagnitudeThresholdAod()) {
            return config.getMagnitudeAboveBits();
        }
        return SurpriseScore.DEFAULT_MAGNITUDE_BITS;
    }

    // ── Valley inversions (D4, D11) ──────────────────────────────────────

    private ComingUpCondition buildInversion(LocalDate builtFor) {
        int windowDays = scoringProperties.getRecurrent().getTrailingWindowDays();
        LocalDate yesterday = builtFor.minusDays(1);
        LocalDate windowStart = yesterday.minusDays(windowDays - 1L);
        ComingUpScoringProperties.Inversion inversionConfig = scoringProperties.getRecurrent().getInversion();

        // Rarity NEVER upgrades for inversion — no unbiased population exists until P7's
        // topic_daily_log matures (plan D4/§1). The historical occurrences below are shown for
        // display only ("nothing is discarded"); they must not feed this number.
        double rarityBits = SurpriseScore.rarity(inversionConfig.getFallbackMeanGapDays());

        Map<LocalDate, Integer> maxScoreByDate = new LinkedHashMap<>();
        try {
            for (SurvivorSignals signal : survivorSignalReader.read(windowStart, yesterday)) {
                if (signal.eventType() != TargetType.SUNRISE || signal.scores().inversion() == null
                        || signal.scores().inversion() < InversionHotTopicStrategy.STRONG_SCORE_INCLUSIVE) {
                    continue;
                }
                maxScoreByDate.merge(signal.date(), signal.scores().inversion(), Math::max);
            }
        } catch (RuntimeException e) {
            LOG.warn("Inversion trailing-window read failed — the condition will show no historical "
                    + "occurrences rather than the whole feed failing: {}", e.toString());
        }

        List<LocalDate> dates = maxScoreByDate.keySet().stream().sorted().toList();
        String rateLabel = dates.isEmpty()
                ? "none in the last " + windowDays + " days"
                : dates.size() + (dates.size() == 1 ? " strong morning" : " strong mornings") + " since "
                        + DATE_LABEL.format(dates.getFirst());

        List<ComingUpConditionOccurrence> occurrences = new ArrayList<>();
        for (LocalDate date : dates) {
            int score = maxScoreByDate.get(date);
            double bits = rarityBits + inversionMagnitude(score, inversionConfig);
            occurrences.add(new ComingUpConditionOccurrence(date, DATE_LABEL.format(date), score + "/10",
                    null, round1(bits), null, STATUS_HELD_BACK, null));
        }

        ComingUpConditionPeak peak = null;
        SurvivorSignals forwardPeak = null;
        try {
            forwardPeak = survivorSignalReader.read(builtFor, PlanHorizon.lastPlanDate(builtFor)).stream()
                    .filter(s -> passesPeakGate(s.eventType()))
                    .filter(s -> s.eventType() == TargetType.SUNRISE && s.scores().inversion() != null
                            && s.scores().inversion() >= InversionHotTopicStrategy.STRONG_SCORE_INCLUSIVE)
                    .max(Comparator.comparingInt(s -> s.scores().inversion()))
                    .orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("Inversion forward-peak read failed — the peak cell will say so rather than the "
                    + "whole feed failing: {}", e.toString());
        }
        if (forwardPeak != null) {
            int score = forwardPeak.scores().inversion();
            double bits = rarityBits + inversionMagnitude(score, inversionConfig);
            peak = new ComingUpConditionPeak(DATE_LABEL.format(forwardPeak.date()), score + "/10", round1(bits));
            occurrences.add(new ComingUpConditionOccurrence(forwardPeak.date(), DATE_LABEL.format(forwardPeak.date()),
                    score + "/10", null, round1(bits), null, STATUS_INSIDE_PLAN, null));
        }

        String quantLabel = "rarity " + fmt1(rarityBits) + " · promoted above "
                + fmt0(inversionConfig.getMagnitudeThresholdScore()) + "/10 (interim)";
        return new ComingUpCondition("VALLEY_INVERSIONS", "Valley inversions",
                scoringProperties.getCadence().getInversion(), true, rateLabel, quantLabel, peak, occurrences);
    }

    private static double inversionMagnitude(int score, ComingUpScoringProperties.Inversion config) {
        return score >= config.getMagnitudeThresholdScore()
                ? config.getMagnitudeAboveBits() : SurpriseScore.DEFAULT_MAGNITUDE_BITS;
    }

    // ── The peak gate (plan D4/D5) ─────────────────────────────────────────

    /**
     * Whether a candidate lands within {@link ComingUpScoringProperties#getPeakLightWindowMinutes()}
     * minutes of a light window — satisfied by construction for every survivor row today, since
     * both are keyed to {@link TargetType#SUNRISE} or {@link TargetType#SUNSET}. The configured
     * minutes bound is not read arithmetically (guarded only as "configured to a positive value"):
     * no forward candidate carries a clock time to compare against a light window's edges yet, so
     * SUNRISE/SUNSET typing is the documented v1 proxy (D5: "in v1 the gate cannot fail"). Written
     * as a real predicate, not assumed true, so the rule exists in code before sub-daily intensities
     * (P7's {@code landed_on_window}) make the bound itself load-bearing.
     */
    boolean passesPeakGate(TargetType eventType) {
        return scoringProperties.getPeakLightWindowMinutes() > 0
                && (eventType == TargetType.SUNRISE || eventType == TargetType.SUNSET);
    }

    // ── Presence-burst grouping (dust) ────────────────────────────────────

    /** A maximal run of consecutive present days, with the day carrying the highest reading. */
    private record Burst(LocalDate start, LocalDate end, LocalDate peakDate, BigDecimal peakValue) {
    }

    /**
     * Groups a sparse map of present dates (absent = not present) into maximal consecutive runs —
     * an "arrival" is one burst, matching README §3's "recurrent — arrives in bursts; use mean
     * inter-arrival" rather than a raw day count.
     */
    private static List<Burst> groupBursts(Map<LocalDate, BigDecimal> presentDates) {
        List<LocalDate> sorted = presentDates.keySet().stream().sorted().toList();
        List<Burst> bursts = new ArrayList<>();
        LocalDate start = null;
        LocalDate prev = null;
        LocalDate peakDate = null;
        BigDecimal peakValue = null;
        for (LocalDate date : sorted) {
            if (start == null) {
                start = date;
                peakDate = date;
                peakValue = presentDates.get(date);
            } else if (prev.plusDays(1).equals(date)) {
                BigDecimal value = presentDates.get(date);
                if (value != null && (peakValue == null || value.compareTo(peakValue) > 0)) {
                    peakDate = date;
                    peakValue = value;
                }
            } else {
                bursts.add(new Burst(start, prev, peakDate, peakValue));
                start = date;
                peakDate = date;
                peakValue = presentDates.get(date);
            }
            prev = date;
        }
        if (start != null) {
            bursts.add(new Burst(start, prev, peakDate, peakValue));
        }
        return bursts;
    }

    private static BigDecimal maxOf(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.max(b);
    }

    /**
     * Records a dust-enhanced day, tolerating a null AOD reading — {@code isDustEnhanced} fires on
     * <b>either</b> AOD or surface dust clearing its own threshold, so the day can be present with
     * no AOD figure at all (plan §1: "the values can be null when the aerosol fetch degraded").
     * {@link Map#merge} requires a non-null value and throws otherwise, which would abort this
     * class's whole trailing-window loop partway through — silently truncating the arrival count to
     * whichever rows happened to be read first ({@code findByTargetDateBetweenAndTargetTypeIn}'s own
     * Javadoc: "in no guaranteed order"). A later non-null reading for the same day still overwrites
     * the placeholder: {@code Map.merge} associates a key mapped to {@code null} directly with the
     * new value rather than invoking the remapping function, so it never calls {@link #maxOf} with a
     * null second argument.
     */
    private static void recordDustPresence(Map<LocalDate, BigDecimal> byDate, LocalDate date, BigDecimal aod) {
        if (aod == null) {
            byDate.putIfAbsent(date, null);
        } else {
            byDate.merge(date, aod, ComingUpConditionsBuilder::maxOf);
        }
    }

    // ── Small shared helpers ─────────────────────────────────────────────

    private static List<LocalDate> datesOf(AlmanacEvent event) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = event.startDate(); !date.isAfter(event.endDate()); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }

    private static LocalDate peakDateOf(AlmanacEvent event) {
        String date = event.meta().get("peakDate");
        if (date == null) {
            date = event.meta().get("figuresFrom");
        }
        if (date == null) {
            return event.startDate();
        }
        try {
            return LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            return event.startDate();
        }
    }

    private static String describeGap(double meanGapDays) {
        if (meanGapDays <= 1.5) {
            return "daily";
        }
        if (meanGapDays <= 9.0) {
            return "one a week";
        }
        if (meanGapDays <= 45.0) {
            return "one a month";
        }
        return "one every " + Math.round(meanGapDays) + " days";
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String fmt1(double value) {
        return String.format(Locale.UK, "%.1f", value);
    }

    private static String fmt0(double value) {
        return String.format(Locale.UK, "%.0f", value);
    }

    private static String fmt2(BigDecimal value) {
        return value == null ? "—" : String.format(Locale.UK, "%.2f", value);
    }
}
