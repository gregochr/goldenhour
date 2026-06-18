package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * {@code forecast_evaluation} directly (the king/spring tide template); makes no external API calls.
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

    private final ForecastEvaluationRepository forecastEvaluationRepository;

    /**
     * Constructs a {@code SnowFreshHotTopicStrategy}.
     *
     * @param forecastEvaluationRepository repository for persisted snow depth and humidity readings
     */
    public SnowFreshHotTopicStrategy(ForecastEvaluationRepository forecastEvaluationRepository) {
        this.forecastEvaluationRepository = forecastEvaluationRepository;
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
        List<Object[]> rows = forecastEvaluationRepository.findSnowFreshDays(
                fromDate, toDate, SNOW_DEPTH_THRESHOLD_METRES);
        if (rows.isEmpty()) {
            return List.of();
        }

        LocalDate earliest = (LocalDate) rows.get(0)[0];
        Set<String> regions = new LinkedHashSet<>();
        boolean misty = false;
        for (Object[] row : rows) {
            if (row[1] != null) {
                regions.add((String) row[1]);
            }
            if (isMisty((Integer) row[2])) {
                misty = true;
            }
        }

        String dayLabel = formatDayLabel(earliest, fromDate);
        if (misty) {
            return List.of(new HotTopic(
                    "SNOW_MIST",
                    "Fresh snow with mist",
                    "Snow lying with mist — exceptional conditions " + dayLabel,
                    earliest,
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
                earliest,
                PRIORITY_FRESH,
                null,
                new ArrayList<>(regions),
                FRESH_DESCRIPTION,
                null));
    }

    private String formatDayLabel(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "today";
        }
        if (date.equals(today.plusDays(1))) {
            return "tomorrow";
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow.getDisplayName(TextStyle.FULL, Locale.UK);
    }
}
