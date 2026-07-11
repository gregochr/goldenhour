package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds the enriched "science showing" fact line for the DUST (Saharan dust) pill.
 *
 * <p>For a day's dust-enhanced survivors the thickest plume is chosen as the representative, and its
 * facts come from data the strategy already holds plus exact solar geometry: the aerosol optical
 * depth (with an honest qualitative thickness band) and the afterglow window — sunset to civil dusk,
 * the interval when dust-scattered colour peaks after the sun has set. The DUST pill is anchored to
 * sunset (via {@code HotTopicEventEnricher}), so the afterglow is computed from sunset/civil-dusk
 * regardless of the representative row's own event type. The plume transport bearing is deliberately
 * omitted — no wind vector exists on the survivor surface, so it would be fabricated.
 */
@Component
public class DustFactsBuilder {

    private static final String NOTE = "look W, high — colour peaks after the sun's gone";
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** AOD at or above which the plume is dense. */
    private static final double DENSE_AOD = 0.6;
    /** AOD at or above which the plume is thick (below dense). */
    private static final double THICK_AOD = 0.4;

    private final SolarService solarService;

    /**
     * Constructs a {@code DustFactsBuilder}.
     *
     * @param solarService provides the sunset and civil-dusk times bounding the afterglow window
     */
    public DustFactsBuilder(SolarService solarService) {
        this.solarService = solarService;
    }

    /**
     * Attaches the dust fact line to the day's topic, choosing the thickest plume as representative.
     * Returns the topic unchanged when no fact can be built.
     *
     * @param topic   the day's base dust topic
     * @param dayRows the day's dust-enhanced survivor rows
     * @return the topic, enriched with facts when possible
     */
    public HotTopic attach(HotTopic topic, List<SurvivorSignals> dayRows) {
        SurvivorSignals rep = dayRows.stream()
                .max(Comparator.comparingDouble(DustFactsBuilder::aod))
                .orElse(null);
        if (rep == null) {
            return topic;
        }

        List<HotTopicFact> facts = new ArrayList<>();
        BigDecimal aodValue = rep.readings().aerosolOpticalDepth();
        if (aodValue != null) {
            double a = aodValue.doubleValue();
            facts.add(HotTopicFact.metric("AOD",
                    String.format(Locale.UK, "%.2f", a) + " · " + thickness(a)));
        }

        double lat = rep.location().getLat();
        double lon = rep.location().getLon();
        LocalDateTime sunset = solarService.sunsetUtc(lat, lon, rep.date());
        LocalDateTime dusk = solarService.civilDuskUtc(lat, lon, rep.date());
        if (sunset != null && dusk != null) {
            facts.add(new HotTopicFact("afterglow",
                    toLondon(sunset) + "–" + toLondon(dusk), null, false, false));
        }

        return facts.isEmpty() ? topic : topic.withScience(facts, NOTE);
    }

    private static double aod(SurvivorSignals s) {
        BigDecimal v = s.readings().aerosolOpticalDepth();
        return v == null ? 0.0 : v.doubleValue();
    }

    private static String thickness(double aodValue) {
        if (aodValue >= DENSE_AOD) {
            return "dense plume";
        }
        if (aodValue >= THICK_AOD) {
            return "thick plume";
        }
        return "light veil";
    }

    private static String toLondon(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC).atZone(LONDON).toLocalTime().format(HH_MM);
    }
}
