package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.AstroConditionsService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.warnings.WarningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.NOW;
import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static com.gregochr.goldenhour.service.ask.AskFixtures.clockAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@link AskSnapshotBuilder} reads from {@link WarningService} (weather-warnings-plan.md section 14, W6): the
 * served view only, nothing derived, a feature that is off knows nothing, and a failed read fails closed.
 */
class AskSnapshotBuilderWarningsTest {

    private static final String URL = "https://www.metoffice.gov.uk/public/weather/warnings/";
    private static final String ATTRIBUTION = "Met Office Weather Warnings © Crown Copyright 2026";

    private final BriefingService briefingService = mock(BriefingService.class);
    private final WarningService warningService = mock(WarningService.class);
    private final TravelDayService travelDays = mock(TravelDayService.class);
    private final AlmanacService almanacService = mock(AlmanacService.class);
    private final SolarEventFreshness freshness = mock(SolarEventFreshness.class);
    private AskSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        when(briefingService.getCachedBriefingForApi()).thenReturn(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, AskFixtures.region("Coast", true,
                        AskFixtures.slot(1L, "Whitby", 4)))), List.of()));
        when(freshness.now()).thenReturn(NOW);
        when(almanacService.getFeed(AlmanacService.DEFAULT_DAYS))
                .thenReturn(new ComingUpResponse(TODAY, null, null, new ArrayList<>()));
        builder = new AskSnapshotBuilder(briefingService, travelDays, almanacService, freshness, clockAt(NOW),
                mock(AstroConditionsRepository.class), mock(AuroraForecastResultRepository.class),
                mock(AstroConditionsService.class), mock(AuroraForecastRunService.class), new AuroraStateCache(),
                () -> AskAreas.Names.NONE, mock(LocationRepository.class), warningService);
    }

    private static WarningDto dto(String id, boolean simulated) {
        return new WarningDto(id, "2.0", "AMBER", List.of("SNOW"), 3, 3, Instant.parse("2026-10-05T06:00:00Z"),
                Instant.parse("2026-10-06T18:00:00Z"), null, null, "Snow, heavy at times", List.of("Heavy snow."),
                null, null, null, true, List.of("NE"), null, null, null, Set.of(1L), simulated);
    }

    @Test
    @DisplayName("the feature off: the snapshot knows nothing of warnings (the shape before W6)")
    void featureOff_isNone() {
        when(warningService.served()).thenReturn(new WarningsResponse(false, null, true, List.of(), URL,
                ATTRIBUTION));

        assertThat(builder.build().orElseThrow().warnings()).isEqualTo(AskSnapshot.Warnings.NONE);
    }

    @Test
    @DisplayName("the served warnings are carried verbatim: level, types, validity, headline, update flag, places")
    void servedWarnings_areCarriedVerbatim() {
        when(warningService.served()).thenReturn(new WarningsResponse(true, Instant.parse("2026-10-05T11:55:00Z"),
                false, List.of(dto("W1", false)), URL, ATTRIBUTION));

        AskSnapshot.Warnings warnings = builder.build().orElseThrow().warnings();

        assertThat(warnings.enabled()).isTrue();
        assertThat(warnings.stale()).isFalse();
        assertThat(warnings.url()).isEqualTo(URL);
        assertThat(warnings.attribution()).isEqualTo(ATTRIBUTION);
        assertThat(warnings.checkedAt()).isEqualTo(Instant.parse("2026-10-05T11:55:00Z"));
        assertThat(warnings.items()).singleElement().satisfies(w -> {
            assertThat(w.id()).isEqualTo("W1");
            assertThat(w.version()).isEqualTo("2.0");
            assertThat(w.level()).isEqualTo("AMBER");
            assertThat(w.types()).containsExactly("SNOW");
            assertThat(w.validFrom()).isEqualTo(Instant.parse("2026-10-05T06:00:00Z"));
            assertThat(w.validTo()).isEqualTo(Instant.parse("2026-10-06T18:00:00Z"));
            assertThat(w.headline()).isEqualTo("Snow, heavy at times");
            assertThat(w.whatToExpect()).containsExactly("Heavy snow.");
            assertThat(w.updated()).isTrue();
            assertThat(w.locationIds()).containsExactly(1L);
        });
    }

    @Test
    @DisplayName("a stale feed is carried as stale, and a simulated or id-less row never reaches Ask")
    void staleFeed_andNoSimulatedRows() {
        WarningDto idless = new WarningDto(null, null, "RED", List.of(), null, null, null, null, null, null, null,
                List.of(), null, null, null, false, List.of(), null, null, null, Set.of(), false);
        when(warningService.served()).thenReturn(new WarningsResponse(true, null, true,
                java.util.Arrays.asList(dto("SIM-1", true), idless, null, dto("W2", false)), URL, ATTRIBUTION));

        AskSnapshot.Warnings warnings = builder.build().orElseThrow().warnings();

        assertThat(warnings.stale()).isTrue();
        assertThat(warnings.items()).extracting(AskSnapshot.Warning::id).containsExactly("W2");
    }

    @Test
    @DisplayName("a response with no warning list is an enabled, empty set")
    void nullWarningList_isEmpty() {
        when(warningService.served()).thenReturn(new WarningsResponse(true, null, false, null, URL, ATTRIBUTION));

        AskSnapshot.Warnings warnings = builder.build().orElseThrow().warnings();

        assertThat(warnings.enabled()).isTrue();
        assertThat(warnings.items()).isEmpty();
    }

    @Test
    @DisplayName("a failed read fails closed: enabled, stale and empty, so no claim is ever read as 'none'")
    void failedRead_failsClosed() {
        when(warningService.served()).thenThrow(new IllegalStateException("database down"));

        AskSnapshot.Warnings warnings = builder.build().orElseThrow().warnings();

        assertThat(warnings.enabled()).isTrue();
        assertThat(warnings.stale()).isTrue();
        assertThat(warnings.items()).isEmpty();
    }

    @Test
    @DisplayName("a builder with no warning service (every pre-W6 caller) knows nothing of warnings")
    void noWarningService_isNone() {
        AskSnapshotBuilder legacy = new AskSnapshotBuilder(briefingService, travelDays, almanacService, freshness,
                clockAt(NOW),
                mock(AstroConditionsRepository.class), mock(AuroraForecastResultRepository.class),
                mock(AstroConditionsService.class), mock(AuroraForecastRunService.class), new AuroraStateCache(),
                () -> AskAreas.Names.NONE, mock(LocationRepository.class));

        assertThat(legacy.build().orElseThrow().warnings()).isEqualTo(AskSnapshot.Warnings.NONE);
    }
}
