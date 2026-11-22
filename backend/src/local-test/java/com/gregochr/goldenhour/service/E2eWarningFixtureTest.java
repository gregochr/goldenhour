package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.warnings.WarningGeometry;
import com.gregochr.goldenhour.service.warnings.WarningPollStatus;
import com.gregochr.goldenhour.service.warnings.WarningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link E2eWarningFixture}: the one warning row, its single-window validity, the epoch bump, the recorded poll
 * success and the self-check. The clock is fixed in 2031 (British Summer Time) at 00:30 BST on the 15th, so the UK
 * civil date is already the 15th while UTC is still the 14th.
 */
class E2eWarningFixtureTest {

    private static final Instant NOW = Instant.parse("2031-07-14T23:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate T_PLUS_1 = LocalDate.of(2031, 7, 16);
    private static final LocalDateTime FIRST_SUNSET = LocalDateTime.of(2031, 7, 16, 19, 0);

    private WeatherWarningVersionRepository repository;
    private ServedBriefingEpoch epoch;
    private WarningPollStatus pollStatus;
    private WarningService warningService;
    private SolarEventFreshness freshness;
    private E2eWarningFixture fixture;
    private List<LocationEntity> places;

    @BeforeEach
    void setUp() {
        repository = mock(WeatherWarningVersionRepository.class);
        epoch = mock(ServedBriefingEpoch.class);
        pollStatus = new WarningPollStatus();
        warningService = mock(WarningService.class);
        freshness = mock(SolarEventFreshness.class);
        fixture = new E2eWarningFixture(repository, epoch, pollStatus, warningService, freshness, CLOCK);

        places = new ArrayList<>();
        long id = 100;
        int lakeIndex = 0;
        for (E2eLocalFixtureSeeder.Spot spot : E2eLocalFixtureSeeder.SPOTS) {
            RegionEntity region = RegionEntity.builder().name(spot.region()).enabled(true).build();
            LocationEntity place = LocationEntity.builder().id(id++).name(spot.name()).region(region).build();
            places.add(place);
            if (spot.region().equals("Lake District")) {
                when(freshness.eventTime(place, T_PLUS_1, TargetType.SUNSET))
                        .thenReturn(FIRST_SUNSET.plusMinutes(10L * lakeIndex++));
            }
        }
    }

    private WeatherWarningVersionEntity savedRow() {
        ArgumentCaptor<WeatherWarningVersionEntity> saved =
                ArgumentCaptor.forClass(WeatherWarningVersionEntity.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("writes one real, active, un-updated, non-simulated version row, then bumps the epoch")
    void writesOneRow() {
        assertThat(fixture.seed(places)).isEqualTo("1 weather warning");

        WeatherWarningVersionEntity row = savedRow();
        assertThat(row.getWarningId()).isEqualTo(E2eWarningFixture.WARNING_ID);
        assertThat(row.getWarningVersion()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(row.getStatus()).isEqualTo(WeatherWarningVersionEntity.STATUS_ACTIVE);
        assertThat(row.getSourceStatus()).isEqualTo("ISSUED");
        assertThat(row.getWarningLevel()).isEqualTo("YELLOW");
        assertThat(row.getWeatherTypes()).isEqualTo("[\"RAIN\"]");
        assertThat(row.getLikelihood()).isEqualTo(2);
        assertThat(row.getImpact()).isEqualTo(2);
        assertThat(row.getHeadline()).isEqualTo(E2eWarningFixture.HEADLINE).doesNotContain("Seeded");
        assertThat(row.getWhatToExpect()).contains(E2eWarningFixture.EXPECT_LINE);
        assertThat(row.isSimulated()).isFalse();
        assertThat(row.getIssuedAt()).isEqualTo(NOW);
        assertThat(row.getModifiedAt()).isEqualTo(NOW);
        assertThat(row.getReceivedAt()).isEqualTo(NOW);
        assertThat(row.getGeometry()).isEqualTo(row.getSimplifiedGeometry());
        InOrder order = inOrder(repository, epoch);
        order.verify(repository).save(row);
        order.verify(epoch).bump();
    }

    @Test
    @DisplayName("is valid for one window: two hours before the earliest to two hours after the latest T+1 sunset")
    void validityIsTheOneWindow() {
        fixture.seed(places);

        WeatherWarningVersionEntity row = savedRow();
        assertThat(row.getValidFrom()).isEqualTo(Instant.parse("2031-07-16T17:00:00Z"));
        assertThat(row.getValidTo()).isEqualTo(Instant.parse("2031-07-16T21:50:00Z"));
    }

    @Test
    @DisplayName("records one successful poll so the feed is not stale")
    void recordsASuccess() {
        fixture.seed(places);

        WarningPollStatus.Attempt attempt = pollStatus.lastAttempt();
        assertThat(attempt.at()).isEqualTo(NOW);
        assertThat(attempt.succeeded()).isTrue();
        assertThat(pollStatus.lastSuccessAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("its square holds every Lake District place and no other region's")
    void geometryCoversTheLakeDistrictOnly() {
        fixture.seed(places);

        var polygons = WarningGeometry.parse(savedRow().getGeometry());
        for (E2eLocalFixtureSeeder.Spot spot : E2eLocalFixtureSeeder.SPOTS) {
            assertThat(WarningGeometry.contains(polygons, spot.lon(), spot.lat()))
                    .as(spot.name()).isEqualTo(spot.region().equals("Lake District"));
        }
    }

    @Test
    @DisplayName("seeding with no Lake District place fails loudly and writes nothing")
    void needsALakeDistrictPlace() {
        List<LocationEntity> none = places.stream()
                .filter(p -> !p.getRegion().getName().equals("Lake District")).toList();
        assertThatThrownBy(() -> fixture.seed(none)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lake District");
        assertThat(pollStatus.lastAttempt()).isNull();
    }

    private WarningsResponse served(boolean enabled, boolean stale, List<WarningDto> warnings) {
        return new WarningsResponse(enabled, NOW, stale, warnings, "https://example.test/", "attribution");
    }

    private WarningDto warning(String id, Set<Long> ids) {
        return new WarningDto(id, "1.0", "YELLOW", List.of("RAIN"), 2, 2, NOW, NOW, NOW, NOW, "h", List.of(), null,
                null, null, false, List.of(), null, null, null, ids, false);
    }

    private Set<Long> lakeIds() {
        return places.stream().filter(p -> p.getRegion().getName().equals("Lake District"))
                .map(LocationEntity::getId).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("self-check passes when exactly the one warning is served over exactly the Lake District's places")
    void verifyPasses() {
        when(warningService.served()).thenReturn(
                served(true, false, List.of(warning(E2eWarningFixture.WARNING_ID, lakeIds()))));
        assertThatCode(() -> fixture.verify(places)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("self-check fails for a disabled or stale feed, no warning, another id or the wrong places")
    void verifyFails() {
        when(warningService.served()).thenReturn(served(false, true, List.of()));
        assertThatThrownBy(() -> fixture.verify(places)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enabled and not stale");

        when(warningService.served()).thenReturn(
                served(true, true, List.of(warning(E2eWarningFixture.WARNING_ID, lakeIds()))));
        assertThatThrownBy(() -> fixture.verify(places)).hasMessageContaining("enabled and not stale");

        when(warningService.served()).thenReturn(served(true, false, List.of()));
        assertThatThrownBy(() -> fixture.verify(places)).hasMessageContaining("exactly the fixture warning");

        when(warningService.served()).thenReturn(
                served(true, false, List.of(warning("OTHER", lakeIds()))));
        assertThatThrownBy(() -> fixture.verify(places)).hasMessageContaining("exactly the fixture warning");

        when(warningService.served()).thenReturn(
                served(true, false, List.of(warning(E2eWarningFixture.WARNING_ID, Set.of(100L)))));
        assertThatThrownBy(() -> fixture.verify(places)).hasMessageContaining("exactly the fixture warning");
    }
}
