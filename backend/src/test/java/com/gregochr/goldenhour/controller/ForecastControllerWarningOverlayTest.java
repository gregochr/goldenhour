package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.model.ForecastDtoMapper;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.AsOfEventResolver;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.LitePickShaper;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.UserSubRegionService;
import com.gregochr.goldenhour.service.warnings.WarningCoverage;
import com.gregochr.goldenhour.service.warnings.WarningOverlay;
import com.gregochr.goldenhour.service.warnings.WarningTimeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The location rows of {@code GET /api/forecast} carry {@code warningIds}, attached last and changing nothing else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForecastControllerWarningOverlayTest {

    private static final Instant NOW_AT = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock NOW = Clock.fixed(NOW_AT, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 16);
    private static final LocalDateTime TOMORROW_SUNSET = LocalDateTime.of(2026, 10, 16, 17, 0);
    private static final String BOX = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-3,54],[-2,54],[-2,55],[-3,55],"
            + "[-3,54]]]]}";

    @Mock
    private ForecastEvaluationRepository repository;
    @Mock
    private LocationService locationService;
    @Mock
    private ScheduledForecastService scheduledForecastService;
    @Mock
    private ForecastDtoMapper dtoMapper;
    @Mock
    private EvaluationViewService evaluationViewService;
    @Mock
    private RunProgressTracker progressTracker;
    @Mock
    private SolarEventFreshness solarEventFreshness;
    @Mock
    private AsOfEventResolver resolver;
    @Mock
    private WeatherWarningVersionRepository warnings;
    @Mock
    private LocationRepository locations;

    private final WarningsProperties properties = new WarningsProperties();
    private LocationEntity inside;
    private LocationEntity outside;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        RegionEntity region = RegionEntity.builder().id(1L).name("Dales").build();
        inside = LocationEntity.builder().id(10L).name("Hartside").lat(54.7).lon(-2.4).region(region).build();
        outside = LocationEntity.builder().id(11L).name("Hexham").lat(54.9).lon(-1.5).region(region).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(inside, outside));
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(inside, outside));
        when(warnings.findAllVersionsOfWarningsValidAfter(NOW_AT)).thenReturn(List.of(active("W-ACT")));
        when(dtoMapper.toListDtoList(anyList(), anyBoolean())).thenAnswer(call -> {
            List<ForecastEvaluationEntity> rows = call.getArgument(0);
            List<ForecastListDto> out = new ArrayList<>();
            for (ForecastEvaluationEntity e : rows) {
                out.add(new ForecastListDto(e.getId(), e.getLocationName(), null, null, e.getTargetDate(),
                        e.getTargetType(), e.getForecastRunAt(), e.getSolarEventTime(), null, e.getRating(), null,
                        null, null, null, null, null, null, null, null, null, null));
            }
            return out;
        });
    }

    private static WeatherWarningVersionEntity active(String id) {
        WeatherWarningVersionEntity w = new WeatherWarningVersionEntity();
        w.setWarningId(id);
        w.setWarningVersion(1);
        w.setStatus(WeatherWarningVersionEntity.STATUS_ACTIVE);
        w.setWarningLevel("Amber");
        w.setGeometry(BOX);
        w.setReceivedAt(Instant.parse("2026-10-15T06:00:00Z"));
        w.setValidFrom(Instant.parse("2026-10-16T00:00:00Z"));
        w.setValidTo(Instant.parse("2026-10-17T00:00:00Z"));
        return w;
    }

    private ForecastController controller() {
        WarningOverlay overlay = new WarningOverlay(warnings, new WarningTimeline(NOW), new WarningCoverage(),
                locations, properties, NOW);
        return new ForecastController(repository, locationService, scheduledForecastService, dtoMapper,
                evaluationViewService, progressTracker, Runnable::run, NOW, resolver, solarEventFreshness,
                new LitePickShaper(mock(UserSubRegionService.class), new SubRegionProperties()), overlay);
    }

    private ForecastEvaluationEntity row(long id, LocationEntity place, Integer rating, LocalDateTime event) {
        return ForecastEvaluationEntity.builder().id(id).location(place).targetDate(TOMORROW)
                .targetType(TargetType.SUNSET).forecastRunAt(LocalDateTime.of(2026, 10, 15, 9, 0))
                .solarEventTime(event).rating(rating).build();
    }

    private void rows(ForecastEvaluationEntity... entities) {
        when(repository.findLatestRunPerSlotByLocationIds(anyCollection(), eq(TODAY.minusDays(2)),
                eq(TODAY.plusDays(5)))).thenReturn(List.of(entities));
    }

    private static ForecastListDto byId(List<ForecastListDto> out, long id) {
        return out.stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a covered place's row carries the id; an uncovered place's row does not; ratings are untouched")
    void coveredRowCarriesTheId() {
        rows(row(1L, inside, 4, TOMORROW_SUNSET), row(2L, outside, 3, TOMORROW_SUNSET));
        Authentication pro = new TestingAuthenticationToken("u", "p", "ROLE_PRO_USER");

        List<ForecastListDto> out = controller().getForecasts(pro);

        assertThat(byId(out, 1L).warningIds()).containsExactly("W-ACT");
        assertThat(byId(out, 1L).rating()).isEqualTo(4);
        assertThat(byId(out, 2L).warningIds()).isNull();
        assertThat(byId(out, 2L).rating()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unscored (stood-down) row still carries the id: a warning is not a rating")
    void unscoredRowStillCarriesTheId() {
        rows(row(1L, inside, null, TOMORROW_SUNSET));
        Authentication pro = new TestingAuthenticationToken("u", "p", "ROLE_PRO_USER");

        List<ForecastListDto> out = controller().getForecasts(pro);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).rating()).isNull();
        assertThat(out.get(0).warningIds()).containsExactly("W-ACT");
    }

    @Test
    @DisplayName("Lite, Pro and Admin are served the same warning ids")
    void everyRoleReadsTheSameIds() {
        rows(row(1L, inside, 4, TOMORROW_SUNSET));
        List<List<String>> seen = new ArrayList<>();
        for (String role : List.of("ROLE_LITE_USER", "ROLE_PRO_USER", "ROLE_ADMIN")) {
            Authentication auth = new TestingAuthenticationToken("u", "p", role);
            seen.add(controller().getForecasts(auth).get(0).warningIds());
        }

        assertThat(seen).containsExactly(List.of("W-ACT"), List.of("W-ACT"), List.of("W-ACT"));
    }

    @Test
    @DisplayName("a row with no event time uses the place's own solar event for that date and type")
    void fallsBackToThePlacesEventTime() {
        rows(row(1L, inside, 4, null));
        when(solarEventFreshness.eventTime(inside, TOMORROW, TargetType.SUNSET)).thenReturn(TOMORROW_SUNSET);
        Authentication pro = new TestingAuthenticationToken("u", "p", "ROLE_PRO_USER");

        assertThat(controller().getForecasts(pro).get(0).warningIds()).containsExactly("W-ACT");
    }

    @Test
    @DisplayName("a row with no event time and no solar event gets no id rather than a guess")
    void noEventTimeNoId() {
        rows(row(1L, inside, 4, null));
        Authentication pro = new TestingAuthenticationToken("u", "p", "ROLE_PRO_USER");

        assertThat(controller().getForecasts(pro).get(0).warningIds()).isNull();
    }

    @Test
    @DisplayName("an event outside the warning's validity gets no id")
    void outsideValidityNoId() {
        rows(row(1L, inside, 4, LocalDateTime.of(2026, 10, 18, 17, 0)));
        Authentication pro = new TestingAuthenticationToken("u", "p", "ROLE_PRO_USER");

        assertThat(controller().getForecasts(pro).get(0).warningIds()).isNull();
    }

    @Test
    @DisplayName("with the flag off the rows are exactly what they were")
    void flagOffChangesNothing() {
        properties.setEnabled(false);
        rows(row(1L, inside, 4, TOMORROW_SUNSET));
        Authentication pro = new TestingAuthenticationToken("u", "p", "ROLE_PRO_USER");

        assertThat(controller().getForecasts(pro).get(0).warningIds()).isNull();
    }
}
