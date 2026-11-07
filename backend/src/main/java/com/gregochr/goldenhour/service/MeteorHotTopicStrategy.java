package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects meteor-shower hot topics from the shower peaks in {@link MeteorPeakCalendar} (IMO's per-year
 * nights, else the table below).
 *
 * <p>The table holds twelve showers, but only the {@linkplain Shower#major() major} ones reach the
 * hot-topic path. The rule: a shower fires a Plan-tab pill only when a UK observer can expect about
 * 10 or more meteors an hour at its peak (ZHR scaled by the radiant's altitude in the best hours).
 * The six minor showers (Eta and S. Delta Aquariids, Draconids, both Taurids, Ursids) fall short of
 * that, so they appear in Coming up through {@code MeteorAlmanacSource} only.
 *
 * <p>During a shower, shooting stars are far more frequent than usual — best captured
 * from dark-sky locations on a long exposure. Fires when a shower peak falls within the
 * detection window AND {@link MeteorMoonGate} does not call it washed out: a bright moon
 * (half lit or more) up for more than half of the shower's best hours. A moon that has set
 * before the best hours, or sets early in them, does not drop the shower (the Leonids of
 * 17 Nov 2026 are half lit at noon but down from about 23:35, and are best from 03:00).
 * Dark-sky suitability is mentioned in the copy but is not a hard gate — a bright shower is
 * still worth flagging near a town. Makes no external API calls.
 */
@Component
public class MeteorHotTopicStrategy implements HotTopicStrategy {

    private static final String METEOR_DESCRIPTION =
            "During a meteor shower, shooting stars are more frequent than usual."
                    + " Best photographed from dark-sky locations with a wide-angle"
                    + " lens on a long exposure.";

    /** Topic priority — calendar heads-up, sorts below the act-on-it topics. */
    private static final int PRIORITY = 7;

    /**
     * A meteor shower and the stable astronomical constants that describe its peak.
     *
     * @param code           the IAU code (QUA, LYR, ...), the key of the {@code meteor_shower_peak} rows
     * @param name           short shower name shown in the pill detail
     * @param peak           the approximate calendar day of maximum activity; the fallback when
     *                       {@link MeteorPeakCalendar} has no row for the year
     * @param zhrPeak        zenithal hourly rate at peak under ideal dark skies (IMO catalogue); the
     *                       fallback when {@link MeteorPeakCalendar} has no row for the year
     * @param radiantCompass compass point the radiant sits toward (drives the look direction)
     * @param bestHours      the local hours the radiant is highest and the shower is best watched
     * @param major          true when a UK observer can expect about 10 or more an hour at peak, so
     *                       the shower may reach the hot-topic path; false lists in Coming up only
     * @param ukRate         the realistic best hourly rate from the UK when the radiant stays too low
     *                       for the ZHR to be a fair guide; null otherwise
     */
    record Shower(String code, String name, MonthDay peak, int zhrPeak, String radiantCompass, String bestHours,
            boolean major, Integer ukRate) { }

    /**
     * Every catalogued shower in calendar order, with peak dates and catalogue constants from the
     * IMO 2026 Meteor Shower Calendar, Table 5 (working list of visual meteor showers). A peak is the
     * EVENING date of the night of maximum (a 06:00 UT maximum on the 6th is stored as the 5th), and
     * peaks must be distinct (the almanac entry id is built from them).
     */
    static final List<Shower> SHOWERS = List.of(
            new Shower("QUA", "Quadrantids", MonthDay.of(1, 3), 80, "NE", "04:00–dawn", true, null),
            new Shower("LYR", "Lyrids", MonthDay.of(4, 22), 18, "SE", "02:00–04:00", true, null),
            new Shower("ETA", "Eta Aquariids", MonthDay.of(5, 5), 50, "E", "03:00–dawn", false, 5),
            new Shower("SDA", "S. Delta Aquariids", MonthDay.of(7, 30), 25, "S", "01:00–04:00", false, 8),
            new Shower("PER", "Perseids", MonthDay.of(8, 12), 100, "NE", "01:00–04:00", true, null),
            new Shower("DRA", "Draconids", MonthDay.of(10, 8), 5, "NW", "20:00–23:00", false, null),
            new Shower("ORI", "Orionids", MonthDay.of(10, 20), 20, "SE", "02:00–05:00", true, null),
            new Shower("STA", "S. Taurids", MonthDay.of(11, 5), 7, "S", "23:00–03:00", false, null),
            new Shower("NTA", "N. Taurids", MonthDay.of(11, 12), 5, "S", "22:00–02:00", false, null),
            new Shower("LEO", "Leonids", MonthDay.of(11, 17), 15, "SE", "03:00–dawn", true, null),
            new Shower("GEM", "Geminids", MonthDay.of(12, 14), 150, "E", "22:00–02:00", true, null),
            new Shower("URS", "Ursids", MonthDay.of(12, 22), 10, "N", "00:00–dawn", false, null));

    private final MeteorMoonGate moonGate;
    private final LocationRepository locationRepository;
    private final MeteorClarityService meteorClarityService;
    private final MeteorPeakCalendar peakCalendar;

    /**
     * Constructs a {@code MeteorHotTopicStrategy}.
     *
     * @param moonGate             decides whether a bright moon is up during the shower's best hours
     * @param locationRepository   repository for dark-sky region lookups
     * @param meteorClarityService cache of how many dark-sky locations are clear overhead on the peak
     * @param peakCalendar         each year's peak night and rate
     */
    public MeteorHotTopicStrategy(MeteorMoonGate moonGate,
            LocationRepository locationRepository, MeteorClarityService meteorClarityService,
            MeteorPeakCalendar peakCalendar) {
        this.moonGate = moonGate;
        this.locationRepository = locationRepository;
        this.meteorClarityService = meteorClarityService;
        this.peakCalendar = peakCalendar;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic for the earliest qualifying major shower peak in the window
     * (minor showers never qualify; peak in range and not washed out by a bright moon that is up
     * for most of the shower's best hours, per {@link MeteorMoonGate}). Returns empty when no
     * qualifying peak falls in the window.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        return detect(fromDate, toDate,
                HotTopicInputs.ofDarkSky(locationRepository::findByBortleClassIsNotNullAndEnabledTrue));
    }

    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate,
            HotTopicInputs inputs) {
        // Sorted by night, so the first shower the moon does not wash out is the earliest qualifying peak.
        MeteorPeakCalendar.ShowerNight best = null;
        MeteorMoonGate.MoonOverShower bestMoon = null;
        for (MeteorPeakCalendar.ShowerNight candidate : peakCalendar.peakNightsIn(fromDate, toDate, true)) {
            MeteorMoonGate.MoonOverShower moon = moonGate.assess(candidate.shower(), candidate.night());
            if (!moon.washedOut()) {
                best = candidate;
                bestMoon = moon;
                break;
            }
        }

        if (best == null) {
            return List.of();
        }
        return List.of(buildTopic(best, bestMoon, inputs));
    }

    private HotTopic buildTopic(MeteorPeakCalendar.ShowerNight night, MeteorMoonGate.MoonOverShower moon,
            HotTopicInputs inputs) {
        Shower shower = night.shower();
        LocalDate peak = night.night();
        List<String> darkSkyRegions =
                inputs.darkSkyLocations().stream()
                        .map(LocationEntity::getRegion)
                        .filter(Objects::nonNull)
                        .map(RegionEntity::getName)
                        .distinct()
                        .toList();

        int moonPct = moon.illuminationPct();
        boolean darkEnough = moonPct < MeteorMoonGate.DARK_MOON_PCT;
        // The headline claim tracks the same band as the moon chip: a shower can fire under a
        // half-lit moon (down, or setting early), so the copy says which, never "dark moon".
        String moonQuality;
        String viewingCue;
        if (darkEnough) {
            moonQuality = "dark enough";
            viewingCue = "dark moon, good viewing";
        } else if (moon.downThroughout()) {
            moonQuality = "down in the best hours";
            viewingCue = "moon down, good viewing";
        } else if (moon.moonset().isPresent()) {
            moonQuality = "sets " + moon.moonset().get();
            viewingCue = "some moonlight, still worth a look";
        } else if (moon.moonrise().isPresent()) {
            moonQuality = "rises " + moon.moonrise().get();
            viewingCue = "some moonlight, still worth a look";
        } else {
            moonQuality = "some moonlight";
            viewingCue = "some moonlight, still worth a look";
        }
        List<HotTopicFact> facts = new ArrayList<>(List.of(
                HotTopicFact.metric("ZHR", "~" + night.zhr() + " at peak"),
                HotTopicFact.directional("radiant", "best " + shower.bestHours(),
                        shower.radiantCompass(), false),
                new HotTopicFact("moon", moonPct + "% · " + moonQuality, null, false, true)));
        addClearSkyFact(facts, peak);

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

    /**
     * Appends the "clear at X of Y dark-sky locations" fact when the overhead-clarity scan covered
     * this peak night — how many dark-sky locations are forecast clear overhead, the honest whole-sky
     * signal for meteors (not the northern-horizon transect aurora/NLC use). Omitted (no fabricated
     * figure) when the scan did not run or returned an inconsistent count.
     *
     * @param facts the fact list to append to
     * @param peak  the shower peak night
     */
    private void addClearSkyFact(List<HotTopicFact> facts, LocalDate peak) {
        meteorClarityService.getCached().forNight(peak).ifPresent(clarity -> {
            if (clarity.totalDarkSkyCount() > 0
                    && clarity.clearLocationCount() <= clarity.totalDarkSkyCount()) {
                facts.add(new HotTopicFact(null,
                        "clear at " + clarity.clearLocationCount() + " of "
                                + clarity.totalDarkSkyCount() + " dark-sky locations",
                        null, false, false));
            }
        });
    }
}
