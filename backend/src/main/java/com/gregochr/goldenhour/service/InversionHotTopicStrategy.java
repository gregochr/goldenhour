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

    /**
     * Constructs an {@code InversionHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (inversion scores)
     */
    public InversionHotTopicStrategy(SurvivorSignalReader survivorSignalReader) {
        this.survivorSignalReader = survivorSignalReader;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic dated to the earliest strong-inversion day in the window,
     * with the distinct regions where strong inversion is forecast. Returns empty when no
     * survivor row in the window reaches the STRONG band.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SurvivorSignals> strong = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> s.scores().inversion() != null
                        && s.scores().inversion() >= STRONG_SCORE_INCLUSIVE)
                .sorted(Comparator.comparing(SurvivorSignals::date))
                .toList();
        if (strong.isEmpty()) {
            return List.of();
        }

        LocalDate earliest = strong.get(0).date();
        Set<String> regions = new LinkedHashSet<>();
        for (SurvivorSignals row : strong) {
            String region = row.location() != null && row.location().getRegion() != null
                    ? row.location().getRegion().getName() : null;
            if (region != null) {
                regions.add(region);
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
