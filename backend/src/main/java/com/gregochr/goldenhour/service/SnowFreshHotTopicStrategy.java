package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.util.DayLabels;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects fresh-snow hot topics by reading persisted forecast evaluations.
 *
 * <p>Fresh snow lying on the ground transforms familiar landscapes — the photographic call is
 * "is there snow underfoot at the shooting hour," not "did it fall last night," so this detector
 * fires purely on lying depth: {@code snow_depth_m} at or above {@value #SNOW_DEPTH_THRESHOLD_METRES}
 * metres ({@code 2 cm}) at the hour nearest the solar event. When the existing briefing mist signal
 * co-occurs (humidity above {@link BriefingVerdictEvaluator#HUMIDITY_MARGINAL}% on a snowy row), the
 * topic is enriched into the SNOW_MIST variant rather than emitted as a separate topic. Reads
 * through the {@link SurvivorSignalReader} (the unified survivor surface), so it fires off the
 * survivor population, not the triaged rejects the legacy {@code forecast_evaluation} read
 * sampled. Makes no external API calls.
 */
@Component
public class SnowFreshHotTopicStrategy implements HotTopicStrategy {

    private static final String FRESH_DESCRIPTION =
            "Overnight snowfall transforms familiar landscapes. Low temperatures mean the snow"
                    + " holds — fresh white ground is a rare opportunity.";

    private static final String MIST_DESCRIPTION =
            "Fresh snow combined with mist creates ethereal conditions. Trees and landmarks"
                    + " emerge from fog against a white landscape — among the most dramatic"
                    + " photography conditions possible.";

    /** Topic priority for plain fresh snow — act-on-it, sorts above the calendar heads-ups. */
    private static final int PRIORITY_FRESH = 2;

    /** Topic priority when mist co-occurs — the standout condition, ranks above plain fresh snow. */
    private static final int PRIORITY_MIST = 1;

    /** Snow depth in metres at or above which snow counts as lying (2 cm). */
    static final double SNOW_DEPTH_THRESHOLD_METRES = 0.02;

    private final SurvivorSignalReader survivorSignalReader;
    private final SolarEventFreshness freshness;

    /**
     * Constructs a {@code SnowFreshHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (snow depth + humidity readings)
     * @param freshness            shared filter dropping sunrise/sunset events already past
     */
    public SnowFreshHotTopicStrategy(SurvivorSignalReader survivorSignalReader,
            SolarEventFreshness freshness) {
        this.survivorSignalReader = survivorSignalReader;
        this.freshness = freshness;
    }

    /**
     * Returns true when snow is lying — depth present and at or above the 2 cm threshold. Exists so
     * the threshold can be unit-tested at its boundary and kept consistent with the SQL query.
     *
     * @param snowDepthMetres lying snow depth in metres, or null
     * @return true if the fresh-snow condition fires
     */
    static boolean isFreshSnow(Double snowDepthMetres) {
        return snowDepthMetres != null && snowDepthMetres >= SNOW_DEPTH_THRESHOLD_METRES;
    }

    /**
     * Returns true when humidity indicates co-occurring mist, reusing the briefing's mist
     * threshold exactly ({@link BriefingVerdictEvaluator#HUMIDITY_MARGINAL}).
     *
     * @param humidityPercent relative humidity percentage, or null
     * @return true if the mist signal is present
     */
    static boolean isMisty(Integer humidityPercent) {
        return humidityPercent != null && humidityPercent > BriefingVerdictEvaluator.HUMIDITY_MARGINAL;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic dated to the earliest snow-lying day in the window, with the distinct
     * regions affected. If any snowy row also shows mist, the topic is upgraded to the SNOW_MIST
     * variant (higher priority, mist-aware label and description). Returns empty when no row in the
     * window has snow lying.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SurvivorSignals> snowy = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> isFreshSnow(s.readings().snowDepthMetres()))
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .sorted(Comparator.comparing(SurvivorSignals::date))
                .toList();
        if (snowy.isEmpty()) {
            return List.of();
        }

        List<LocalDate> days = snowy.stream()
                .map(SurvivorSignals::date)
                .distinct()
                .sorted()
                .toList();
        Set<String> regions = new LinkedHashSet<>();
        boolean misty = false;
        for (SurvivorSignals s : snowy) {
            String region = s.location() != null && s.location().getRegion() != null
                    ? s.location().getRegion().getName() : null;
            if (region != null) {
                regions.add(region);
            }
            if (isMisty(s.readings().humidity())) {
                misty = true;
            }
        }

        String dayLabel = DayLabels.joinRelative(days, fromDate);
        if (misty) {
            return List.of(new HotTopic(
                    "SNOW_MIST",
                    "Fresh snow with mist",
                    "Snow lying with mist — exceptional conditions " + dayLabel,
                    days.get(0),
                    PRIORITY_MIST,
                    null,
                    new ArrayList<>(regions),
                    MIST_DESCRIPTION,
                    null));
        }
        return List.of(new HotTopic(
                "SNOW_FRESH",
                "Fresh snow",
                "Snow lying — white landscapes " + dayLabel,
                days.get(0),
                PRIORITY_FRESH,
                null,
                new ArrayList<>(regions),
                FRESH_DESCRIPTION,
                null));
    }
}
