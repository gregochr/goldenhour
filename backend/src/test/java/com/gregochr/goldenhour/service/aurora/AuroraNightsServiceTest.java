package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraNightsResponse;
import com.gregochr.goldenhour.model.CurrentNight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AuroraNightsService}: which nights a Pro reader would be offered an aurora row for.
 * The current-night rule itself (pre-dawn, BST) is owned and tested by {@link AuroraForecastRunService};
 * here {@code currentNight()} is stubbed and the filtering and live-night rules are pinned.
 */
@ExtendWith(MockitoExtension.class)
class AuroraNightsServiceTest {

    private static final LocalDate TONIGHT = LocalDate.of(2026, 10, 25);
    private static final Instant ENDS_AT = Instant.parse("2026-10-26T05:41:00Z");
    private static final AuroraStateCache.SimulatedNoaaData SIMULATION =
            new AuroraStateCache.SimulatedNoaaData(7.0, 45.0, -12.0, "G3");

    @Mock
    private AuroraForecastRunService forecastRunService;

    @Mock
    private AuroraStateCache stateCache;

    private AuroraNightsService service;

    @BeforeEach
    void setUp() {
        service = new AuroraNightsService(forecastRunService, stateCache);
        when(forecastRunService.currentNight()).thenReturn(new CurrentNight(TONIGHT, ENDS_AT));
    }

    @Test
    @DisplayName("Stored nights before the current night are dropped; the current night and later are kept")
    void storedNights_beforeCurrentNight_dropped() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of("2026-10-23", "2026-10-24",
                "2026-10-25", "2026-10-26"));

        AuroraNightsResponse response = service.offeredNights();

        assertThat(response.nights()).containsExactly("2026-10-25", "2026-10-26");
    }

    @Test
    @DisplayName("Pre-dawn, when the current night is yesterday's date, yesterday's stored night is kept")
    void preDawn_yesterdaysNightKept() {
        LocalDate yesterday = TONIGHT.minusDays(1);
        when(forecastRunService.currentNight()).thenReturn(new CurrentNight(yesterday, ENDS_AT));
        when(forecastRunService.getAvailableDates()).thenReturn(List.of("2026-10-23", "2026-10-24",
                "2026-10-25"));

        assertThat(service.offeredNights().nights()).containsExactly("2026-10-24", "2026-10-25");
    }

    @Test
    @DisplayName("A live MODERATE alert adds the current night when nothing is stored")
    void liveModerate_addsCurrentNight() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of());
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(stateCache.isActive()).thenReturn(true);

        assertThat(service.offeredNights().nights()).containsExactly("2026-10-25");
    }

    @Test
    @DisplayName("A live STRONG alert adds the current night")
    void liveStrong_addsCurrentNight() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of());
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.STRONG);
        when(stateCache.isActive()).thenReturn(true);

        assertThat(service.offeredNights().nights()).containsExactly("2026-10-25");
    }

    @Test
    @DisplayName("A live MINOR alert adds nothing")
    void liveMinor_addsNothing() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of());
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.MINOR);
        when(stateCache.isActive()).thenReturn(true);

        assertThat(service.offeredNights().nights()).isEmpty();
    }

    @Test
    @DisplayName("A null level (QUIET) adds nothing")
    void nullLevel_addsNothing() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of());
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(null);
        when(stateCache.isActive()).thenReturn(true);

        assertThat(service.offeredNights().nights()).isEmpty();
    }

    @Test
    @DisplayName("A MODERATE level that is not active adds nothing")
    void inactiveModerate_addsNothing() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of());
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(stateCache.isActive()).thenReturn(false);

        assertThat(service.offeredNights().nights()).isEmpty();
    }

    @Test
    @DisplayName("An active STRONG simulation adds nothing, and the level is never consulted")
    void simulation_addsNothing() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of());
        when(stateCache.getSimulatedData()).thenReturn(SIMULATION);

        assertThat(service.offeredNights().nights()).isEmpty();

        verify(stateCache).getSimulatedData();
        verifyNoMoreInteractions(stateCache);
    }

    @Test
    @DisplayName("A simulation does not remove a genuinely stored night")
    void simulation_keepsStoredNights() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of("2026-10-26"));
        when(stateCache.getSimulatedData()).thenReturn(SIMULATION);

        assertThat(service.offeredNights().nights()).containsExactly("2026-10-26");
    }

    @Test
    @DisplayName("A live night that is also stored appears once")
    void liveNightAlreadyStored_appearsOnce() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of("2026-10-25"));
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.STRONG);
        when(stateCache.isActive()).thenReturn(true);

        assertThat(service.offeredNights().nights()).containsExactly("2026-10-25");
    }

    @Test
    @DisplayName("The result is sorted by date even when the stored list is not, with the live night merged in")
    void result_isSortedAscending() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of("2026-10-27", "2026-10-26"));
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(stateCache.isActive()).thenReturn(true);

        assertThat(service.offeredNights().nights())
                .containsExactly("2026-10-25", "2026-10-26", "2026-10-27");
    }

    @Test
    @DisplayName("currentNightEndsAt is the end of the same currentNight() the list was filtered against")
    void currentNightEndsAt_isTheCurrentNightEnd() {
        when(forecastRunService.getAvailableDates()).thenReturn(List.of());
        when(stateCache.getSimulatedData()).thenReturn(null);

        assertThat(service.offeredNights().currentNightEndsAt()).isEqualTo(ENDS_AT);
        verify(forecastRunService).currentNight();
    }
}
