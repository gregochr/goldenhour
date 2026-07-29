package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CloseToHomeResponse;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CloseToHomeService} — the server-side port of the Close to home
 * derivation.
 *
 * <p>Home is Durham (54.7753, -1.5849) throughout. Distances below are real: Durham→Penshaw is
 * roughly 9 miles and Durham→Bamburgh roughly 60, so the 22-mile gate genuinely separates them
 * rather than relying on invented coordinates.
 */
@ExtendWith(MockitoExtension.class)
class CloseToHomeServiceTest {

    private static final double HOME_LAT = 54.7753;
    private static final double HOME_LON = -1.5849;
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 22);
    /** Fixed "now" well before the event times below, so nothing reads as past. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC);

    @Mock private BriefingService briefingService;
    @Mock private LocationService locationService;
    @Mock private DriveTimeResolver driveTimeResolver;

    private CloseToHomeService service;

    @BeforeEach
    void setUp() {
        service = new CloseToHomeService(briefingService, locationService, driveTimeResolver,
                CLOCK, 22);
        lenient().when(driveTimeResolver.getAllMinutes(anyLong())).thenReturn(Map.of());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static LocationEntity loc(long id, String name, double lat, double lon,
            String regionName) {
        RegionEntity region = regionName == null ? null
                : RegionEntity.builder().id(1L).name(regionName).build();
        return LocationEntity.builder()
                .id(id).name(name).lat(lat).lon(lon).region(region).enabled(true).build();
    }

    /** ~9 miles from home. */
    private static LocationEntity nearLoc() {
        return loc(1L, "Penshaw Monument", 54.8926, -1.4776, "Tyne and Wear");
    }

    /** ~60 miles from home — outside the 22-mile gate. */
    private static LocationEntity farLoc() {
        return loc(2L, "Bamburgh", 55.6090, -1.7099, "Northumberland");
    }

    private static BriefingSlot slot(Long locationId, String name, Integer rating,
            Verdict verdict) {
        return new BriefingSlot(locationId, name, LocalDateTime.of(2026, 4, 22, 19, 30), verdict,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70, 10.0, 8.0, 0,
                        BigDecimal.ONE, 30, 40),
                BriefingSlot.TideInfo.NONE, List.of(), null)
                .withClaudeScores(rating, null, null, "Summary for " + name);
    }

    private void givenBriefing(BriefingSlot... slots) {
        BriefingRegion region = new BriefingRegion("Tyne and Wear", Verdict.GO,
                "Clear nearby", List.of(), List.of(slots), 10.5, 8.0, 3.2, 1, null, null,
                DisplayVerdict.WORTH_IT, slots.length);
        BriefingEventSummary es = new BriefingEventSummary(
                TargetType.SUNSET, List.of(region), List.of());
        when(briefingService.getCachedBriefingForApi()).thenReturn(
                new DailyBriefingResponse(LocalDateTime.of(2026, 4, 22, 4, 0),
                        "Headline", List.of(new BriefingDay(TODAY, List.of(es))), List.of(),
                        null, null, false, false, 0, "Opus", List.of(), List.of()));
    }

    // ── the home gate ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("no home postcode yields an EMPTY panel, not an error")
    void noHome_returnsEmptyPanel() {
        CloseToHomeResponse r = service.build(1L, null, null);

        assertThat(r.cards()).isEmpty();
        assertThat(r.breadcrumb().worthIt()).isFalse();
        assertThat(r.radiusMiles()).isEqualTo(22);
    }

    // ── the proximity gate ────────────────────────────────────────────────────

    @Test
    @DisplayName("a location beyond the radius is excluded however good it is")
    void beyondRadius_excluded() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc(), farLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 3, Verdict.GO),
                slot(2L, "Bamburgh", 5, Verdict.GO));

        CloseToHomeResponse r = service.build(1L, HOME_LAT, HOME_LON);

        // Bamburgh is the better forecast and is still excluded — proximity gates, rating sorts.
        assertThat(r.cards()).singleElement()
                .satisfies(c -> assertThat(c.locationName()).isEqualTo("Penshaw Monument"));
    }

    @Test
    @DisplayName("distance is reported in miles, rounded")
    void distanceReportedInMiles() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.GO));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards().get(0).distanceMiles())
                .isBetween(8, 11);
    }

    // ── the join: the whole reason this moved server-side ─────────────────────

    @Test
    @DisplayName("joins on locationId — a RENAMED location is still matched")
    void joinsOnId_survivesRename() {
        // The bug this replaces: the client matched on name, so renaming a location silently
        // emptied the panel. Here the slot carries the OLD name and the right id.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument (old name)", 4, Verdict.GO));

        CloseToHomeResponse r = service.build(1L, HOME_LAT, HOME_LON);

        assertThat(r.cards()).hasSize(1);
        assertThat(r.cards().get(0).locationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("falls back to the name for legacy payloads with no locationId")
    void fallsBackToName_forLegacyPayloads() {
        // Cached briefings written before BriefingSlot carried an id keep being served until
        // their key ages out, so this path is rollover, not dead code.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(null, "Penshaw Monument", 4, Verdict.GO));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards()).hasSize(1);
    }

    // ── qualification and ranking ─────────────────────────────────────────────

    @Test
    @DisplayName("an unrated slot never becomes a card")
    void unratedSlot_excluded() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", null, Verdict.GO));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards()).isEmpty();
    }

    @Test
    @DisplayName("a poorly-RATED slot is excluded — the rating decides, not the triage verdict")
    void poorlyRatedSlot_excluded() {
        // DisplayVerdict.resolve puts the Claude rating above the triage verdict, so "poor" means
        // rating <= 2, not verdict == STANDDOWN. Mirrors the client's isPoorSlot, which also
        // prefers displayVerdict when present.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 2, Verdict.GO));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards()).isEmpty();
    }

    @Test
    @DisplayName("a well-rated slot survives a STANDDOWN triage verdict — the rating overrides it")
    void wellRatedSlot_survivesStanddownVerdict() {
        // Deliberate project semantics rather than an oversight: a slot Claude rated 4 is worth
        // offering even though triage stood it down, because the rating is the later, better
        // judgement. Pinned so a future "tidy-up" cannot quietly invert it.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.STANDDOWN));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards()).hasSize(1);
    }

    @Test
    @DisplayName("an UNRATED stand-down slot is excluded — nothing overrides the verdict there")
    void unratedStanddownSlot_excluded() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", null, Verdict.STANDDOWN));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards()).isEmpty();
    }

    @Test
    @DisplayName("cards are rating-ordered, best first, and the best is flagged lead")
    void cardsRatingOrdered_leadFlagged() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        LocationEntity b = loc(2L, "Bravo", 54.81, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a, b));
        givenBriefing(slot(1L, "Alpha", 3, Verdict.GO), slot(2L, "Bravo", 5, Verdict.GO));

        CloseToHomeResponse r = service.build(1L, HOME_LAT, HOME_LON);

        assertThat(r.cards()).extracting(CloseToHomeResponse.Card::locationName)
                .containsExactly("Bravo", "Alpha");
        assertThat(r.cards().get(0).lead()).isTrue();
        assertThat(r.cards().get(1).lead()).isFalse();
    }

    @Test
    @DisplayName("the card count is capped")
    void cardsAreCapped() {
        List<LocationEntity> locs = List.of(
                loc(1L, "A", 54.80, -1.58, "D"), loc(2L, "B", 54.81, -1.58, "D"),
                loc(3L, "C", 54.82, -1.58, "D"), loc(4L, "D", 54.83, -1.58, "D"),
                loc(5L, "E", 54.84, -1.58, "D"));
        when(locationService.findAllEnabled()).thenReturn(locs);
        givenBriefing(slot(1L, "A", 5, Verdict.GO), slot(2L, "B", 5, Verdict.GO),
                slot(3L, "C", 5, Verdict.GO), slot(4L, "D", 5, Verdict.GO),
                slot(5L, "E", 5, Verdict.GO));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards())
                .hasSize(CloseToHomeService.MAX_CARDS);
    }

    // ── the honesty label ─────────────────────────────────────────────────────

    @Test
    @DisplayName("reports the location's OWN region, not the briefing group it was listed under")
    void reportsLocationOwnRegion() {
        // St Mary's Lighthouse is briefed under one region but belongs to another; showing the
        // briefing group would misattribute it.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.GO));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards().get(0).regionName())
                .isEqualTo("Tyne and Wear");
    }

    // ── the breadcrumb ────────────────────────────────────────────────────────

    @Test
    @DisplayName("worthIt with the leading location when something nearby qualifies")
    void breadcrumb_worthIt() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.GO));

        CloseToHomeResponse.Breadcrumb b = service.build(1L, HOME_LAT, HOME_LON).breadcrumb();

        assertThat(b.worthIt()).isTrue();
        assertThat(b.topLocationName()).isEqualTo("Penshaw Monument");
        assertThat(b.topRating()).isEqualTo(4);
        assertThat(b.targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("not worthIt names the dominant cause, so the client can phrase a reason")
    void breadcrumb_namesDominantReason() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "D");
        LocationEntity b = loc(2L, "Bravo", 54.81, -1.58, "D");
        when(locationService.findAllEnabled()).thenReturn(List.of(a, b));
        BriefingSlot s1 = new BriefingSlot(1L, "Alpha", LocalDateTime.of(2026, 4, 22, 19, 30),
                Verdict.STANDDOWN, null, BriefingSlot.TideInfo.NONE, List.of(), "Heavy cloud");
        BriefingSlot s2 = new BriefingSlot(2L, "Bravo", LocalDateTime.of(2026, 4, 22, 19, 30),
                Verdict.STANDDOWN, null, BriefingSlot.TideInfo.NONE, List.of(), "Heavy cloud");
        givenBriefing(s1, s2);

        CloseToHomeResponse.Breadcrumb bc = service.build(1L, HOME_LAT, HOME_LON).breadcrumb();

        assertThat(bc.worthIt()).isFalse();
        assertThat(bc.dominantReason()).isEqualTo("Heavy cloud");
        // The server supplies the FACT; the sentence stays on the client.
        assertThat(bc.topLocationName()).isNull();
    }

    // ── drive times ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("drive minutes come from the caller's own map, keyed by location id")
    void driveMinutes_perUser() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.GO));
        when(driveTimeResolver.getAllMinutes(7L)).thenReturn(Map.of(1L, 25));

        assertThat(service.build(7L, HOME_LAT, HOME_LON).cards().get(0).driveMinutes())
                .isEqualTo(25);
    }

    @Test
    @DisplayName("a location with no drive time recorded reports null, not zero")
    void driveMinutes_absentIsNull() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.GO));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).cards().get(0).driveMinutes()).isNull();
    }

    // ── configuration ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the radius is server-configurable — the point of moving it off the client")
    void radiusIsConfigurable() {
        CloseToHomeService wide = new CloseToHomeService(briefingService, locationService,
                driveTimeResolver, CLOCK, 100);
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc(), farLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 3, Verdict.GO),
                slot(2L, "Bamburgh", 5, Verdict.GO));

        CloseToHomeResponse r = wide.build(1L, HOME_LAT, HOME_LON);

        assertThat(r.radiusMiles()).isEqualTo(100);
        assertThat(r.cards()).extracting(CloseToHomeResponse.Card::locationName)
                .containsExactly("Bamburgh", "Penshaw Monument");
    }
}
