package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.config.AppBuild;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How {@link ForecastService#fetchWeatherAndTriage} gets a v2 valley profile onto a slot
 * (inversion-v2-plan Phase 1b): a prefetched map is keyed by LOCATION ID, a null map means "fetch on demand",
 * a map without the key means "no profile and no on-demand fallback", and no profile failure ever fails the
 * slot or skips the mist step that follows.
 */
@ExtendWith(MockitoExtension.class)
class ForecastServiceInversionProfileTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 12);
    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 8, 12, 4, 40);
    private static final long LOCATION_ID = 5L;

    private static final LocationEntity ELIGIBLE = LocationEntity.builder()
            .id(LOCATION_ID).name("Cat Bells").lat(54.568).lon(-3.170)
            .elevationMetres(451).overlooksWater(true)
            .valleyFloorMetres(80).valleyFloorLat(54.575).valleyFloorLng(-3.152).build();

    @Mock
    private SolarService solarService;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private ForecastDataAugmentor augmentor;
    @Mock
    private ForecastEvaluationRepository repository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private WeatherTriageEvaluator weatherTriageEvaluator;
    @Mock
    private com.gregochr.goldenhour.service.evaluation.SlotAtmosphereWriter slotAtmosphereWriter;
    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), java.time.ZoneOffset.UTC);
    @Spy
    private AppBuild appBuild = new AppBuild("v2.0.0");

    @InjectMocks
    private ForecastService forecastService;

    @BeforeEach
    void setUp() {
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any())).thenReturn(SUNRISE);
        lenient().when(solarService.sunriseAzimuthDeg(anyDouble(), anyDouble(), any())).thenReturn(65);
        lenient().when(openMeteoService.getAtmosphericDataFromCache(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(
                        TestAtmosphericData.builder().targetType(TargetType.SUNRISE).solarEventTime(SUNRISE)
                                .build(), new OpenMeteoForecastResponse()));
        lenient().when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(
                        TestAtmosphericData.builder().targetType(TargetType.SUNRISE).solarEventTime(SUNRISE)
                                .build(), new OpenMeteoForecastResponse()));
        lenient().when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(), anyInt(), any(),
                any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(), anyInt(), any(),
                any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithTideData(any(), any(), any(), any(), anyDouble(), anyDouble(), any(),
                any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithLocationOrientation(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithStormSurge(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithInversionScore(any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithBluebellConditions(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithValleyMist(any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());
        lenient().when(augmentor.inversionV2Enabled()).thenReturn(true);
    }

    /** A whole-day profile of the valley cell, 80 m floor. */
    private static InversionProfile wholeProfile() {
        OpenMeteoForecastResponse r = new OpenMeteoForecastResponse();
        r.setElevation(80.0);
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        List<String> times = new ArrayList<>();
        List<Double> temps = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            times.add(LocalDateTime.of(2026, 8, 12, hour, 0).toString());
            temps.add(8.0);
        }
        h.setTime(times);
        h.setTemperature2m(temps);
        r.setHourly(h);
        return InversionProfile.from(r, 80);
    }

    private AtmosphericData scoredInput() {
        ArgumentCaptor<AtmosphericData> captor = ArgumentCaptor.forClass(AtmosphericData.class);
        verify(augmentor).augmentWithInversionScore(captor.capture(), any(), anyBoolean());
        return captor.getValue();
    }

    private ForecastPreEvalResult callNineArg(LocationEntity location) {
        return forecastService.fetchWeatherAndTriage(location, DATE, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null, Map.of(), null, null);
    }

    @Test
    @DisplayName("a prefetched map is keyed by location id: the profile is sliced to the event and attached")
    void prefetchedProfileIsAttached() {
        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(ELIGIBLE, DATE, TargetType.SUNRISE,
                Set.of(), EvaluationModel.SONNET, null, Map.of(), null, null, Map.of(LOCATION_ID, wholeProfile()));

        AtmosphericData scored = scoredInput();
        assertThat(result.triaged()).isFalse();
        assertThat(scored.inversionV2()).isNotNull();
        InversionProfile slice = scored.inversionV2().profile();
        assertThat(slice.slotTime()).isEqualTo(LocalDateTime.of(2026, 8, 12, 5, 0));
        assertThat(slice.hours()).hasSize(5);
        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
    }

    @Test
    @DisplayName("a prefetched map without the location's id means no profile and NO on-demand fallback")
    void mapWithoutTheKeyHasNoFallback() {
        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(ELIGIBLE, DATE, TargetType.SUNRISE,
                Set.of(), EvaluationModel.SONNET, null, Map.of(), null, null, Map.of(99L, wholeProfile()));

        assertThat(result.triaged()).isFalse();
        assertThat(scoredInput().inversionV2()).isNull();
        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
    }

    @Test
    @DisplayName("an empty prefetched map (the prefetch failed) is no profile, never one request per slot")
    void emptyMapMeansNoProfile() {
        forecastService.fetchWeatherAndTriage(ELIGIBLE, DATE, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null, Map.of(), null, null, Map.of());

        assertThat(scoredInput().inversionV2()).isNull();
        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
    }

    @Test
    @DisplayName("the nine-argument and six-argument forms (no prefetch) fetch on demand")
    void noPrefetchFetchesOnDemand() {
        when(openMeteoService.fetchInversionProfileOnDemand(ELIGIBLE)).thenReturn(wholeProfile());

        callNineArg(ELIGIBLE);
        forecastService.fetchWeatherAndTriage(ELIGIBLE, DATE, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null);

        verify(openMeteoService, times(2)).fetchInversionProfileOnDemand(ELIGIBLE);
        ArgumentCaptor<AtmosphericData> captor = ArgumentCaptor.forClass(AtmosphericData.class);
        verify(augmentor, times(2)).augmentWithInversionScore(captor.capture(), any(), anyBoolean());
        assertThat(captor.getAllValues()).allSatisfy(d -> assertThat(d.inversionV2()).isNotNull());
    }

    @Test
    @DisplayName("a profile failure on demand never fails the slot and mist is still scored")
    void onDemandFailureDoesNotFailTheSlot() {
        when(openMeteoService.fetchInversionProfileOnDemand(ELIGIBLE)).thenThrow(new RuntimeException("429"));

        ForecastPreEvalResult result = callNineArg(ELIGIBLE);

        assertThat(result.triaged()).isFalse();
        assertThat(scoredInput().inversionV2()).isNull();
        verify(augmentor).augmentWithValleyMist(any(), anyBoolean());
        verify(slotAtmosphereWriter).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a profile that does not cover the event is no profile")
    void profileNotCoveringTheEvent() {
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.of(2026, 9, 30, 6, 0));

        forecastService.fetchWeatherAndTriage(ELIGIBLE, LocalDate.of(2026, 9, 30), TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null, Map.of(), null, null, Map.of(LOCATION_ID, wholeProfile()));

        assertThat(scoredInput().inversionV2()).isNull();
    }

    @Test
    @DisplayName("with the v2 switch off nothing is fetched or attached")
    void switchOffFetchesNothing() {
        when(augmentor.inversionV2Enabled()).thenReturn(false);

        callNineArg(ELIGIBLE);
        forecastService.fetchWeatherAndTriage(ELIGIBLE, DATE, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null, Map.of(), null, null, Map.of(LOCATION_ID, wholeProfile()));

        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
        ArgumentCaptor<AtmosphericData> captor = ArgumentCaptor.forClass(AtmosphericData.class);
        verify(augmentor, times(2)).augmentWithInversionScore(captor.capture(), any(), anyBoolean());
        assertThat(captor.getAllValues()).allSatisfy(d -> assertThat(d.inversionV2()).isNull());
    }

    @Test
    @DisplayName("a location without a valley floor, or below 200 m, gets no profile even from a full map")
    void ineligibleLocationGetsNoProfile() {
        LocationEntity noFloor = LocationEntity.builder().id(LOCATION_ID).name("No floor").lat(54.568).lon(-3.170)
                .elevationMetres(451).overlooksWater(true).build();

        forecastService.fetchWeatherAndTriage(noFloor, DATE, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null, Map.of(), null, null, Map.of(LOCATION_ID, wholeProfile()));

        assertThat(scoredInput().inversionV2()).isNull();
        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
    }

    @Test
    @DisplayName("inversionV2Enabled() reports the augmentor's switch")
    void reportsTheSwitch() {
        assertThat(forecastService.inversionV2Enabled()).isTrue();
        when(augmentor.inversionV2Enabled()).thenReturn(false);
        assertThat(forecastService.inversionV2Enabled()).isFalse();
    }
}
