package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.BriefingWindow.WarningPlaceCount;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WarningOverlay}: windows and places carry the ids and counts, and nothing else moves.
 */
@ExtendWith(MockitoExtension.class)
class WarningOverlayTest {

    private static final Instant NOW = Instant.parse("2026-10-08T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime EVENT = LocalDateTime.of(2026, 10, 11, 6, 0);
    private static final String BOX = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-3,54],[-2,54],[-2,55],[-3,55],"
            + "[-3,54]]]]}";

    @Mock
    private WeatherWarningVersionRepository repository;
    @Mock
    private LocationRepository locations;
    @Mock
    private SolarEventFreshness solarEventFreshness;

    private WarningsProperties properties;
    private WarningOverlay overlay;
    private LocationEntity hawes;
    private LocationEntity askrigg;
    private LocationEntity bamburgh;

    @BeforeEach
    void setUp() {
        properties = new WarningsProperties();
        properties.setEnabled(true);
        overlay = new WarningOverlay(repository, new WarningTimeline(CLOCK), new WarningCoverage(), locations,
                properties, CLOCK, solarEventFreshness);
        RegionEntity dales = RegionEntity.builder().id(1L).name("Dales").build();
        RegionEntity coast = RegionEntity.builder().id(2L).name("Northumberland").build();
        hawes = place(1L, "Hawes", dales, 54.3, -2.2);
        askrigg = place(2L, "Askrigg", dales, 54.0, -1.0);
        bamburgh = place(3L, "Bamburgh", coast, 55.6, -1.7);
    }

    private static LocationEntity place(long id, String name, RegionEntity region, double lat, double lon) {
        LocationEntity l = new LocationEntity();
        l.setId(id);
        l.setName(name);
        l.setRegion(region);
        l.setLat(lat);
        l.setLon(lon);
        return l;
    }

    private static WeatherWarningVersionEntity warning(String id, String validFrom, String validTo,
            boolean simulated) {
        WeatherWarningVersionEntity w = new WeatherWarningVersionEntity();
        w.setWarningId(id);
        w.setWarningVersion(1);
        w.setStatus(WeatherWarningVersionEntity.STATUS_ACTIVE);
        w.setWarningLevel("Amber");
        w.setGeometry(BOX);
        w.setSimulated(simulated);
        w.setReceivedAt(Instant.parse("2026-10-08T06:00:00Z"));
        w.setValidFrom(validFrom == null ? null : Instant.parse(validFrom));
        w.setValidTo(Instant.parse(validTo));
        return w;
    }

    private static DailyBriefingResponse briefing(BriefingWindow window, String... regionNames) {
        List<com.gregochr.goldenhour.model.BriefingRegion> regions = new java.util.ArrayList<>();
        for (String name : regionNames) {
            regions.add(com.gregochr.goldenhour.service.TideFactFixtures.region(name, 0));
        }
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE, regions, List.of(), EVENT, window);
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new com.gregochr.goldenhour.model.BriefingDay(DAY, List.of(summary))), List.of(), null, null,
                false, null, List.of(), List.of());
    }

    private static BriefingWindow window(LocalDateTime eventTime) {
        return new BriefingWindow(eventTime, DisplayVerdict.AWAITING, null, null, null, List.of(), null);
    }

    /** Warnings only: for tests where nothing survives the timeline, so coverage never reads the roster. */
    private void stubWarningsOnly(WeatherWarningVersionEntity... versions) {
        when(repository.findAllVersionsOfWarningsValidAfter(NOW)).thenReturn(List.of(versions));
    }

    private void stubWorld(WeatherWarningVersionEntity... versions) {
        stubWarningsOnly(versions);
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(hawes, askrigg, bamburgh));
    }

    private static BriefingWindow windowOf(DailyBriefingResponse response) {
        return response.days().get(0).eventSummaries().get(0).window();
    }

    @Test
    @DisplayName("a window whose event is inside an active warning carries its id and the region's place count")
    void windowCarriesIdAndCounts() {
        stubWorld(warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false));
        DailyBriefingResponse in = briefing(window(EVENT), "Dales", "Northumberland");

        DailyBriefingResponse out = overlay.overlay(in);

        assertThat(windowOf(out).warningIds()).containsExactly("W1");
        assertThat(windowOf(out).warningPlaceCounts()).containsExactly(new WarningPlaceCount("W1", "Dales", 1, 2));
        assertThat(windowOf(in).warningIds()).as("the input is not mutated").isNull();
    }

    @Test
    @DisplayName("the window's event time must lie inside [validFrom, validTo]; either side outside gets no id")
    void eventOutsideValidityGetsNothing() {
        stubWorld(warning("EARLY", "2026-10-11T07:00:00Z", "2026-10-12T00:00:00Z", false),
                warning("LATE", "2026-10-10T00:00:00Z", "2026-10-11T05:00:00Z", false));
        DailyBriefingResponse in = briefing(window(EVENT), "Dales");

        assertThat(overlay.overlay(in)).isSameAs(in);
    }

    @Test
    @DisplayName("a warning with no validFrom is open at the start")
    void nullValidFromIsOpen() {
        stubWorld(warning("W1", null, "2026-10-12T00:00:00Z", false));

        assertThat(windowOf(overlay.overlay(briefing(window(EVENT), "Dales"))).warningIds()).containsExactly("W1");
    }

    @Test
    @DisplayName("the summary's solar time stands in for a window with no event time; with neither, nothing is added")
    void fallsBackToSummaryTimeThenNothing() {
        stubWorld(warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false));

        assertThat(windowOf(overlay.overlay(briefing(window(null), "Dales"))).warningIds()).containsExactly("W1");

        BriefingEventSummary timeless = new BriefingEventSummary(TargetType.SUNRISE, List.of(), List.of(), null,
                window(null));
        DailyBriefingResponse noTime = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new com.gregochr.goldenhour.model.BriefingDay(DAY, List.of(timeless))), List.of(), null, null,
                false, null, List.of(), List.of());
        assertThat(overlay.overlay(noTime)).isSameAs(noTime);
    }

    @Test
    @DisplayName("a summary with no window is left alone")
    void noWindowNoOverlay() {
        stubWorld(warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false));
        BriefingEventSummary bare = new BriefingEventSummary(TargetType.SUNRISE, List.of(), List.of(), EVENT, null);
        DailyBriefingResponse in = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new com.gregochr.goldenhour.model.BriefingDay(DAY, List.of(bare))), List.of(), null, null,
                false, null, List.of(), List.of());

        assertThat(overlay.overlay(in)).isSameAs(in);
    }

    @Test
    @DisplayName("a simulated warning is excluded for everyone")
    void simulatedExcluded() {
        stubWarningsOnly(warning("SIM-1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", true));
        DailyBriefingResponse in = briefing(window(EVENT), "Dales");

        assertThat(overlay.overlay(in)).isSameAs(in);
    }

    @Test
    @DisplayName("a warning covering no enabled place is not surfaced")
    void coveringNobodyIsDropped() {
        WeatherWarningVersionEntity w = warning("FAR", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false);
        w.setGeometry("{\"type\":\"MultiPolygon\",\"coordinates\":[[[[10,10],[11,10],[11,11],[10,11],[10,10]]]]}");
        stubWorld(w);
        DailyBriefingResponse in = briefing(window(EVENT), "Dales");

        assertThat(overlay.overlay(in)).isSameAs(in);
    }

    @Test
    @DisplayName("a cancelled latest version shows nothing")
    void cancelledShowsNothing() {
        WeatherWarningVersionEntity v1 = warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false);
        WeatherWarningVersionEntity cancel = warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false);
        cancel.setWarningVersion(2);
        cancel.setStatus(WeatherWarningVersionEntity.STATUS_CANCELLED);
        stubWarningsOnly(v1, cancel);
        DailyBriefingResponse in = briefing(window(EVENT), "Dales");

        assertThat(overlay.overlay(in)).isSameAs(in);
    }

    @Test
    @DisplayName("the flag off, a null briefing, or nothing in force touches no data and returns the input")
    void offNullAndEmptyAreNoOps() {
        DailyBriefingResponse in = briefing(window(EVENT), "Dales");
        properties.setEnabled(false);
        assertThat(overlay.overlay(in)).isSameAs(in);
        assertThat(overlay.snapshot().isEmpty()).isTrue();
        verifyNoInteractions(repository);

        properties.setEnabled(true);
        assertThat(overlay.overlay(null)).isNull();
        when(repository.findAllVersionsOfWarningsValidAfter(NOW)).thenReturn(List.of());
        assertThat(overlay.overlay(in)).isSameAs(in);
        verify(locations, never()).findAllByEnabledTrueOrderByNameAsc();
    }

    @Test
    @DisplayName("a failing lookup never takes the briefing down: it is served unchanged")
    void failSoft() {
        when(repository.findAllVersionsOfWarningsValidAfter(NOW)).thenThrow(new IllegalStateException("db down"));
        DailyBriefingResponse in = briefing(window(EVENT), "Dales");

        assertThat(overlay.overlay(in)).isSameAs(in);
        assertThat(overlay.snapshot().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("idsFor answers per place and instant, and tolerates a null place or instant")
    void idsForPerPlace() {
        stubWorld(warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false));
        WarningOverlay.Snapshot snapshot = overlay.snapshot();
        Instant inside = Instant.parse("2026-10-11T06:00:00Z");

        assertThat(snapshot.idsFor(1L, inside)).containsExactly("W1");
        assertThat(snapshot.idsFor(2L, inside)).as("Askrigg is outside the polygon").isEmpty();
        assertThat(snapshot.idsFor(1L, Instant.parse("2026-10-13T06:00:00Z"))).isEmpty();
        assertThat(snapshot.idsFor(null, inside)).isEmpty();
        assertThat(snapshot.idsFor(1L, null)).isEmpty();
    }

    @Test
    @DisplayName("a place with no region cannot place a warning in a window: no ids, no counts")
    void placeWithoutRegion() {
        LocationEntity lone = place(4L, "Lone", null, 54.5, -2.5);
        when(repository.findAllVersionsOfWarningsValidAfter(NOW))
                .thenReturn(List.of(warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false)));
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(lone));
        DailyBriefingResponse in = briefing(window(EVENT), "Dales");

        assertThat(overlay.overlay(in)).isSameAs(in);
    }

    @Test
    @DisplayName("a warning covering only region B does not tag a window listing only region A")
    void otherRegionWarningDoesNotTag() {
        stubWorld(warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false));
        DailyBriefingResponse in = briefing(window(EVENT), "Northumberland");

        assertThat(overlay.overlay(in)).isSameAs(in);
    }

    @Test
    @DisplayName("a window listing A and B is tagged with counts for the covered region only; ids match the counts")
    void idsMatchCounts() {
        stubWorld(warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false));

        BriefingWindow w = windowOf(overlay.overlay(briefing(window(EVENT), "Northumberland", "Dales")));

        assertThat(w.warningPlaceCounts()).containsExactly(new WarningPlaceCount("W1", "Dales", 1, 2));
        assertThat(w.warningIds()).containsExactlyElementsOf(
                w.warningPlaceCounts().stream().map(WarningPlaceCount::warningId).distinct().toList());
    }

    @Test
    @DisplayName("a window listing no region (unregioned slots only) carries no warning")
    void noRegionsNoWarning() {
        stubWorld(warning("W1", "2026-10-11T00:00:00Z", "2026-10-12T00:00:00Z", false));
        DailyBriefingResponse in = briefing(window(EVENT));

        assertThat(overlay.overlay(in)).isSameAs(in);
    }

    @Test
    @DisplayName("the disabled overlay is a permanent no-op")
    void disabledFactory() {
        DailyBriefingResponse in = briefing(window(EVENT), "Dales");

        assertThat(WarningOverlay.disabled().overlay(in)).isSameAs(in);
        assertThat(WarningOverlay.disabled().snapshot().isEmpty()).isTrue();
    }
}
