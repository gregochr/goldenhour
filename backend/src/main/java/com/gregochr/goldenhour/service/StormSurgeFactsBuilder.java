package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SeaState;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.service.evaluation.PromptUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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

    private static final String NOTE = "shoot long from safe high ground";
    private static final double MS_TO_MPH = 2.237;

    private final MarineWaveRepository marineWaveRepository;

    /**
     * Constructs a {@code StormSurgeFactsBuilder}.
     *
     * @param marineWaveRepository the shared sea-state carrier (V123)
     */
    public StormSurgeFactsBuilder(MarineWaveRepository marineWaveRepository) {
        this.marineWaveRepository = marineWaveRepository;
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

        return facts.isEmpty() ? topic : topic.withScience(facts, NOTE);
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
