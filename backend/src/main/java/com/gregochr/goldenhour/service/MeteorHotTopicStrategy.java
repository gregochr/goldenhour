package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Detects meteor-shower hot topics from a fixed calendar of major shower peaks.
 *
 * <p>During a shower, shooting stars are far more frequent than usual — best captured
 * from dark-sky locations on a long exposure. Fires when a shower peak falls within the
 * detection window AND the Moon is less than {@value #MAX_ILLUMINATION} illuminated at
 * peak (a washed-out peak is not worth a pill). Dark-sky suitability is mentioned in the
 * copy but is not a hard gate — a bright shower is still worth flagging near a town.
 * Makes no external API calls.
 */
@Component
public class MeteorHotTopicStrategy implements HotTopicStrategy {

    private static final String METEOR_DESCRIPTION =
            "During a meteor shower, shooting stars are more frequent than usual."
                    + " Best photographed from dark-sky locations with a wide-angle"
                    + " lens on a long exposure.";

    /** Topic priority — calendar heads-up, sorts below the act-on-it topics. */
    private static final int PRIORITY = 7;

    /** Maximum lunar illumination (fraction) at peak for the topic to fire. */
    private static final double MAX_ILLUMINATION = 0.5;

    /** Lunar illumination percent below which the sky is genuinely dark for meteors. */
    private static final int MOON_DARK_PCT = 25;

    /**
     * A meteor shower and the stable astronomical constants that describe its peak.
     *
     * @param name           short shower name shown in the pill detail
     * @param peak           the approximate calendar day of maximum activity
     * @param zhrPeak        zenithal hourly rate at peak under ideal dark skies (IMO catalogue)
     * @param radiantCompass compass point the radiant sits toward (drives the look direction)
     * @param bestHours      the local hours the radiant is highest and the shower is best watched
     */
    record Shower(String name, MonthDay peak, int zhrPeak, String radiantCompass, String bestHours) { }

    /** Major showers worth flagging, with standard peak dates and catalogue constants. */
    static final List<Shower> SHOWERS = List.of(
            new Shower("Quadrantids", MonthDay.of(1, 3), 120, "NE", "04:00–dawn"),
            new Shower("Lyrids", MonthDay.of(4, 22), 18, "NE", "02:00–04:00"),
            new Shower("Perseids", MonthDay.of(8, 12), 100, "NE", "01:00–04:00"),
            new Shower("Orionids", MonthDay.of(10, 21), 20, "SE", "02:00–05:00"),
            new Shower("Geminids", MonthDay.of(12, 14), 150, "E", "22:00–02:00"));

    private final LunarPhaseService lunarPhaseService;
    private final LocationRepository locationRepository;

    /**
     * Constructs a {@code MeteorHotTopicStrategy}.
     *
     * @param lunarPhaseService  lunar illumination service for the dark-moon gate
     * @param locationRepository repository for dark-sky region lookups
     */
    public MeteorHotTopicStrategy(LunarPhaseService lunarPhaseService,
            LocationRepository locationRepository) {
        this.lunarPhaseService = lunarPhaseService;
        this.locationRepository = locationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic for the earliest qualifying shower peak in the window
     * (peak in range and Moon below {@value #MAX_ILLUMINATION} illuminated). Returns
     * empty when no qualifying peak falls in the window.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        // Candidate years cover a window that may straddle a year boundary (e.g. Dec→Jan).
        Set<Integer> years = new LinkedHashSet<>();
        years.add(fromDate.getYear());
        years.add(toDate.getYear());

        Shower best = null;
        LocalDate bestPeak = null;
        for (Shower shower : SHOWERS) {
            for (int year : years) {
                LocalDate peak = shower.peak().atYear(year);
                if (peak.isBefore(fromDate) || peak.isAfter(toDate)) {
                    continue;
                }
                if (lunarPhaseService.getIlluminationFraction(peak) >= MAX_ILLUMINATION) {
                    continue;
                }
                if (bestPeak == null || peak.isBefore(bestPeak)) {
                    best = shower;
                    bestPeak = peak;
                }
            }
        }

        if (best == null) {
            return List.of();
        }
        return List.of(buildTopic(best, bestPeak));
    }

    private HotTopic buildTopic(Shower shower, LocalDate peak) {
        List<String> darkSkyRegions =
                locationRepository.findByBortleClassIsNotNullAndEnabledTrue().stream()
                        .map(LocationEntity::getRegion)
                        .filter(Objects::nonNull)
                        .map(RegionEntity::getName)
                        .distinct()
                        .toList();

        int moonPct = (int) Math.round(lunarPhaseService.getIlluminationFraction(peak) * 100);
        boolean darkEnough = moonPct < MOON_DARK_PCT;
        // The headline claim must track the same illumination band as the moon chip: the fire gate
        // admits up to MAX_ILLUMINATION (50%), so a half-lit peak still qualifies and must not be
        // sold as a "dark moon" beside a chip that reads "some moonlight".
        String moonQuality = darkEnough ? "dark enough" : "some moonlight";
        String viewingCue = darkEnough ? "dark moon, good viewing" : "some moonlight, still worth a look";
        List<HotTopicFact> facts = List.of(
                HotTopicFact.metric("ZHR", "~" + shower.zhrPeak() + " at peak"),
                HotTopicFact.directional("radiant", "best " + shower.bestHours(),
                        shower.radiantCompass(), false),
                new HotTopicFact("moon", moonPct + "% · " + moonQuality, null, false, true));

        return new HotTopic(
                "METEOR",
                "Meteor shower",
                shower.name() + " peak — " + viewingCue,
                peak,
                PRIORITY,
                null,
                darkSkyRegions,
                METEOR_DESCRIPTION,
                null)
                .withScience(facts, null);
    }
}
