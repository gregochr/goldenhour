package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
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
 * evaluation (only for coastal locations). This detector fires when any survivor in the
 * window is classified {@value #HIGH_RISK} — high only, not moderate. Reads through the
 * {@link SurvivorSignalReader} (the unified survivor surface), so it fires off the survivor
 * population, not the triaged rejects the legacy {@code forecast_evaluation} read sampled.
 * Makes no external API calls.
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

    private final SurvivorSignalReader survivorSignalReader;

    /**
     * Constructs a {@code StormSurgeHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (surge risk readings)
     */
    public StormSurgeHotTopicStrategy(SurvivorSignalReader survivorSignalReader) {
        this.survivorSignalReader = survivorSignalReader;
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
        List<SurvivorSignals> highRisk = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> HIGH_RISK.equals(s.readings().surgeRiskLevel()))
                .sorted(Comparator.comparing(SurvivorSignals::date))
                .toList();
        if (highRisk.isEmpty()) {
            return List.of();
        }

        LocalDate earliest = highRisk.get(0).date();
        Set<String> regions = new LinkedHashSet<>();
        for (SurvivorSignals s : highRisk) {
            String region = s.location() != null && s.location().getRegion() != null
                    ? s.location().getRegion().getName() : null;
            if (region != null) {
                regions.add(region);
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
