package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.ServedBriefingEpoch;
import com.gregochr.goldenhour.service.warnings.WarningCoverage;
import com.gregochr.goldenhour.service.warnings.WarningPollStatus;
import com.gregochr.goldenhour.service.warnings.WarningService;
import com.gregochr.goldenhour.service.warnings.WarningTimeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The merge against the real {@link WarningService} and {@link WarningTimeline}, on a stored-version fixture: entries
 * appear and disappear with the injected clock (the licence's timeliness rule for a cancellation or an expiry), a
 * cancelled version or a simulated row never reaches a reader, and a rewound clock shows what had been received.
 */
@ExtendWith(MockitoExtension.class)
class ComingUpWarningMergerClockTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final String BOX = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-2,54],[-1,54],[-1,55],[-2,55],"
            + "[-2,54]]]]}";

    @Mock
    private WeatherWarningVersionRepository repository;
    @Mock
    private LocationRepository locations;

    private final WarningsProperties properties = new WarningsProperties();

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("test-key");
    }

    /** A merger at the given instant that will read exactly these stored versions and one enabled place. */
    private ComingUpWarningMerger mergerAt(Instant at, List<WeatherWarningVersionEntity> versions) {
        when(repository.findAllVersionsOfWarningsValidAfter(at)).thenReturn(versions);
        LocationEntity place = new LocationEntity();
        place.setId(7L);
        place.setLat(54.5);
        place.setLon(-1.5);
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(place));
        return mergerWithoutStubs(at);
    }

    private ComingUpWarningMerger mergerWithoutStubs(Instant at) {
        Clock clock = Clock.fixed(at, ZoneOffset.UTC);
        WarningService service = new WarningService(repository, new WarningTimeline(clock), new WarningCoverage(),
                locations, new WarningPollStatus(), properties, new ServedBriefingEpoch(), clock);
        return new ComingUpWarningMerger(service, clock);
    }

    private static WeatherWarningVersionEntity version(String id, String number, String status, String level,
            boolean simulated, Instant receivedAt) {
        WeatherWarningVersionEntity w = new WeatherWarningVersionEntity();
        w.setWarningId(id);
        w.setWarningVersion(new BigDecimal(number));
        w.setStatus(status);
        w.setWarningLevel(level);
        w.setWeatherTypes("[\"SNOW\"]");
        w.setHeadline("Headline");
        w.setValidFrom(Instant.parse("2026-11-19T12:00:00Z"));
        w.setValidTo(Instant.parse("2026-11-24T06:00:00Z"));
        w.setReceivedAt(receivedAt);
        w.setGeometry(BOX);
        w.setSimplifiedGeometry(BOX);
        w.setSimulated(simulated);
        return w;
    }

    private static ComingUpResponse emptyFeed() {
        return new ComingUpResponse(LocalDate.of(2026, 11, 18), null, null, List.of());
    }

    private static ComingUpResponse oneAlmanac() {
        return new ComingUpResponse(LocalDate.of(2026, 11, 18), null, null, List.of(ComingUpEntry.from(
                new AlmanacEvent(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), AlmanacKind.ALMANAC,
                        "solstice", "t", "d", Map.of(), List.of()), LocalDate.of(2026, 9, 1))));
    }

    @Test
    @DisplayName("an active warning has an entry now, and none once the clock passes its end")
    void appearsAndExpires() {
        List<WeatherWarningVersionEntity> stored =
                List.of(version("W1", "1.0", "ACTIVE", "AMBER", false, NOW.minus(Duration.ofHours(1))));

        assertThat(mergerAt(NOW, stored).merge(emptyFeed(), 90).entries()).hasSize(1);
        Instant after = Instant.parse("2026-11-24T06:00:01Z");
        assertThat(mergerAt(after, stored).merge(emptyFeed(), 90).entries()).isEmpty();
    }

    @Test
    @DisplayName("a stored cancellation removes the warning from the very next serve")
    void cancellationDisappears() {
        WeatherWarningVersionEntity issued = version("W1", "1.0", "ACTIVE", "AMBER", false,
                NOW.minus(Duration.ofHours(2)));
        ComingUpResponse cached = oneAlmanac();
        assertThat(mergerAt(NOW, List.of(issued)).merge(cached, 90).entries()).hasSize(2);

        WeatherWarningVersionEntity cancelled =
                version("W1", "2.0", "CANCELLED", "AMBER", false, NOW.minus(Duration.ofMinutes(1)));
        ComingUpResponse after = mergerAt(NOW, List.of(issued, cancelled)).merge(cached, 90);

        assertThat(after).isSameAs(cached);
        assertThat(after.entries()).hasSize(1);
    }

    @Test
    @DisplayName("a simulated row never reaches a reader")
    void simulatedNeverServed() {
        List<WeatherWarningVersionEntity> stored =
                List.of(version("SIM-1", "1.0", "ACTIVE", "RED", true, NOW.minus(Duration.ofHours(1))));

        ComingUpResponse feed = emptyFeed();
        assertThat(mergerAt(NOW, stored).merge(feed, 90)).isSameAs(feed);
    }

    @Test
    @DisplayName("a rewound clock shows what had been received: nothing before receipt, the entry after")
    void rewindShowsWhatHadBeenReceived() {
        List<WeatherWarningVersionEntity> stored = List.of(version("W1", "1.0", "ACTIVE", "AMBER", false, NOW));

        assertThat(mergerAt(NOW.minus(Duration.ofHours(3)), stored).merge(emptyFeed(), 90).entries()).isEmpty();
        assertThat(mergerAt(NOW, stored).merge(emptyFeed(), 90).entries()).hasSize(1);
    }

    @Test
    @DisplayName("a raised level is flagged so the entry floats, and the merged feed differs from the cached one")
    void raisedLevelFlagsAndBodyChanges() {
        List<WeatherWarningVersionEntity> stored = List.of(
                version("W1", "1.0", "ACTIVE", "YELLOW", false, NOW.minus(Duration.ofHours(5))),
                version("W1", "2.0", "ACTIVE", "AMBER", false, NOW.minus(Duration.ofHours(2))));
        ComingUpResponse cached = oneAlmanac();

        ComingUpResponse merged = mergerAt(NOW, stored).merge(cached, 90);

        assertThat(merged).isNotEqualTo(cached);
        assertThat(merged.entries().getFirst().meta()).containsEntry("upgradedFrom", "YELLOW")
                .containsEntry("level", "AMBER");
    }

    @Test
    @DisplayName("on but with no API key: the same instance and the store is never read")
    void noKeyReadsNothing() {
        properties.setApiKey("");

        ComingUpResponse feed = oneAlmanac();
        assertThat(mergerWithoutStubs(NOW).merge(feed, 90)).isSameAs(feed);
        verifyNoInteractions(repository, locations);
    }

    @Test
    @DisplayName("the feature off: the same instance and the store is never read")
    void featureOffReadsNothing() {
        properties.setEnabled(false);

        ComingUpResponse feed = oneAlmanac();
        assertThat(mergerWithoutStubs(NOW).merge(feed, 90)).isSameAs(feed);
        verifyNoInteractions(repository, locations);
    }
}
