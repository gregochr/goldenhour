package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 * Detects Saharan dust hot topics by reading persisted forecast evaluations.
 *
 * <p>Saharan dust carried north scatters light at sunrise and sunset, producing unusually
 * vivid orange and red skies. This detector replicates the existing Saharan dust badge
 * proxy exactly (see {@code isDustEnhanced} in the map popup): elevated AOD
 * (&gt; {@value #AOD_THRESHOLD}) or surface dust (&gt; {@value #DUST_THRESHOLD} µg/m³),
 * with PM2.5 low enough (&lt; {@value #PM25_THRESHOLD} µg/m³, or absent) to rule out
 * smoke/haze. Reads the aerosol readings through the {@link SurvivorSignalReader} (the unified
 * survivor surface), so the proxy fires off the SURVIVOR population — the good forecasts Claude
 * scored — not the triaged rejects the legacy {@code forecast_evaluation} read sampled. Makes no
 * external API calls.
 */
@Component
public class DustHotTopicStrategy implements HotTopicStrategy {

    private static final String DUST_DESCRIPTION =
            "Saharan dust carried north by upper winds scatters light at sunrise and"
                    + " sunset, producing unusually vivid orange and red skies.";

    /** Topic priority — act-on-it, sorts above the calendar heads-up topics. */
    private static final int PRIORITY = 3;

    /** Aerosol optical depth above which dust is elevated (mirrors the dust badge). */
    static final BigDecimal AOD_THRESHOLD = new BigDecimal("0.3");

    /** Surface dust in µg/m³ above which dust is elevated (mirrors the dust badge). */
    static final BigDecimal DUST_THRESHOLD = new BigDecimal("50");

    /** PM2.5 in µg/m³ below which smoke/haze is ruled out (mirrors the dust badge). */
    static final BigDecimal PM25_THRESHOLD = new BigDecimal("35");

    private final SurvivorSignalReader survivorSignalReader;

    /**
     * Constructs a {@code DustHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (aerosol readings)
     */
    public DustHotTopicStrategy(SurvivorSignalReader survivorSignalReader) {
        this.survivorSignalReader = survivorSignalReader;
    }

    /**
     * Returns true when aerosol readings indicate dust-enhanced skies, replicating the
     * Saharan dust badge proxy exactly: elevated AOD or surface dust rules dust in, while
     * low (or absent) PM2.5 rules smoke/haze out. {@link #detect} applies this proxy to each
     * survivor composite's aerosol readings; isolating it here keeps it boundary-unit-testable
     * and consistent with the frontend badge.
     *
     * @param aod  aerosol optical depth, or null
     * @param dust surface dust in µg/m³, or null
     * @param pm25 PM2.5 in µg/m³, or null
     * @return true if the dust proxy fires
     */
    static boolean isDustEnhanced(BigDecimal aod, BigDecimal dust, BigDecimal pm25) {
        boolean aodHigh = aod != null && aod.compareTo(AOD_THRESHOLD) > 0;
        boolean dustHigh = dust != null && dust.compareTo(DUST_THRESHOLD) > 0;
        boolean pm25Low = pm25 == null || pm25.compareTo(PM25_THRESHOLD) < 0;
        return (aodHigh || dustHigh) && pm25Low;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic dated to the earliest dust-enhanced day in the window, with
     * the distinct regions affected. Returns empty when no row in the window meets the
     * dust proxy.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SurvivorSignals> dusty = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> isDustEnhanced(s.readings().aerosolOpticalDepth(),
                        s.readings().dust(), s.readings().pm25()))
                .sorted(Comparator.comparing(SurvivorSignals::date))
                .toList();
        if (dusty.isEmpty()) {
            return List.of();
        }

        LocalDate earliest = dusty.get(0).date();
        Set<String> regions = new LinkedHashSet<>();
        for (SurvivorSignals s : dusty) {
            String region = s.location() != null && s.location().getRegion() != null
                    ? s.location().getRegion().getName() : null;
            if (region != null) {
                regions.add(region);
            }
        }

        return List.of(new HotTopic(
                "DUST",
                "Saharan dust",
                "Elevated dust — vivid colour potential at sunset " + formatDayLabel(earliest, fromDate),
                earliest,
                PRIORITY,
                null,
                new ArrayList<>(regions),
                DUST_DESCRIPTION,
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
