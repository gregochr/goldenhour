package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 *
 * <p><b>Advance notice.</b> An inversion "sea of clouds" is a dawn phenomenon — it is only
 * useful as night-before planning, because once sunrise has passed you can no longer get to the
 * viewpoint in time. This detector therefore drops a strong-inversion row dated to <em>today</em>
 * once the current time is past that location's sunrise, so the topic rolls forward to the next
 * actionable morning (or disappears) rather than pointing at a dawn the operator has already
 * missed. A pre-dawn briefing (before today's sunrise) still surfaces today's inversion.
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
    private final SolarService solarService;
    private final Clock clock;

    /**
     * Constructs an {@code InversionHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (inversion scores)
     * @param solarService         solar calculator for per-location sunrise times
     * @param clock                UTC clock used to decide whether today's dawn has passed
     */
    public InversionHotTopicStrategy(SurvivorSignalReader survivorSignalReader,
            SolarService solarService, Clock clock) {
        this.survivorSignalReader = survivorSignalReader;
        this.solarService = solarService;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic dated to the earliest strong-inversion day that is still
     * <em>actionable</em> — a future morning, or today only while the current time is before
     * that location's sunrise. Lists the distinct regions where strong inversion is forecast on
     * actionable days. Returns empty when no such row exists (including when the only strong
     * rows are for a dawn that has already passed today).
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SurvivorSignals> strong = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> s.scores().inversion() != null
                        && s.scores().inversion() >= STRONG_SCORE_INCLUSIVE)
                .filter(s -> isActionable(s, fromDate, now))
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

    /**
     * Whether a strong-inversion row is still worth acting on. A future morning always is; a
     * row dated today is only actionable while the current time is before that location's
     * sunrise (an inversion is a dawn event — once sunrise passes, the shoot cannot be reached).
     * A row dated before today (not expected from the reader) is treated as stale.
     */
    private boolean isActionable(SurvivorSignals row, LocalDate today, LocalDateTime now) {
        LocalDate date = row.date();
        if (date.isAfter(today)) {
            return true;
        }
        if (!date.isEqual(today)) {
            return false;
        }
        LocationEntity location = row.location();
        if (location == null) {
            return true;
        }
        LocalDateTime sunrise = solarService.sunriseUtc(
                location.getLat(), location.getLon(), today);
        return now.isBefore(sunrise);
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
