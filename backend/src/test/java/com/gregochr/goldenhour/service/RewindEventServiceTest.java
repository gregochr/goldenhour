package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RewindEventService}: which events, timed across which roster, rewound to what moment.
 *
 * <p>Real {@link SolarService} (solar-utils is deterministic), a fixed clock at 09:30 UTC on
 * 2026-10-04 — a Sunday after that morning's sunrise and before its sunset.
 */
class RewindEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    private final LocationRepository locations = mock(LocationRepository.class);
    private final BriefingService briefingService = mock(BriefingService.class);
    private final SolarService solarService = new SolarService();
    private final RewindEventService service = new RewindEventService(
            locations, new SolarEventFreshness(solarService, CLOCK), briefingService, CLOCK);

    private static LocationEntity place(String name, double lat, double lon, LocationType... types) {
        LocationEntity e = new LocationEntity();
        e.setName(name);
        e.setLat(lat);
        e.setLon(lon);
        e.setLocationType(Set.of(types));
        e.setEnabled(true);
        return e;
    }

    @Test
    @DisplayName("three UK days, two events each, newest first: today's sunset, today's sunrise, "
            + "yesterday's sunset …")
    void sixEventsNewestFirst() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                place("Durham", 54.78, -1.58, LocationType.LANDSCAPE)));

        RewindEventService.RewindEvents result = service.events();

        assertThat(result.now()).isEqualTo(NOW);
        assertThat(result.events()).extracting(RewindEventService.RewindEvent::date,
                RewindEventService.RewindEvent::eventType).containsExactly(
                        Tuple.tuple(TODAY, TargetType.SUNSET),
                        Tuple.tuple(TODAY, TargetType.SUNRISE),
                        Tuple.tuple(TODAY.minusDays(1), TargetType.SUNSET),
                        Tuple.tuple(TODAY.minusDays(1), TargetType.SUNRISE),
                        Tuple.tuple(TODAY.minusDays(2), TargetType.SUNSET),
                        Tuple.tuple(TODAY.minusDays(2), TargetType.SUNRISE));
    }

    @Test
    @DisplayName("the roster's earliest and latest times span the sky locations; rewindTo is 60 min "
            + "before the earliest")
    void rosterSpanAndLead() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                place("Durham", 54.78, -1.58, LocationType.LANDSCAPE),
                place("Lizard", 49.96, -5.20, LocationType.SEASCAPE)));
        LocalDateTime durhamRise = solarService.sunriseUtc(54.78, -1.58, TODAY);
        LocalDateTime lizardRise = solarService.sunriseUtc(49.96, -5.20, TODAY);
        // Sanity: the east-coast sunrise is the earlier one, by a real margin.
        assertThat(durhamRise).isBefore(lizardRise.minusMinutes(10));

        RewindEventService.RewindEvent sunrise = service.events().events().stream()
                .filter(e -> e.date().equals(TODAY) && e.eventType() == TargetType.SUNRISE)
                .findFirst().orElseThrow();

        assertThat(sunrise.earliest()).isEqualTo(durhamRise.toInstant(ZoneOffset.UTC));
        assertThat(sunrise.latest()).isEqualTo(lizardRise.toInstant(ZoneOffset.UTC));
        assertThat(sunrise.rewindTo()).isEqualTo(durhamRise.minusMinutes(60).toInstant(ZoneOffset.UTC));
        assertThat(sunrise.locationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("passed follows the Plan tab's own rule at the roster's latest time: this morning has, "
            + "this evening has not")
    void passedFlag() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                place("Durham", 54.78, -1.58, LocationType.LANDSCAPE)));

        List<RewindEventService.RewindEvent> events = service.events().events();

        assertThat(events.get(0).eventType()).isEqualTo(TargetType.SUNSET);
        assertThat(events.get(0).passed()).isFalse();
        assertThat(events.get(1).eventType()).isEqualTo(TargetType.SUNRISE);
        assertThat(events.get(1).passed()).isTrue();
        assertThat(events.subList(2, 6)).allMatch(RewindEventService.RewindEvent::passed);
    }

    @Test
    @DisplayName("inBriefing follows the cached briefing's days: only a date the last build kept can reach "
            + "the Plan tab")
    void inBriefingFollowsCachedDays() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                place("Durham", 54.78, -1.58, LocationType.LANDSCAPE)));
        // The ordinary shape after today's first cycle: today..today+3, yesterday gone.
        when(briefingService.getCachedDays()).thenReturn(List.of(
                briefingDay(TODAY), briefingDay(TODAY.plusDays(1)), briefingDay(TODAY.plusDays(2)),
                briefingDay(TODAY.plusDays(3))));

        List<RewindEventService.RewindEvent> events = service.events().events();

        assertThat(events).filteredOn(e -> e.date().equals(TODAY))
                .hasSize(2).allMatch(RewindEventService.RewindEvent::inBriefing);
        assertThat(events).filteredOn(e -> e.date().isBefore(TODAY))
                .hasSize(4).noneMatch(RewindEventService.RewindEvent::inBriefing);
    }

    @Test
    @DisplayName("with no briefing at all nothing is in the briefing — and a null-dated day is ignored, not a crash")
    void inBriefingWithoutABriefing() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                place("Durham", 54.78, -1.58, LocationType.LANDSCAPE)));

        when(briefingService.getCachedDays()).thenReturn(null);
        assertThat(service.events().events()).noneMatch(RewindEventService.RewindEvent::inBriefing);

        when(briefingService.getCachedDays()).thenReturn(Arrays.asList(null, briefingDay(null)));
        assertThat(service.events().events()).noneMatch(RewindEventService.RewindEvent::inBriefing);
    }

    /** A real, empty briefing day for {@code date} — a record, so no mock is needed or wanted. */
    private static BriefingDay briefingDay(LocalDate date) {
        return new BriefingDay(date, List.of(), null);
    }

    @Test
    @DisplayName("a wildlife-only hide is not timed — it has no sunrise or sunset forecast")
    void hideExcluded() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                place("Durham", 54.78, -1.58, LocationType.LANDSCAPE),
                place("Gosforth", 55.03, -1.59, LocationType.WILDLIFE)));

        assertThat(service.events().events()).isNotEmpty().allMatch(e -> e.locationCount() == 1);
    }

    @Test
    @DisplayName("with no sky locations there are no events, and still an answer")
    void emptyRoster() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

        RewindEventService.RewindEvents result = service.events();

        assertThat(result.events()).isEmpty();
        assertThat(result.now()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("the cached briefing's build time rides along as an instant; null when none exists")
    void briefingGeneratedAt() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(briefingService.getCachedGeneratedAt()).thenReturn(LocalDateTime.of(2026, 10, 4, 6, 42));
        assertThat(service.events().briefingGeneratedAt()).isEqualTo(Instant.parse("2026-10-04T06:42:00Z"));

        when(briefingService.getCachedGeneratedAt()).thenReturn(null);
        assertThat(service.events().briefingGeneratedAt()).isNull();
    }
}
