package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.client.NlcSightingClient;
import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.model.NlcSightingReport;
import com.gregochr.goldenhour.model.NlcSightingResponse;
import com.gregochr.goldenhour.model.NlcWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Observer-place and region parsing edge cases of {@link NlcSightingService}: a location with no
 * comma, a leading comma, empty tokens and a location made only of country tokens.
 */
@ExtendWith(MockitoExtension.class)
class NlcSightingServiceLocationParsingTest {

    private static final Instant NOW = Instant.parse("2026-06-20T21:00:00Z");
    private static final LocalDate TONIGHT = LocalDate.of(2026, 6, 20);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final NlcWindow EVENING = new NlcWindow("22:46", "23:52", "NW");
    private static final NlcWindow MORNING = new NlcWindow("02:10", "03:18", "NE");

    @Mock
    private NlcSightingClient client;
    @Mock
    private NlcClarityService clarityService;
    @Mock
    private DynamicSchedulerService schedulerService;

    private NlcSightingService service;

    @BeforeEach
    void setUp() {
        service = new NlcSightingService(client, clarityService, new NlcProperties(), schedulerService, CLOCK);
    }

    private NlcSightingResponse sightingFor(String location) {
        when(clarityService.isNlcSeason(TONIGHT)).thenReturn(true);
        when(client.getReports()).thenReturn(List.of(
                new NlcSightingReport("Observer", location, NOW.minusSeconds(600))));
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TONIGHT, 4, 100, List.of("Scotland"), EVENING, MORNING))));
        return service.currentSighting();
    }

    @Test
    @DisplayName("a single-token location is both the observer place and the region")
    void singleToken() {
        NlcSightingResponse response = sightingFor("Elgin");

        assertThat(response.active()).isTrue();
        assertThat(response.observerLocation()).isEqualTo("Elgin");
        assertThat(response.region()).isEqualTo("Elgin");
        assertThat(response.description()).isEqualTo("Noctilucent cloud reported over Elgin");
    }

    @Test
    @DisplayName("a leading comma leaves the observer as the whole trimmed location")
    void leadingComma() {
        NlcSightingResponse response = sightingFor(", Scotland");

        assertThat(response.observerLocation()).isEqualTo(", Scotland");
        assertThat(response.region()).isEqualTo("Scotland");
    }

    @Test
    @DisplayName("a trailing empty token is skipped when choosing the region")
    void trailingEmptyToken() {
        NlcSightingResponse response = sightingFor("Elgin, Scotland, ");

        assertThat(response.observerLocation()).isEqualTo("Elgin");
        assertThat(response.region()).isEqualTo("Scotland");
    }

    @Test
    @DisplayName("a location made only of country tokens falls back to the whole trimmed location")
    void onlyCountryTokens() {
        NlcSightingResponse response = sightingFor("United Kingdom, uk");

        assertThat(response.observerLocation()).isEqualTo("United Kingdom");
        assertThat(response.region()).isEqualTo("United Kingdom, uk");
    }
}
