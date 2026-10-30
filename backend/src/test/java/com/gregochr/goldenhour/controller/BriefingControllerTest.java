package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BigSeasDay;
import com.gregochr.goldenhour.model.BigSeasStructure;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.service.BriefingRoleShaper;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BriefingController}.
 */
class BriefingControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing returns 200 with cached briefing")
    void getBriefing_returnsCachedResult() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Today sunset looks promising in Lake District"))
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].date").value("2026-03-25"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].targetType").value("SUNSET"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].regionName")
                        .value("Lake District"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].verdict").value("GO"))
                // The map-tab tide-on-the-light fields land FLAT on the slot through the REAL
                // Jackson 3 response chain (@JsonUnwrapped survives the Jackson 2 -> 3 boundary;
                // DailyBriefingResponseJsonTest only proves the hand-built Jackson 2 mapper).
                .andExpect(jsonPath(
                        "$.days[0].eventSummaries[0].regions[0].slots[0].tideOnTheLight")
                        .value(true))
                .andExpect(jsonPath(
                        "$.days[0].eventSummaries[0].regions[0].slots[0].nearestSolarOffsetPhrase")
                        .value("HW 19:00 · 30m after sunset"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing serialises the UNRATED display verdict by name on region and slot "
            + "through the real Jackson 3 response chain (unrated-verdict-plan.md U1)")
    void getBriefing_serialisesUnratedByName() throws Exception {
        BriefingSlot slot = new BriefingSlot("Bamburgh", LocalDateTime.of(2026, 10, 16, 6, 40),
                Verdict.GO, new BriefingSlot.WeatherConditions(10, BigDecimal.ZERO, 20000, 60,
                        12.0, 10.0, 1, BigDecimal.ONE, 0, 0),
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion("Northumberland",
                Verdict.GO, "Clear at 1 of 1 location",
                List.of(), List.of(slot), 10.5, 8.0, 3.2, 1, null, null,
                DisplayVerdict.UNRATED, 1);
        BriefingDay day = new BriefingDay(LocalDate.of(2026, 10, 16), List.of(
                new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of())));
        when(briefingService.getCachedBriefingForApi()).thenReturn(new DailyBriefingResponse(
                LocalDateTime.of(2026, 10, 13, 14, 0), "Headline",
                List.of(day), List.of(), null, null, false, false, 0, "Opus", List.of(), List.of()));

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].displayVerdict")
                        .value("UNRATED"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].displayVerdict")
                        .value("UNRATED"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing returns 204 when cache is empty")
    void getBriefing_noContent() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /api/briefing returns 401 without authentication")
    void getBriefing_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/briefing accessible to LITE_USER")
    void getBriefing_liteUser() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("JSON includes slot-level detail")
    void getBriefing_slotDetail() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].locationName")
                        .value("Keswick"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].verdict")
                        .value("GO"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].lowCloudPercent")
                        .value(15))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].flags[0]")
                        .value("Tide aligned"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing routes through getCachedBriefingForApi (not the raw accessor)")
    void getBriefing_routesThroughHonestyApiAccessor() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing")).andExpect(status().isOk());

        verify(briefingService).getCachedBriefingForApi();
        verify(briefingService, org.mockito.Mockito.never()).getCachedBriefing();
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing — honesty-filtered region JSON shape "
            + "(verdictLabel, empty slots, replacement summary)")
    void getBriefing_zeroCoverageSurfacesHonestyFilter() throws Exception {
        // Stubs the service with a region in its post-filter shape — verdictLabel set,
        // slots cleared, replacement summary — so the test asserts the controller serialises
        // those fields correctly without coupling to the filter implementation.
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildHonestyFilteredBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].displayVerdict")
                        .value("STAND_DOWN"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].verdictLabel")
                        .value("Too unsettled to forecast"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].summary")
                        .value("No per-location forecast — conditions too unsettled to evaluate"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots.length()")
                        .value(0))
                // Triage verdict preserved for downstream consumers.
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].verdict")
                        .value("GO"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing — bestBets field is present even when empty")
    void getBriefing_bestBetsAlwaysPresent() throws Exception {
        // Verifies that @JsonInclude(ALWAYS) on bestBets means the field is never silently
        // omitted from the HTTP response body, even when the list is empty.
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBets").isArray())
                .andExpect(jsonPath("$.bestBets.length()").value(0));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/briefing — a LITE reader gets bestBets present and empty, and no model, "
            + "status or withdrawn flag, even when the briefing lists a best bet")
    void getBriefing_liteBestBetsEmptyButPresent() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildPickBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBets").isArray())
                .andExpect(jsonPath("$.bestBets.length()").value(0))
                .andExpect(jsonPath("$.bestBetModel").doesNotExist())
                .andExpect(jsonPath("$.bestBetStatus").doesNotExist())
                .andExpect(jsonPath("$.bestBetsWithdrawn").doesNotExist())
                .andExpect(jsonPath("$.days[0].eventSummaries[0].window.pick").doesNotExist())
                .andExpect(jsonPath("$.days[0].eventSummaries[0].window.bestRating").value(4));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing — a PRO reader keeps the window pick, the best bets, the model "
            + "and the status")
    void getBriefing_proKeepsPicks() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildPickBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBets.length()").value(1))
                .andExpect(jsonPath("$.bestBetModel").value("Opus"))
                .andExpect(jsonPath("$.bestBetStatus").value("SUCCESS_WITH_PICKS"))
                .andExpect(jsonPath("$.bestBetsWithdrawn").value(true))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].window.pick.kind").value("BEST"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].window.pick.locationName")
                        .value("Keswick"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/briefing — an ADMIN keeps the window pick and the best bets")
    void getBriefing_adminKeepsPicks() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildPickBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBets.length()").value(1))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].window.pick.kind").value("BEST"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing — a bare ROLE_USER fails closed: no picks")
    void getBriefing_bareUserFailsClosedOnPicks() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildPickBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBets.length()").value(0))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].window.pick").doesNotExist());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/briefing/run triggers refresh and returns 200 for ADMIN")
    void runBriefing_adminTriggersRefresh() throws Exception {
        when(briefingService.refreshBriefingIfIdle()).thenReturn(true);

        mockMvc.perform(post("/api/briefing/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Briefing refresh complete."));

        verify(briefingService).refreshBriefingIfIdle();
        // One build per press: not the refusing entry AND the waiting one after it.
        verify(briefingService, never()).refreshBriefing();
    }

    /**
     * ⚠️ The admin endpoint takes the REFUSING entry point. The waiting one
     * ({@code refreshBriefing}) belongs to the pipeline alone: taken here, a press during the
     * pipeline's build would hold the request open and then run a second full build behind it.
     */
    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/briefing/run returns 409 while a briefing refresh is already running")
    void runBriefing_whileRunning_returns409() throws Exception {
        when(briefingService.refreshBriefingIfIdle()).thenReturn(false);

        mockMvc.perform(post("/api/briefing/run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("A briefing refresh is already running."));

        verify(briefingService, never()).refreshBriefing();
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/briefing/run returns 403 for non-admin")
    void runBriefing_nonAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/briefing/run"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/briefing/run returns 401 without authentication")
    void runBriefing_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/briefing/run"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/briefing: the role-shaped tide body (tide-pro-gate-plan.md §7) ───────────────
    //
    // BriefingRoleShaper is the REAL bean here, never a @MockitoBean: a mock would fork
    // AbstractControllerTest's shared context. The shaped body is what the caller sees on the
    // Jackson 3 wire.

    private static final String WINDOW = "$.days[0].eventSummaries[0].window";
    private static final String SLOT = "$.days[0].eventSummaries[0].regions[0].slots[0]";

    private static DailyBriefingResponse tideBriefing() {
        BriefingWindowTide tide = new BriefingWindowTide("Whitby", TideState.MID,
                BriefingWindowTide.Direction.FALLING, "HW", "19:28", "1h43 before sunset",
                "4.9 m", "1.2 m above an average tide", "0.3 m · smooth",
                List.of(0.0, 0.5, 1.0), 0.88, 0.42, 0.25, 0.75,
                List.of(new BriefingWindowTide.Extreme("HW", 0.8, "19:28")), "3.1 m",
                "HW", "4.6 m", "20:49");
        LocationTideFact fact = new LocationTideFact(7L, "Bamburgh", "HIGH", true, 0.61, true,
                "HW 06:12 · 25m after sunrise", 0.83, "RISING", "4.9 m", "wants higher",
                "Tide suits this spot");
        BriefingWindow window = new BriefingWindow(null, DisplayVerdict.WORTH_IT, 4, null, null,
                List.of(), null, tide, List.of(fact));
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh", LocalDateTime.of(2026, 10, 11, 6, 0),
                Verdict.GO, null, null, List.of("Tide aligned", "Clear all layers"), null, null,
                null, null, null, null, DisplayVerdict.AWAITING, null, false, null);
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "s", List.of(),
                List.of(slot), null, null, null, null, null, null, DisplayVerdict.AWAITING, 0,
                null, false, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(region), List.of(), null, window);
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new BriefingDay(LocalDate.of(2026, 10, 11), List.of(summary))),
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/briefing as LITE: no curve, no positions, no height, no match; free facts stay")
    void getBriefing_liteGetsNoCurveAndNoMatch() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(tideBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                // curve is stripped to the empty list, never to an absent key.
                .andExpect(jsonPath(WINDOW + ".tide.curve").isEmpty())
                .andExpect(jsonPath(WINDOW + ".tide.windowPosition").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tide.windowLevel").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tide.sunrisePosition").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tide.sunsetPosition").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tide.extremes").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tide.heightAtWindow").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tide.state").value("MID"))
                .andExpect(jsonPath(WINDOW + ".tide.direction").value("FALLING"))
                .andExpect(jsonPath(WINDOW + ".tide.nearestTime").value("19:28"))
                .andExpect(jsonPath(WINDOW + ".tide.rangeAnomaly")
                        .value("1.2 m above an average tide"))
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideAligned").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideAlignmentQuality").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideShortfall").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideFitPhrase").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideHeight").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideLevel").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideState").value("HIGH"))
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideDirection").value("RISING"))
                // The tide against the light is Pro (tide-light-pro-plan.md B1).
                .andExpect(jsonPath(WINDOW + ".tide.nearestOffset").value(org.hamcrest.Matchers.nullValue()))
                // The map strip's light-alignment fields are omitted, not sent null.
                .andExpect(jsonPath(WINDOW + ".tide.lightAlignment").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tide.nearestHeight").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tide.eventTime").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideOnTheLight").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].nearestSolarOffsetPhrase").doesNotExist())
                .andExpect(jsonPath(SLOT + ".flags.length()").value(1))
                .andExpect(jsonPath(SLOT + ".flags[0]").value("Clear all layers"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing as PRO keeps the curve, the positions and the match")
    void getBriefing_proKeepsCurveAndMatch() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(tideBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(WINDOW + ".tide.curve.length()").value(3))
                .andExpect(jsonPath(WINDOW + ".tide.windowPosition").value(0.88))
                .andExpect(jsonPath(WINDOW + ".tide.windowLevel").value(0.42))
                .andExpect(jsonPath(WINDOW + ".tide.heightAtWindow").value("3.1 m"))
                .andExpect(jsonPath(WINDOW + ".tide.nearestOffset").value("1h43 before sunset"))
                .andExpect(jsonPath(WINDOW + ".tide.lightAlignment").value("HW"))
                .andExpect(jsonPath(WINDOW + ".tide.nearestHeight").value("4.6 m"))
                .andExpect(jsonPath(WINDOW + ".tide.eventTime").value("20:49"))
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideOnTheLight").value(true))
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].nearestSolarOffsetPhrase")
                        .value("HW 06:12 · 25m after sunrise"))
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideAligned").value(true))
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideFitPhrase")
                        .value("Tide suits this spot"))
                .andExpect(jsonPath(SLOT + ".flags.length()").value(2))
                .andExpect(jsonPath(SLOT + ".flags[0]").value("Tide aligned"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/briefing as ADMIN keeps the curve, the positions and the match")
    void getBriefing_adminKeepsCurveAndMatch() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(tideBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(WINDOW + ".tide.curve.length()").value(3))
                .andExpect(jsonPath(WINDOW + ".tide.nearestOffset").value("1h43 before sunset"))
                .andExpect(jsonPath(WINDOW + ".tide.lightAlignment").value("HW"))
                .andExpect(jsonPath(WINDOW + ".tide.nearestHeight").value("4.6 m"))
                .andExpect(jsonPath(WINDOW + ".tide.eventTime").value("20:49"))
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideOnTheLight").value(true))
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideAligned").value(true))
                .andExpect(jsonPath(SLOT + ".flags[0]").value("Tide aligned"));
    }

    // ── GET /api/briefing: the tide at the light (tide-light-pro-plan.md section 3, B1) ───────

    private static final String ALIGNED_SENTENCE = "tide aligned with sunrise at 47 of 61 coastal locations";
    private static final String LITE_SENTENCE = "61 coastal locations";

    /** A briefing with a king-tide topic, its window badge, a big-seas day and a tide-mismatch stand-down. */
    private static DailyBriefingResponse tideLightBriefing() {
        LocalDate day = LocalDate.of(2026, 10, 11);
        HotTopic king = new HotTopic(
                "KING_TIDE", "King tide", ALIGNED_SENTENCE, day, 1, null, List.of("North East"), "d",
                new ExpandedHotTopicDetail(List.of(), null,
                        new ExpandedHotTopicDetail.TideMetrics("King tide", "Full Moon", 47, 3)),
                "SUNRISE", "06:41", null, null, null, null, null,
                new TideRunDay("KING RUN", 1, 3, "SUN 11", "Whitby", "4.2 m",
                        null, null, null, null, "06:41", "17:55", null, List.of(),
                        "HW 07:12 · 31m after sunrise", true, true, "sunrise", "HW 07:12 · 31m after sunrise",
                        null, false, null),
                null, null, null, null).withLiteDetail(LITE_SENTENCE);
        BriefingWindow window = new BriefingWindow(null, DisplayVerdict.WORTH_IT, 4, null, null,
                List.of(new BriefingWindow.Badge("KING_TIDE", "King tide", ALIGNED_SENTENCE, List.of(),
                        "06:41", 2, null, null, null)), null, null, null);
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh", LocalDateTime.of(2026, 10, 11, 6, 0),
                Verdict.STANDDOWN, null, null, List.of(), "Tide mismatch", null, null, null, null, null,
                DisplayVerdict.STAND_DOWN, null, false, null);
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "s", List.of(),
                List.of(slot), null, null, null, null, null, null, DisplayVerdict.AWAITING, 0,
                null, false, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(region), List.of(), null, window);
        LocalDateTime hw = LocalDateTime.of(2026, 10, 11, 7, 12);
        BigSeasDay seas = new BigSeasDay(day, hw.minusHours(1), hw.plusHours(1), hw,
                BigSeasDay.AlignedEvent.SUNRISE, 31L, BigSeasDay.GapSide.AFTER, 4.2, 80, 9.5,
                List.of(new BigSeasStructure(243L, "Roker Pier", CoastalStructure.PIER, 4.2, 80, 9.5, 90, hw,
                        true)),
                0, false, null, null);
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new BriefingDay(day, List.of(summary))),
                List.of(), null, null, false, false, 0, null, List.of(king), List.of())
                .withBigSeas(List.of(seas));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/briefing as LITE: no alignment sentence, run row, event, aligned counts, badge detail "
            + "or clock, big-seas gap, aligned flag or tide-mismatch reason; the topic and daylight HW stay")
    void getBriefing_liteTideLightIsStripped() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(tideLightBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotTopics.length()").value(1))
                .andExpect(jsonPath("$.hotTopics[0].type").value("KING_TIDE"))
                .andExpect(jsonPath("$.hotTopics[0].label").value("King tide"))
                .andExpect(jsonPath("$.hotTopics[0].detail").value(LITE_SENTENCE))
                .andExpect(jsonPath("$.hotTopics[0].liteDetail").doesNotExist())
                .andExpect(jsonPath("$.hotTopics[0].tideRun").doesNotExist())
                .andExpect(jsonPath("$.hotTopics[0].eventType").doesNotExist())
                .andExpect(jsonPath("$.hotTopics[0].eventTime").doesNotExist())
                .andExpect(jsonPath("$.hotTopics[0].expandedDetail.tideMetrics.tidalClassification")
                        .value("King tide"))
                .andExpect(jsonPath("$.hotTopics[0].expandedDetail.tideMetrics.sunriseAlignedCount").doesNotExist())
                .andExpect(jsonPath("$.hotTopics[0].expandedDetail.tideMetrics.sunsetAlignedCount").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".badges[0].detail").value(LITE_SENTENCE))
                .andExpect(jsonPath(WINDOW + ".badges[0].eventTime").doesNotExist())
                .andExpect(jsonPath(SLOT + ".standdownReason").doesNotExist())
                .andExpect(jsonPath(SLOT + ".verdict").value("STANDDOWN"))
                .andExpect(jsonPath("$.bigSeas[0].alignedEvent").doesNotExist())
                .andExpect(jsonPath("$.bigSeas[0].gapMinutes").doesNotExist())
                .andExpect(jsonPath("$.bigSeas[0].gapSide").doesNotExist())
                .andExpect(jsonPath("$.bigSeas[0].structures[0].aligned").value(false))
                .andExpect(jsonPath("$.bigSeas[0].hwTime").value("2026-10-11T07:12:00"))
                .andExpect(jsonPath("$.bigSeas[0].structures[0].label").value("Roker Pier"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing as PRO keeps the alignment sentence, run row, event, counts, badge and gap")
    void getBriefing_proKeepsTideLight() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(tideLightBriefing());

        assertProTideLight();
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/briefing as ADMIN gets exactly what PRO gets (Admin = Pro)")
    void getBriefing_adminKeepsTideLight() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(tideLightBriefing());

        assertProTideLight();
    }

    private void assertProTideLight() throws Exception {
        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotTopics[0].detail").value(ALIGNED_SENTENCE))
                .andExpect(jsonPath("$.hotTopics[0].liteDetail").doesNotExist())
                .andExpect(jsonPath("$.hotTopics[0].tideRun.alignedEvent").value("sunrise"))
                .andExpect(jsonPath("$.hotTopics[0].eventType").value("SUNRISE"))
                .andExpect(jsonPath("$.hotTopics[0].eventTime").value("06:41"))
                .andExpect(jsonPath("$.hotTopics[0].expandedDetail.tideMetrics.sunriseAlignedCount").value(47))
                .andExpect(jsonPath("$.hotTopics[0].expandedDetail.tideMetrics.sunsetAlignedCount").value(3))
                .andExpect(jsonPath(WINDOW + ".badges[0].detail").value(ALIGNED_SENTENCE))
                .andExpect(jsonPath(WINDOW + ".badges[0].eventTime").value("06:41"))
                .andExpect(jsonPath(SLOT + ".standdownReason").value("Tide mismatch"))
                .andExpect(jsonPath("$.bigSeas[0].alignedEvent").value("SUNRISE"))
                .andExpect(jsonPath("$.bigSeas[0].gapMinutes").value(31))
                .andExpect(jsonPath("$.bigSeas[0].gapSide").value("AFTER"))
                .andExpect(jsonPath("$.bigSeas[0].structures[0].aligned").value(true));
    }

    @Test
    @DisplayName("PRO and ADMIN are served the service's own object (isSameAs): nothing is rebuilt, so the "
            + "body is byte-identical; LITE and an unrecognised role are served a shaped copy")
    void getBriefing_proAndAdminAreServedTheServiceObjectItself() {
        DailyBriefingResponse served = tideLightBriefing();
        when(briefingService.getCachedBriefingForApi()).thenReturn(served);
        BriefingController controller = new BriefingController(briefingService, null,
                new BriefingRoleShaper(),
                new com.gregochr.goldenhour.service.LitePickShaper(null,
                        new com.gregochr.goldenhour.config.SubRegionProperties()));

        assertThat(controller.getBriefing(auth("ROLE_PRO_USER")).getBody()).isSameAs(served);
        assertThat(controller.getBriefing(auth("ROLE_ADMIN")).getBody()).isSameAs(served);
        assertThat(controller.getBriefing(auth("ROLE_LITE_USER")).getBody()).isNotSameAs(served);
        assertThat(controller.getBriefing(auth("ROLE_USER")).getBody()).isNotSameAs(served);
        assertThat(controller.getBriefing(auth("ROLE_USER")).getBody().hotTopics().get(0).tideRun()).isNull();
        assertThat(served.hotTopics().get(0).tideRun()).as("the service object is never mutated").isNotNull();
    }

    private static Authentication auth(String authority) {
        return new UsernamePasswordAuthenticationToken("u", "p", List.of(new SimpleGrantedAuthority(authority)));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing fails closed: the default ROLE_USER is served as LITE")
    void getBriefing_unknownRoleIsServedAsLite() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(tideBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(WINDOW + ".tide.curve").isEmpty())
                .andExpect(jsonPath(WINDOW + ".tideFacts[0].tideAligned").doesNotExist());
    }

    // ── GET /api/briefing/digest ──────────────────────────────────────────────────────────────
    //
    // Driven through the REAL Spring MVC / Jackson 3 response chain and the REAL
    // BriefingDigestService bean (only BriefingService is mocked), because the whole value of this
    // endpoint is the flat wire shape a memory-constrained client decodes — which a service-level
    // test on the Jackson 2 graph cannot prove (CLAUDE.md's two-object-graphs warning).
    //
    // The fixture sits in the year 2999 deliberately. The wired SolarEventFreshness carries the
    // real system clock, so a fixture near today would retire its own windows partway through an
    // afternoon and the suite would fail by the hour. The afterglow rule itself has a fixed-clock
    // boundary pair in BriefingDigestServiceTest, which is where it belongs.

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing/digest flattens each window onto one object")
    void getDigest_returnsFlatWindows() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isOk())
                // ⚠️ Both timestamps pinned as ISO STRINGS, not just `.exists()`. This is the one
                // endpoint built for a foreign decoder, and CLAUDE.md's two-Jackson-graphs warning
                // is exactly about a `LocalDateTime` that reaches the wire as [2999,3,25,18,30]
                // instead. `JsonDateFormatContractTest` covers /api/forecast and /api/user/settings
                // and would not have noticed this one. `eventTime` is the field a widget puts on
                // screen, so it is pinned on the window too, below.
                .andExpect(jsonPath("$.generatedAt").value("2999-03-25T09:00:00"))
                .andExpect(jsonPath("$.windows.length()").value(2))
                .andExpect(jsonPath("$.windows[0].date").value("2999-03-25"))
                .andExpect(jsonPath("$.windows[0].event").value("SUNSET"))
                .andExpect(jsonPath("$.windows[0].eventTime").value("2999-03-25T18:30:00"))
                .andExpect(jsonPath("$.windows[0].verdict").value("WORTH_IT"))
                .andExpect(jsonPath("$.windows[0].bestRating").value(4))
                // ⚠️ LOWERCASE, and pinned here on purpose. `Confidence` carries a
                // `@JsonValue` of "high"/"medium"/"low", so the enum NAME never reaches the
                // wire — and the digest has to speak the same vocabulary as the full
                // briefing or a widget and the Plan tab would read one region's confidence
                // two different ways. Asserting "HIGH" here is what a service-level test on
                // the Jackson 2 graph would have let through.
                .andExpect(jsonPath("$.windows[0].confidence").value("high"))
                .andExpect(jsonPath("$.windows[0].pick").value("BEST"))
                .andExpect(jsonPath("$.windows[0].headline").value("A long clear run to the west"))
                .andExpect(jsonPath("$.windows[0].regionName").value("Lake District"))
                .andExpect(jsonPath("$.windows[0].locationName").value("Keswick"))
                // The id travels with the name for the reason BriefingWindow.Pick gives: every
                // per-user contract joins on it and carries no name, so a name-only payload forces
                // a join a rename silently empties.
                .andExpect(jsonPath("$.windows[0].locationId").value(7))
                // Flat: no tree survives the projection, which is the point of the endpoint.
                .andExpect(jsonPath("$.windows[0].slots").doesNotExist())
                .andExpect(jsonPath("$.windows[0].regions").doesNotExist())
                .andExpect(jsonPath("$.hotTopics").doesNotExist())
                .andExpect(jsonPath("$.days").doesNotExist());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing/digest omits an absent narrative rather than sending nulls")
    void getDigest_omitsAbsentNarrative() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[1].event").value("SUNRISE"))
                .andExpect(jsonPath("$.windows[1].pick").doesNotExist())
                .andExpect(jsonPath("$.windows[1].headline").doesNotExist())
                .andExpect(jsonPath("$.windows[1].regionName").doesNotExist());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing/digest honours an explicit limit")
    void getDigest_honoursLimit() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows.length()").value(1))
                .andExpect(jsonPath("$.windows[0].date").value("2999-03-25"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/briefing/digest returns 204 when cache is empty")
    void getDigest_noContent() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);

        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("a LITE user may read the digest, with the pick and what it supplies removed")
    void getDigest_liteUserMayRead() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows.length()").value(2))
                .andExpect(jsonPath("$.windows[0].verdict").value("WORTH_IT"))
                .andExpect(jsonPath("$.windows[0].bestRating").value(4))
                .andExpect(jsonPath("$.windows[0].confidence").value("high"))
                .andExpect(jsonPath("$.windows[0].pick").doesNotExist())
                .andExpect(jsonPath("$.windows[0].headline").doesNotExist())
                .andExpect(jsonPath("$.windows[0].regionName").doesNotExist())
                .andExpect(jsonPath("$.windows[0].locationName").doesNotExist())
                .andExpect(jsonPath("$.windows[0].locationId").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("a bare ROLE_USER is served the LITE digest: the check fails closed")
    void getDigest_bareUserFailsClosed() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].pick").doesNotExist())
                .andExpect(jsonPath("$.windows[0].locationId").doesNotExist());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a PRO user may read it too")
    void getDigest_proUserMayRead() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].pick").value("BEST"))
                .andExpect(jsonPath("$.windows[0].locationId").value(7));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an admin may read it — no role is excluded, which is the claim being pinned")
    void getDigest_adminMayRead() throws Exception {
        // The three roles are asserted for the reason CLAUDE.md gives on the almanac feed: this
        // project has already documented an endpoint as ADMIN that the code never enforced, so a
        // missing @PreAuthorize has to be provably deliberate rather than merely absent.
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].pick").value("BEST"))
                .andExpect(jsonPath("$.windows[0].locationId").value(7));
    }

    @Test
    @DisplayName("GET /api/briefing/digest returns 401 without authentication")
    void getDigest_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Two windows on consecutive days: a sunset carrying a pick, and a sunrise carrying none.
     */
    private static DailyBriefingResponse buildDigestBriefing() {
        BriefingWindow.Pick pick = new BriefingWindow.Pick(BriefingWindow.PickKind.BEST,
                "Lake District", "A long clear run to the west", null, 4.25, "Keswick", 7L);
        BriefingEventSummary sunset = new BriefingEventSummary(
                TargetType.SUNSET, List.of(), List.of())
                .withWindow(new BriefingWindow(LocalDateTime.of(2999, 3, 25, 18, 30),
                        DisplayVerdict.WORTH_IT, 4, Confidence.HIGH, pick, List.of(), null, null));
        BriefingEventSummary sunrise = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(), List.of())
                .withWindow(new BriefingWindow(LocalDateTime.of(2999, 3, 26, 6, 0),
                        DisplayVerdict.MAYBE, 2, Confidence.LOW, null, List.of(), null, null));

        return new DailyBriefingResponse(
                LocalDateTime.of(2999, 3, 25, 9, 0), "headline",
                List.of(new BriefingDay(LocalDate.of(2999, 3, 25), List.of(sunset)),
                        new BriefingDay(LocalDate.of(2999, 3, 26), List.of(sunrise))),
                List.of(), null, null, false, false, 0, "Opus", List.of(), List.of());
    }

    /** A briefing whose one window carries a BEST pick and which lists one best bet. */
    private static DailyBriefingResponse buildPickBriefing() {
        BriefingWindow.Pick pick = new BriefingWindow.Pick(BriefingWindow.PickKind.BEST,
                "Lake District", "A long clear run to the west", null, 4.25, "Keswick", 7L);
        BriefingEventSummary sunset = new BriefingEventSummary(
                TargetType.SUNSET, List.of(), List.of())
                .withWindow(new BriefingWindow(LocalDateTime.of(2026, 3, 25, 18, 30),
                        DisplayVerdict.WORTH_IT, 4, Confidence.HIGH, pick, List.of(), null, null));
        com.gregochr.goldenhour.model.BestBet bet = new com.gregochr.goldenhour.model.BestBet(1,
                "Go west", "Clear", "Wed sunset", "Lake District", Confidence.HIGH, null,
                "Wednesday", "SUNSET", "18:30", null, null);
        return new DailyBriefingResponse(
                LocalDateTime.of(2026, 3, 25, 14, 0), "h",
                List.of(new BriefingDay(LocalDate.of(2026, 3, 25), List.of(sunset))),
                List.of(bet), null, null, false, false, 0, "Opus", List.of(), List.of(),
                com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_WITH_PICKS, true);
    }

    private static DailyBriefingResponse buildSampleBriefing() {
        BriefingSlot slot = new BriefingSlot("Keswick",
                LocalDateTime.of(2026, 3, 25, 18, 30), Verdict.GO,
                new BriefingSlot.WeatherConditions(15, BigDecimal.ZERO, 20000, 65,
                        10.5, 8.0, 1, new BigDecimal("3.2"), 0, 0),
                // 13-arg constructor (not the 9-arg legacy overload) — this fixture must exercise
                // the map-tab tide-on-the-light fields through the REAL Spring MVC / Jackson 3
                // response chain, which the model-level DailyBriefingResponseJsonTest (a hand-built
                // Jackson 2 ObjectMapper) cannot prove on its own (CLAUDE.md's two-object-graphs
                // warning). HW 19:00 is 30 minutes after the 18:30 sunset.
                new BriefingSlot.TideInfo("HIGH", true,
                        LocalDateTime.of(2026, 3, 25, 19, 0), new BigDecimal("1.5"),
                        false, false, null, null, null,
                        30, "HW", true, "HW 19:00 · 30m after sunset"),
                List.of("Tide aligned"), null);

        // Use the 13-arg convenience constructor with scoredLocationCount=1 so
        // the Gate 2 honesty filter does NOT rewrite this fixture — the existing
        // controller tests assert end-to-end JSON shape on a covered region.
        BriefingRegion region = new BriefingRegion("Lake District",
                Verdict.GO, "Clear at 1 of 1 location",
                List.of(), List.of(slot), 10.5, 8.0, 3.2, 1, null, null,
                com.gregochr.goldenhour.model.DisplayVerdict.WORTH_IT, 1);

        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNSET, List.of(region), List.of());

        BriefingDay day = new BriefingDay(
                LocalDate.of(2026, 3, 25), List.of(eventSummary));

        return new DailyBriefingResponse(
                LocalDateTime.of(2026, 3, 25, 14, 0),
                "Today sunset looks promising in Lake District",
                List.of(day), List.of(), null, null, false, false, 0, "Opus", List.of(), List.of());
    }

    private static DailyBriefingResponse buildHonestyFilteredBriefing() {
        // Region pre-built in its post-filter shape: STAND_DOWN, verdictLabel set,
        // replacement summary, gloss replaced, slots cleared, but triage verdict
        // and weather snapshot preserved (factual fields the filter leaves alone).
        BriefingRegion region = new BriefingRegion(
                "The Lake District",
                Verdict.GO,
                "No per-location forecast — conditions too unsettled to evaluate",
                List.of(), List.of(),
                14.0, 13.0, 4.5, 3,
                null,
                "Conditions across this region were classified as too unsettled to evaluate "
                        + "confidently at this horizon. No per-location forecast was produced. "
                        + "The picture may firm up closer to the date — or it may remain unsettled.",
                com.gregochr.goldenhour.model.DisplayVerdict.STAND_DOWN,
                0,
                "Too unsettled to forecast",
                false);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNSET, List.of(region), List.of());
        BriefingDay day = new BriefingDay(LocalDate.of(2026, 5, 23), List.of(eventSummary));
        return new DailyBriefingResponse(
                LocalDateTime.of(2026, 5, 21, 14, 0),
                "Sunset window looks promising in the Lake District",
                List.of(day), List.of(), null, null, false, false, 0, "Opus", List.of(), List.of());
    }
}
