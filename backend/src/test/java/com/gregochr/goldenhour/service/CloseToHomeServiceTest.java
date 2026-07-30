package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.model.BestBet;
import java.util.Set;
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

    /** Builds a briefing with one event summary per (date, targetType) supplied. */
    private void givenWindows(List<WindowSpec> specs, List<BestBet> bestBets) {
        Map<LocalDate, List<BriefingEventSummary>> byDate = new java.util.LinkedHashMap<>();
        for (WindowSpec spec : specs) {
            BriefingRegion region = new BriefingRegion(spec.regionName(), spec.regionVerdict(),
                    "Summary", List.of(), spec.slots(), 10.5, 8.0, 3.2, 1, null, null,
                    DisplayVerdict.WORTH_IT, spec.slots().size());
            byDate.computeIfAbsent(spec.date(), d -> new java.util.ArrayList<>())
                    .add(new BriefingEventSummary(spec.targetType(), List.of(region), List.of()));
        }
        List<BriefingDay> days = byDate.entrySet().stream()
                .map(e -> new BriefingDay(e.getKey(), e.getValue()))
                .toList();
        when(briefingService.getCachedBriefingForApi()).thenReturn(
                new DailyBriefingResponse(LocalDateTime.of(2026, 4, 22, 4, 0), "Headline",
                        days, bestBets, null, null, false, false, 0, "Opus",
                        List.of(), List.of()));
    }

    private record WindowSpec(LocalDate date, TargetType targetType, String regionName,
            Verdict regionVerdict, List<BriefingSlot> slots) {
    }

    private static BriefingSlot slotAt(Long id, String name, Integer rating, LocalDate date,
            int hour) {
        return new BriefingSlot(id, name, date.atTime(hour, 15), Verdict.GO,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70, 10.0, 8.0, 0,
                        BigDecimal.ONE, 30, 40),
                BriefingSlot.TideInfo.NONE, List.of(), null)
                .withClaudeScores(rating, null, null, "Summary");
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

    /** Every card across every window, for assertions about selection rather than grouping. */
    private static List<CloseToHomeResponse.Card> cardsOf(CloseToHomeResponse r) {
        return r.windows().stream().flatMap(w -> w.cards().stream()).toList();
    }

    // ── the home gate ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("no home postcode yields an EMPTY panel, not an error")
    void noHome_returnsEmptyPanel() {
        CloseToHomeResponse r = service.build(1L, null, null);

        assertThat(r.windows()).isEmpty();
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
        assertThat(cardsOf(r)).singleElement()
                .satisfies(c -> assertThat(c.locationName()).isEqualTo("Penshaw Monument"));
    }

    @Test
    @DisplayName("distance is reported in miles, rounded")
    void distanceReportedInMiles() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.GO));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON)).get(0).distanceMiles())
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

        assertThat(cardsOf(r)).hasSize(1);
        assertThat(cardsOf(r).get(0).locationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("falls back to the name for legacy payloads with no locationId")
    void fallsBackToName_forLegacyPayloads() {
        // Cached briefings written before BriefingSlot carried an id keep being served until
        // their key ages out, so this path is rollover, not dead code.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(null, "Penshaw Monument", 4, Verdict.GO));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON))).hasSize(1);
    }

    // ── qualification and ranking ─────────────────────────────────────────────

    @Test
    @DisplayName("an unrated slot never becomes a card")
    void unratedSlot_excluded() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", null, Verdict.GO));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON))).isEmpty();
    }

    @Test
    @DisplayName("a poorly-RATED slot is excluded — the rating decides, not the triage verdict")
    void poorlyRatedSlot_excluded() {
        // DisplayVerdict.resolve puts the Claude rating above the triage verdict, so "poor" means
        // rating <= 2, not verdict == STANDDOWN. Mirrors the client's isPoorSlot, which also
        // prefers displayVerdict when present.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 2, Verdict.GO));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON))).isEmpty();
    }

    @Test
    @DisplayName("a well-rated slot survives a STANDDOWN triage verdict — the rating overrides it")
    void wellRatedSlot_survivesStanddownVerdict() {
        // Deliberate project semantics rather than an oversight: a slot Claude rated 4 is worth
        // offering even though triage stood it down, because the rating is the later, better
        // judgement. Pinned so a future "tidy-up" cannot quietly invert it.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.STANDDOWN));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON))).hasSize(1);
    }

    @Test
    @DisplayName("an UNRATED stand-down slot is excluded — nothing overrides the verdict there")
    void unratedStanddownSlot_excluded() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", null, Verdict.STANDDOWN));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON))).isEmpty();
    }

    @Test
    @DisplayName("cards are rating-ordered, best first, and the best is flagged lead")
    void cardsRatingOrdered_leadFlagged() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        LocationEntity b = loc(2L, "Bravo", 54.81, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a, b));
        givenBriefing(slot(1L, "Alpha", 3, Verdict.GO), slot(2L, "Bravo", 5, Verdict.GO));

        CloseToHomeResponse r = service.build(1L, HOME_LAT, HOME_LON);

        assertThat(cardsOf(r)).extracting(CloseToHomeResponse.Card::locationName)
                .containsExactly("Bravo", "Alpha");
        assertThat(cardsOf(r).get(0).lead()).isTrue();
        assertThat(cardsOf(r).get(1).lead()).isFalse();
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

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON)))
                .hasSize(CloseToHomeService.MAX_CARDS_PER_WINDOW);
    }

    // ── the honesty label ─────────────────────────────────────────────────────

    @Test
    @DisplayName("reports the location's OWN region, not the briefing group it was listed under")
    void reportsLocationOwnRegion() {
        // St Mary's Lighthouse is briefed under one region but belongs to another; showing the
        // briefing group would misattribute it.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.GO));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON)).get(0).regionName())
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

        assertThat(cardsOf(service.build(7L, HOME_LAT, HOME_LON)).get(0).driveMinutes())
                .isEqualTo(25);
    }

    @Test
    @DisplayName("a location with no drive time recorded reports null, not zero")
    void driveMinutes_absentIsNull() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 4, Verdict.GO));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON)).get(0).driveMinutes()).isNull();
    }

    // ── window grouping: the redesign's whole point ───────────────────────────

    @Test
    @DisplayName("cards are GROUPED by event window, chronologically, not flattened")
    void windowsAreChronological() {
        // The flat list this replaces never said which event a card was for. Windows go
        // chronologically because the user is deciding WHEN; cards inside go by rating.
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenWindows(List.of(
                new WindowSpec(TODAY.plusDays(1), TargetType.SUNSET, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 5, TODAY.plusDays(1), 21))),
                new WindowSpec(TODAY, TargetType.SUNRISE, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 3, TODAY, 5)))),
                List.of());

        CloseToHomeResponse r = service.build(1L, HOME_LAT, HOME_LON);

        // The 5-star sunset is LATER, so it comes second. Rating does not reorder windows.
        assertThat(r.windows()).hasSize(2);
        assertThat(r.windows().get(0).targetType()).isEqualTo(TargetType.SUNRISE);
        assertThat(r.windows().get(0).date()).isEqualTo(TODAY);
        assertThat(r.windows().get(1).targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("SUNRISE and SUNSET windows both appear — never sunrise-only")
    void bothEventTypesEligible() {
        // A 05:09 sunrise is an easy dismissal when it is the only thing offered. The handoff
        // requires both event types compete so an early alarm can be weighed against an evening.
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenWindows(List.of(
                new WindowSpec(TODAY, TargetType.SUNRISE, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 4, TODAY, 5))),
                new WindowSpec(TODAY, TargetType.SUNSET, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 4, TODAY, 21)))),
                List.of());

        assertThat(service.build(1L, HOME_LAT, HOME_LON).windows())
                .extracting(CloseToHomeResponse.Window::targetType)
                .containsExactly(TargetType.SUNRISE, TargetType.SUNSET);
    }

    @Test
    @DisplayName("exactly ONE lead card in the whole block — rank 1 of the first window")
    void onlyOneLeadAcrossAllWindows() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenWindows(List.of(
                new WindowSpec(TODAY, TargetType.SUNRISE, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 4, TODAY, 5))),
                new WindowSpec(TODAY, TargetType.SUNSET, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 5, TODAY, 21)))),
                List.of());

        CloseToHomeResponse r = service.build(1L, HOME_LAT, HOME_LON);

        // A lead per window would make the gold accent meaningless.
        assertThat(cardsOf(r)).filteredOn(CloseToHomeResponse.Card::lead).hasSize(1);
        assertThat(r.windows().get(0).cards().get(0).lead()).isTrue();
    }

    // ── the two window flags ──────────────────────────────────────────────────

    @Test
    @DisplayName("NOT IN THE BRIEFING when the region stands down but a local spot still rates")
    void notInBriefingFlag() {
        // The signal the region-level grid structurally cannot show: Tyne and Wear averages
        // Stand down for the window while Angel of the North individually rates 4.
        LocationEntity a = loc(1L, "Angel of the North", 54.80, -1.58, "Tyne and Wear");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNRISE, "Tyne and Wear",
                Verdict.STANDDOWN, List.of(slotAt(1L, "Angel of the North", 4, TODAY, 5)))),
                List.of());

        CloseToHomeResponse.Window w = service.build(1L, HOME_LAT, HOME_LON).windows().get(0);

        assertThat(w.notInBriefing()).isTrue();
        assertThat(w.flaggedRegionName()).isEqualTo("Tyne and Wear");
    }

    @Test
    @DisplayName("no NOT IN THE BRIEFING flag when the region itself is fine")
    void noFlagWhenRegionIsGo() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNRISE, "Durham", Verdict.GO,
                List.of(slotAt(1L, "Alpha", 4, TODAY, 5)))), List.of());

        CloseToHomeResponse.Window w = service.build(1L, HOME_LAT, HOME_LON).windows().get(0);

        assertThat(w.notInBriefing()).isFalse();
        assertThat(w.flaggedRegionName()).isNull();
    }

    @Test
    @DisplayName("SAME WINDOW AS BEST BET when the day and event match the current pick")
    void sameWindowAsBestBetFlag() {
        // Matched by GENERATING the Best Bet's dayName/eventType labels for the window rather
        // than parsing the pick's back into a date — BestBet carries no date.
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        BestBet pick = new BestBet(1, "Headline", "Detail", "sunset", "Tyne and Wear",
                null, null, "Today", "sunset", "21:19", null, List.of());
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNSET, "Durham", Verdict.GO,
                List.of(slotAt(1L, "Alpha", 4, TODAY, 21)))), List.of(pick));

        CloseToHomeResponse.Window w = service.build(1L, HOME_LAT, HOME_LON).windows().get(0);

        assertThat(w.sameWindowAsBestBet()).isTrue();
        assertThat(w.bestBetRegionName()).isEqualTo("Tyne and Wear");
    }

    @Test
    @DisplayName("no SAME WINDOW flag when the Best Bet is a different event")
    void noBestBetFlagOnDifferentEvent() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        BestBet pick = new BestBet(1, "H", "D", "sunrise", "Tyne and Wear",
                null, null, "Today", "sunrise", "05:09", null, List.of());
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNSET, "Durham", Verdict.GO,
                List.of(slotAt(1L, "Alpha", 4, TODAY, 21)))), List.of(pick));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).windows().get(0).sameWindowAsBestBet())
                .isFalse();
    }

    // ── location types, for the card icons ────────────────────────────────────

    @Test
    @DisplayName("cards carry the location's subject types for the icons")
    void cardsCarryLocationTypes() {
        LocationEntity a = LocationEntity.builder()
                .id(1L).name("Alpha").lat(54.80).lon(-1.58).enabled(true)
                .locationType(Set.of(LocationType.SEASCAPE, LocationType.WATERFALL)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenBriefing(slot(1L, "Alpha", 4, Verdict.GO));

        assertThat(cardsOf(service.build(1L, HOME_LAT, HOME_LON)).get(0).locationTypes())
                .containsExactlyInAnyOrder(LocationType.SEASCAPE, LocationType.WATERFALL);
    }

    // ── configuration ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the caller's OWN radius wins over the deployment default")
    void userRadiusOverridesDefault() {
        // The default is what a null column means, not a cap. A user who widened to 100 miles
        // must see the further location the 22-mile default would have gated out.
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc(), farLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 3, Verdict.GO),
                slot(2L, "Bamburgh", 5, Verdict.GO));

        CloseToHomeResponse r = service.build(1L, HOME_LAT, HOME_LON, 100);

        assertThat(r.radiusMiles()).isEqualTo(100);
        assertThat(cardsOf(r)).extracting(CloseToHomeResponse.Card::locationName)
                .contains("Bamburgh");
    }

    @Test
    @DisplayName("a null user radius falls back to the deployment default")
    void nullUserRadiusFallsBackToDefault() {
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc(), farLoc()));
        givenBriefing(slot(1L, "Penshaw Monument", 3, Verdict.GO),
                slot(2L, "Bamburgh", 5, Verdict.GO));

        CloseToHomeResponse r = service.build(1L, HOME_LAT, HOME_LON, null);

        assertThat(r.radiusMiles()).isEqualTo(22);
        assertThat(cardsOf(r)).extracting(CloseToHomeResponse.Card::locationName)
                .doesNotContain("Bamburgh");
    }

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
        assertThat(cardsOf(r)).extracting(CloseToHomeResponse.Card::locationName)
                .containsExactly("Bamburgh", "Penshaw Monument");
    }
}
