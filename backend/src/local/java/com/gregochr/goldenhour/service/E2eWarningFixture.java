package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.warnings.WarningGeometry;
import com.gregochr.goldenhour.service.warnings.WarningPollStatus;
import com.gregochr.goldenhour.service.warnings.WarningService;
import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gives the <b>E2E smoke suite's</b> local backend one real (non-simulated) Met Office weather warning, so the
 * Map's warning chip and its Details toggle can be driven end to end.
 *
 * <p>Called only by {@link E2eLocalFixtureSeeder}, so it inherits that bean's refusals (the {@code local & !prod}
 * profile, the {@code photocast.e2e.seed-fixture} flag, the H2 and empty-database checks). It writes one version
 * row through {@link WeatherWarningVersionRepository}, the persistence path the real poller uses, bumps the
 * served-briefing epoch as a simulation insert does, and records one successful poll in {@link WarningPollStatus}
 * (E2E only: nothing ever polls, and the smoke script sets {@code photocast.warnings.poll-stale-minutes=1440} so the
 * feed does not go stale mid-suite and print a stale line on every surface). It makes no Met Office request (the
 * script points the warnings base URL at a refused loopback port, and no scheduler row exists) and no Claude call.
 *
 * <p><b>One window only, never the first</b>: the warning is valid from two hours before the earliest to two hours
 * after the latest of the six Lake District places' sunset on T+1 (UK civil date), so it shows on that one window and
 * on no other, and the instants do not depend on the time of day the suite runs. It covers exactly one region's six
 * places through a square (the Lake District), and no other region's.
 *
 * <p>The wording is paraphrased from the shape of real Met Office warnings (the fixtures under
 * {@code src/test/resources/warnings/real}), not copied.
 */
@Component
@Profile("local & !prod")
@ConditionalOnProperty(name = "photocast.e2e.seed-fixture", havingValue = "true")
public class E2eWarningFixture {

    /** The warning's stable id. */
    public static final String WARNING_ID = "E2E-FIXTURE-1";

    /** The warning's headline, which the Details panel shows. */
    public static final String HEADLINE = "Heavy rain may cause travel disruption across the Lake District.";

    /** A "what to expect" line, which the Details panel shows. */
    public static final String EXPECT_LINE =
            "Spray and standing water could make some fell roads and bridges difficult to use.";

    /** The region the warning covers. */
    static final String REGION = "Lake District";

    /** Which day after today the one window is. */
    static final int WINDOW_DAYS_AHEAD = 1;

    /** The Lake District square's centre latitude; the square holds the region's six places. */
    static final double CENTRE_LATITUDE = 54.505;

    /** The square's centre longitude. */
    static final double CENTRE_LONGITUDE = -3.08;

    /** Half the square's side, in degrees. */
    static final double HALF_SIDE_DEGREES = 0.35;

    /** How far either side of the sunsets the warning runs. */
    static final Duration MARGIN = Duration.ofHours(2);

    private static final BigDecimal VERSION = new BigDecimal("1.00");
    private static final int LIKELIHOOD = 2;
    private static final int IMPACT = 2;

    private final WeatherWarningVersionRepository repository;
    private final ServedBriefingEpoch epoch;
    private final WarningPollStatus pollStatus;
    private final WarningService warningService;
    private final SolarEventFreshness freshness;
    private final Clock clock;

    /**
     * Creates the fixture.
     *
     * @param repository      the warning version store the real poller writes through
     * @param epoch           the served-briefing epoch, bumped after the insert
     * @param pollStatus      the in-memory poll record, given one success so the feed is not stale
     * @param warningService  the service {@code /api/warnings} serves from, read back by the self-check
     * @param freshness       the project's one answer to solar event times
     * @param clock           the application clock
     */
    public E2eWarningFixture(WeatherWarningVersionRepository repository, ServedBriefingEpoch epoch,
            WarningPollStatus pollStatus, WarningService warningService, SolarEventFreshness freshness,
            Clock clock) {
        this.repository = repository;
        this.epoch = epoch;
        this.pollStatus = pollStatus;
        this.warningService = warningService;
        this.freshness = freshness;
        this.clock = clock;
    }

    /**
     * Inserts the one warning, records the one successful poll and bumps the epoch.
     *
     * @param locations the saved fixture places (the Lake District's six set the validity window)
     * @return a short summary for the seed log
     * @throws IllegalStateException when no Lake District place is among the locations
     */
    String seed(List<LocationEntity> locations) {
        LocalDate day = ForecastHorizon.today(clock).plusDays(WINDOW_DAYS_AHEAD);
        LocalDateTime earliest = null;
        LocalDateTime latest = null;
        for (LocationEntity location : locations) {
            if (!REGION.equals(location.getRegion().getName())) {
                continue;
            }
            LocalDateTime sunset = freshness.eventTime(location, day, TargetType.SUNSET);
            earliest = earliest == null || sunset.isBefore(earliest) ? sunset : earliest;
            latest = latest == null || sunset.isAfter(latest) ? sunset : latest;
        }
        if (earliest == null) {
            throw new IllegalStateException("No " + REGION + " place to time the fixture warning against");
        }
        Instant seedInstant = clock.instant();
        String geometry = WarningGeometry.square(CENTRE_LONGITUDE, CENTRE_LATITUDE, HALF_SIDE_DEGREES);
        WeatherWarningVersionEntity row = new WeatherWarningVersionEntity();
        row.setWarningId(WARNING_ID);
        row.setWarningVersion(VERSION);
        row.setStatus(WeatherWarningVersionEntity.STATUS_ACTIVE);
        row.setSourceStatus("ISSUED");
        row.setWarningLevel("YELLOW");
        row.setWeatherTypes(WarningGeometry.toJson(List.of("RAIN")));
        row.setLikelihood(LIKELIHOOD);
        row.setImpact(IMPACT);
        row.setValidFrom(earliest.toInstant(ZoneOffset.UTC).minus(MARGIN));
        row.setValidTo(latest.toInstant(ZoneOffset.UTC).plus(MARGIN));
        row.setIssuedAt(seedInstant);
        row.setModifiedAt(seedInstant);
        row.setHeadline(HEADLINE);
        row.setWhatToExpect(WarningGeometry.toJson(List.of(EXPECT_LINE,
                "A small chance of some short-lived power cuts in exposed valleys.")));
        row.setWhatShouldIDo("Check road conditions before you set out and allow extra time for your journey.");
        row.setFurtherDetails("Rain, heavy at times, is expected to move across the fells during the afternoon "
                + "and evening, easing overnight.");
        row.setAffectedAreas(WarningGeometry.toJson(List.of("NW")));
        row.setGeometry(geometry);
        row.setSimplifiedGeometry(geometry);
        row.setSimulated(false);
        row.setReceivedAt(seedInstant);
        repository.save(row);
        epoch.bump();
        pollStatus.record(new WarningPollStatus.Attempt(seedInstant, true, null, 0, 1, 0, 0, 0));
        return "1 weather warning";
    }

    /**
     * The self-check: the shared view serves exactly this one warning, covering exactly the Lake District's places.
     *
     * @param locations the saved fixture places
     * @throws IllegalStateException when the served warnings differ from that
     */
    void verify(List<LocationEntity> locations) {
        Set<Long> expected = new HashSet<>();
        for (LocationEntity location : locations) {
            if (REGION.equals(location.getRegion().getName())) {
                expected.add(location.getId());
            }
        }
        WarningsResponse response = warningService.served();
        if (!response.enabled() || response.stale()) {
            throw new IllegalStateException("Self-check: /api/warnings must be enabled and not stale (the smoke"
                    + " script sets the warnings api key and poll-stale-minutes)");
        }
        List<WarningDto> served = response.warnings();
        if (served.size() != 1 || !WARNING_ID.equals(served.getFirst().warningId())
                || !expected.equals(new HashSet<>(served.getFirst().locationIds()))) {
            throw new IllegalStateException("Self-check: /api/warnings must serve exactly the fixture warning over"
                    + " the " + REGION + "'s places, but served " + served);
        }
    }
}
