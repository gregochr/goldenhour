package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

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
     * <p>Emits one topic per high-surge-risk day in the window (a persistent coastal condition, so
     * no solar-event cutoff applies), each dated to that day and carrying only its coastal regions,
     * so a multi-day blow surfaces as an adjacent run of day cards. Returns empty when no row in the
     * window is classified high risk.
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

        return PerDateHotTopicBuilder.perDate(
                highRisk,
                "STORM_SURGE",
                "Storm surge",
                "High surge risk — dramatic coastal conditions",
                PRIORITY,
                STORM_SURGE_DESCRIPTION);
    }
}
