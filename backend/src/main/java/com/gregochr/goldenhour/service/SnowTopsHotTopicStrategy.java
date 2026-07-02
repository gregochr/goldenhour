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
     * <p>Emits a single topic dated to the earliest white-tops day, enumerating every white-tops
     * day in the window (an all-day condition, so no solar-event cutoff applies) and covering all
     * their regions. Returns empty when no row in the window has the freezing level far enough
     * below summit elevation.
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

        List<LocalDate> days = white.stream()
                .map(SurvivorSignals::date)
                .distinct()
                .sorted()
                .toList();
        Set<String> regions = new LinkedHashSet<>();
        for (SurvivorSignals s : white) {
            String region = s.location() != null && s.location().getRegion() != null
                    ? s.location().getRegion().getName() : null;
            if (region != null) {
                regions.add(region);
            }
        }

        return List.of(new HotTopic(
                "SNOW_TOPS",
                "Snow on the fells",
                "Tops white above the valleys " + DayLabels.joinRelative(days, fromDate),
                days.get(0),
                PRIORITY,
                null,
                new ArrayList<>(regions),
                TOPS_DESCRIPTION,
                null));
    }
}
