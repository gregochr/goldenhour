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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How {@link ForecastService#fetchWeatherAndTriage} gets a v2 valley profile onto a slot
 * (inversion-v2-plan Phase 1b): a prefetched map is keyed by LOCATION ID, a null map means "fetch on demand",
 * a map without the key means "no profile and no on-demand fallback", and no profile failure ever fails the
 * slot or skips the mist step that follows. Each test stubs only what its path uses.
 */
@ExtendWith(MockitoExtension.class)
class ForecastServiceInversionProfileTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 12);
    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 8, 12, 4, 40);
    private static final long LOCATION_ID = 5L;
    private static final double LAT = 54.568;
    private static final double LON = -3.170;
    private static final int AZIMUTH = 65;

    private static final LocationEntity ELIGIBLE = LocationEntity.builder()
            .id(LOCATION_ID).name("Cat Bells").lat(LAT).lon(LON)
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

    /** The weather data every extraction carries; the augmentations up to the inversion step pass it through. */
    private static final AtmosphericData BASE = TestAtmosphericData.builder().targetType(TargetType.SUNRISE)
            .solarEventTime(SUNRISE).build();

    /** The raw forecast response every extraction carries (the storm surge step receives it). */
    private static final OpenMeteoForecastResponse RESPONSE = new OpenMeteoForecastResponse();

    private static WeatherExtractionResult extraction() {
        return new WeatherExtractionResult(BASE, RESPONSE);
    }

    private static ForecastRequest request() {
        return new ForecastRequest(LAT, LON, "Cat Bells", DATE, TargetType.SUNRISE);
    }

    /** Stubs the sunrise and its azimuth for the location on {@code date}. */
    private void stubSolar(LocalDate date, LocalDateTime sunrise) {
        when(solarService.sunriseUtc(eq(LAT), eq(LON), eq(date))).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(eq(LAT), eq(LON), eq(date))).thenReturn(AZIMUTH);
    }

    /** Weather arrives from the prefetched map (the nine- and ten-argument forms with a weather map). */
    private void stubCachedWeather(LocalDateTime sunrise) {
        when(openMeteoService.getAtmosphericDataFromCache(eq(request()), eq(sunrise), eq(Map.of())))
                .thenReturn(extraction());
    }

    /** Weather is fetched individually (the six-argument form). */
    private void stubFetchedWeather(LocalDateTime sunrise) {
        when(openMeteoService.getAtmosphericDataWithResponse(eq(request()), eq(sunrise), isNull()))
                .thenReturn(extraction());
    }

    /**
     * Pins every pass-through augmentation to the values this location and slot fix. The inversion step and
     * the two after it (mist, bluebell) receive data that may or may not carry a v2 carrier, so their data
     * argument stays open and the tests inspect it with captors instead; the cloud-approach step also takes
     * the wall-clock time, which a test cannot know.
     */
    private void stubAugmentationChain(LocalDate date, LocalDateTime sunrise, int elevation) {
        when(augmentor.augmentWithDirectionalCloud(eq(BASE), eq(LAT), eq(LON), eq(AZIMUTH), eq(sunrise), isNull(),
                isNull())).then(returnsFirstArg());
        when(augmentor.augmentWithCloudApproach(eq(BASE), eq(LAT), eq(LON), eq(AZIMUTH), eq(sunrise), any(),
                isNull(), isNull())).then(returnsFirstArg());
        when(augmentor.augmentWithTideData(eq(BASE), eq(LOCATION_ID), eq(sunrise), eq(Set.of()), eq(LAT), eq(LON),
                eq(TargetType.SUNRISE), isNull())).then(returnsFirstArg());
        when(augmentor.augmentWithLocationOrientation(eq(BASE), eq(ELIGIBLE.getSolarEventType())))
                .then(returnsFirstArg());
        when(augmentor.augmentWithStormSurge(eq(BASE), any(), eq(LOCATION_ID), eq("Cat Bells"), same(RESPONSE)))
                .then(returnsFirstArg());
        when(augmentor.augmentWithInversionScore(any(), eq(elevation), eq(true))).then(returnsFirstArg());
        when(augmentor.augmentWithBluebellConditions(any(), eq(ELIGIBLE.getLocationType()),
                eq(ELIGIBLE.getBluebellExposure()), eq(date))).then(returnsFirstArg());
        when(augmentor.augmentWithValleyMist(any(), eq(false))).then(returnsFirstArg());
    }

    /** A whole-day profile of the valley cell on {@link #DATE}, 80 m floor. */
    private static InversionProfile wholeProfile() {
        OpenMeteoForecastResponse r = new OpenMeteoForecastResponse();
        r.setElevation(80.0);
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        List<String> times = new ArrayList<>();
        List<Double> temps = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            times.add(DATE.atTime(hour, 0).toString());
            temps.add(8.0);
        }
        h.setTime(times);
        h.setTemperature2m(temps);
        r.setHourly(h);
        return InversionProfile.from(r, 80);
    }

    private AtmosphericData scoredInput(int elevation) {
        ArgumentCaptor<AtmosphericData> captor = ArgumentCaptor.forClass(AtmosphericData.class);
        verify(augmentor).augmentWithInversionScore(captor.capture(), eq(elevation), eq(true));
        return captor.getValue();
    }

    private ForecastPreEvalResult nineArg(LocationEntity location) {
        return forecastService.fetchWeatherAndTriage(location, DATE, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null, Map.of(), null, null);
    }

    private ForecastPreEvalResult tenArg(LocationEntity location, Map<Long, InversionProfile> profiles) {
        return forecastService.fetchWeatherAndTriage(location, DATE, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null, Map.of(), null, null, profiles);
    }

    private void stubPath() {
        stubPath(451);
    }

    private void stubPath(int elevation) {
        stubSolar(DATE, SUNRISE);
        stubCachedWeather(SUNRISE);
        stubAugmentationChain(DATE, SUNRISE, elevation);
    }

    @Test
    @DisplayName("a prefetched map is keyed by location id: the profile is sliced to the event and attached")
    void prefetchedProfileIsAttached() {
        stubPath();
        when(augmentor.inversionV2Enabled()).thenReturn(true);

        ForecastPreEvalResult result = tenArg(ELIGIBLE, Map.of(LOCATION_ID, wholeProfile()));

        AtmosphericData scored = scoredInput(451);
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
        stubPath();
        when(augmentor.inversionV2Enabled()).thenReturn(true);

        ForecastPreEvalResult result = tenArg(ELIGIBLE, Map.of(99L, wholeProfile()));

        assertThat(result.triaged()).isFalse();
        assertThat(scoredInput(451).inversionV2()).isNull();
        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
    }

    @Test
    @DisplayName("an empty prefetched map (the prefetch failed) is no profile, never one request per slot")
    void emptyMapMeansNoProfile() {
        stubPath();
        when(augmentor.inversionV2Enabled()).thenReturn(true);

        tenArg(ELIGIBLE, Map.of());

        assertThat(scoredInput(451).inversionV2()).isNull();
        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
    }

    @Test
    @DisplayName("the nine-argument and six-argument forms (no prefetch) fetch on demand")
    void noPrefetchFetchesOnDemand() {
        stubSolar(DATE, SUNRISE);
        stubCachedWeather(SUNRISE);
        stubFetchedWeather(SUNRISE);
        stubAugmentationChain(DATE, SUNRISE, 451);
        when(augmentor.inversionV2Enabled()).thenReturn(true);
        when(openMeteoService.fetchInversionProfileOnDemand(ELIGIBLE)).thenReturn(wholeProfile());

        nineArg(ELIGIBLE);
        forecastService.fetchWeatherAndTriage(ELIGIBLE, DATE, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null);

        verify(openMeteoService, times(2)).fetchInversionProfileOnDemand(ELIGIBLE);
        ArgumentCaptor<AtmosphericData> captor = ArgumentCaptor.forClass(AtmosphericData.class);
        verify(augmentor, times(2)).augmentWithInversionScore(captor.capture(), eq(451), eq(true));
        assertThat(captor.getAllValues()).allSatisfy(d -> assertThat(d.inversionV2()).isNotNull());
    }

    @Test
    @DisplayName("a profile failure on demand never fails the slot and mist is still scored")
    void onDemandFailureDoesNotFailTheSlot() {
        stubPath();
        when(augmentor.inversionV2Enabled()).thenReturn(true);
        when(openMeteoService.fetchInversionProfileOnDemand(ELIGIBLE)).thenThrow(new RuntimeException("429"));

        ForecastPreEvalResult result = nineArg(ELIGIBLE);

        assertThat(result.triaged()).isFalse();
        assertThat(scoredInput(451).inversionV2()).isNull();
        verify(augmentor).augmentWithValleyMist(any(), eq(false));
        ArgumentCaptor<AtmosphericData> written = ArgumentCaptor.forClass(AtmosphericData.class);
        verify(slotAtmosphereWriter).write(eq(ELIGIBLE), eq(DATE), eq(TargetType.SUNRISE), written.capture());
        assertThat(written.getValue().inversionV2()).isNull();
    }

    @Test
    @DisplayName("a profile that does not cover the event is no profile")
    void profileNotCoveringTheEvent() {
        LocalDate september = LocalDate.of(2026, 9, 30);
        LocalDateTime septemberSunrise = LocalDateTime.of(2026, 9, 30, 6, 0);
        stubSolar(september, septemberSunrise);
        when(openMeteoService.getAtmosphericDataFromCache(
                eq(new ForecastRequest(LAT, LON, "Cat Bells", september, TargetType.SUNRISE)),
                eq(septemberSunrise), eq(Map.of()))).thenReturn(extraction());
        stubAugmentationChain(september, septemberSunrise, 451);
        when(augmentor.inversionV2Enabled()).thenReturn(true);

        forecastService.fetchWeatherAndTriage(ELIGIBLE, september, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null, Map.of(), null, null, Map.of(LOCATION_ID, wholeProfile()));

        assertThat(scoredInput(451).inversionV2()).isNull();
    }

    @Test
    @DisplayName("with the v2 switch off nothing is fetched or attached")
    void switchOffFetchesNothing() {
        stubPath();
        when(augmentor.inversionV2Enabled()).thenReturn(false);

        nineArg(ELIGIBLE);
        tenArg(ELIGIBLE, Map.of(LOCATION_ID, wholeProfile()));

        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
        ArgumentCaptor<AtmosphericData> captor = ArgumentCaptor.forClass(AtmosphericData.class);
        verify(augmentor, times(2)).augmentWithInversionScore(captor.capture(), eq(451), eq(true));
        assertThat(captor.getAllValues()).allSatisfy(d -> assertThat(d.inversionV2()).isNull());
    }

    @Test
    @DisplayName("a location without a valley floor gets no profile even from a full map")
    void noFloorGetsNoProfile() {
        LocationEntity noFloor = LocationEntity.builder().id(LOCATION_ID).name("Cat Bells").lat(LAT).lon(LON)
                .elevationMetres(451).overlooksWater(true).build();
        stubPath();

        tenArg(noFloor, Map.of(LOCATION_ID, wholeProfile()));

        assertThat(scoredInput(451).inversionV2()).isNull();
        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
    }

    @Test
    @DisplayName("a location at 199 m gets no profile even with a floor and a full map")
    void below200MetresGetsNoProfile() {
        LocationEntity low = LocationEntity.builder().id(LOCATION_ID).name("Cat Bells").lat(LAT).lon(LON)
                .elevationMetres(199).overlooksWater(true)
                .valleyFloorMetres(80).valleyFloorLat(54.575).valleyFloorLng(-3.152).build();
        stubPath(199);

        tenArg(low, Map.of(LOCATION_ID, wholeProfile()));

        assertThat(scoredInput(199).inversionV2()).isNull();
        verify(openMeteoService, never()).fetchInversionProfileOnDemand(any());
    }

    /** The switch accessor needs none of the fetch path's stubs, so it lives in its own nested class. */
    @Nested
    class Switch {

        @Test
        @DisplayName("inversionV2Enabled() reports the augmentor's switch, both ways")
        void reportsTheSwitch() {
            when(augmentor.inversionV2Enabled()).thenReturn(true, false);

            assertThat(forecastService.inversionV2Enabled()).isTrue();
            assertThat(forecastService.inversionV2Enabled()).isFalse();
        }
    }
}
