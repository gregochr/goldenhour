package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraAlertResponse;
import com.gregochr.goldenhour.model.CurrentNight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AuroraAlertService}: the role-free alert behind the Lite banner.
 */
@ExtendWith(MockitoExtension.class)
class AuroraAlertServiceTest {

    private static final LocalDate TONIGHT = LocalDate.of(2026, 10, 25);
    private static final Instant ENDS_AT = Instant.parse("2026-10-26T05:41:00Z");
    private static final int DARK_SKY_SITES = 4;
    private static final AuroraStateCache.SimulatedNoaaData SIMULATION =
            new AuroraStateCache.SimulatedNoaaData(7.0, 45.0, -12.0, "G3");

    @Mock
    private AuroraForecastRunService forecastRunService;

    @Mock
    private AuroraStateCache stateCache;

    private AuroraAlertService service;

    @BeforeEach
    void setUp() {
        service = new AuroraAlertService(forecastRunService, stateCache);
    }

    private void live(AlertLevel level) {
        when(forecastRunService.currentNight()).thenReturn(new CurrentNight(TONIGHT, ENDS_AT));
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(level);
        when(stateCache.isActive()).thenReturn(true);
    }

    @Test
    @DisplayName("A null level is QUIET")
    void nullLevel_isQuiet() {
        live(null);

        AuroraAlertResponse response = service.liteAlert();

        assertThat(response.level()).isEqualTo(AlertLevel.QUIET);
    }

    @Test
    @DisplayName("An active STRONG simulation answers QUIET, inactive, no trigger, not overcast")
    void simulation_answersQuiet() {
        when(forecastRunService.currentNight()).thenReturn(new CurrentNight(TONIGHT, ENDS_AT));
        when(stateCache.getSimulatedData()).thenReturn(SIMULATION);

        AuroraAlertResponse response = service.liteAlert();

        assertThat(response.level()).isEqualTo(AlertLevel.QUIET);
        assertThat(response.active()).isFalse();
        assertThat(response.triggerType()).isNull();
        assertThat(response.allOvercast()).isFalse();
        assertThat(response.currentNightDate()).isEqualTo(TONIGHT);
        verify(stateCache).getSimulatedData();
        verifyNoMoreInteractions(stateCache);
    }

    @Test
    @DisplayName("allOvercast is true when there are dark-sky sites and none is clear")
    void allOvercast_noClearSites_true() {
        live(AlertLevel.MODERATE);
        when(stateCache.getDarkSkyLocationCount()).thenReturn(DARK_SKY_SITES);
        when(stateCache.getClearLocationCount()).thenReturn(0);

        assertThat(service.liteAlert().allOvercast()).isTrue();
    }

    @Test
    @DisplayName("allOvercast is false when some dark-sky site is clear")
    void allOvercast_someClear_false() {
        live(AlertLevel.MODERATE);
        when(stateCache.getDarkSkyLocationCount()).thenReturn(DARK_SKY_SITES);
        when(stateCache.getClearLocationCount()).thenReturn(1);

        assertThat(service.liteAlert().allOvercast()).isFalse();
    }

    @Test
    @DisplayName("allOvercast is false when the clear count is unknown")
    void allOvercast_unknownClear_false() {
        live(AlertLevel.MODERATE);
        when(stateCache.getDarkSkyLocationCount()).thenReturn(DARK_SKY_SITES);
        when(stateCache.getClearLocationCount()).thenReturn(null);

        assertThat(service.liteAlert().allOvercast()).isFalse();
    }

    @Test
    @DisplayName("allOvercast is false when there are no dark-sky sites")
    void allOvercast_noDarkSkySites_false() {
        live(AlertLevel.MODERATE);
        when(stateCache.getDarkSkyLocationCount()).thenReturn(0);
        when(stateCache.getClearLocationCount()).thenReturn(0);

        assertThat(service.liteAlert().allOvercast()).isFalse();
    }

    @Test
    @DisplayName("Trigger type maps as /status does: forecast lookahead, realtime, none")
    void triggerType_mapsLikeStatus() {
        live(AlertLevel.MODERATE);
        when(stateCache.getLastTriggerType()).thenReturn(TriggerType.FORECAST_LOOKAHEAD, TriggerType.REALTIME,
                (TriggerType) null);

        assertThat(service.liteAlert().triggerType()).isEqualTo("forecast");
        assertThat(service.liteAlert().triggerType()).isEqualTo("realtime");
        assertThat(service.liteAlert().triggerType()).isNull();
    }

    @Test
    @DisplayName("The night fields come from a single currentNight() call")
    void nightFields_fromOneCall() {
        live(AlertLevel.STRONG);

        AuroraAlertResponse response = service.liteAlert();

        assertThat(response.level()).isEqualTo(AlertLevel.STRONG);
        assertThat(response.active()).isTrue();
        assertThat(response.currentNightDate()).isEqualTo(TONIGHT);
        assertThat(response.currentNightEndsAt()).isEqualTo(ENDS_AT);
        verify(forecastRunService, times(1)).currentNight();
    }

    @Test
    @DisplayName("A null night (stubbed service) is relayed as null fields")
    void nullNight_relayedAsNulls() {
        when(forecastRunService.currentNight()).thenReturn(null);
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(stateCache.isActive()).thenReturn(true);

        AuroraAlertResponse response = service.liteAlert();

        assertThat(response.currentNightDate()).isNull();
        assertThat(response.currentNightEndsAt()).isNull();
    }
}
