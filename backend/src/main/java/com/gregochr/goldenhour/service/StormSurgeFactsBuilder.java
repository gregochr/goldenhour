package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SeaState;
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

        Double surge = rep.readings().surgeTotalMetres();
        if (surge != null) {
            // Plain-language framing: the surge IS the water raised above the predicted tide.
            facts.add(new HotTopicFact("surge", metres(surge) + " above normal", null, !haveWaves, false));
        }

        Double windMs = rep.readings().surgeWindSpeedMs();
        if (windMs != null) {
            facts.add(HotTopicFact.metric("wind", wind(windMs, rep.readings().surgeWindDirectionDegrees()))
                    .asOptional());
        }

        HotTopic enriched = facts.isEmpty() ? topic : topic.withScience(facts, NOTE);

        // The curve is attached AFTER withScience, never before: every wither rebuilds the record
        // positionally, so attaching first and enriching second would silently drop it.
        SurgeRunDay surgeRun = surgeRunDayBuilder.build(
                rep.location(), rep.date(), surgeCurveService.getCached());
        if (surgeRun == null || !agreesWithChip(surgeRun, surge, rep)) {
            return enriched;
        }
        return enriched.withSurgeRun(surgeRun);
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
     * @return true when the two are compatible, or when there is no chip to contradict
     */
    private static boolean agreesWithChip(SurgeRunDay run, Double chip, SurvivorSignals rep) {
        if (chip == null || run.surgeMetres() == null) {
            return true;
        }
        List<Double> values = run.surgeMetres().stream().filter(Objects::nonNull).toList();
        if (values.isEmpty()) {
            return true;
        }
        double low = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double high = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        boolean agrees = chip >= low - CURVE_CHIP_TOLERANCE_M && chip <= high + CURVE_CHIP_TOLERANCE_M;
        if (!agrees) {
            LOG.warn("Surge chart suppressed for {} on {}: chip {} m lies outside the curve's "
                            + "{} m to {} m envelope — the carrier is probably stale",
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
