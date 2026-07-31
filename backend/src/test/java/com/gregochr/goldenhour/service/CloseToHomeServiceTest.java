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
                    spec.regionDisplayVerdict(), spec.slots().size());
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
            Verdict regionVerdict, DisplayVerdict regionDisplayVerdict, List<BriefingSlot> slots) {

        /** The ordinary case: the cell the user sees reads WORTH_IT. */
        WindowSpec(LocalDate date, TargetType targetType, String regionName, Verdict verdict,
                List<BriefingSlot> slots) {
            this(date, targetType, regionName, verdict, DisplayVerdict.WORTH_IT, slots);
        }
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
    @DisplayName("every qualifier is carried, so the count is always reachable")
    void everyQualifierIsCarried() {
        // The window used to hold four cards under a header saying "5 within reach" — a number the
        // user could read but not act on. The client decides how many to show at once; the payload
        // must make each one reachable, so cards.size() and withinReach can never disagree.
        List<LocationEntity> locs = List.of(
                loc(1L, "A", 54.80, -1.58, "D"), loc(2L, "B", 54.81, -1.58, "D"),
                loc(3L, "C", 54.82, -1.58, "D"), loc(4L, "D", 54.83, -1.58, "D"),
                loc(5L, "E", 54.84, -1.58, "D"));
        when(locationService.findAllEnabled()).thenReturn(locs);
        givenBriefing(slot(1L, "A", 5, Verdict.GO), slot(2L, "B", 5, Verdict.GO),
                slot(3L, "C", 5, Verdict.GO), slot(4L, "D", 5, Verdict.GO),
                slot(5L, "E", 5, Verdict.GO));

        CloseToHomeResponse response = service.build(1L, HOME_LAT, HOME_LON);

        assertThat(cardsOf(response)).hasSize(5);
        assertThat(response.windows().get(0).withinReach()).isEqualTo(5);
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

    @Test
    @DisplayName("a rating tie breaks on the shorter drive, in all three gold elements")
    void ratingTie_breaksOnDrive_consistentlyAcrossBothGoldElements() {
        // Distance still never outranks a better-rated spot further out — this only orders
        // locations the rating cannot separate, where "closer" beats the alphabet.
        //
        // All three assertions matter together: the earliest window IS windows[0], so the panel's
        // gold elements all describe the same event. When only the card list carried the tiebreak,
        // a 4★ tie put Copt Hill on the lead card and Angel of the North in "Next local window";
        // when the breadcrumb's headline location was the one left out, the "Worth it" paragraph
        // named Angel above a Copt Hill card. Either way, one block naming two different places
        // for one sunrise — so each selection is pinned rather than just the pair that broke last.
        List<LocationEntity> locs = List.of(
                loc(1L, "Angel of the North", 54.80, -1.58, "Tyne and Wear"),
                loc(2L, "Copt Hill", 54.81, -1.58, "Tyne and Wear"));
        when(locationService.findAllEnabled()).thenReturn(locs);
        givenBriefing(slot(1L, "Angel of the North", 4, Verdict.GO),
                slot(2L, "Copt Hill", 4, Verdict.GO));
        when(driveTimeResolver.getAllMinutes(7L)).thenReturn(Map.of(1L, 45, 2L, 10));

        CloseToHomeResponse response = service.build(7L, HOME_LAT, HOME_LON);

        // Alphabetically Angel of the North wins; on drive time Copt Hill does.
        assertThat(cardsOf(response).get(0).locationName()).isEqualTo("Copt Hill");
        assertThat(response.breadcrumb().nextWindow().card().locationName()).isEqualTo("Copt Hill");
        // The prose one. It renders as "◎ Worth it" directly above the lead card.
        assertThat(response.breadcrumb().topLocationName()).isEqualTo("Copt Hill");
    }

    @Test
    @DisplayName("an unmeasured drive sorts last — not knowing is not evidence of being close")
    void ratingTie_unknownDriveSortsLast() {
        List<LocationEntity> locs = List.of(
                loc(1L, "Angel of the North", 54.80, -1.58, "Tyne and Wear"),
                loc(2L, "Copt Hill", 54.81, -1.58, "Tyne and Wear"));
        when(locationService.findAllEnabled()).thenReturn(locs);
        givenBriefing(slot(1L, "Angel of the North", 4, Verdict.GO),
                slot(2L, "Copt Hill", 4, Verdict.GO));
        // Only Copt Hill has a measured drive, and it is a long one.
        when(driveTimeResolver.getAllMinutes(7L)).thenReturn(Map.of(2L, 55));

        assertThat(cardsOf(service.build(7L, HOME_LAT, HOME_LON)).get(0).locationName())
                .isEqualTo("Copt Hill");
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
        // The region's DISPLAY verdict is what the grid renders and what the flag now reads.
        // Setting only the triage verdict — as this fixture first did — described a state the
        // serve path cannot produce, so the test passed against code that was wrong.
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNRISE, "Tyne and Wear",
                Verdict.STANDDOWN, DisplayVerdict.STAND_DOWN,
                List.of(slotAt(1L, "Angel of the North", 4, TODAY, 5)))),
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
        // Joined on the date-bearing event id, not the display label — the labels are baked at
        // briefing BUILD time and go stale by a civil day after midnight.
        BestBet pick = new BestBet(1, "Headline", "Detail", TODAY + "_sunset", "Tyne and Wear",
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
        BestBet pick = new BestBet(1, "H", "D", TODAY + "_sunrise", "Tyne and Wear",
                null, null, "Today", "sunrise", "05:09", null, List.of());
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNSET, "Durham", Verdict.GO,
                List.of(slotAt(1L, "Alpha", 4, TODAY, 21)))), List.of(pick));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).windows().get(0).sameWindowAsBestBet())
                .isFalse();
    }

    @Test
    @DisplayName("a RUNNER-UP pick does not flag the window — only rank 1 is the Best Bet")
    void runnerUpPickDoesNotFlagTheWindow() {
        // The banner directly above labels rank 2 "ALSO GOOD". Flagging it here would have the two
        // screens contradict each other, and "going local is not a compromise" is untrue when
        // rank 1 is a different window entirely.
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        BestBet rank1 = new BestBet(1, "H", "D", TODAY.plusDays(2) + "_sunset", "Northumberland",
                null, null, "Saturday", "sunset", "21:19", null, List.of());
        BestBet rank2 = new BestBet(2, "H", "D", TODAY + "_sunrise", "Durham",
                null, null, "Today", "sunrise", "05:09", null, List.of());

        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNRISE, "Durham", Verdict.GO,
                List.of(slotAt(1L, "Alpha", 4, TODAY, 5)))), List.of(rank1, rank2));

        CloseToHomeResponse.Window w = service.build(1L, HOME_LAT, HOME_LON).windows().get(0);

        assertThat(w.sameWindowAsBestBet())
                .as("rank 2 occupies this window, but it is not the Best Bet")
                .isFalse();
        assertThat(w.bestBetRegionName()).isNull();
    }

    @Test
    @DisplayName("a STALE day label does not misfire the Best Bet flag — the join is on the date")
    void staleDayLabelDoesNotMisfireBestBetFlag() {
        // dayName is baked at briefing BUILD time. Between midnight and the next pipeline run it
        // is a civil day behind, so a label comparison put the chip on TOMORROW's window and took
        // it off the one that actually was the Best Bet. The event id carries the real date.
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        BestBet stalePick = new BestBet(1, "H", "D", TODAY + "_sunset", "Tyne and Wear",
                null, null, "Yesterday", "sunset", "21:19", null, List.of());

        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNSET, "Durham", Verdict.GO,
                List.of(slotAt(1L, "Alpha", 4, TODAY, 21)))), List.of(stalePick));

        // The label says "Yesterday" and the date says today. The date wins.
        assertThat(service.build(1L, HOME_LAT, HOME_LON).windows().get(0).sameWindowAsBestBet())
                .isTrue();
    }

    @Test
    @DisplayName("an aurora pick never flags a sunrise/sunset window")
    void auroraPickNeverFlagsASolarWindow() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        BestBet aurora = new BestBet(1, "H", "D", TODAY + "_aurora", "Northumberland",
                null, null, "Today", "aurora", "after dark", null, List.of());

        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNSET, "Durham", Verdict.GO,
                List.of(slotAt(1L, "Alpha", 4, TODAY, 21)))), List.of(aurora));

        assertThat(service.build(1L, HOME_LAT, HOME_LON).windows().get(0).sameWindowAsBestBet())
                .isFalse();
    }

    @Test
    @DisplayName("no flag when the grid cell itself does not read stand-down")
    void noFlagWhenDisplayVerdictIsHealthy() {
        // The inverse of notInBriefingFlag: triage said STANDDOWN but live ratings lifted the cell
        // to WORTH_IT, so the user sees a healthy region and the chip must not contradict it.
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNRISE, "Durham",
                Verdict.STANDDOWN, DisplayVerdict.WORTH_IT,
                List.of(slotAt(1L, "Alpha", 4, TODAY, 5)))), List.of());

        assertThat(service.build(1L, HOME_LAT, HOME_LON).windows().get(0).notInBriefing()).isFalse();
    }

    // ── the breadcrumb's next-window ordering ─────────────────────────────────

    @Test
    @DisplayName("nextWindow tiebreaks on RATING within an event, not on per-location solar time")
    void nextWindowTiebreaksOnRatingWithinAnEvent() {
        // The rule the deleted client suite documented as a shipped bug: keying on the
        // per-location solarEventTime makes the rating tiebreak unreachable, because two
        // locations never share a sunrise to the minute. The EVENT is the key.
        LocationEntity early = loc(1L, "Early Poor", 54.80, -1.58, "Durham");
        LocationEntity late = loc(2L, "Later Better", 54.81, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(early, late));

        BriefingSlot earlySlot = new BriefingSlot(1L, "Early Poor", TODAY.atTime(5, 12),
                Verdict.GO, null, BriefingSlot.TideInfo.NONE, List.of(), null)
                .withClaudeScores(3, null, null, "s");
        BriefingSlot lateSlot = new BriefingSlot(2L, "Later Better", TODAY.atTime(5, 15),
                Verdict.GO, null, BriefingSlot.TideInfo.NONE, List.of(), null)
                .withClaudeScores(5, null, null, "s");
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNRISE, "Durham", Verdict.GO,
                List.of(earlySlot, lateSlot))), List.of());

        assertThat(service.build(1L, HOME_LAT, HOME_LON).breadcrumb().nextWindow().card().locationName())
                .as("the better-rated spot at the same event wins, despite the later solar time")
                .isEqualTo("Later Better");
    }

    @Test
    @DisplayName("nextWindow carries the CARD, identical to rank 1 of the first window")
    void nextWindowCarriesTheSameCardAsTheGrid() {
        // The shortcut now renders everything a card renders — region, distance, drive, tide — and
        // it is by construction rank 1 of windows[0]: the same comparator over the same qualifying
        // set decides both. Copying those fields onto NextWindow would put the same location on
        // one screen twice from two mappings, so the record carries the card itself and this pins
        // that they are equal, field for field.
        // TWO locations, differing in every displayed field. With one, "the shortcut equals the
        // grid" is satisfied by any mapping at all, including one that picked the wrong candidate:
        // the runner-up exists so a shortcut resolved from the wrong end of the sort fails here.
        LocationEntity coastal = loc(1L, "Seaham", 54.84, -1.34, "Durham");
        LocationEntity inland = loc(2L, "Penshaw Monument", 54.8926, -1.4776, "Tyne and Wear");
        when(locationService.findAllEnabled()).thenReturn(List.of(coastal, inland));
        BriefingSlot slot = new BriefingSlot(1L, "Seaham", TODAY.atTime(21, 0), Verdict.GO,
                null, tideInfo("HIGH", true, false, true), List.of(), null)
                .withClaudeScores(5, null, null, "s");
        BriefingSlot runnerUp = new BriefingSlot(2L, "Penshaw Monument", TODAY.atTime(21, 0),
                Verdict.GO, null, BriefingSlot.TideInfo.NONE, List.of(), null)
                .withClaudeScores(3, null, null, "s");
        givenBriefing(slot, runnerUp);
        when(driveTimeResolver.getAllMinutes(7L)).thenReturn(Map.of(1L, 18, 2L, 25));

        CloseToHomeResponse response = service.build(7L, HOME_LAT, HOME_LON);
        CloseToHomeResponse.Card grid = cardsOf(response).get(0);
        CloseToHomeResponse.Card shortcut = response.breadcrumb().nextWindow().card();

        assertThat(grid.locationName()).as("rank 1 is the 5-star spot").isEqualTo("Seaham");
        assertThat(shortcut.locationName()).isEqualTo(grid.locationName());
        assertThat(shortcut.regionName()).isEqualTo(grid.regionName());
        assertThat(shortcut.rating()).isEqualTo(grid.rating());
        assertThat(shortcut.distanceMiles()).isEqualTo(grid.distanceMiles());
        assertThat(shortcut.driveMinutes()).isEqualTo(grid.driveMinutes());
        assertThat(shortcut.tideLabel()).isEqualTo(grid.tideLabel()).isEqualTo("spring tide");
        // Not lead, though the grid copy is: the gold accent marks rank 1 of the FIRST WINDOW, and
        // a second gold-accented card outside the grid would spend the signal twice.
        assertThat(grid.lead()).isTrue();
        assertThat(shortcut.lead()).isFalse();
    }

    @Test
    @DisplayName("nextWindow picks the SOONEST event first, before rating")
    void nextWindowPrefersTheSoonestEvent() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenWindows(List.of(
                new WindowSpec(TODAY.plusDays(1), TargetType.SUNSET, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 5, TODAY.plusDays(1), 21))),
                new WindowSpec(TODAY, TargetType.SUNRISE, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 3, TODAY, 5)))),
                List.of());

        // The 5★ is later; the soonest qualifying window is what a user can act on tonight.
        assertThat(service.build(1L, HOME_LAT, HOME_LON).breadcrumb().nextWindow().date())
                .isEqualTo(TODAY);
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

    // ── tide labels: ported from the deleted client suite, whose rule I got wrong ─────

    @Test
    @DisplayName("a king tide is named even when the tide state is unknown")
    void kingTideNamedWithoutState() {
        // The first port gated king/spring on a non-null tideState and silently dropped them.
        assertThat(tideLabelFor(tideInfo(null, false, true, false))).isEqualTo("king tide");
    }

    @Test
    @DisplayName("a spring tide is named even when the tide state is unknown")
    void springTideNamedWithoutState() {
        assertThat(tideLabelFor(tideInfo(null, false, false, true))).isEqualTo("spring tide");
    }

    @Test
    @DisplayName("a plain tide state is named only when it MATCHES the location's preference")
    void plainStateOnlyWhenAligned() {
        assertThat(tideLabelFor(tideInfo("HIGH", true, false, false)))
                .isEqualTo("high tide");
    }

    @Test
    @DisplayName("a MISMATCHED tide is not advertised — worse than saying nothing")
    void mismatchedTideNotNamed() {
        // A low-tide location sitting at high water read "high tide", advertising the very state
        // it is not wanted at.
        assertThat(tideLabelFor(tideInfo("HIGH", false, false, false))).isNull();
    }

    @Test
    @DisplayName("king outranks spring, and both outrank the plain state")
    void kingOutranksSpring() {
        assertThat(tideLabelFor(tideInfo("HIGH", true, true, true))).isEqualTo("king tide");
    }

    private static BriefingSlot.TideInfo tideInfo(String state, boolean aligned,
            boolean king, boolean spring) {
        return new BriefingSlot.TideInfo(state, aligned, null, null, king, spring,
                null, null, null);
    }

    private String tideLabelFor(BriefingSlot.TideInfo tide) {
        LocationEntity a = loc(1L, "Coastal", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        BriefingSlot slot = new BriefingSlot(1L, "Coastal", TODAY.atTime(21, 0), Verdict.GO,
                null, tide, List.of(), null).withClaudeScores(4, null, null, "s");
        givenBriefing(slot);
        return cardsOf(service.build(1L, HOME_LAT, HOME_LON)).get(0).tideLabel();
    }

    // ── caps and counts ───────────────────────────────────────────────────────

    @Test
    @DisplayName("at most 3 windows, keeping the SOONEST three")
    void windowsCappedAtThree() {
        LocationEntity a = loc(1L, "Alpha", 54.80, -1.58, "Durham");
        when(locationService.findAllEnabled()).thenReturn(List.of(a));
        givenWindows(List.of(
                new WindowSpec(TODAY, TargetType.SUNRISE, "D", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 4, TODAY, 5))),
                new WindowSpec(TODAY, TargetType.SUNSET, "D", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 4, TODAY, 21))),
                new WindowSpec(TODAY.plusDays(1), TargetType.SUNRISE, "D", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 4, TODAY.plusDays(1), 5))),
                new WindowSpec(TODAY.plusDays(1), TargetType.SUNSET, "D", Verdict.GO,
                        List.of(slotAt(1L, "Alpha", 5, TODAY.plusDays(1), 21)))),
                List.of());

        List<CloseToHomeResponse.Window> w = service.build(1L, HOME_LAT, HOME_LON).windows();

        // The 5-star window is fourth chronologically and is dropped: the block is a glance at
        // what is SOON, not a ranked list of the horizon.
        assertThat(w).hasSize(3);
        assertThat(w.get(0).date()).isEqualTo(TODAY);
        assertThat(w).extracting(CloseToHomeResponse.Window::bestRating).doesNotContain(5);
    }

    @Test
    @DisplayName("withinReach and the card list can never disagree")
    void withinReachEqualsTheCardCount() {
        // The header reads this number under "within N miles of home". It describes the radius —
        // and now also the list, since the client is handed every qualifier and decides for itself
        // how many to show at once.
        List<LocationEntity> locs = new java.util.ArrayList<>();
        List<BriefingSlot> slots = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            locs.add(loc(i, "L" + i, 54.80 + i * 0.001, -1.58, "Durham"));
            slots.add(slotAt((long) i, "L" + i, 4, TODAY, 5));
        }
        when(locationService.findAllEnabled()).thenReturn(locs);
        givenWindows(List.of(new WindowSpec(TODAY, TargetType.SUNRISE, "D", Verdict.GO, slots)),
                List.of());

        CloseToHomeResponse.Window w = service.build(1L, HOME_LAT, HOME_LON).windows().get(0);

        assertThat(w.cards()).hasSize(6);
        assertThat(w.withinReach()).isEqualTo(6);
    }

    // ── the breadcrumb's event time ───────────────────────────────────────────

    @Test
    @DisplayName("the next event still carries a time when nothing in radius is briefed for it")
    void breadcrumbTimeSurvivesAnEmptyRadius() {
        // Taking the time only from in-radius candidates left it null exactly when the block
        // matters most, so the breadcrumb read "Tomorrow sunrise" with no time beside it.
        LocationEntity far = loc(2L, "Bamburgh", 55.6090, -1.7099, "Northumberland");
        when(locationService.findAllEnabled()).thenReturn(List.of(nearLoc(), far));
        givenWindows(List.of(
                new WindowSpec(TODAY, TargetType.SUNRISE, "Northumberland", Verdict.GO,
                        List.of(slotAt(2L, "Bamburgh", 5, TODAY, 5))),
                new WindowSpec(TODAY, TargetType.SUNSET, "Durham", Verdict.GO,
                        List.of(slotAt(1L, "Penshaw Monument", 4, TODAY, 21)))),
                List.of());

        CloseToHomeResponse.Breadcrumb b = service.build(1L, HOME_LAT, HOME_LON).breadcrumb();

        assertThat(b.eventTime()).as("time comes from the briefing, not only from in-radius slots")
                .isNotNull();
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
