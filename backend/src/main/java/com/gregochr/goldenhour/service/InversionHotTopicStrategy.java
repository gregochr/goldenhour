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
 * Detects cloud inversion hot topics by reading persisted forecast evaluations.
 *
 * <p>A temperature inversion traps cloud below elevated viewpoints, creating a "sea of
 * clouds" at dawn. The forecast pipeline already computes and persists
 * {@code inversion_potential} per evaluation (only for elevated / overlooks-water
 * locations). This detector fires when any row in the window is classified
 * {@value #STRONG_POTENTIAL} — strong only, not moderate. Reads {@code forecast_evaluation}
 * directly (the king/spring tide template); makes no external API calls.
 */
@Component
public class InversionHotTopicStrategy implements HotTopicStrategy {

    private static final String INVERSION_DESCRIPTION =
            "A temperature inversion traps cloud below elevated viewpoints, creating a"
                    + " 'sea of clouds' effect. Best seen from high ground overlooking"
                    + " water at dawn.";

    /** Topic priority — act-on-it, sorts above the calendar heads-up topics. */
    private static final int PRIORITY = 2;

    /** The only inversion classification that fires the topic. */
    private static final String STRONG_POTENTIAL = "STRONG";

    private final ForecastEvaluationRepository forecastEvaluationRepository;

    /**
     * Constructs an {@code InversionHotTopicStrategy}.
     *
     * @param forecastEvaluationRepository repository for persisted inversion classifications
     */
    public InversionHotTopicStrategy(ForecastEvaluationRepository forecastEvaluationRepository) {
        this.forecastEvaluationRepository = forecastEvaluationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic dated to the earliest strong-inversion day in the window,
     * with the distinct regions where strong inversion is forecast. Returns empty when no
     * row in the window is classified strong.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<Object[]> rows = forecastEvaluationRepository.findInversionDaysByPotential(
                fromDate, toDate, STRONG_POTENTIAL);
        if (rows.isEmpty()) {
            return List.of();
        }

        LocalDate earliest = (LocalDate) rows.get(0)[0];
        Set<String> regions = new LinkedHashSet<>();
        for (Object[] row : rows) {
            if (row[1] != null) {
                regions.add((String) row[1]);
            }
        }

        return List.of(new HotTopic(
                "INVERSION",
                "Cloud inversion",
                "Strong inversion likely at elevated locations " + formatDayLabel(earliest, fromDate),
                earliest,
                PRIORITY,
                null,
                new ArrayList<>(regions),
                INVERSION_DESCRIPTION,
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
