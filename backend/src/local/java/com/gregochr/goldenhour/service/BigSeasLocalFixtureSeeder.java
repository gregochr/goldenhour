package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gives a <b>local</b> app big-seas data to look at: local profiles never run Flyway, so V188's
 * structure columns and {@code marine_high_water} rows would otherwise never exist on a laptop.
 *
 * <p>Only under the {@code local} profile and never {@code prod} ({@code @Profile("local & !prod")}),
 * only when {@code photocast.big-seas.seed-local-fixture} is exactly {@code true}, and it re-checks
 * the active profile at runtime. It makes no Open-Meteo or Claude call. It sets the structure
 * columns on whichever of the seven seeded names exist locally, then replaces tomorrow's
 * {@code marine_high_water} rows for up to three of them with invented figures: one high water
 * aligned with sunset (Hs 4 m from 30 degrees, 11 s), one aligned with sunrise, one at midday. Run
 * it again and nothing doubles. The numbers are for a local rehearsal and never mistaken for data.
 */
@Component
@Profile("local & !prod")
@ConditionalOnProperty(name = "photocast.big-seas.seed-local-fixture", havingValue = "true")
public class BigSeasLocalFixtureSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(BigSeasLocalFixtureSeeder.class);

    private static final long HALF_WINDOW_MINUTES = 90;
    private static final long SUNSET_GAP_MINUTES = 20;
    private static final long SUNRISE_GAP_MINUTES = 35;
    private static final int MIDDAY_HOUR = 12;
    private static final BigDecimal HW_HEIGHT_METRES = new BigDecimal("5.100");

    /** One structure's seed values: name, kind, faces, label. */
    private record Structure(String name, CoastalStructure kind, int faces, String label) { }

    private static final List<Structure> STRUCTURES = List.of(
            new Structure("Roker Pier & Lighthouse", CoastalStructure.PIER, 45, "Roker Pier"),
            new Structure("St. Mary's Lighthouse", CoastalStructure.LIGHTHOUSE, 90, "St Mary's"),
            new Structure("Amble", CoastalStructure.PIER, 45, "Amble piers"),
            new Structure("Saltburn-by-the-Sea", CoastalStructure.PIER, 0, "Saltburn Pier"),
            new Structure("Whitby Piers", CoastalStructure.PIER, 0, "Whitby piers"),
            new Structure("Seaham North Pier & Lighthouse", CoastalStructure.PIER, 90, "Seaham Pier"),
            new Structure("Hartlepool Headland", CoastalStructure.BREAKWATER, 90, "Hartlepool"));

    private final LocationRepository locationRepository;
    private final MarineHighWaterWriter writer;
    private final SolarService solarService;
    private final Environment environment;
    private final Clock clock;

    /**
     * Creates the seeder.
     *
     * @param locationRepository locations
     * @param writer             replaces a location's high-water rows in one transaction
     * @param solarService       sunrise and sunset times the fixture aligns to
     * @param environment        the environment, checked for the {@code local} profile
     * @param clock              the application clock
     */
    public BigSeasLocalFixtureSeeder(LocationRepository locationRepository, MarineHighWaterWriter writer,
            SolarService solarService, Environment environment, Clock clock) {
        this.locationRepository = locationRepository;
        this.writer = writer;
        this.solarService = solarService;
        this.environment = environment;
        this.clock = clock;
    }

    /**
     * Seeds once the application is ready. Best effort: a failure is logged and never stops startup.
     *
     * @throws IllegalStateException when the {@code local} profile is not active (or {@code prod} is)
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        if (!environment.acceptsProfiles(Profiles.of("local"))
                || environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "photocast.big-seas.seed-local-fixture is only for the local profile; refusing to seed");
        }
        try {
            seed();
        } catch (RuntimeException e) {
            LOG.warn("[BIG SEAS FIXTURE] Seeding stopped early, the application carries on: {}", e.toString());
        }
    }

    /**
     * Writes the fixture. Package-private so a test can run it twice and compare.
     *
     * @return the number of high-water rows written
     */
    int seed() {
        List<LocationEntity> present = new ArrayList<>();
        for (Structure structure : STRUCTURES) {
            Optional<LocationEntity> found = locationRepository.findByName(structure.name());
            if (found.isPresent()) {
                LocationEntity loc = found.get();
                loc.setCoastalStructure(structure.kind());
                loc.setStructureFacesDeg(structure.faces());
                loc.setStructureLabel(structure.label());
                present.add(locationRepository.save(loc));
            }
        }
        LocalDate tomorrow = ForecastHorizon.today(clock).plusDays(1);
        LocalDateTime from = ForecastHorizon.ukDayStartUtc(tomorrow);
        LocalDateTime to = ForecastHorizon.ukDayStartUtc(tomorrow.plusDays(1));
        int written = 0;
        for (int i = 0; i < Math.min(present.size(), 3); i++) {
            LocationEntity loc = present.get(i);
            MarineHighWaterEntity row = switch (i) {
                case 0 -> row(loc, solarService.sunsetUtc(loc.getLat(), loc.getLon(), tomorrow)
                        .minusMinutes(SUNSET_GAP_MINUTES), 4.0, 30, 11.0);
                case 1 -> row(loc, solarService.sunriseUtc(loc.getLat(), loc.getLon(), tomorrow)
                        .plusMinutes(SUNRISE_GAP_MINUTES), 3.4, 60, 9.0);
                default -> row(loc, tomorrow.atTime(MIDDAY_HOUR, 0), 3.1, 45, 8.5);
            };
            writer.replace(loc.getId(), from, to, List.of(row));
            written++;
        }
        LOG.info("[BIG SEAS FIXTURE] {} structures marked, {} high-water rows for {}",
                present.size(), written, tomorrow);
        return written;
    }

    private MarineHighWaterEntity row(LocationEntity loc, LocalDateTime hwTime, double hs,
            int direction, double period) {
        LocalDateTime peak = hwTime.truncatedTo(ChronoUnit.HOURS);
        return MarineHighWaterEntity.builder()
                .location(loc)
                .hwTime(hwTime)
                .hwHeightMetres(HW_HEIGHT_METRES)
                .windowStart(hwTime.minusMinutes(HALF_WINDOW_MINUTES))
                .windowEnd(hwTime.plusMinutes(HALF_WINDOW_MINUTES))
                .peakTime(peak)
                .waveHeightMetres(hs)
                .wavePeriodSeconds(period)
                .swellWaveHeightMetres(hs * 0.8)
                .swellWavePeriodSeconds(period + 1)
                .waveDirectionDegrees(direction)
                .evaluatedAt(Instant.now(clock))
                .build();
    }
}
