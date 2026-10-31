package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.comingup.ComingUpAction;
import com.gregochr.goldenhour.model.comingup.ComingUpBands;
import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpCounts;
import com.gregochr.goldenhour.model.comingup.ComingUpDayMark;
import com.gregochr.goldenhour.model.comingup.ComingUpDayRange;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpFact;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.model.comingup.ComingUpTide;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import com.gregochr.goldenhour.util.LogSanitizer;
import com.gregochr.solarutils.MoonriseMoonset;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Grows every eligible {@link AlmanacEvent} to the full "Coming up" chronology entry shape (plan
 * §5 P2, §13) and wraps the result in a {@link ComingUpResponse}.
 *
 * <p>{@code AlmanacService} still owns the source fan-out, eligibility filter and cache; this class
 * is purely the enrichment pass — bits (plan D4), server-ordered facts, the coincidence merge (D10),
 * superlatives, thresholds, and the served {@code counts}/{@code bands}. Called once per cache
 * rebuild, never per request.
 *
 * <p><b>Seven known types, one documented fallback.</b> The type-string constants below duplicate
 * literals owned by the seven {@code AlmanacSource} implementations (their own constants are
 * package-private to a sibling package, and the plan's sources stay untouched) — an unrecognised
 * type degrades to a generic score rather than throwing, which only a hand-built test fixture can
 * ever exercise.
 */
@Component
public class ComingUpAssembler {

    private static final Logger LOG = LoggerFactory.getLogger(ComingUpAssembler.class);

    static final String TYPE_SPRING_TIDE = "spring-tide";
    static final String TYPE_KING_TIDE = "king-tide";
    static final String TYPE_METEOR = "meteor";
    static final String TYPE_SUPERMOON = "supermoon";
    static final String TYPE_EQUINOX = "equinox";
    static final String TYPE_SOLSTICE = "solstice";
    static final String TYPE_NLC_SEASON = "nlc-season";
    static final String TYPE_ECLIPSE = "eclipse";
    static final String TYPE_LUNAR_ECLIPSE = "lunar-eclipse";

    /**
     * The lunar eclipse's exposure cue, carried as {@code aside} rather than {@code note} on the
     * Coming-up entry (that field belongs to {@code HotTopic}, a different record) — see
     * {@code LunarEclipseHotTopicStrategy}'s own copy of this string for why it is not a safety
     * warning (lunar-eclipse-plan.md §4 #5).
     */
    private static final String LUNAR_ECLIPSE_EXPOSURE_NOTE =
            "No filter needed — bracket, the shadow is ~10 stops under the lit edge";

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE d", Locale.UK);
    private static final DateTimeFormatter DAY_MONTH_LABEL = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final String MARK_PEAK = "peak";
    private static final String MARK_FULL_MOON = "full moon";
    private static final String SEPARATOR = " · ";

    /**
     * The separator inside an event-water row: the dot is bound to the word before it by a
     * non-breaking space, so a wrapped row can never begin with a bare {@code ·}.
     */
    private static final String EVENT_SEPARATOR = "\u00a0· ";

    /** Within this many minutes a high water reads "at sunrise/sunset" (TideWording's rounding). */
    private static final long EVENT_COINCIDENT_MINUTES = 5;

    private static final long MINUTES_PER_HOUR = 60;

    /** The sparkline's marked extremum: the card names high water only, so it is always HW. */
    private static final String PHASE_HW = "HW";
    private static final String COUNTED_AS_ONE = " Counted as one event, not two.";
    private static final String JOIN_STEM = "Same cause, two effects. The moon is closest to Earth, which both "
            + "enlarges it and stretches the tide.";

    private final LocationRepository locationRepository;
    private final TideRunBuilder tideRunBuilder;
    private final TideRunPeakHistory tideRunPeakHistory;
    private final DailyRangeHistory dailyRangeHistory;
    private final TideService tideService;
    private final ComingUpScoringProperties scoringProperties;
    private final MoonriseMoonsetCalculator moonriseMoonsetCalculator;

    /**
     * Constructs a {@code ComingUpAssembler}.
     *
     * @param locationRepository supplies the coastal roster tide-run scoring is measured against
     * @param tideRunBuilder     supplies a run's numeric peak range and representative
     * @param tideRunPeakHistory supplies the representative's own historical run-peak distribution
     * @param dailyRangeHistory  supplies the past year's daily ranges each day of a run is ranked against
     * @param tideService        supplies each representative's stored range statistics
     * @param scoringProperties  every knob the surprise model uses
     * @param moonriseMoonsetCalculator supplies a merged supermoon night's moonrise at the merged
     *                           run's representative location
     */
    public ComingUpAssembler(LocationRepository locationRepository, TideRunBuilder tideRunBuilder,
            TideRunPeakHistory tideRunPeakHistory, DailyRangeHistory dailyRangeHistory,
            TideService tideService,
            ComingUpScoringProperties scoringProperties,
            MoonriseMoonsetCalculator moonriseMoonsetCalculator) {
        this.locationRepository = locationRepository;
        this.tideRunBuilder = tideRunBuilder;
        this.tideRunPeakHistory = tideRunPeakHistory;
        this.dailyRangeHistory = dailyRangeHistory;
        this.tideService = tideService;
        this.scoringProperties = scoringProperties;
        this.moonriseMoonsetCalculator = moonriseMoonsetCalculator;
    }

    /**
     * Builds the full response from the events that already cleared eligibility, with no
     * Plan-owned merge partners.
     *
     * @param builtFor       the UK civil date the feed is built for
     * @param eligibleEvents events that have not yet ended (plan D1 as amended by
     *                       coming-up-near-term-plan.md), any order
     * @return the wrapped response, entries sorted by start date then span length then type
     */
    public ComingUpResponse assemble(LocalDate builtFor, List<AlmanacEvent> eligibleEvents) {
        return assemble(builtFor, eligibleEvents, List.of());
    }

    /**
     * Builds the full response from the events that already cleared eligibility, pairing them
     * against the events Plan owns too (supermoon-tide-run-plan.md §4.7).
     *
     * <p>Production no longer passes partners (coming-up-near-term-plan.md: every event that has not
     * ended is eligible), so this path is exercised only by its own tests and is a candidate for removal.
     *
     * <p>{@code partnersOnly} events (those ending inside Plan's window) are staged but used ONLY as
     * D10 merge partners: one that pairs with an eligible event is merged into it, one that pairs
     * with nothing is dropped, and two of them never pair with each other (their union would end
     * inside Plan's window). Superlatives and thresholds are still computed over the eligible runs
     * alone.
     *
     * @param builtFor       the UK civil date the feed is built for
     * @param eligibleEvents events that have not yet ended, any order
     * @param partnersOnly   events wholly inside Plan's window, considered only as merge partners
     * @return the wrapped response, entries sorted by start date then span length then type
     */
    public ComingUpResponse assemble(LocalDate builtFor, List<AlmanacEvent> eligibleEvents,
            List<AlmanacEvent> partnersOnly) {
        List<LocationEntity> coastalRoster = coastalRoster();

        List<Staged> staged = new ArrayList<>();
        for (AlmanacEvent event : eligibleEvents) {
            staged.add(stage(builtFor, event, coastalRoster));
        }
        List<Staged> eligibleStaged = List.copyOf(staged);
        for (AlmanacEvent event : partnersOnly) {
            // Only a type that can pair is ever staged: enriching anything else would be wasted
            // work, and a failure enriching some unrelated Plan-window event must not fail the feed.
            if (!isTideRun(event.type()) && !TYPE_SUPERMOON.equals(event.type())) {
                continue;
            }
            // ...and only one that OVERLAPS an eligible event of the complementary type, the same
            // test the pairing uses: an unpaired partner is discarded anyway, so its enrichment
            // (repository reads for a tide run) is never paid for.
            if (eligibleEvents.stream().noneMatch(e -> complementary(event, e) && overlaps(event, e))) {
                continue;
            }
            try {
                Staged partner = stage(builtFor, event, coastalRoster);
                partner.partnerOnly = true;
                staged.add(partner);
            } catch (RuntimeException e) {
                // A partner can never fail the feed: drop it, and the eligible event stays unmerged.
                LOG.warn("Coming up partner {} {}..{} could not be staged and was dropped: {}",
                        LogSanitizer.sanitize(event.type()), event.startDate(), event.endDate(),
                        LogSanitizer.sanitize(e.toString()));
            }
        }

        // Superlatives/thresholds MUST run before the merge: they compare a tide run's own range
        // against every other tide run in the window, and mergeCoincidences can fold a whole tide
        // run into a merged entry — dropping its Staged object, and its range, from the list
        // entirely. Computed here, every run's range is still in the comparison set even if it is
        // about to be merged away; computed after, a later smaller run could falsely claim
        // "biggest until X" past a bigger run the reader can still see in that entry's own
        // coincidence factsLabel. Over the ELIGIBLE runs only: a Plan-owned partner is not a run
        // this feed lists.
        markTideSuperlativesAndThresholds(eligibleStaged);
        staged = mergeCoincidences(staged);
        resolveSuperlativeDates(staged);
        // Chronological BEFORE first-of-type: partners are appended after the eligible events, so a
        // card hosted by a Plan-owned partner would otherwise be visited last and a LATER card of
        // its type would take the "say the definition once" prose.
        staged.sort(Comparator.comparing((Staged s) -> s.startDate)
                .thenComparing(s -> s.endDate)
                .thenComparing(s -> s.event.type()));
        markFirstOfType(staged);
        markScoreNotes(staged);

        List<ComingUpEntry> entries = staged.stream()
                .map(Staged::toEntry)
                .sorted(Comparator.comparing(ComingUpEntry::startDate)
                        .thenComparing(ComingUpEntry::endDate)
                        .thenComparing(ComingUpEntry::type))
                .toList();

        ComingUpBands bands = new ComingUpBands(
                scoringProperties.getBands().getList(),
                scoringProperties.getBands().getAnnounce(),
                scoringProperties.getBands().getInterrupt());
        return new ComingUpResponse(builtFor, bands, countsOf(entries), entries);
    }

    // ── Staging: one entry, before merge/superlative/threshold passes ─────────

    /** Mutable working state for one entry, converted to an immutable {@link ComingUpEntry} once
     * every pass has run. Package-private so tests can inspect intermediate scoring. */
    static final class Staged {
        AlmanacEvent event;
        /** {@code event.meta()} with every key promoted to a typed field or fact removed (plan
         * §5: "drop the promoted keys from meta in the same commit") — starts as a straight copy
         * and each {@code enrich*} method strips its own promotions. */
        Map<String, String> meta;
        LocalDate enteredWindow;
        String id;
        String family;
        String kindTag;
        String superlative;
        String metric;
        String prose;
        List<ComingUpFact> facts = new ArrayList<>();
        String aside;
        String threshold;
        String scoreNote;
        ComingUpAction action;
        double rarityBits;
        /** False when the rarity figure is a configured estimate, so no sentence may state it. */
        boolean rarityIsStatable = true;
        double magnitudeBits;
        double bits;
        ComingUpTide tide;
        List<ComingUpCoincidenceLine> coincidence = new ArrayList<>();
        String joinNote;
        /** {@link Double#NaN} unless this is a tide-run entry with a derivable peak range. */
        double tideRangeMetres = Double.NaN;
        /** The magnitude component's own "usual" figure (e.g. {@code "2.5 m"}), set only when the
         * magnitude was computed from a mature (non-cold-start) history — never a bucketed
         * reference, which names no real distribution to compare against. Read by {@link
         * #markScoreNotes} to name what "unusually big" is being measured against. */
        String magnitudeMedianLabel;
        /** True only where magnitude was bucketed (cold start) or entirely unmeasurable — never a
         * blanket default. A non-tide type's magnitude default (1.0, the median) is "by definition
         * typical" (plan D4), not a provisional estimate, so it is NOT interim; only the tide
         * branches that fail to reach a mature (≥60-observation) empirical distribution set this
         * true. Defaults false so every other {@code enrich*} method's entries are eligible for a
         * badge, per D4's "never badge from a bucketed magnitude" — narrowly, not universally. */
        boolean interim;
        /** First day of the entry's span — the event's own until a merge widens it to the union. */
        LocalDate startDate;
        /** Last day of the entry's span — the event's own until a merge widens it to the union. */
        LocalDate endDate;
        /** The entry's title — the event's own until a merge composes the host's with the other's. */
        String title;
        /** Days a merged entry marks per topic; empty until a merge, and served only then. */
        List<ComingUpDayMark> dayMarks = List.of();
        /** A tide run's per-day ranges; the run's own on a merged entry, whichever topic hosts. */
        List<ComingUpDayRange> dayRanges = List.of();
        /** The past year's mean daily range (1 dp) the bars' dashed line and {@code tide.delta} share; null
         * unless the run has {@code dayRanges} and the year sample was sufficient. */
        Double rangeAverageMetres;
        /** The location a tide run's range, bars and a merged supermoon's moonrise are measured at;
         * null for any other type, or when no peak range could be derived. */
        LocationEntity representative;
        /** True for an event staged only as a merge partner (supermoon-tide-run-plan.md §4.7; no longer
         * passed by production, see coming-up-near-term-plan.md); dropped unless a merge consumes it. */
        boolean partnerOnly;
        /** The later, bigger run a "biggest until" superlative names — resolved to a date only after
         * the merge, from that run's final card (see {@link #resolveSuperlativeDates}). */
        Staged superlativeUntil;
        /** The Staged that now carries this one's card after a merge; null when it was not merged. */
        Staged mergedInto;

        ComingUpEntry toEntry() {
            return new ComingUpEntry(startDate, endDate, event.kind(), event.type(),
                    title, event.detail(), meta, event.regions(), enteredWindow,
                    id, family, kindTag, superlative, metric, prose, facts, aside, threshold, scoreNote,
                    action, round1(bits), interim, tide, coincidence, joinNote, dayMarks, dayRanges,
                    rangeAverageMetres);
        }
    }

    private Staged stage(LocalDate builtFor, AlmanacEvent event, List<LocationEntity> coastalRoster) {
        Staged s = new Staged();
        s.event = event;
        s.meta = event.meta();
        s.startDate = event.startDate();
        s.endDate = event.endDate();
        s.title = event.title();
        s.enteredWindow = event.startDate().minusDays(AlmanacService.DEFAULT_DAYS - 1L);
        s.id = event.type() + ":" + event.startDate() + ":" + event.endDate();
        s.kindTag = "Almanac";
        s.action = defaultAction(event);

        switch (event.type()) {
            case TYPE_SPRING_TIDE, TYPE_KING_TIDE -> enrichTide(builtFor, event, coastalRoster, s);
            case TYPE_METEOR -> enrichMeteor(event, s);
            case TYPE_SUPERMOON -> enrichSupermoon(event, s);
            case TYPE_EQUINOX, TYPE_SOLSTICE -> enrichSolar(event, s);
            case TYPE_NLC_SEASON -> enrichNlcSeason(event, s);
            case TYPE_ECLIPSE -> enrichEclipse(event, s);
            case TYPE_LUNAR_ECLIPSE -> enrichLunarEclipse(event, s);
            default -> enrichUnknown(event, s);
        }
        return s;
    }

    /**
     * The coastal roster tide-run scoring is measured against, degrading to an empty list on a DB
     * failure rather than propagating it.
     *
     * <p>{@code AlmanacService.build()} isolates each {@code AlmanacSource} the same way — one
     * source failing must not blank the feed. This read sits outside that per-source protection
     * (it happens once, before any source-specific enrichment), so without its own guard a
     * transient failure confined to tide data would 500 the whole endpoint and discard every
     * successfully built ephemeris entry (meteors, eclipses, equinoxes, …) that never touches a
     * coastal location at all. An empty roster degrades gracefully on its own: every tide-run
     * enrichment already treats "no coastal locations" the same as "no derivable peak" — interim
     * scoring, no {@code tide} field, no metric — exactly {@link #enrichTide}'s existing degrade
     * path for a representative it could not resolve.
     *
     * @return the coastal roster, or an empty list if it could not be fetched
     */
    private List<LocationEntity> coastalRoster() {
        try {
            return locationRepository.findCoastalLocations();
        } catch (RuntimeException e) {
            LOG.warn("Coastal roster fetch failed — tide-run entries in this build will score "
                    + "without a distribution rather than the whole feed failing: {}", e.toString());
            return List.of();
        }
    }

    private ComingUpAction defaultAction(AlmanacEvent event) {
        LocalDate date = event.startDate();
        return new ComingUpAction("See the plan for " + DATE_LABEL.format(date) + " →", "plan", date);
    }

    // ── Per-type enrichment ─────────────────────────────────────────────────

    private void enrichTide(LocalDate builtFor, AlmanacEvent event, List<LocationEntity> coastalRoster,
            Staged s) {
        s.family = "coastal";
        s.rarityBits = SurpriseScore.rarity(scoringProperties.getRarity().getSpringTideMeanGapDays());
        s.magnitudeBits = SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        // Interim (unconfirmed) by default — the same "no magnitude claim at all"/cold-start
        // treatment as every branch below that never reaches a mature empirical distribution
        // (no derivable peak, no TideStats, or a thin run-peak history). Only the mature branch
        // below clears it.
        s.interim = true;

        Optional<TideRunBuilder.RunPeak> peakOpt =
                tideRunBuilder.peakRange(datesOf(event), coastalRoster);
        if (peakOpt.isPresent()) {
            TideRunBuilder.RunPeak peak = peakOpt.get();
            s.tideRangeMetres = peak.rangeMetres();
            // Before the TideStats branch below: a merged supermoon's moonrise is read at this
            // location and must not depend on whether range statistics resolved.
            s.representative = peak.representative();
            s.dayRanges = dayRangesOf(peak.representative(), event);
            DailyRangeHistory.YearSample year = rankDays(builtFor, peak.representative(), s);
            s.metric = event.meta().get("range");

            Optional<TideStats> stats = tideService.getTideStats(peak.representative().getId());
            if (stats.isPresent()) {
                List<Double> history = tideRunPeakHistory.peakRanges(
                        peak.representative(), coastalRoster, builtFor, event.startDate());
                SurpriseScore.MagnitudeResult result = SurpriseScore.magnitudeFromHistory(
                        history, peak.rangeMetres(), scoringProperties.getMagnitude());
                s.magnitudeBits = result.bits();
                // Cold start (plan D4): "never badge from a bucketed magnitude — list, don't
                // badge." interim is the only carrier telling a future badge reader (P5) not to
                // treat this score as confident, since bits alone can't say so without distorting
                // the printed "rarity + magnitude = bits" sum.
                s.interim = result.coldStart();
                // The "usual" figure a magnitude-carried scoreNote names the entry's own metric
                // against — only meaningful once the magnitude was computed from a real
                // distribution (never a bucketed cold-start reference, which names no history at
                // all) and only once there is history to take a median of.
                if (!result.coldStart()) {
                    List<Double> sortedHistory = history.stream()
                            .filter(java.util.Objects::nonNull).sorted().toList();
                    if (!sortedHistory.isEmpty()) {
                        s.magnitudeMedianLabel =
                                String.format(Locale.UK, "%.1f m", medianOf(sortedHistory));
                    }
                }

                // One average per card: the 12-month mean the bars' dashed line shows when it is
                // served, else the all-history TideStats figure (a different statistic, see
                // ComingUpTide).
                java.math.BigDecimal avgRange = year != null
                        ? java.math.BigDecimal.valueOf(year.average()) : stats.get().avgRangeMetres();
                if (avgRange != null) {
                    // Range as the metric is spelt; delta is the DISPLAYED range minus the average, so
                    // "range - avg" stays honest on the card (not the raw difference, rounded apart).
                    double shownRange = metresToOneDp(peak.rangeMetres());
                    s.tide = new ComingUpTide(shownRange,
                            metresToOneDp(shownRange - avgRange.doubleValue()), PHASE_HW);
                }
            }
        }
        s.bits = s.rarityBits + s.magnitudeBits;
        s.facts = tideFacts(event);

        LocalDate actionDate = peakDateOf(event);
        s.action = new ComingUpAction(
                "Show coastal spots for " + DATE_LABEL.format(actionDate) + " →",
                "coastal-spots", actionDate);
        // range → metric, and read by tideFacts() whenever present, so dropping it is safe exactly
        // when it was present at all. location and the eight sunrise/sunset high-water keys +
        // eventWaterPartial → read by tideFacts() unconditionally too, so they are stripped from the
        // served meta (the old alignment/alignmentDate/noAlignment keys are no longer written at
        // all). peakDate/figuresFrom → action.date, computed unconditionally above. partialCoverage
        // drives facts text, not a value duplicate, so it stays. highWater is read nowhere yet and
        // stays as passthrough.
        //
        // rangeAnomaly is DIFFERENT: it is never read from meta at all — tide.delta is computed
        // independently from a fresh DB query (peak.rangeMetres() - avgRange), and that query can
        // fail (no peak, no TideStats, no avgRangeMetres) in a run where TideAlmanacSource's own
        // build-time meta still populated the string. Dropping it unconditionally would lose the
        // only copy of that fact whenever s.tide ends up null. Drop it only when tide was actually
        // built — the one case where a typed field genuinely supersedes it.
        List<String> droppedKeys = new ArrayList<>(
                List.of("range", "location", "peakDate", "figuresFrom", "sunriseHw", "sunriseHwGap",
                        "sunriseHwDate", "sunriseHwAligned", "sunsetHw", "sunsetHwGap", "sunsetHwDate",
                        "sunsetHwAligned", "eventWaterPartial", "shoulderDates"));
        if (s.tide != null) {
            droppedKeys.add("rangeAnomaly");
        }
        s.meta = withoutKeys(event.meta(), droppedKeys.toArray(new String[0]));
    }

    /**
     * The run's per-day ranges at its representative, 1 dp, with {@code peak} on the day the event's
     * own {@code peakDate} names (never {@link #peakDateOf}, whose fallback to {@code figuresFrom}
     * would crown a partial-coverage run's first measured day). Empty unless at least two days are
     * derivable.
     */
    private List<ComingUpDayRange> dayRangesOf(LocationEntity representative, AlmanacEvent event) {
        List<LocalDate> runDates = datesOf(event);
        List<LocalDate> shoulders = shoulderDatesOf(event);
        List<LocalDate> wanted = new ArrayList<>(runDates);
        wanted.addAll(shoulders);
        wanted.sort(null);
        Map<LocalDate, Double> ranges;
        try {
            ranges = tideRunBuilder.dailyRangesAt(representative, wanted);
        } catch (RuntimeException e) {
            // Bars are decoration on a card that is complete without them: degrade, never fail the feed.
            LOG.warn("Coming up: per-day tide ranges unavailable for {} {}: {}", event.type(),
                    event.startDate(), e.toString());
            return List.of();
        }
        // The "fewer than two" rule is about the run, so a shoulder can never make up the numbers.
        if (runDates.stream().filter(ranges::containsKey).count() < 2) {
            return List.of();
        }
        LocalDate trueDate = parseDate(event.meta().get("peakDate"));
        List<ComingUpDayRange> out = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> e : ranges.entrySet()) {
            out.add(new ComingUpDayRange(e.getKey(), metresToOneDp(e.getValue()),
                    e.getKey().equals(trueDate), null, shoulders.contains(e.getKey())));
        }
        return out;
    }

    /** The near-miss dates the tide source recorded in its build-time meta, ascending; empty if none. */
    private static List<LocalDate> shoulderDatesOf(AlmanacEvent event) {
        String raw = event.meta().get("shoulderDates");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        for (String part : raw.split(",")) {
            LocalDate date = parseDate(part.trim());
            if (date != null) {
                dates.add(date);
            }
        }
        return dates;
    }

    /**
     * A range in metres to one decimal place by the same rule the headline figure is spelt with
     * ({@code TideWording.metres}'s {@code "%.1f"}), so the run's tallest bar can never read 0.1 m away from
     * the metric printed beside it. Used for the day ranges and for {@code ComingUpTide.range}/{@code delta}.
     */
    private static double metresToOneDp(double metres) {
        return DailyRangeHistory.metresToOneDp(metres);
    }

    /**
     * Ranks each of the run's days against the past year at the same port and records the year's
     * average on the staged entry. Does nothing, and reads nothing, when the run has no day ranges; a
     * failed read or a thin sample leaves the run exactly as it was (no percentile, no average).
     *
     * @return the sufficient year sample that was applied, or null when none was
     */
    private DailyRangeHistory.YearSample rankDays(LocalDate builtFor, LocationEntity representative,
            Staged s) {
        if (s.dayRanges.isEmpty()) {
            return null;
        }
        DailyRangeHistory.YearSample year;
        try {
            year = dailyRangeHistory.sampleFor(representative, builtFor);
        } catch (RuntimeException e) {
            LOG.warn("Coming up: year of daily tide ranges unavailable for {} {}: {}", s.event.type(),
                    s.event.startDate(), e.toString());
            return null;
        }
        if (year == null || !year.sufficient()) {
            return null;
        }
        s.dayRanges = s.dayRanges.stream()
                .map(d -> new ComingUpDayRange(d.date(), d.rangeMetres(), d.peak(),
                        year.percentileOf(d.rangeMetres()), d.shoulder()))
                .toList();
        s.rangeAverageMetres = year.average();
        return year;
    }

    private void enrichMeteor(AlmanacEvent event, Staged s) {
        s.family = "night-sky";
        s.rarityBits = SurpriseScore.rarity(scoringProperties.getRarity().getMeteorShowerMeanGapDays());
        s.magnitudeBits = SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        s.bits = s.rarityBits + s.magnitudeBits;

        String zhr = event.meta().get("zhr");
        s.metric = zhr == null ? null : "~" + zhr + "/hr";
        s.facts = meteorFacts(event);
        s.action = new ComingUpAction(
                "Show dark-sky spots for " + DATE_LABEL.format(event.startDate()) + " →",
                "dark-sky-spots", event.startDate());
        // zhr → metric; radiant/bestHours/moonIllumination/washedOut → facts (verbatim).
        s.meta = withoutKeys(event.meta(), "zhr", "radiant", "bestHours", "moonIllumination",
                "washedOut");
    }

    private void enrichSupermoon(AlmanacEvent event, Staged s) {
        s.rarityIsStatable = false;
        s.family = "sun-moon";
        s.rarityBits = SurpriseScore.rarity(scoringProperties.getRarity().getSupermoonMeanGapDays());
        s.magnitudeBits = SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        s.bits = s.rarityBits + s.magnitudeBits;

        LocalDate peak = peakDateOf(event);
        s.facts = new ArrayList<>(List.of(new ComingUpFact(List.of(
                new ComingUpFact.Segment("peaks ", "base"),
                new ComingUpFact.Segment(DATE_LABEL.format(peak), "strong")))));
        regionScopeFact(event).ifPresent(s.facts::add);
        s.action = new ComingUpAction(
                "Show dark-sky spots for " + DATE_LABEL.format(peak) + " →", "dark-sky-spots", peak);
        // peakDate → facts + action.date.
        s.meta = withoutKeys(event.meta(), "peakDate");
    }

    private void enrichSolar(AlmanacEvent event, Staged s) {
        s.family = "sun-moon";
        s.rarityBits =
                SurpriseScore.rarity(scoringProperties.getRarity().getSolarTurningPointMeanGapDays());
        s.magnitudeBits = SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        s.bits = s.rarityBits + s.magnitudeBits;

        LocalDate peak = peakDateOf(event);
        s.facts = new ArrayList<>(List.of(new ComingUpFact(List.of(
                new ComingUpFact.Segment("falls ", "base"),
                new ComingUpFact.Segment(DATE_LABEL.format(peak), "strong")))));
        regionScopeFact(event).ifPresent(s.facts::add);
        s.action = new ComingUpAction(
                "See the plan for " + DATE_LABEL.format(peak) + " →", "plan", peak);
        // peakDate → facts + action.date.
        s.meta = withoutKeys(event.meta(), "peakDate");
    }

    private void enrichNlcSeason(AlmanacEvent event, Staged s) {
        s.family = "night-sky";
        s.rarityBits = SurpriseScore.rarity(scoringProperties.getRarity().getNlcSeasonMeanGapDays());
        s.magnitudeBits = SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        s.bits = s.rarityBits + s.magnitudeBits;

        s.facts = new ArrayList<>();
        if ("true".equals(event.meta().get("startsBeforeWindow"))) {
            s.facts.add(new ComingUpFact(List.of(
                    new ComingUpFact.Segment("already running", "base"))));
        }
        if ("true".equals(event.meta().get("endsAfterWindow"))) {
            s.facts.add(new ComingUpFact(List.of(
                    new ComingUpFact.Segment("continues past this window", "base"))));
        }
        regionScopeFact(event).ifPresent(s.facts::add);
        s.action = new ComingUpAction(
                "Show dark-sky spots for " + DATE_LABEL.format(event.startDate()) + " →",
                "dark-sky-spots", event.startDate());
        // startsBeforeWindow/endsAfterWindow → facts.
        s.meta = withoutKeys(event.meta(), "startsBeforeWindow", "endsAfterWindow");
    }

    private void enrichEclipse(AlmanacEvent event, Staged s) {
        s.rarityIsStatable = false;
        s.family = "eclipse";
        s.rarityBits = SurpriseScore.rarity(scoringProperties.getRarity().getEclipseMeanGapDays());
        s.magnitudeBits = SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        s.bits = s.rarityBits + s.magnitudeBits;

        Map<String, String> meta = event.meta();
        String coverage = meta.get("coverage");
        s.metric = coverage == null ? null : coverage.replace(" covered", "");
        s.facts = eclipseFacts(meta);
        s.action = new ComingUpAction(
                "See the plan for " + DATE_LABEL.format(event.startDate()) + " →",
                "plan", event.startDate());
        // coverage → metric + facts; maximum/rarity/location → facts (verbatim).
        s.meta = withoutKeys(meta, "coverage", "maximum", "rarity", "location");
    }

    private void enrichLunarEclipse(AlmanacEvent event, Staged s) {
        s.rarityIsStatable = false;
        s.family = "eclipse";
        s.rarityBits = SurpriseScore.rarity(scoringProperties.getRarity().getLunarEclipseMeanGapDays());
        s.magnitudeBits = SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        s.bits = s.rarityBits + s.magnitudeBits;

        Map<String, String> meta = event.meta();
        String magnitude = meta.get("magnitude");
        s.metric = magnitude == null ? null : magnitude.replace(" in shadow", "");
        // "shadow" reads "… → sets HH:mm" only when the representative sets while still eclipsed
        // (LunarEclipseAlmanacSource#shadowLine) — a lookup on the already-composed string rather
        // than a second reduction of the eclipse's geometry, which this class has no access to.
        String shadow = meta.get("shadow");
        s.superlative = shadow != null && shadow.contains("sets ") ? "sets in shadow" : null;
        s.facts = lunarEclipseFacts(meta);
        s.aside = LUNAR_ECLIPSE_EXPOSURE_NOTE;
        // Server-authored ahead of markScoreNotes' generic pass, and takes precedence over it (see
        // that method's own guard) — "since" is a catalogue fact with no bits-derived phrasing to
        // improve on, and null only for the catalogue's very first entry, which has nothing earlier
        // to point to (LunarEclipseAlmanacSource#sinceLine).
        String since = meta.get("since");
        s.scoreNote = since == null ? null : "The first visible from here since " + since + ".";
        s.action = new ComingUpAction(
                "See the plan for " + DATE_LABEL.format(event.startDate()) + " →",
                "plan", event.startDate());
        // magnitude → metric + facts; maximum/shadow/next/location → facts (verbatim); since →
        // scoreNote. seen is deliberately NOT dropped: no fact row consumes it (plan §2.4 names
        // only three fact rows), so it stays as a passthrough figure on the served meta map.
        s.meta = withoutKeys(meta, "magnitude", "maximum", "shadow", "next", "since", "location");
    }

    /**
     * Unreachable in production — the seven real {@code AlmanacSource} types are matched explicitly
     * above. Exists only so a type this class does not recognise degrades to a plausible score
     * rather than throwing.
     */
    private void enrichUnknown(AlmanacEvent event, Staged s) {
        s.family = "night-sky";
        s.rarityBits =
                SurpriseScore.rarity(scoringProperties.getRarity().getSolarTurningPointMeanGapDays());
        s.magnitudeBits = SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        s.bits = s.rarityBits + s.magnitudeBits;
        s.facts = new ArrayList<>();
        regionScopeFact(event).ifPresent(s.facts::add);
    }

    // ── Fact-row builders ───────────────────────────────────────────────────

    /**
     * A tide run's fact rows, built from the event's own meta: the range, the high water at sunrise
     * and at sunset (each always dated, merged card or not), the partial-coverage note and the
     * region scope.
     */
    private static List<ComingUpFact> tideFacts(AlmanacEvent event) {
        Map<String, String> meta = event.meta();
        List<ComingUpFact> facts = new ArrayList<>();

        if (meta.get("range") != null) {
            List<ComingUpFact.Segment> segs = new ArrayList<>(List.of(
                    new ComingUpFact.Segment("range ", "base"),
                    new ComingUpFact.Segment(meta.get("range"), "strong")));
            if (meta.get("location") != null) {
                segs.add(new ComingUpFact.Segment(" at " + meta.get("location"), "base"));
            }
            facts.add(new ComingUpFact(segs));
        }
        facts.addAll(eventWaterFacts(event));
        if ("true".equals(meta.get("partialCoverage"))) {
            facts.add(new ComingUpFact(List.of(new ComingUpFact.Segment(
                    "figures cover only part of this run", "base"))));
        }
        regionScopeFact(event).ifPresent(facts::add);
        return facts;
    }

    /**
     * The sunrise and sunset high-water rows of a tide run, in that order, each present only when the
     * run has a measured high water for the event. Used by the tide-hosted card and, from the tide
     * topic's own event, by a supermoon-hosted merged card.
     */
    private static List<ComingUpFact> eventWaterFacts(AlmanacEvent tideEvent) {
        Map<String, String> meta = tideEvent.meta();
        boolean partial = "true".equals(meta.get("eventWaterPartial"));
        List<ComingUpFact> rows = new ArrayList<>();
        eventWaterFact("sunrise", "sunriseHw", partial, meta).ifPresent(rows::add);
        eventWaterFact("sunset", "sunsetHw", partial, meta).ifPresent(rows::add);
        return rows;
    }

    /**
     * One event row: {@code sunrise · HW 07:40, 10m before · Wed 28} when the high water is inside the
     * alignment window, else {@code sunrise · no high water within an hour · nearest
     * HW 05:30, 2h20 before · Wed 28}. Empty when the run has no measured high water for the event. On a run
     * only partly measured the miss is qualified {@code on the days measured}. Figures carry
     * non-breaking spaces so a phone wraps only around the separators.
     */
    private static Optional<ComingUpFact> eventWaterFact(String word, String key, boolean partial,
            Map<String, String> meta) {
        String clock = meta.get(key);
        Long gap = parseLong(meta.get(key + "Gap"));
        LocalDate date = parseDate(meta.get(key + "Date"));
        if (clock == null || gap == null || date == null) {
            return Optional.empty();
        }
        String water = nb("HW " + clock);
        String offset = nb(eventOffset(gap, word));
        String day = nb(DAY_LABEL.format(date));
        List<ComingUpFact.Segment> segs = new ArrayList<>();
        if ("true".equals(meta.get(key + "Aligned"))) {
            segs.add(new ComingUpFact.Segment(word + EVENT_SEPARATOR, "base"));
            segs.add(new ComingUpFact.Segment(water + ", " + offset, "accent"));
            segs.add(new ComingUpFact.Segment(EVENT_SEPARATOR + day, "base"));
        } else {
            segs.add(new ComingUpFact.Segment(word + EVENT_SEPARATOR + "no high water within an hour"
                    + (partial ? " on the days measured" : "") + EVENT_SEPARATOR + "nearest "
                    + water + ", " + nb(eventOffset(gap, null)) + EVENT_SEPARATOR + day, "base"));
        }
        return Optional.of(new ComingUpFact(segs, ComingUpFact.Kind.EVENT_WATER));
    }

    /**
     * {@code 10m before}, {@code 2h20 after}, or {@code at sunrise} within five minutes; with a null
     * {@code word} (the miss form, whose label already names the event) the "at" form never arises.
     */
    private static String eventOffset(long gapMinutes, String word) {
        long magnitude = Math.abs(gapMinutes);
        if (word != null && magnitude <= EVENT_COINCIDENT_MINUTES) {
            return "at " + word;
        }
        String duration = magnitude < MINUTES_PER_HOUR
                ? magnitude + "m"
                : String.format(Locale.UK, "%dh%02d", magnitude / MINUTES_PER_HOUR,
                        magnitude % MINUTES_PER_HOUR);
        return duration + (gapMinutes >= 0 ? " after" : " before");
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<ComingUpFact> meteorFacts(AlmanacEvent event) {
        Map<String, String> meta = event.meta();
        List<ComingUpFact> facts = new ArrayList<>();

        List<ComingUpFact.Segment> segs = new ArrayList<>();
        if (meta.get("radiant") != null) {
            segs.add(new ComingUpFact.Segment("radiates from the ", "base"));
            segs.add(new ComingUpFact.Segment(meta.get("radiant"), "strong"));
        }
        if (meta.get("bestHours") != null) {
            if (!segs.isEmpty()) {
                segs.add(new ComingUpFact.Segment(" · ", "base"));
            }
            segs.add(new ComingUpFact.Segment("best " + meta.get("bestHours"), "base"));
        }
        if (!segs.isEmpty()) {
            facts.add(new ComingUpFact(segs));
        }
        if ("true".equals(meta.get("washedOut"))) {
            facts.add(new ComingUpFact(List.of(new ComingUpFact.Segment(
                    "moon " + meta.getOrDefault("moonIllumination", "")
                            + " lit — washes out all but the brightest", "accent"))));
        }
        regionScopeFact(event).ifPresent(facts::add);
        return facts;
    }

    private static List<ComingUpFact> eclipseFacts(Map<String, String> meta) {
        List<ComingUpFact> facts = new ArrayList<>();
        List<ComingUpFact.Segment> segs = new ArrayList<>();
        if (meta.get("coverage") != null) {
            segs.add(new ComingUpFact.Segment(meta.get("coverage"), "strong"));
        }
        if (meta.get("maximum") != null) {
            if (!segs.isEmpty()) {
                segs.add(new ComingUpFact.Segment(" · ", "base"));
            }
            segs.add(new ComingUpFact.Segment("maximum " + meta.get("maximum"), "base"));
        }
        if (!segs.isEmpty()) {
            facts.add(new ComingUpFact(segs));
        }
        if (meta.get("rarity") != null) {
            facts.add(new ComingUpFact(List.of(new ComingUpFact.Segment(meta.get("rarity"), "accent"))));
        }
        if (meta.get("location") != null) {
            facts.add(new ComingUpFact(List.of(
                    new ComingUpFact.Segment("figures for ", "base"),
                    new ComingUpFact.Segment(meta.get("location"), "strong"))));
        }
        return facts;
    }

    private static List<ComingUpFact> lunarEclipseFacts(Map<String, String> meta) {
        List<ComingUpFact> facts = new ArrayList<>();
        List<ComingUpFact.Segment> segs = new ArrayList<>();
        if (meta.get("magnitude") != null) {
            segs.add(new ComingUpFact.Segment(meta.get("magnitude"), "strong"));
        }
        if (meta.get("maximum") != null) {
            if (!segs.isEmpty()) {
                segs.add(new ComingUpFact.Segment(" · ", "base"));
            }
            segs.add(new ComingUpFact.Segment("maximum " + meta.get("maximum"), "base"));
        }
        if (!segs.isEmpty()) {
            facts.add(new ComingUpFact(segs));
        }
        if (meta.get("shadow") != null) {
            facts.add(new ComingUpFact(List.of(new ComingUpFact.Segment(meta.get("shadow"), "base"))));
        }
        if (meta.get("next") != null) {
            facts.add(new ComingUpFact(List.of(new ComingUpFact.Segment(meta.get("next"), "accent"))));
        }
        if (meta.get("location") != null) {
            facts.add(new ComingUpFact(List.of(
                    new ComingUpFact.Segment("figures for ", "base"),
                    new ComingUpFact.Segment(meta.get("location"), "strong"))));
        }
        return facts;
    }

    /**
     * A fact row naming the entry's regional scope — only where the read is genuinely regional
     * (plan §5's scope rule). None of the seven {@code AlmanacSource}s populate {@code regions} yet,
     * so this is dead in production today and exercised only by a direct fixture; it exists so a
     * future regional read has somewhere to plug in without a schema change.
     */
    private static Optional<ComingUpFact> regionScopeFact(AlmanacEvent event) {
        if (event.regions().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ComingUpFact(List.of(
                new ComingUpFact.Segment("applies to ", "base"),
                new ComingUpFact.Segment(String.join(", ", event.regions()), "strong"))));
    }

    // ── Coincidence merge (plan D10) ────────────────────────────────────────

    private static boolean isTideRun(String type) {
        return TYPE_SPRING_TIDE.equals(type) || TYPE_KING_TIDE.equals(type);
    }

    /** Whether {@code b} is the other half of a pairable coincidence with {@code a} (run ↔ supermoon). */
    private static boolean complementary(AlmanacEvent a, AlmanacEvent b) {
        return (isTideRun(a.type()) && TYPE_SUPERMOON.equals(b.type()))
                || (TYPE_SUPERMOON.equals(a.type()) && isTideRun(b.type()));
    }

    private static boolean overlaps(AlmanacEvent a, AlmanacEvent b) {
        return !a.endDate().isBefore(b.startDate()) && !b.endDate().isBefore(a.startDate());
    }

    /**
     * Pairs every tide run with an overlapping supermoon (D10), then merges each pair — in two
     * passes, deliberately, because pairing and emitting in one pass makes the result depend on
     * list order. {@code AlmanacService.build()} sorts entries by date, and a supermoon's span
     * commonly starts before or ends sooner than the tide run it overlaps; a single forward pass
     * that both searches for partners AND immediately emits unmatched entries would emit that
     * supermoon standalone before the later tide run ever got a chance to claim it — producing
     * both the standalone supermoon AND a second, merged copy of the same coincidence. Finding
     * every pair first, over the read-only input list, then building the output from that
     * complete pairing is the fix: which entries merge no longer depends on which one the loop
     * reaches first.
     *
     * @param staged every staged entry, any order
     * @return the same entries with each tide-run/supermoon pair collapsed to one, in the order
     *         a plain forward pass over the input would emit them (final ordering is imposed by
     *         {@link #assemble} regardless)
     */
    private List<Staged> mergeCoincidences(List<Staged> staged) {
        Map<Integer, Integer> tideToSupermoon = new LinkedHashMap<>();
        Set<Integer> supermoonConsumed = new HashSet<>();
        for (int i = 0; i < staged.size(); i++) {
            if (!isTideRun(staged.get(i).event.type())) {
                continue;
            }
            int partnerIndex = findSupermoonPartner(staged, i, supermoonConsumed);
            if (partnerIndex >= 0) {
                tideToSupermoon.put(i, partnerIndex);
                supermoonConsumed.add(partnerIndex);
            }
        }

        List<Staged> result = new ArrayList<>();
        for (int i = 0; i < staged.size(); i++) {
            if (supermoonConsumed.contains(i)) {
                continue;
            }
            Integer partnerIndex = tideToSupermoon.get(i);
            if (partnerIndex != null) {
                result.add(mergeEntries(staged.get(i), staged.get(partnerIndex)));
            } else if (!staged.get(i).partnerOnly) {
                // A Plan-owned event that paired with nothing is not this feed's to list.
                result.add(staged.get(i));
            }
        }
        return result;
    }

    private static int findSupermoonPartner(List<Staged> staged, int i, Set<Integer> consumed) {
        for (int j = 0; j < staged.size(); j++) {
            if (j == i || consumed.contains(j)) {
                continue;
            }
            Staged candidate = staged.get(j);
            // Two Plan-owned events never pair: their union would end inside Plan's window.
            if (staged.get(i).partnerOnly && candidate.partnerOnly) {
                continue;
            }
            if (TYPE_SUPERMOON.equals(candidate.event.type()) && overlaps(staged.get(i).event,
                    candidate.event)) {
                return j;
            }
        }
        return -1;
    }

    /**
     * Merges a tide run and a supermoon sharing dates into one entry (supermoon-tide-run-plan.md
     * R1–R10): the bits winner still decides the SCORE ({@code bits = max}, {@code interim} and
     * {@code enteredWindow} from the winner), but the SHAPE follows the span — the union of both
     * topics' dates, hosted by the topic with the longest span (a tie goes to the winner). The host
     * supplies type, family, facts, action and the rest; each topic keeps its own dated coincidence
     * line and its own marked days.
     */
    private Staged mergeEntries(Staged tide, Staged supermoon) {
        Staged winner = tide.bits >= supermoon.bits ? tide : supermoon;
        Staged host = hostOf(tide, supermoon, winner);
        Staged other = host == tide ? supermoon : tide;

        double bits = Math.max(tide.bits, supermoon.bits);
        boolean interim = winner.interim;
        LocalDate enteredWindow = winner.enteredWindow;

        host.startDate = tide.startDate.isBefore(supermoon.startDate) ? tide.startDate : supermoon.startDate;
        host.endDate = tide.endDate.isAfter(supermoon.endDate) ? tide.endDate : supermoon.endDate;
        host.id = host.event.type() + ":" + host.startDate + ":" + host.endDate;
        host.title = host.event.title() + ", on a " + lowerFirst(other.event.title());
        host.bits = bits;
        host.interim = interim;
        host.enteredWindow = enteredWindow;
        host.partnerOnly = false;
        other.mergedInto = host;

        host.coincidence = new ArrayList<>(List.of(
                coincidenceLine(host, tide.representative),
                coincidenceLine(other, tide.representative)));
        host.joinNote = perigeeJoinNote(supermoon, tide);

        List<ComingUpDayMark> marks = new ArrayList<>(dayMarksOf(host));
        marks.addAll(dayMarksOf(other));
        marks.sort(Comparator.comparing(ComingUpDayMark::date));
        host.dayMarks = marks;
        host.dayRanges = tide.dayRanges;
        host.rangeAverageMetres = tide.rangeAverageMetres;

        // Facts by construction, never by text surgery: both event rows carry their own date, and a
        // hosting supermoon drops the "peaks" fact (the join note and its mark say it better).
        if (host == tide) {
            host.facts = tideFacts(host.event);
        } else {
            host.facts = new ArrayList<>();
            regionScopeFact(host.event).ifPresent(host.facts::add);
            // The run's high-water rows still ride the merged card, read from the TIDE topic's own
            // event (never the host's); the range-style and "peaks" facts stay dropped.
            host.facts.addAll(eventWaterFacts(tide.event));
        }
        return host;
    }

    /** R2: the longest span hosts; a tie goes to the bits winner (which, on a bits tie, is the run). */
    private static Staged hostOf(Staged tide, Staged supermoon, Staged winner) {
        long tideDays = java.time.temporal.ChronoUnit.DAYS.between(tide.startDate, tide.endDate);
        long moonDays = java.time.temporal.ChronoUnit.DAYS.between(supermoon.startDate, supermoon.endDate);
        if (tideDays == moonDays) {
            return winner;
        }
        return tideDays > moonDays ? tide : supermoon;
    }

    private static String lowerFirst(String title) {
        return title.isEmpty() ? title : Character.toLowerCase(title.charAt(0)) + title.substring(1);
    }

    private ComingUpCoincidenceLine coincidenceLine(Staged topic, LocationEntity runRepresentative) {
        return new ComingUpCoincidenceLine(topic.family, topic.event.title(),
                coincidenceFacts(topic, runRepresentative, true), topic.event.type(),
                coincidenceFacts(topic, runRepresentative, false));
    }

    // ── Per-type vocabulary: day marks, coincidence facts, join note (plan §2.2) ───────────

    /** The days a topic marks on a merged card: a run's true peak, a supermoon's every night. */
    private static List<ComingUpDayMark> dayMarksOf(Staged topic) {
        List<ComingUpDayMark> marks = new ArrayList<>();
        if (TYPE_SUPERMOON.equals(topic.event.type())) {
            for (LocalDate night : datesOf(topic.event)) {
                marks.add(new ComingUpDayMark(night, MARK_FULL_MOON, topic.family));
            }
        } else {
            LocalDate peak = parseDate(topic.event.meta().get("peakDate"));
            if (peak != null) {
                marks.add(new ComingUpDayMark(peak, MARK_PEAK, topic.family));
            }
        }
        return marks;
    }

    /**
     * One topic's coincidence-line facts, as a plain string. A tide run reads its own span, true peak
     * day, range and (only when the alignment is on the peak day) the alignment clock; a supermoon
     * reads its span and the moonrise at the merged run's representative. {@code withLightClock} false
     * builds the Lite label: the same string without the alignment clock, the only clause that
     * states where high water falls against the light (tide-light-pro-plan.md §3 B2).
     */
    private String coincidenceFacts(Staged topic, LocationEntity runRepresentative,
            boolean withLightClock) {
        String span = spanLabel(topic.event.startDate(), topic.event.endDate());
        if (TYPE_SUPERMOON.equals(topic.event.type())) {
            return span + moonriseClause(topic, runRepresentative);
        }
        Map<String, String> meta = topic.event.meta();
        LocalDate truePeak = parseDate(meta.get("peakDate"));
        if (truePeak != null) {
            StringBuilder label = new StringBuilder(span).append(SEPARATOR)
                    .append(nb("biggest " + DAY_LABEL.format(truePeak)));
            String rank = peakRankLabel(topic);
            if (rank != null) {
                label.append(SEPARATOR).append(nb(rank));
            } else if (topic.metric != null) {
                label.append(SEPARATOR).append(nb(topic.metric));
            }
            String clock = withLightClock ? peakDayAlignedClock(meta, truePeak) : null;
            if (clock != null) {
                label.append(SEPARATOR).append(nb(clock));
            }
            return label.toString();
        }
        LocalDate figuresFrom = parseDate(meta.get("figuresFrom"));
        if (figuresFrom != null && topic.metric != null) {
            return span + SEPARATOR + nb(topic.metric + " on " + DAY_LABEL.format(figuresFrom));
        }
        return span;
    }

    /**
     * {@code top 2% of the year} for a run's true-peak day, from the run's own served day ranges; null
     * when the peak day carries no percentile (thin year sample, no bars).
     */
    private static String peakRankLabel(Staged tide) {
        return tide.dayRanges.stream()
                .filter(d -> d.peak() && d.rangePercentile() != null)
                .map(d -> "top " + d.rangePercentile() + "% of the year")
                .findFirst().orElse(null);
    }

    /**
     * Non-breaking spaces inside one segment of a facts label, so a phone wraps the line only
     * around the {@code " · "} separators and the en dash, never inside a figure or a date.
     */
    private static String nb(String segment) {
        return segment.replace(' ', ' ');
    }

    /**
     * {@code HW 15:48} — the high water of an ALIGNED event row dated on the run's true peak (sunset
     * first, then sunrise), or null. The same pick the card's rows state, so one card never gives two
     * answers.
     */
    private static String peakDayAlignedClock(Map<String, String> meta, LocalDate truePeak) {
        for (String key : List.of("sunsetHw", "sunriseHw")) {
            if ("true".equals(meta.get(key + "Aligned")) && meta.get(key) != null
                    && truePeak.equals(parseDate(meta.get(key + "Date")))) {
                return "HW " + meta.get(key);
            }
        }
        return null;
    }

    private String moonriseClause(Staged supermoon, LocationEntity representative) {
        if (representative == null) {
            return "";
        }
        LocalDate night = peakDateOf(supermoon.event);
        MoonriseMoonset moonriseMoonset = moonriseMoonsetCalculator.calculate(
                night, representative.getLat(), representative.getLon(), LONDON);
        // Formatted from the instant, never from the returned zone: the same rule the hot topic's
        // own moonrise fact follows.
        return moonriseMoonset.moonrise()
                .map(rise -> rise.withZoneSameInstant(LONDON).toLocalTime().format(HH_MM))
                .map(time -> SEPARATOR + nb("moonrise " + time + (supermoon.event.startDate()
                        .equals(supermoon.event.endDate()) ? "" : " on the " + ordinal(night.getDayOfMonth()))))
                .orElse("");
    }

    /** {@code Mon 26 Oct}, {@code Sun 25 – Fri 30 Oct}, {@code Fri 30 Oct – Tue 3 Nov}; non-breaking per part. */
    static String spanLabel(LocalDate start, LocalDate end) {
        if (start.equals(end)) {
            return nb(DAY_MONTH_LABEL.format(start));
        }
        if (start.getMonth() == end.getMonth() && start.getYear() == end.getYear()) {
            return nb(DAY_LABEL.format(start)) + " – " + nb(DAY_MONTH_LABEL.format(end));
        }
        return nb(DAY_MONTH_LABEL.format(start)) + " – " + nb(DAY_MONTH_LABEL.format(end));
    }

    /** {@code 1st 2nd 3rd 4th … 11th 12th 13th … 21st 22nd 23rd … 31st}. */
    static String ordinal(int day) {
        int lastTwo = day % 100;
        if (lastTwo >= 11 && lastTwo <= 13) {
            return day + "th";
        }
        return day + switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /**
     * The merged card's join note (R7): the shared cause, then each topic's own date(s) — same-day
     * wording when the peak lands on a full-moon night, a relative offset from the nearest night
     * otherwise, nothing about the peak when the run has no true peak. No month name, no bits.
     */
    private static String perigeeJoinNote(Staged supermoon, Staged tide) {
        LocalDate peak = parseDate(tide.event.meta().get("peakDate"));
        if (peak == null) {
            return JOIN_STEM + COUNTED_AS_ONE;
        }
        List<LocalDate> nights = datesOf(supermoon.event);
        if (nights.contains(peak)) {
            return JOIN_STEM + " The moon is full and the tide peaks on the same day, the "
                    + ordinal(peak.getDayOfMonth()) + "." + COUNTED_AS_ONE;
        }
        LocalDate nearest = nights.getFirst();
        for (LocalDate night : nights) {
            if (Math.abs(java.time.temporal.ChronoUnit.DAYS.between(night, peak))
                    < Math.abs(java.time.temporal.ChronoUnit.DAYS.between(nearest, peak))) {
                nearest = night;
            }
        }
        long gap = java.time.temporal.ChronoUnit.DAYS.between(nearest, peak);
        long days = Math.abs(gap);
        String relative = (days == 1 ? "a day" : days + " days") + (gap > 0 ? " later" : " earlier");
        return JOIN_STEM + " The moon is full on the " + nightsList(nights) + " and the tide peaks "
                + relative + ", on the " + ordinal(peak.getDayOfMonth()) + "." + COUNTED_AS_ONE;
    }

    /** {@code 26th} / {@code 24th and 25th} / {@code 23rd, 24th and 25th}. */
    private static String nightsList(List<LocalDate> nights) {
        List<String> words = nights.stream().map(d -> ordinal(d.getDayOfMonth())).toList();
        if (words.size() == 1) {
            return words.getFirst();
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
    }

    // ── First-of-type prose, superlatives, thresholds, score notes ─────────

    private static void markFirstOfType(List<Staged> staged) {
        Set<String> seen = new HashSet<>();
        for (Staged s : staged) {
            if (seen.add(s.event.type())) {
                s.prose = s.event.detail();
            }
        }
    }

    /**
     * Superlatives and threshold lines for tide-run entries — the only P2 type with a comparable
     * numeric magnitude across occurrences in the same window (plan §5).
     *
     * <p>The superlative must be falsifiable-proof: "biggest until 26 Nov" is only true of a run
     * that is <b>itself</b> the biggest seen so far in the window. Comparing only against later
     * runs is not enough — a run with a bigger one earlier in the same window was never "biggest"
     * at all, and would print a false claim a reader can falsify by scrolling up.
     */
    private static void markTideSuperlativesAndThresholds(List<Staged> staged) {
        List<Staged> tideRuns = staged.stream()
                .filter(s -> isTideRun(s.event.type()) && !Double.isNaN(s.tideRangeMetres))
                .sorted(Comparator.comparing(s -> s.event.startDate()))
                .toList();
        if (tideRuns.size() < 2) {
            return;
        }
        double biggestSoFar = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tideRuns.size(); i++) {
            Staged current = tideRuns.get(i);
            boolean isBiggestSoFar = current.tideRangeMetres > biggestSoFar;
            biggestSoFar = Math.max(biggestSoFar, current.tideRangeMetres);

            if (isBiggestSoFar) {
                for (int j = i + 1; j < tideRuns.size(); j++) {
                    if (tideRuns.get(j).tideRangeMetres > current.tideRangeMetres) {
                        // The date is resolved after the merge, from the card the reader will see.
                        current.superlativeUntil = tideRuns.get(j);
                        break;
                    }
                }
            }
            current.threshold = thresholdLine(current, tideRuns);
        }
    }

    /**
     * Turns each "biggest until" reference into its date, after the merge: the later run may now be
     * a merged card whose start is the union's (or may have been absorbed into another host), and
     * the superlative must name the date THAT card shows. The comparison set itself was fixed
     * before the merge.
     */
    private static void resolveSuperlativeDates(List<Staged> staged) {
        for (Staged s : staged) {
            if (s.superlativeUntil == null) {
                continue;
            }
            Staged shown = s.superlativeUntil.mergedInto != null
                    ? s.superlativeUntil.mergedInto : s.superlativeUntil;
            s.superlative = "biggest until " + DATE_LABEL.format(shown.startDate);
        }
    }

    private static String thresholdLine(Staged current, List<Staged> tideRuns) {
        List<Double> others = tideRuns.stream()
                .filter(other -> other != current)
                .map(other -> other.tideRangeMetres)
                .toList();
        double min = others.stream().mapToDouble(Double::doubleValue).min().orElse(current.tideRangeMetres);
        double max = others.stream().mapToDouble(Double::doubleValue).max().orElse(current.tideRangeMetres);
        return String.format(Locale.UK, "The other %d run%s in this window ranged %.1f–%.1f m.",
                others.size(), others.size() == 1 ? "" : "s", min, max);
    }

    /**
     * One server-authored, plain-language sentence for entries at or above the announce band
     * (plan D4), naming whichever component — rarity or magnitude — carried the score. Skipped
     * for a merged entry (its {@code joinNote} explains the coincidence; the merged card carries no
     * separate score sentence), and for any entry whose own
     * {@code enrich*} method already wrote a more specific {@code scoreNote} — the lunar eclipse's
     * "first visible from here since …" sentence, derived from the catalogue rather than from
     * bits, must win over this method's generic rarity/magnitude phrasing (lunar-eclipse-plan.md
     * §2.4); a null {@code scoreNote} still falls through to the generic pass below, which is how
     * an eclipse with nothing to compare against (the catalogue's earliest entry) still gets one.
     *
     * <p>No user-facing string here shows a surprisal score (lunar-eclipse plan §2.9): the
     * rarity-carried branch reads back the mean gap in plain calendar words
     * ({@link SurpriseScore#gapWord}) rather than the bits number that produced it, and the
     * magnitude-carried branch names the entry's own figure against the "usual" one from its
     * history — falling back to a figure-free sentence only when no such history exists (a
     * bucketed cold-start magnitude names no real distribution to compare against).
     */
    private void markScoreNotes(List<Staged> staged) {
        double announce = scoringProperties.getBands().getAnnounce();
        for (Staged s : staged) {
            if (s.bits < announce || s.joinNote != null || s.scoreNote != null) {
                continue;
            }
            if (s.rarityBits >= s.magnitudeBits) {
                // A configured estimate (eclipses, supermoon) scores the badge but is never stated
                // to a reader as a frequency; only ephemeris-derived gaps are.
                s.scoreNote = s.rarityIsStatable
                        ? "It comes round about once " + SurpriseScore.gapWord(s.rarityBits)
                                + ", which is rare enough to flag on its own."
                        : "It is rare enough to flag on its own.";
            } else if (s.metric != null && s.magnitudeMedianLabel != null) {
                s.scoreNote = "An unusually big one — " + s.metric + " against the usual "
                        + s.magnitudeMedianLabel + ".";
            } else {
                s.scoreNote = "An unusually big one carries it, not rarity.";
            }
        }
    }

    // ── Counts ───────────────────────────────────────────────────────────

    private static ComingUpCounts countsOf(List<ComingUpEntry> entries) {
        int fixed = 0;
        int forecast = 0;
        Map<String, Integer> byFamily = new LinkedHashMap<>();
        for (ComingUpEntry entry : entries) {
            if (entry.kind() == AlmanacKind.ALMANAC) {
                fixed++;
            } else {
                forecast++;
            }
            // A merged entry counts under every distinct family it holds, so the client's family
            // filter (which finds it under each) and these chip counts agree; fixed/forecast above
            // still count it once.
            Set<String> families = new LinkedHashSet<>();
            families.add(entry.family());
            entry.coincidence().forEach(line -> families.add(line.family()));
            families.forEach(family -> byFamily.merge(family, 1, Integer::sum));
        }
        return new ComingUpCounts(fixed, forecast, byFamily);
    }

    // ── Small shared helpers ─────────────────────────────────────────────

    private static List<LocalDate> datesOf(AlmanacEvent event) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = event.startDate(); !date.isAfter(event.endDate());
                date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }

    /** The date a meta value names, or null when absent or unparseable — never a fallback date. */
    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
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

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * Nearest-rank median (the lower of the two middle values for an even count), so a
     * scoreNote's "usual" figure has one fixed definition of "median".
     *
     * @param sortedAscending a non-empty list, ascending order
     */
    private static double medianOf(List<Double> sortedAscending) {
        int index = Math.max(0, (int) Math.ceil(0.5 * sortedAscending.size()) - 1);
        return sortedAscending.get(Math.min(index, sortedAscending.size() - 1));
    }

    /**
     * {@code meta} with the given keys removed (plan §5: "drop the promoted keys from meta in the
     * same commit") — every key here is one this class re-expressed as a typed field or a fact row
     * verbatim, so leaving it in {@code meta} would serve the same information twice.
     *
     * @param meta the source event's meta map
     * @param keys keys to drop; a key absent from {@code meta} is a harmless no-op
     * @return a new map, or {@code meta} unchanged when it was already empty
     */
    private static Map<String, String> withoutKeys(Map<String, String> meta, String... keys) {
        if (meta.isEmpty()) {
            return meta;
        }
        Map<String, String> result = new LinkedHashMap<>(meta);
        for (String key : keys) {
            result.remove(key);
        }
        return result;
    }
}
