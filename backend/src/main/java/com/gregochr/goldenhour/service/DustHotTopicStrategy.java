package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

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
    private final SolarEventFreshness freshness;
    private final DustFactsBuilder dustFactsBuilder;

    /**
     * Constructs a {@code DustHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (aerosol readings)
     * @param freshness            shared filter dropping sunrise/sunset events already past
     * @param dustFactsBuilder     builds the enriched AOD + afterglow fact line
     */
    public DustHotTopicStrategy(SurvivorSignalReader survivorSignalReader,
            SolarEventFreshness freshness, DustFactsBuilder dustFactsBuilder) {
        this.survivorSignalReader = survivorSignalReader;
        this.freshness = freshness;
        this.dustFactsBuilder = dustFactsBuilder;
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
     * <p>Emits one topic per non-expired dust-enhanced day in the window, each dated to that day
     * and carrying only its regions, so a multi-day dust episode surfaces as an adjacent run of day
     * cards. Rows whose sunrise/sunset has already passed are dropped ({@link SolarEventFreshness}).
     * Returns empty when no remaining row meets the dust proxy.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SurvivorSignals> dusty = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> isDustEnhanced(s.readings().aerosolOpticalDepth(),
                        s.readings().dust(), s.readings().pm25()))
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .sorted(Comparator.comparing(SurvivorSignals::date))
                .toList();
        if (dusty.isEmpty()) {
            return List.of();
        }

        return PerDateHotTopicBuilder.perDate(
                dusty,
                "DUST",
                "Saharan dust",
                "Elevated dust — vivid colour potential at sunrise and sunset",
                PRIORITY,
                DUST_DESCRIPTION,
                dustFactsBuilder::attach);
    }
}
