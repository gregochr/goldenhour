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
 * Detects storm surge hot topics by reading persisted forecast evaluations.
 *
 * <p>Low pressure and onshore winds push water levels above predicted heights; combined
 * with high tide this produces crashing waves and dramatic coastal conditions — even at
 * midday. The forecast pipeline already computes and persists {@code surge_risk_level} per
 * evaluation (only for coastal locations). This detector fires when any row in the window
 * is classified {@value #HIGH_RISK} — high only, not moderate. Reads
 * {@code forecast_evaluation} directly (the king/spring tide template); makes no external
 * API calls.
 */
@Component
public class StormSurgeHotTopicStrategy implements HotTopicStrategy {

    private static final String STORM_SURGE_DESCRIPTION =
            "Low pressure and onshore winds push water levels above predicted heights."
                    + " Combined with high tide, this creates crashing waves and"
                    + " dramatic coastal conditions — even midday.";

    /** Topic priority — most act-on-it, sorts at the top alongside aurora and king tide. */
    private static final int PRIORITY = 1;

    /** The only surge risk level that fires the topic. */
    private static final String HIGH_RISK = "HIGH";

    private final ForecastEvaluationRepository forecastEvaluationRepository;

    /**
     * Constructs a {@code StormSurgeHotTopicStrategy}.
     *
     * @param forecastEvaluationRepository repository for persisted surge classifications
     */
    public StormSurgeHotTopicStrategy(ForecastEvaluationRepository forecastEvaluationRepository) {
        this.forecastEvaluationRepository = forecastEvaluationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic dated to the earliest high-surge-risk day in the window, with
     * the distinct coastal regions affected. Returns empty when no row in the window is
     * classified high risk.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<Object[]> rows = forecastEvaluationRepository.findSurgeDaysByRiskLevel(
                fromDate, toDate, HIGH_RISK);
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
                "STORM_SURGE",
                "Storm surge",
                "High surge risk — dramatic coastal conditions " + formatDayLabel(earliest, fromDate),
                earliest,
                PRIORITY,
                null,
                new ArrayList<>(regions),
                STORM_SURGE_DESCRIPTION,
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
