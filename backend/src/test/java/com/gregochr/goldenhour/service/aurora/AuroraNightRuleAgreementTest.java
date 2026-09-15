package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two places that decide which aurora night is current must name the same night, with the same
 * dark window, at every instant: {@link AuroraPollingJob} (which night the alert is about) and
 * {@link AuroraForecastRunService} (which night the map and the admin forecast run serve).
 *
 * <p>They are the same rule written twice, on independently declared constants — Durham's
 * coordinates and the 35-minute nautical buffer — and until the job took its instant from outside,
 * the agreement was by review alone: the job read the wall clock, so it could not be put on the
 * service's pinned instant. Both now answer for an instant they are given, and both use the real
 * solar-utils calculator here, so a drift in either — a constant, the comparison, the zone — goes
 * red. The latitude's further copies in {@code ClaudeAuroraInterpreter} and
 * {@code BriefingAuroraSummaryBuilder} are outside this test.
 */
@ExtendWith(MockitoExtension.class)
class AuroraNightRuleAgreementTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Mock
    private NoaaSwpcClient noaaClient;
    @Mock
    private WeatherTriageService weatherTriage;
    @Mock
    private ClaudeAuroraInterpreter claudeInterpreter;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private AuroraForecastResultRepository resultRepository;
    @Mock
    private AuroraStateCache stateCache;
    @Mock
    private AuroraForecastResultWriter resultWriter;
    @Mock
    private AuroraOrchestrator orchestrator;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private final SolarCalculator sun = new SolarCalculator();
    private AuroraPollingJob job;

    @BeforeEach
    void setUp() {
        // The job's own clock is irrelevant to calculateTonightWindow, which answers for the instant
        // it is handed; pin it somewhere none of the checked instants are.
        job = new AuroraPollingJob(orchestrator, new AuroraProperties(), sun, dynamicSchedulerService,
                Clock.fixed(Instant.parse("2031-06-01T12:00:00Z"), UTC));
    }

    @Test
    @DisplayName("through a whole year, every 97 minutes, both name the same night")
    void throughAYear_bothNameTheSameNight() {
        Instant end = Instant.parse("2028-01-01T00:00:00Z");
        for (Instant at = Instant.parse("2027-01-01T00:00:00Z"); at.isBefore(end);
                at = at.plus(Duration.ofMinutes(97))) {
            assertSameNight(at);
        }
    }

    @Test
    @DisplayName("a second either side of every nautical dawn and dusk of a year, and on each, both agree")
    void atEveryDawnAndDusk_bothNameTheSameNight() {
        // The boundaries come from the job's own windows, not from constants redeclared here, so a
        // change to the job's rule moves the probes with it rather than leaving them mid-window.
        for (LocalDate day = LocalDate.of(2027, 1, 1); day.getYear() == 2027; day = day.plusDays(1)) {
            TonightWindow night = job.calculateTonightWindow(day.atTime(12, 0).atZone(UTC));
            for (Instant boundary : new Instant[] {night.dusk().toInstant(), night.dawn().toInstant()}) {
                assertSameNight(boundary.minusSeconds(1));
                assertSameNight(boundary);
                assertSameNight(boundary.plusSeconds(1));
            }
        }
    }

    private void assertSameNight(Instant at) {
        AuroraForecastRunService service = new AuroraForecastRunService(noaaClient, weatherTriage,
                claudeInterpreter, locationRepository, resultRepository, new AuroraProperties(), sun,
                stateCache, resultWriter, Clock.fixed(at, UTC));
        TonightWindow servicesNight = service.computeWindowForDate(service.currentNightDate());

        assertThat(job.calculateTonightWindow(at.atZone(UTC)))
                .as("tonight's window at %s", at)
                .isEqualTo(servicesNight);
    }
}
