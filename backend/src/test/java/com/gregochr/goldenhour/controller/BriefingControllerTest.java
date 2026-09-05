package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.BriefingModelTestResultEntity;
import com.gregochr.goldenhour.entity.BriefingModelTestRunEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
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
    @WithMockUser
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
    @WithMockUser
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
    @WithMockUser
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
    @WithMockUser
    @DisplayName("GET /api/briefing routes through getCachedBriefingForApi (not the raw accessor)")
    void getBriefing_routesThroughHonestyApiAccessor() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing")).andExpect(status().isOk());

        verify(briefingService).getCachedBriefingForApi();
        verify(briefingService, org.mockito.Mockito.never()).getCachedBriefing();
    }

    @Test
    @WithMockUser
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
    @WithMockUser
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
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/briefing/run triggers refresh and returns 200 for ADMIN")
    void runBriefing_adminTriggersRefresh() throws Exception {
        mockMvc.perform(post("/api/briefing/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Briefing refresh complete."));

        verify(briefingService).refreshBriefing();
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

    // ── Compare models endpoints ──

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/briefing/compare-models returns 200 for ADMIN")
    void compareModels_adminSuccess() throws Exception {
        BriefingModelTestRunEntity run = BriefingModelTestRunEntity.builder()
                .id(1L).succeeded(3).failed(0).build();
        when(briefingModelTestService.runComparison()).thenReturn(run);

        mockMvc.perform(post("/api/briefing/compare-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.succeeded").value(3));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/briefing/compare-models returns 403 for non-admin")
    void compareModels_nonAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/briefing/compare-models"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/briefing/compare-models/runs returns recent runs")
    void getComparisonRuns_adminSuccess() throws Exception {
        when(briefingModelTestService.getRecentRuns()).thenReturn(List.of(
                BriefingModelTestRunEntity.builder().id(1L).succeeded(3).failed(0).build()));

        mockMvc.perform(get("/api/briefing/compare-models/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/briefing/compare-models/results returns results for run")
    void getComparisonResults_adminSuccess() throws Exception {
        when(briefingModelTestService.getResults(1L)).thenReturn(List.of(
                BriefingModelTestResultEntity.builder()
                        .id(1L).testRunId(1L).evaluationModel(EvaluationModel.HAIKU)
                        .succeeded(true).createdAt(LocalDateTime.now()).build()));

        mockMvc.perform(get("/api/briefing/compare-models/results").param("runId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].evaluationModel").value("HAIKU"));
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
    @WithMockUser
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
    @WithMockUser
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
    @WithMockUser
    @DisplayName("GET /api/briefing/digest honours an explicit limit")
    void getDigest_honoursLimit() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows.length()").value(1))
                .andExpect(jsonPath("$.windows[0].date").value("2999-03-25"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing/digest returns 204 when cache is empty")
    void getDigest_noContent() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);

        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("a LITE user may read the digest — it projects a payload they already read in full")
    void getDigest_liteUserMayRead() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows.length()").value(2));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a PRO user may read it too")
    void getDigest_proUserMayRead() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an admin may read it — no role is excluded, which is the claim being pinned")
    void getDigest_adminMayRead() throws Exception {
        // The three roles are asserted for the reason CLAUDE.md gives on the almanac feed: this
        // project has already documented an endpoint as ADMIN that the code never enforced, so a
        // missing @PreAuthorize has to be provably deliberate rather than merely absent.
        when(briefingService.getCachedBriefingForApi()).thenReturn(buildDigestBriefing());

        mockMvc.perform(get("/api/briefing/digest")).andExpect(status().isOk());
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
