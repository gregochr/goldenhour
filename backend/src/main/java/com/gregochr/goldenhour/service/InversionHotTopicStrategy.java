package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Detects cloud inversion hot topics by reading the survivor surface ({@code forecast_score}).
 *
 * <p>A temperature inversion traps cloud below elevated viewpoints, creating a "sea of
 * clouds" at dawn. The nightly pipeline's dual-write records the Claude-evaluated inversion
 * score (0–10) as an {@link ForecastType#INVERSION} component for every scored survivor at
 * an inversion-eligible (elevated / overlooks-water) location. This detector fires when any
 * such row in the window reaches the STRONG band — score &ge; {@value #STRONG_SCORE_INCLUSIVE},
 * mirroring {@code PromptBuilder.InversionPotential.fromScore} (9–10 = STRONG).
 *
 * <p>Reads through the {@link SurvivorSignalReader} (the unified survivor surface, backed by
 * {@code forecast_score}), NOT {@code forecast_evaluation}: nightly the latter holds only the
 * triaged-out rejects, so the legacy {@code inversion_potential} read was inert in production
 * (the evaluated survivors route here). Makes no external API calls.
 *
 * <p><b>Advance notice, every morning.</b> An inversion "sea of clouds" is a dawn phenomenon —
 * it is only useful as night-before planning, because once sunrise has passed you can no longer
 * get to the viewpoint in time. This detector drops any row whose sunrise has already passed
 * ({@link SolarEventFreshness}) and lists <em>every</em> remaining strong-inversion morning in
 * the window, so a multi-day setup is surfaced in full rather than collapsed to the earliest day.
 */
@Component
public class InversionHotTopicStrategy implements HotTopicStrategy {

    private static final String INVERSION_DESCRIPTION =
            "A temperature inversion traps cloud below elevated viewpoints, creating a"
                    + " 'sea of clouds' effect. Best seen from high ground overlooking"
                    + " water at dawn.";

    /** Topic priority — act-on-it, sorts above the calendar heads-up topics. */
    private static final int PRIORITY = 2;

    /**
     * Inclusive lower bound of the STRONG inversion band on the stored 0–10 score. Matches
     * {@code PromptBuilder.InversionPotential.fromScore} (score &ge; 9 = STRONG); MODERATE
     * (7–8) and below never fire the topic.
     */
    static final int STRONG_SCORE_INCLUSIVE = 9;

    private final SurvivorSignalReader survivorSignalReader;
    private final SolarEventFreshness freshness;

    /**
     * Constructs an {@code InversionHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (inversion scores)
     * @param freshness            shared filter dropping strong-inversion mornings already past
     */
    public InversionHotTopicStrategy(SurvivorSignalReader survivorSignalReader,
            SolarEventFreshness freshness) {
        this.survivorSignalReader = survivorSignalReader;
        this.freshness = freshness;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per non-expired strong-inversion morning in the window — each dated to
     * that morning and carrying only its regions — so a multi-day setup surfaces as an adjacent
     * run of day cards rather than a single collapsed pill. Returns empty when no strong-inversion
     * row remains after dropping mornings already past.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SurvivorSignals> strong = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> s.scores().inversion() != null
                        && s.scores().inversion() >= STRONG_SCORE_INCLUSIVE)
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .sorted(Comparator.comparing(SurvivorSignals::date))
                .toList();
        if (strong.isEmpty()) {
            return List.of();
        }

        return PerDateHotTopicBuilder.perDate(
                strong,
                "INVERSION",
                "Cloud inversion",
                "Strong inversion likely at elevated locations",
                PRIORITY,
                INVERSION_DESCRIPTION);
    }
}
