package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SeaState;
import com.gregochr.goldenhour.model.SurgeCurve;
import com.gregochr.goldenhour.model.SurgeRunDay;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.service.evaluation.PromptUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds the enriched "science showing" fact line for the STORM_SURGE pill.
 *
 * <p>For a day's high-surge-risk survivors, the strongest surge is chosen as the representative, and
 * its facts are drawn entirely from data the surge model already computes (now persisted on the
 * survivor surface, V123) plus the shared {@code marine_wave} sea-state sample. Wave height is the
 * genuine drama signal, so it leads when present (anomaly-first, paired with its sea-state band);
 * the surge offset and onshore wind follow.
 */
@Component
public class StormSurgeFactsBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(StormSurgeFactsBuilder.class);

    private static final String NOTE = "shoot long from safe high ground";

    /**
     * Slack allowed when checking the curve against the persisted chip. Wide enough to absorb the
     * rounding and the hour the two sample independently, narrow enough that a curve from a
     * different build cannot slip through.
     */
    private static final double CURVE_CHIP_TOLERANCE_M = 0.15;
    private static final double MS_TO_MPH = 2.237;

    private final MarineWaveRepository marineWaveRepository;
    private final SurgeCurveService surgeCurveService;
    private final SurgeRunDayBuilder surgeRunDayBuilder;

    /**
     * Constructs a {@code StormSurgeFactsBuilder}.
     *
     * @param marineWaveRepository the shared sea-state carrier (V123)
     * @param surgeCurveService    the in-memory hourly surge curve, populated on the build path
     * @param surgeRunDayBuilder   formats one day's curve into the row the pill renders
     */
    public StormSurgeFactsBuilder(MarineWaveRepository marineWaveRepository,
            SurgeCurveService surgeCurveService,
            SurgeRunDayBuilder surgeRunDayBuilder) {
        this.marineWaveRepository = marineWaveRepository;
        this.surgeCurveService = surgeCurveService;
        this.surgeRunDayBuilder = surgeRunDayBuilder;
    }

    /**
     * Attaches the storm-surge fact line to the day's topic, choosing the strongest surge as the
     * representative. Returns the topic unchanged when no fact can be built.
     *
     * @param topic   the day's base storm-surge topic
     * @param dayRows the day's high-surge-risk survivor rows
     * @return the topic, enriched with facts when possible
     */
    public HotTopic attach(HotTopic topic, List<SurvivorSignals> dayRows) {
        SurvivorSignals rep = dayRows.stream()
                .max(Comparator.comparingDouble(StormSurgeFactsBuilder::surgeMetres))
                .orElse(null);
        if (rep == null) {
            return topic;
        }

        Double surge = rep.readings().surgeTotalMetres();

        // Decided BEFORE the chips, because whether the curve survives changes which chips belong.
        SurgeCurve curve = surgeCurveService.getCached();
        SurgeRunDay candidate = surgeRunDayBuilder.build(rep.location(), rep.date(), curve);
        SurgeRunDay surgeRun = candidate != null && agreesWithChip(candidate, surge, rep, curve)
                ? candidate : null;

        List<HotTopicFact> facts = new ArrayList<>();
        Double hs = marineWaveRepository
                .findByLocation_IdAndEvaluationDateAndEventType(
                        rep.location().getId(), rep.date(), rep.eventType())
                .map(MarineWaveEntity::getSignificantWaveHeightMetres)
                .orElse(null);
        boolean haveWaves = hs != null;
        if (haveWaves) {
            facts.add(HotTopicFact.metric("waves", metres(hs) + " · " + SeaState.fromHs(hs).label()));
        }

        // The surge chip stands down when a curve is attached. Both are the same quantity, and the
        // pill was printing two of them: the curve's day peak ("+0.72 m at 14:00") beside the
        // next-high-tide sample ("0.6 m above normal"), at different precisions, in words that gave
        // a reader no way to tell they were different instants rather than a contradiction. The
        // tide run states the same rule for the same reason — its chart REPLACES the chips. On the
        // suppressed-chart path the chip is the only magnitude the pill has, so it stays.
        boolean haveCurve = surgeRun != null;
        if (surge != null && !haveCurve) {
            // Plain-language framing: the surge IS the water raised above the predicted tide.
            facts.add(new HotTopicFact("surge", metres(surge) + " above normal", null, !haveWaves, false));
        }

        Double windMs = rep.readings().surgeWindSpeedMs();
        if (windMs != null) {
            HotTopicFact windFact = HotTopicFact.metric(
                    "wind", wind(windMs, rep.readings().surgeWindDirectionDegrees()));
            // Emphasis followed the surge chip's absence before; with the chip gone on the curve
            // path, wind carries it when there are no waves, so the line still has a lead fact
            // rather than nothing but an optional one.
            facts.add(haveCurve && !haveWaves ? windFact : windFact.asOptional());
        }

        HotTopic enriched = facts.isEmpty() ? topic : topic.withScience(facts, NOTE);

        // The curve is attached AFTER withScience, never before: every wither rebuilds the record
        // positionally, so attaching first and enriching second would silently drop it.
        return surgeRun == null ? enriched : enriched.withSurgeRun(surgeRun);
    }

    /**
     * True when the curve and the persisted "N m above normal" chip can be describing the same
     * weather, so the pill cannot show a chart contradicting the number printed beside it.
     *
     * <p><b>An ENVELOPE test, not an equality test — deliberately.</b> The obvious check is
     * "curve value at the high-water hour equals the chip", but {@code Readings} records no sample
     * timestamp: the chip was taken at the next high tide after its solar event, which need not be
     * the day's <em>highest</em> high water and can even fall on the following day. Comparing at a
     * guessed hour would suppress a perfectly good chart whenever those two instants differed,
     * which is most days.
     *
     * <p>What can be asserted without guessing: whatever hour the chip sampled, if the curve came
     * from the same weather then the chip's value lies somewhere inside the day's own range. A
     * value outside it means the carrier is stale — a curve from an earlier build, or another
     * location — which is exactly the disagreement worth suppressing.
     *
     * @param run    the curve about to be attached
     * @param chip   the persisted surge scalar shown as a fact chip, or null
     * @param rep    the representative survivor row, for logging
     * @param curve  the carrier, so the next day's series can extend the envelope
     * @return true when the two are compatible, or when there is no chip to contradict
     */
    private static boolean agreesWithChip(SurgeRunDay run, Double chip, SurvivorSignals rep,
            SurgeCurve curve) {
        if (chip == null || run.surgeMetres() == null) {
            return true;
        }
        List<Double> values = new ArrayList<>(run.surgeMetres());
        // The chip is sampled at the next high tide AFTER its solar event, which can fall on the
        // FOLLOWING day — at most one ~12h25 high-water interval past it, so tomorrow's series is
        // the whole of the remaining exposure. Without this, a surge building overnight produces a
        // chip legitimately larger than today's maximum, and the guard deletes a correct chart on
        // exactly the biggest nights. Worse, attach() picks the representative by MAX surge, which
        // actively selects for the next-day-sampled row in that case.
        List<Double> tomorrow = curve == null ? null
                : curve.forLocation(rep.location().getId(), rep.date().plusDays(1));
        if (tomorrow != null) {
            values.addAll(tomorrow);
        }
        List<Double> present = values.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return true;
        }
        double low = present.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double high = present.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        boolean agrees = chip >= low - CURVE_CHIP_TOLERANCE_M && chip <= high + CURVE_CHIP_TOLERANCE_M;
        if (!agrees) {
            // States the observation, not a diagnosis. A stale carrier is only one cause — a
            // representative row from another location, or a sample instant beyond the window
            // above, produce the same reading, and naming one of them here would misdirect
            // whoever reads the log.
            LOG.warn("Surge chart suppressed for {} on {}: chip {} m lies outside the {} m to {} m "
                            + "envelope of the curve for that day and the next",
                    rep.location().getName(), rep.date(), chip, low, high);
        }
        return agrees;
    }

    private static double surgeMetres(SurvivorSignals s) {
        Double v = s.readings().surgeTotalMetres();
        return v == null ? 0.0 : v;
    }

    private static String wind(double windMs, Double windDirectionDegrees) {
        String mph = Math.round(windMs * MS_TO_MPH) + " mph";
        if (windDirectionDegrees == null) {
            return mph;
        }
        return PromptUtils.toCardinal((int) Math.round(windDirectionDegrees)) + " " + mph;
    }

    private static String metres(double value) {
        return String.format(Locale.UK, "%.1f m", value);
    }
}
