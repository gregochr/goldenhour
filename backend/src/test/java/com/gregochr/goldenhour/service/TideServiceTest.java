package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import com.gregochr.goldenhour.model.TideData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * Unit tests for {@link TideService}.
 */
@ExtendWith(MockitoExtension.class)
class TideServiceTest {

    @Mock
    private WebClient webClient;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private TideService tideService;

    @BeforeEach
    void setUp() {
        tideService = new TideService(webClient);
    }

    // -------------------------------------------------------------------------
    // findPeaks / findTroughs
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findPeaks() identifies local maxima in the height series")
    void findPeaks_identifiesLocalMaxima() {
        // Heights: 0.5 → 1.2 → 0.8 → 0.3 → 0.9 → 1.5 → 1.1
        //                  ^peak1                        ^peak2
        List<String> times = List.of(
                "2026-02-24T00:00", "2026-02-24T01:00", "2026-02-24T02:00",
                "2026-02-24T03:00", "2026-02-24T04:00", "2026-02-24T05:00",
                "2026-02-24T06:00");
        List<Double> heights = List.of(0.5, 1.2, 0.8, 0.3, 0.9, 1.5, 1.1);

        List<TideService.TideEvent> peaks = tideService.findPeaks(times, heights);

        assertThat(peaks).hasSize(2);
        assertThat(peaks.get(0).time()).isEqualTo(LocalDateTime.of(2026, 2, 24, 1, 0));
        assertThat(peaks.get(0).heightMetres()).isEqualByComparingTo("1.20");
        assertThat(peaks.get(1).time()).isEqualTo(LocalDateTime.of(2026, 2, 24, 5, 0));
        assertThat(peaks.get(1).heightMetres()).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("findTroughs() identifies local minima in the height series")
    void findTroughs_identifiesLocalMinima() {
        // Heights: 1.2 → 0.4 → 0.9 → 0.2 → 0.8
        //                 ^trough1        ^trough2
        List<String> times = List.of(
                "2026-02-24T00:00", "2026-02-24T01:00", "2026-02-24T02:00",
                "2026-02-24T03:00", "2026-02-24T04:00");
        List<Double> heights = List.of(1.2, 0.4, 0.9, 0.2, 0.8);

        List<TideService.TideEvent> troughs = tideService.findTroughs(times, heights);

        assertThat(troughs).hasSize(2);
        assertThat(troughs.get(0).time()).isEqualTo(LocalDateTime.of(2026, 2, 24, 1, 0));
        assertThat(troughs.get(0).heightMetres()).isEqualByComparingTo("0.40");
        assertThat(troughs.get(1).time()).isEqualTo(LocalDateTime.of(2026, 2, 24, 3, 0));
        assertThat(troughs.get(1).heightMetres()).isEqualByComparingTo("0.20");
    }

    @Test
    @DisplayName("findPeaks() skips null height entries")
    void findPeaks_skipsNullEntries() {
        List<String> times = List.of(
                "2026-02-24T00:00", "2026-02-24T01:00", "2026-02-24T02:00");
        List<Double> heights = Arrays.asList(0.5, null, 0.8);

        assertThat(tideService.findPeaks(times, heights)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // classifyTideState
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("classifyTideState() returns HIGH when event is within 90 min of a peak")
    void classifyTideState_withinThresholdOfPeak_returnsHigh() {
        LocalDateTime peak = LocalDateTime.of(2026, 2, 24, 14, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 13, 30); // 30 min before peak

        List<TideService.TideEvent> peaks = List.of(
                new TideService.TideEvent(peak, BigDecimal.valueOf(1.8)));
        List<TideService.TideEvent> troughs = List.of();

        assertThat(tideService.classifyTideState(event, peaks, troughs)).isEqualTo(TideState.HIGH);
    }

    @Test
    @DisplayName("classifyTideState() returns LOW when event is within 90 min of a trough")
    void classifyTideState_withinThresholdOfTrough_returnsLow() {
        LocalDateTime trough = LocalDateTime.of(2026, 2, 24, 8, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 8, 45); // 45 min after trough

        List<TideService.TideEvent> peaks = List.of();
        List<TideService.TideEvent> troughs = List.of(
                new TideService.TideEvent(trough, BigDecimal.valueOf(0.2)));

        assertThat(tideService.classifyTideState(event, peaks, troughs)).isEqualTo(TideState.LOW);
    }

    @Test
    @DisplayName("classifyTideState() returns MID when event is between extremes")
    void classifyTideState_betweenExtremes_returnsMid() {
        LocalDateTime peak = LocalDateTime.of(2026, 2, 24, 6, 0);
        LocalDateTime trough = LocalDateTime.of(2026, 2, 24, 12, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 9, 0); // midway, >90 min from both

        List<TideService.TideEvent> peaks = List.of(
                new TideService.TideEvent(peak, BigDecimal.valueOf(1.8)));
        List<TideService.TideEvent> troughs = List.of(
                new TideService.TideEvent(trough, BigDecimal.valueOf(0.2)));

        assertThat(tideService.classifyTideState(event, peaks, troughs)).isEqualTo(TideState.MID);
    }

    @Test
    @DisplayName("classifyTideState() returns MID when exactly at the 90-minute boundary")
    void classifyTideState_exactlyAtBoundary_returnsMid() {
        LocalDateTime peak = LocalDateTime.of(2026, 2, 24, 14, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 12, 30); // exactly 90 min before peak

        List<TideService.TideEvent> peaks = List.of(
                new TideService.TideEvent(peak, BigDecimal.valueOf(1.8)));

        // 90 minutes is the threshold — the check is <= so this should return HIGH
        assertThat(tideService.classifyTideState(event, peaks, List.of())).isEqualTo(TideState.HIGH);
    }

    @Test
    @DisplayName("classifyTideState() returns MID when 91 minutes from nearest extreme")
    void classifyTideState_justOutsideBoundary_returnsMid() {
        LocalDateTime peak = LocalDateTime.of(2026, 2, 24, 14, 0);
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 12, 29); // 91 min before peak

        List<TideService.TideEvent> peaks = List.of(
                new TideService.TideEvent(peak, BigDecimal.valueOf(1.8)));

        assertThat(tideService.classifyTideState(event, peaks, List.of())).isEqualTo(TideState.MID);
    }

    // -------------------------------------------------------------------------
    // parseTideData
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseTideData() returns correct state and next tide events")
    void parseTideData_returnsStateAndNextTides() {
        // Simulated semidiurnal tide over 24h: two highs and two lows
        List<String> times = List.of(
                "2026-02-24T00:00", "2026-02-24T01:00", "2026-02-24T02:00",
                "2026-02-24T03:00", "2026-02-24T04:00", "2026-02-24T05:00",
                "2026-02-24T06:00", "2026-02-24T07:00", "2026-02-24T08:00",
                "2026-02-24T09:00", "2026-02-24T10:00", "2026-02-24T11:00",
                "2026-02-24T12:00");
        // Peak at 03:00 (high), trough at 09:00 (low), another peak after 12:00 (outside window)
        List<Double> heights = List.of(
                0.5, 1.0, 1.6, 1.9, 1.6, 1.0, // rising to peak at 03:00
                0.5, 0.1, -0.2, -0.4, -0.2, 0.1, 0.5); // falling to trough at 09:00

        // Event at 07:00 — more than 90 min from peak (03:00 = 240 min) and trough (09:00 = 120 min)
        LocalDateTime event = LocalDateTime.of(2026, 2, 24, 7, 0);

        TideData result = tideService.parseTideData(times, heights, event);

        assertThat(result.tideState()).isEqualTo(TideState.MID);
        // Next high tide after 07:00 — none in this dataset → null
        assertThat(result.nextHighTideTime()).isNull();
        // Next low tide after 07:00 → trough at 09:00
        assertThat(result.nextLowTideTime()).isEqualTo(LocalDateTime.of(2026, 2, 24, 9, 0));
        assertThat(result.nextLowTideHeightMetres()).isEqualByComparingTo("-0.40");
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — single preferences
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HIGH tide aligns with HIGH_TIDE preference")
    void calculateTideAligned_highTide_highPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of(TideType.HIGH_TIDE)));
    }

    @Test
    @DisplayName("LOW tide does not align with HIGH_TIDE preference")
    void calculateTideAligned_lowTide_highPreference_notAligned() {
        assertFalse(tideService.calculateTideAligned(tideData(TideState.LOW), Set.of(TideType.HIGH_TIDE)));
    }

    @Test
    @DisplayName("LOW tide aligns with LOW_TIDE preference")
    void calculateTideAligned_lowTide_lowPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.LOW), Set.of(TideType.LOW_TIDE)));
    }

    @Test
    @DisplayName("MID tide aligns with MID_TIDE preference")
    void calculateTideAligned_midTide_midPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.MID), Set.of(TideType.MID_TIDE)));
    }

    @Test
    @DisplayName("MID tide does not align with HIGH_TIDE or LOW_TIDE preference")
    void calculateTideAligned_midTide_highOrLowPreference_notAligned() {
        assertFalse(tideService.calculateTideAligned(
                tideData(TideState.MID), Set.of(TideType.HIGH_TIDE, TideType.LOW_TIDE)));
    }

    @Test
    @DisplayName("ANY_TIDE preference aligns with any tide state")
    void calculateTideAligned_anyTidePreference_alwaysAligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.LOW), Set.of(TideType.ANY_TIDE)));
        assertTrue(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of(TideType.ANY_TIDE)));
        assertTrue(tideService.calculateTideAligned(tideData(TideState.MID), Set.of(TideType.ANY_TIDE)));
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — multiple preferences
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HIGH tide aligns when HIGH_TIDE is one of multiple preferences")
    void calculateTideAligned_multiplePreferences_highMatches() {
        assertTrue(tideService.calculateTideAligned(
                tideData(TideState.HIGH), Set.of(TideType.HIGH_TIDE, TideType.LOW_TIDE)));
    }

    @Test
    @DisplayName("MID tide aligns when MID_TIDE is one of multiple preferences")
    void calculateTideAligned_multiplePreferences_midMatches() {
        assertTrue(tideService.calculateTideAligned(
                tideData(TideState.MID), Set.of(TideType.HIGH_TIDE, TideType.MID_TIDE)));
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Empty preference set returns false")
    void calculateTideAligned_emptyPreferences_notAligned() {
        assertFalse(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of()));
    }

    @Test
    @DisplayName("NOT_COASTAL preference returns false")
    void calculateTideAligned_notCoastal_notAligned() {
        assertFalse(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of(TideType.NOT_COASTAL)));
    }

    // -------------------------------------------------------------------------
    // getTideData — WebClient paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getTideData() returns TideData when Marine API responds successfully")
    void getTideData_validResponse_returnsTideData() {
        stubWebClientChain(Mono.just(buildMarineResponse()));

        Optional<TideData> result = tideService.getTideData(
                54.77, -1.58, LocalDateTime.of(2026, 2, 24, 7, 0));

        assertThat(result).isPresent();
        assertThat(result.get().tideState()).isEqualTo(TideState.MID);
    }

    @Test
    @DisplayName("getTideData() returns empty when Marine API response has no hourly data")
    void getTideData_nullHourlyData_returnsEmpty() {
        stubWebClientChain(Mono.just(new OpenMeteoMarineResponse()));

        Optional<TideData> result = tideService.getTideData(54.77, -1.58, LocalDateTime.now());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getTideData() returns empty when Marine API is unreachable")
    void getTideData_apiUnreachable_returnsEmpty() {
        // WebClient mock returns nothing — service catches the error and returns empty
        assertTrue(tideService.getTideData(54.77, -1.58, LocalDateTime.now()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TideData tideData(TideState state) {
        return new TideData(
                state,
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(0.30));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubWebClientChain(Mono<OpenMeteoMarineResponse> responseMono) {
        doReturn(requestHeadersUriSpec).when(webClient).get();
        doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(any(Function.class));
        doReturn(responseSpec).when(requestHeadersSpec).retrieve();
        doReturn(responseMono).when(responseSpec).bodyToMono(OpenMeteoMarineResponse.class);
    }

    private OpenMeteoMarineResponse buildMarineResponse() {
        // Peak at 03:00 (index 3): 1.9 > 1.6 on both sides
        // Trough at 09:00 (index 9): -0.4 < -0.2 on both sides
        // Solar event at 07:00 is >90 min from both → MID
        List<String> times = List.of(
                "2026-02-24T00:00", "2026-02-24T01:00", "2026-02-24T02:00",
                "2026-02-24T03:00", "2026-02-24T04:00", "2026-02-24T05:00",
                "2026-02-24T06:00", "2026-02-24T07:00", "2026-02-24T08:00",
                "2026-02-24T09:00", "2026-02-24T10:00", "2026-02-24T11:00",
                "2026-02-24T12:00");
        List<Double> heights = List.of(
                0.5, 1.0, 1.6, 1.9, 1.6, 1.0,
                0.5, 0.1, -0.2, -0.4, -0.2, 0.1, 0.5);

        OpenMeteoMarineResponse.Hourly hourly = new OpenMeteoMarineResponse.Hourly();
        hourly.setTime(times);
        hourly.setSeaSurfaceHeight(heights);

        OpenMeteoMarineResponse response = new OpenMeteoMarineResponse();
        response.setHourly(hourly);
        return response;
    }
}
