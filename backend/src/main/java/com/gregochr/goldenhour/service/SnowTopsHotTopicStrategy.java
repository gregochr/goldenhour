package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Detects snow-on-the-tops hot topics by reading persisted forecast evaluations.
 *
 * <p>When the freezing level drops below the fell tops, summits are capped with snow while the
 * valleys stay green — a classic Lake District and Pennine composition. This detector fires when a
 * location's {@code freezing_level_m} sits at least {@value #FREEZING_LEVEL_MARGIN_METRES} metres
 * below its summit {@code elevation_m} (the margin gives confidence the tops are actually white, not
 * merely at the theoretical freezing altitude). The freezing-level-versus-elevation comparison
 * self-selects high ground, so no minimum-elevation floor is applied. Reads through the
 * {@link SurvivorSignalReader} (the unified survivor surface), so it fires off the survivor
 * population, not the triaged rejects the legacy {@code forecast_evaluation} read sampled.
 * Makes no external API calls.
 */
@Component
public class SnowTopsHotTopicStrategy implements HotTopicStrategy {

    private static final String TOPS_DESCRIPTION =
            "When the freezing level drops below the peaks, fell tops are capped with snow while"
                    + " valleys stay green. A classic Lake District and Pennine composition.";

    /** Topic priority — act-on-it, sorts above the calendar heads-up topics. */
    private static final int PRIORITY = 3;

    /** Metres the freezing level must sit below summit elevation to call the tops white. */
    static final int FREEZING_LEVEL_MARGIN_METRES = 100;

    /** The italic "where to shoot" cue on the enriched fact line. */
    private static final String TOPS_NOTE = "shoot from low ground looking up, before cloud builds";

    private final SurvivorSignalReader survivorSignalReader;

    /**
     * Constructs a {@code SnowTopsHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (freezing-level readings)
     */
    public SnowTopsHotTopicStrategy(SurvivorSignalReader survivorSignalReader) {
        this.survivorSignalReader = survivorSignalReader;
    }

    /**
     * Returns true when the freezing level sits at least the margin below the summit elevation —
     * i.e. the tops are confidently white. Exists so the threshold can be unit-tested at its
     * boundary and kept consistent with the SQL query.
     *
     * @param freezingLevelMetres altitude of the 0 °C isotherm in metres above sea level, or null
     * @param elevationMetres     location summit elevation in metres, or null
     * @return true if the snow-on-the-tops condition fires
     */
    static boolean isTopsWhite(Double freezingLevelMetres, Integer elevationMetres) {
        return freezingLevelMetres != null && elevationMetres != null
                && freezingLevelMetres <= elevationMetres - FREEZING_LEVEL_MARGIN_METRES;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per white-tops day in the window (an all-day condition, so no solar-event
     * cutoff applies), each dated to that day and carrying only its regions, so a multi-day cold
     * snap surfaces as an adjacent run of day cards. Returns empty when no row in the window has the
     * freezing level far enough below summit elevation.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SurvivorSignals> white = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> isTopsWhite(s.readings().freezingLevelMetres(),
                        s.location() != null ? s.location().getElevationMetres() : null))
                .sorted(Comparator.comparing(SurvivorSignals::date))
                .toList();
        if (white.isEmpty()) {
            return List.of();
        }

        return PerDateHotTopicBuilder.perDate(
                white,
                "SNOW_TOPS",
                "Snow on the fells",
                "Tops white above the valleys",
                PRIORITY,
                TOPS_DESCRIPTION,
                this::attachFacts);
    }

    /**
     * Attaches the snow-on-the-fells fact line for a day's topic — the snow line (freezing-level
     * altitude) and, anomaly-first, how far it sits below the summit (the margin that confirms the
     * tops are white, not merely at the theoretical freezing altitude). The representative is the
     * row with the greatest margin — the location most confidently capped. Every row here passed
     * {@link #isTopsWhite}, so both figures are present; the guard is defensive.
     *
     * @param topic   the day's base topic
     * @param dayRows that day's white-tops rows
     * @return the topic enriched with the snow-line facts (unchanged if no row carries both figures)
     */
    private HotTopic attachFacts(HotTopic topic, List<SurvivorSignals> dayRows) {
        SurvivorSignals rep = dayRows.stream()
                .filter(s -> s.readings().freezingLevelMetres() != null
                        && s.location() != null && s.location().getElevationMetres() != null)
                .max(Comparator.comparingDouble(SnowTopsHotTopicStrategy::marginMetres))
                .orElse(null);
        if (rep == null) {
            return topic;
        }
        long snowLine = Math.round(rep.readings().freezingLevelMetres());
        long marginMetres = Math.round(marginMetres(rep));
        List<HotTopicFact> facts = List.of(
                HotTopicFact.metric("snow line", "~" + snowLine + " m"),
                new HotTopicFact(null, marginMetres + " m below the tops", null, false, true));
        return topic.withScience(facts, TOPS_NOTE);
    }

    private static double marginMetres(SurvivorSignals s) {
        return s.location().getElevationMetres() - s.readings().freezingLevelMetres();
    }
}
