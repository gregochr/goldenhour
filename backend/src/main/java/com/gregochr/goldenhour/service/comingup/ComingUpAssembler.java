package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.comingup.ComingUpAction;
import com.gregochr.goldenhour.model.comingup.ComingUpBands;
import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpCounts;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpFact;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.model.comingup.ComingUpTide;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p><b>Six known types, one documented fallback.</b> The type-string constants below duplicate
 * literals owned by the six {@code AlmanacSource} implementations (their own constants are
 * package-private to a sibling package, and the plan's sources stay untouched) — an unrecognised
 * type degrades to a generic score rather than throwing, which only a hand-built test fixture can
 * ever exercise.
 */
@Component
public class ComingUpAssembler {

    static final String TYPE_SPRING_TIDE = "spring-tide";
    static final String TYPE_KING_TIDE = "king-tide";
    static final String TYPE_METEOR = "meteor";
    static final String TYPE_SUPERMOON = "supermoon";
    static final String TYPE_EQUINOX = "equinox";
    static final String TYPE_SOLSTICE = "solstice";
    static final String TYPE_NLC_SEASON = "nlc-season";
    static final String TYPE_ECLIPSE = "eclipse";

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.UK);

    private final LocationRepository locationRepository;
    private final TideRunBuilder tideRunBuilder;
    private final TideRunPeakHistory tideRunPeakHistory;
    private final TideService tideService;
    private final ComingUpScoringProperties scoringProperties;

    /**
     * Constructs a {@code ComingUpAssembler}.
     *
     * @param locationRepository supplies the coastal roster tide-run scoring is measured against
     * @param tideRunBuilder     supplies a run's numeric peak range and representative
     * @param tideRunPeakHistory supplies the representative's own historical run-peak distribution
     * @param tideService        supplies each representative's stored range statistics
     * @param scoringProperties  every knob the surprise model uses
     */
    public ComingUpAssembler(LocationRepository locationRepository, TideRunBuilder tideRunBuilder,
            TideRunPeakHistory tideRunPeakHistory, TideService tideService,
            ComingUpScoringProperties scoringProperties) {
        this.locationRepository = locationRepository;
        this.tideRunBuilder = tideRunBuilder;
        this.tideRunPeakHistory = tideRunPeakHistory;
        this.tideService = tideService;
        this.scoringProperties = scoringProperties;
    }

    /**
     * Builds the full response from the events that already cleared eligibility.
     *
     * @param builtFor       the UK civil date the feed is built for
     * @param eligibleEvents events past Plan's boundary (plan D1), any order
     * @return the wrapped response, entries sorted by start date then span length then type
     */
    public ComingUpResponse assemble(LocalDate builtFor, List<AlmanacEvent> eligibleEvents) {
        List<LocationEntity> coastalRoster = locationRepository.findCoastalLocations();

        List<Staged> staged = new ArrayList<>();
        for (AlmanacEvent event : eligibleEvents) {
            staged.add(stage(builtFor, event, coastalRoster));
        }

        // Superlatives/thresholds MUST run before the merge: they compare a tide run's own range
        // against every other tide run in the window, and mergeCoincidences can absorb a whole tide
        // run into a supermoon's coincidence line — dropping its Staged object, and its range, from
        // the list entirely. Computed here, every run's range is still in the comparison set even
        // if it is about to be merged away; computed after, a later smaller run could falsely claim
        // "biggest until X" past a bigger run the reader can still see in that entry's own
        // coincidence factsLabel.
        markTideSuperlativesAndThresholds(staged);
        staged = mergeCoincidences(staged);
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
        return new ComingUpResponse(builtFor, bands, countsOf(entries), List.of(), entries);
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
        String threshold;
        String scoreNote;
        ComingUpAction action;
        double rarityBits;
        double magnitudeBits;
        double bits;
        ComingUpTide tide;
        List<ComingUpCoincidenceLine> coincidence = new ArrayList<>();
        String joinNote;
        /** {@link Double#NaN} unless this is a tide-run entry with a derivable peak range. */
        double tideRangeMetres = Double.NaN;
        /** True only where magnitude was bucketed (cold start) or entirely unmeasurable — never a
         * blanket default. A non-tide type's magnitude default (1.0, the median) is "by definition
         * typical" (plan D4), not a provisional estimate, so it is NOT interim; only the tide
         * branches that fail to reach a mature (≥60-observation) empirical distribution set this
         * true. Defaults false so every other {@code enrich*} method's entries are eligible for a
         * badge, per D4's "never badge from a bucketed magnitude" — narrowly, not universally. */
        boolean interim;

        ComingUpEntry toEntry() {
            return new ComingUpEntry(event.startDate(), event.endDate(), event.kind(), event.type(),
                    event.title(), event.detail(), meta, event.regions(), enteredWindow,
                    id, family, kindTag, superlative, metric, prose, facts, threshold, scoreNote,
                    action, round1(bits), interim, tide, coincidence, joinNote);
        }
    }

    private Staged stage(LocalDate builtFor, AlmanacEvent event, List<LocationEntity> coastalRoster) {
        Staged s = new Staged();
        s.event = event;
        s.meta = event.meta();
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
            default -> enrichUnknown(event, s);
        }
        return s;
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

                java.math.BigDecimal avgRange = stats.get().avgRangeMetres();
                if (avgRange != null) {
                    s.tide = new ComingUpTide(round1(peak.rangeMetres()),
                            round1(peak.rangeMetres() - avgRange.doubleValue()), phaseOf(event));
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
        // when it was present at all. alignment/location → read by tideFacts() unconditionally too.
        // peakDate/figuresFrom → action.date, computed unconditionally above. noAlignment/
        // partialCoverage drive facts text, not a value duplicate, so they stay. highWater/
        // alignmentDate are read nowhere yet and stay as passthrough.
        //
        // rangeAnomaly is DIFFERENT: it is never read from meta at all — tide.delta is computed
        // independently from a fresh DB query (peak.rangeMetres() - avgRange), and that query can
        // fail (no peak, no TideStats, no avgRangeMetres) in a run where TideAlmanacSource's own
        // build-time meta still populated the string. Dropping it unconditionally would lose the
        // only copy of that fact whenever s.tide ends up null. Drop it only when tide was actually
        // built — the one case where a typed field genuinely supersedes it.
        List<String> droppedKeys = new ArrayList<>(
                List.of("range", "alignment", "location", "peakDate", "figuresFrom"));
        if (s.tide != null) {
            droppedKeys.add("rangeAnomaly");
        }
        s.meta = withoutKeys(event.meta(), droppedKeys.toArray(new String[0]));
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

    /**
     * Unreachable in production — the six real {@code AlmanacSource} types are matched explicitly
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
        if (meta.get("alignment") != null) {
            facts.add(new ComingUpFact(List.of(
                    new ComingUpFact.Segment("tide ", "base"),
                    new ComingUpFact.Segment(meta.get("alignment"), "accent"))));
        } else if ("true".equals(meta.get("noAlignment"))) {
            facts.add(new ComingUpFact(List.of(new ComingUpFact.Segment(
                    "no water lands in the light on this run", "base"))));
        }
        if ("true".equals(meta.get("partialCoverage"))) {
            facts.add(new ComingUpFact(List.of(new ComingUpFact.Segment(
                    "figures cover only part of this run", "base"))));
        }
        regionScopeFact(event).ifPresent(facts::add);
        return facts;
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

    /**
     * A fact row naming the entry's regional scope — only where the read is genuinely regional
     * (plan §5's scope rule). None of the six {@code AlmanacSource}s populate {@code regions} yet,
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

    private static boolean overlaps(AlmanacEvent a, AlmanacEvent b) {
        return !a.endDate().isBefore(b.startDate()) && !b.endDate().isBefore(a.startDate());
    }

    private List<Staged> mergeCoincidences(List<Staged> staged) {
        List<Staged> result = new ArrayList<>();
        Set<Integer> consumed = new HashSet<>();
        for (int i = 0; i < staged.size(); i++) {
            if (consumed.contains(i)) {
                continue;
            }
            Staged a = staged.get(i);
            int partnerIndex = isTideRun(a.event.type()) ? findSupermoonPartner(staged, i, consumed) : -1;
            if (partnerIndex >= 0) {
                consumed.add(partnerIndex);
                result.add(mergeEntries(a, staged.get(partnerIndex)));
            } else {
                result.add(a);
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
            if (TYPE_SUPERMOON.equals(candidate.event.type()) && overlaps(staged.get(i).event,
                    candidate.event)) {
                return j;
            }
        }
        return -1;
    }

    /**
     * Merges a tide run and a supermoon sharing dates into one entry, per D10's max rule: the
     * pair's causal link is one perigee, so the winner's own score stands and the loser folds into
     * {@code coincidence} rather than adding.
     */
    private Staged mergeEntries(Staged tide, Staged supermoon) {
        Staged winner = tide.bits >= supermoon.bits ? tide : supermoon;
        Staged loser = winner == tide ? supermoon : tide;

        winner.coincidence = new ArrayList<>(winner.coincidence);
        winner.coincidence.add(new ComingUpCoincidenceLine(
                loser.family, loser.event.title(), loserFactsLabel(loser)));
        winner.bits = Math.max(tide.bits, supermoon.bits);
        winner.joinNote = "One perigee causes both, so the pair scores as the maximum of the two, "
                + "not the sum: " + round1(winner.bits) + " bits — the " + winner.event.title()
                + " carries it.";
        return winner;
    }

    private static String loserFactsLabel(Staged loser) {
        LocalDate date = peakDateOf(loser.event);
        return loser.metric == null
                ? DATE_LABEL.format(date)
                : loser.metric + " · " + DATE_LABEL.format(date);
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
                        current.superlative = "biggest until "
                                + DATE_LABEL.format(tideRuns.get(j).event.startDate());
                        break;
                    }
                }
            }
            current.threshold = thresholdLine(current, tideRuns);
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
     * One server-authored sentence for entries at or above the announce band (plan D4), naming
     * whichever component — rarity or magnitude — carried the score. Skipped for a merged entry,
     * whose {@code joinNote} already explains it.
     */
    private void markScoreNotes(List<Staged> staged) {
        double announce = scoringProperties.getBands().getAnnounce();
        for (Staged s : staged) {
            if (s.bits < announce || s.joinNote != null) {
                continue;
            }
            s.scoreNote = s.rarityBits >= s.magnitudeBits
                    ? "Rarity alone carries it over the top contour — this occurrence itself is"
                            + " unremarkable."
                    : "An unusually large occurrence carries it over the top contour, even though"
                            + " it is not a rare one.";
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
            byFamily.merge(entry.family(), 1, Integer::sum);
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

    /** {@code "HW"} or {@code "LW"} for the water the entry's own alignment fact names, defaulting
     * to {@code "HW"} when no water aligned — a display choice only, never a claim about which
     * water is bigger. */
    private static String phaseOf(AlmanacEvent event) {
        String alignment = event.meta().get("alignment");
        return alignment != null && alignment.startsWith("LW") ? "LW" : "HW";
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
