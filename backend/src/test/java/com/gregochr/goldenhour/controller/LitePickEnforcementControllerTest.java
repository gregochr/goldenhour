package com.gregochr.goldenhour.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ForecastEvaluationDto;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.SubRegionLocationRow;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.AsOfEventResolver;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.util.ForecastHorizon;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Per-endpoint tests of sub-region enforcement (docs/engineering/sub-regions-plan.md S3, §4.2) through the full
 * filter chain: with {@code photocast.sub-regions.enforce} on, a Lite reader's responses lose the ratings of
 * every location outside their picks and are marked {@code gated}; Pro, Admin and the flag-off path are served
 * exactly as before.
 *
 * <p>The reader {@code anna} has picked one sub-region holding Bamburgh Castle (id 7); Craster (id 8) is in a
 * sub-region she has not picked. {@code LitePickShaper} is the REAL bean; the picks read is the mocked
 * {@code UserSubRegionService}.
 */
class LitePickEnforcementControllerTest extends AbstractControllerTest {

    private static final long PICKED_ID = 7L;
    private static final long UNPICKED_ID = 8L;
    private static final String PICKED = "Bamburgh Castle";
    private static final String UNPICKED = "Craster";
    private static final LocalDateTime EVENT = LocalDateTime.of(2999, 3, 25, 18, 30);
    private static final LocalDate DAY = LocalDate.of(2999, 3, 25);

    private static final String REGION = "$.days[0].eventSummaries[0].regions[0]";
    private static final String PICKED_SLOT = REGION + ".slots[0]";
    private static final String UNPICKED_SLOT = REGION + ".slots[1]";
    private static final String WINDOW = "$.days[0].eventSummaries[0].window";

    private static final LocationEntity BAMBURGH = LocationEntity.builder()
            .id(PICKED_ID).name(PICKED).lat(55.6).lon(-1.7).build();
    private static final LocationEntity CRASTER = LocationEntity.builder()
            .id(UNPICKED_ID).name(UNPICKED).lat(55.47).lon(-1.59).bortleClass(3).build();

    @Autowired
    private MockMvc mockMvc;

    private void enforcementOnWithPicks() {
        when(subRegionProperties.isEnforce()).thenReturn(true);
        when(userSubRegionService.pickedLocationRows("anna")).thenReturn(
                List.of(new SubRegionLocationRow(100L, PICKED_ID, PICKED, 55.6, -1.7)));
    }

    // ------------------------------------------------------------------ fixtures

    private static BriefingSlot slot(long id, String name) {
        return new BriefingSlot(id, name, EVENT, Verdict.GO, null, null, List.of("Clear all layers"),
                "a reason", 4, 3, null, null, null, DisplayVerdict.WORTH_IT, null, false, null, Boolean.TRUE);
    }

    private static HotTopic bluebellTopic() {
        ExpandedHotTopicDetail detail = new ExpandedHotTopicDetail(List.of(
                new ExpandedHotTopicDetail.RegionGroup("North East", null, null, null, List.of(
                        new ExpandedHotTopicDetail.LocationEntry(PICKED, "Woodland", "Best",
                                new ExpandedHotTopicDetail.BluebellLocationMetrics(5, "WOODLAND", "In bloom"),
                                null),
                        new ExpandedHotTopicDetail.LocationEntry(UNPICKED, "Open fell", null,
                                new ExpandedHotTopicDetail.BluebellLocationMetrics(4, "OPEN_FELL", "Good"),
                                null)))),
                new ExpandedHotTopicDetail.BluebellMetrics(5, "Excellent", 2), null);
        return new HotTopic("BLUEBELL", "Bluebell conditions", "In bloom today", DAY, 1, "BLUEBELL",
                List.of("North East"), "description", detail)
                .withScience(List.of(HotTopicFact.metric("conditions", "5/5 · excellent")), "note")
                .withGlance(new GlanceLine("5/5 · excellent", "5/5 · excellent", null, false));
    }

    private static DailyBriefingResponse briefing() {
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "Region prose", List.of(),
                List.of(slot(PICKED_ID, PICKED), slot(UNPICKED_ID, UNPICKED)), null, null, null, null, null,
                null, DisplayVerdict.WORTH_IT, 2, "Worth it", false, Confidence.HIGH, 3.5, 5, 0.4, true, null);
        BriefingWindow window = new BriefingWindow(EVENT, DisplayVerdict.WORTH_IT, 5, Confidence.HIGH, null,
                List.of(), null, null, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNSET, List.of(region),
                List.of(slot(UNPICKED_ID, "Unregioned spot")), EVENT, window);
        return new DailyBriefingResponse(LocalDateTime.of(2999, 3, 25, 9, 0), "headline",
                List.of(new BriefingDay(DAY, List.of(summary))), List.of(), null, null, false, false, 0, "Opus",
                List.of(bluebellTopic()), List.of());
    }

    private static ForecastListDto listRow(Long id, String name) {
        return new ForecastListDto(id, name, BigDecimal.valueOf(55.6), BigDecimal.valueOf(-1.7), DAY,
                TargetType.SUNSET, LocalDateTime.of(2999, 3, 24, 12, 0), EVENT, 270, 4, null, null,
                null, null, null, 10.0, 9.0, BigDecimal.valueOf(3), 200, BigDecimal.ZERO, 5);
    }

    private static ForecastEvaluationEntity entity(long id, LocationEntity location, LocalDate day) {
        return ForecastEvaluationEntity.builder()
                .id(id).location(location).locationLat(BigDecimal.valueOf(55.6))
                .locationLon(BigDecimal.valueOf(-1.7)).targetDate(day).targetType(TargetType.SUNSET)
                .forecastRunAt(LocalDateTime.of(2999, 3, 24, 12, 0)).daysAhead(0)
                .evaluationModel(EvaluationModel.SONNET).solarEventTime(EVENT).build();
    }

    private static ForecastEvaluationDto detailDto(long id, String name) {
        java.util.Map<String, Object> values = java.util.Map.ofEntries(
                java.util.Map.entry("id", id), java.util.Map.entry("locationName", name),
                java.util.Map.entry("targetDate", DAY), java.util.Map.entry("targetType", TargetType.SUNSET),
                java.util.Map.entry("rating", 4), java.util.Map.entry("fierySkyPotential", 72),
                java.util.Map.entry("goldenHourPotential", 80), java.util.Map.entry("summary", "Why"),
                java.util.Map.entry("lowCloud", 10), java.util.Map.entry("bluebellScore", 4),
                java.util.Map.entry("bluebellSummary", "Good"), java.util.Map.entry("headline", "A headline"),
                java.util.Map.entry("mistPotential", "LIKELY"));
        try {
            java.lang.reflect.RecordComponent[] comps = ForecastEvaluationDto.class.getRecordComponents();
            Class<?>[] types = new Class<?>[comps.length];
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                args[i] = values.get(comps[i].getName());
            }
            return ForecastEvaluationDto.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AstroConditionsEntity astro(LocationEntity location) {
        AstroConditionsEntity entity = new AstroConditionsEntity();
        entity.setId(location.getId());
        entity.setLocation(location);
        entity.setForecastDate(LocalDate.of(2026, 4, 1));
        entity.setRunTimestamp(Instant.parse("2026-04-01T12:00:00Z"));
        entity.setStars(4);
        entity.setSummary("Clear and dark");
        entity.setCloudExplanation("Clear skies");
        entity.setVisibilityExplanation("Good visibility");
        entity.setMoonExplanation("Moon below horizon");
        entity.setMoonPhase("NEW_MOON");
        entity.setMoonIlluminationPct(2.0);
        entity.setNauticalDuskUtc(LocalDateTime.of(2026, 4, 1, 19, 40));
        entity.setNauticalDawnUtc(LocalDateTime.of(2026, 4, 2, 4, 55));
        return entity;
    }

    private static LocalDate today() {
        return ForecastHorizon.today(Clock.systemUTC());
    }

    private void stubForecastList() {
        when(locationService.findAllEnabled()).thenReturn(List.of(BAMBURGH, CRASTER));
        ForecastEvaluationEntity picked = entity(1L, BAMBURGH, DAY);
        ForecastEvaluationEntity unpicked = entity(2L, CRASTER, DAY);
        LocalDate from = today().minusDays(ForecastHorizon.SERVE_PAST_DAYS);
        LocalDate horizon = today().plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS);
        when(forecastEvaluationRepository.findLatestRunPerSlotByLocationIds(
                List.of(PICKED_ID, UNPICKED_ID), from, horizon)).thenReturn(List.of(picked, unpicked));
        when(dtoMapper.toListDtoList(List.of(picked, unpicked), true)).thenReturn(
                List.of(listRow(1L, PICKED), listRow(2L, UNPICKED)));
        when(dtoMapper.toListDtoList(List.of(picked, unpicked), false)).thenReturn(
                List.of(listRow(1L, PICKED), listRow(2L, UNPICKED)));
    }

    // ------------------------------------------------------------------ GET /api/briefing

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing as Lite with enforcement on: unpicked slots are gated, picked ones are not")
    void briefing_gatesTheUnpickedSlots() throws Exception {
        enforcementOnWithPicks();
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PICKED_SLOT + ".claudeRating").value(4))
                .andExpect(jsonPath(PICKED_SLOT + ".verdict").value("GO"))
                .andExpect(jsonPath(PICKED_SLOT + ".gated").doesNotExist())
                .andExpect(jsonPath(UNPICKED_SLOT + ".gated").value(true))
                .andExpect(jsonPath(UNPICKED_SLOT + ".locationName").value(UNPICKED))
                .andExpect(jsonPath(UNPICKED_SLOT + ".claudeRating").doesNotExist())
                .andExpect(jsonPath(UNPICKED_SLOT + ".skyRating").doesNotExist())
                .andExpect(jsonPath(UNPICKED_SLOT + ".verdict").doesNotExist())
                .andExpect(jsonPath(UNPICKED_SLOT + ".displayVerdict").doesNotExist())
                .andExpect(jsonPath(UNPICKED_SLOT + ".standdownReason").doesNotExist())
                .andExpect(jsonPath(UNPICKED_SLOT + ".flags").isEmpty())
                .andExpect(jsonPath("$.days[0].eventSummaries[0].unregioned[0].gated").value(true))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].unregioned[0].claudeRating").doesNotExist())
                .andExpect(jsonPath("$.allowedLocationIds.length()").value(1))
                .andExpect(jsonPath("$.allowedLocationIds[0]").value(7));
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing as Lite: region and window best and mean stars go; verdicts and prose stay")
    void briefing_stripsRegionAndWindowStars() throws Exception {
        enforcementOnWithPicks();
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(REGION + ".bestRating").doesNotExist())
                .andExpect(jsonPath(REGION + ".meanRating").doesNotExist())
                .andExpect(jsonPath(REGION + ".meanRatingDelta").doesNotExist())
                .andExpect(jsonPath(REGION + ".displayVerdict").value("WORTH_IT"))
                .andExpect(jsonPath(REGION + ".verdictLabel").value("Worth it"))
                .andExpect(jsonPath(REGION + ".summary").value("Region prose"))
                .andExpect(jsonPath(REGION + ".scoredLocationCount").value(2))
                .andExpect(jsonPath(WINDOW + ".bestRating").doesNotExist())
                .andExpect(jsonPath(WINDOW + ".verdict").value("WORTH_IT"))
                .andExpect(jsonPath(WINDOW + ".confidence").value("high"));
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing as Lite: the bluebell topic loses unpicked ratings and aggregates, keeps labels")
    void briefing_stripsTheBluebellTopic() throws Exception {
        enforcementOnWithPicks();
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());
        String topic = "$.hotTopics[0]";
        String groupLocations = topic + ".expandedDetail.regionGroups[0].locations";

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(topic + ".label").value("Bluebell conditions"))
                .andExpect(jsonPath(topic + ".detail").value("today"))
                .andExpect(jsonPath(topic + ".facts").doesNotExist())
                .andExpect(jsonPath(topic + ".glance.value").doesNotExist())
                .andExpect(jsonPath(topic + ".expandedDetail.bluebellMetrics.bestScore").doesNotExist())
                .andExpect(jsonPath(topic + ".expandedDetail.bluebellMetrics.scoringLocationCount").value(2))
                .andExpect(jsonPath(groupLocations + "[0].bluebellLocationMetrics.score").value(5))
                .andExpect(jsonPath(groupLocations + "[0].badge").value("Best"))
                .andExpect(jsonPath(groupLocations + "[1].locationName").value(UNPICKED))
                .andExpect(jsonPath(groupLocations + "[1].bluebellLocationMetrics").doesNotExist())
                .andExpect(jsonPath(groupLocations + "[1].locationType").value("Open fell"));
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing as Lite with enforcement on: the body is no-store and carries no ETag")
    void briefing_isNoStoreWithNoEtag() throws Exception {
        enforcementOnWithPicks();
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());

        MvcResult result = mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing with a failed picks read fails closed: every slot is gated")
    void briefing_failedPicksReadFailsClosed() throws Exception {
        when(subRegionProperties.isEnforce()).thenReturn(true);
        when(userSubRegionService.pickedLocationRows("anna")).thenThrow(new IllegalStateException("db down"));
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PICKED_SLOT + ".gated").value(true))
                .andExpect(jsonPath(PICKED_SLOT + ".claudeRating").doesNotExist())
                .andExpect(jsonPath("$.allowedLocationIds.length()").value(0));
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing with zero picks gates every slot and serves an empty allowed list")
    void briefing_zeroPicksGatesEverything() throws Exception {
        when(subRegionProperties.isEnforce()).thenReturn(true);
        when(userSubRegionService.pickedLocationRows("anna")).thenReturn(List.of());
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PICKED_SLOT + ".gated").value(true))
                .andExpect(jsonPath("$.allowedLocationIds.length()").value(0));
    }

    @Test
    @WithMockUser(username = "sam", roles = "USER")
    @DisplayName("GET /api/briefing for an unknown role is shaped like Lite, never served in full")
    void briefing_unknownRoleIsShaped() throws Exception {
        when(subRegionProperties.isEnforce()).thenReturn(true);
        when(userSubRegionService.pickedLocationRows("sam")).thenReturn(List.of());
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PICKED_SLOT + ".gated").value(true));
    }

    @Test
    @DisplayName("GET /api/briefing as Pro or Admin with enforcement on is byte-identical to enforcement off")
    void briefing_proAndAdminAreUnchangedByTheFlag() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());
        String proOff = body("/api/briefing", "pro", "PRO_USER");
        String adminOff = body("/api/briefing", "ann", "ADMIN");

        when(subRegionProperties.isEnforce()).thenReturn(true);
        MvcResult proOn = mockMvc.perform(get("/api/briefing").with(user("pro").roles("PRO_USER"))).andReturn();

        assertThat(proOn.getResponse().getContentAsString()).isEqualTo(proOff);
        assertThat(body("/api/briefing", "ann", "ADMIN")).isEqualTo(adminOff);
        assertThat(proOff).doesNotContain("gated").doesNotContain("allowedLocationIds");
        assertThat(proOn.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).doesNotContain("no-store");
        assertThat(proOn.getResponse().getHeader(HttpHeaders.ETAG)).isNotNull();
        verifyNoInteractions(userSubRegionService);
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing as Lite with enforcement off: no gated, no allowedLocationIds, picks never read")
    void briefing_flagOffLiteBodyHasNoNewFields() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing());

        MvcResult result = mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(UNPICKED_SLOT + ".claudeRating").value(4))
                .andExpect(jsonPath(REGION + ".bestRating").value(5))
                .andExpect(jsonPath(WINDOW + ".bestRating").value(5))
                .andExpect(jsonPath("$.hotTopics[0].expandedDetail.bluebellMetrics.bestScore").value(5))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("gated")
                .doesNotContain("allowedLocationIds");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).doesNotContain("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isNotNull();
        verifyNoInteractions(userSubRegionService);
    }

    private String body(String path, String username, String role) throws Exception {
        return mockMvc.perform(get(path).with(user(username).roles(role))).andReturn().getResponse()
                .getContentAsString();
    }

    // ------------------------------------------------------------------ GET /api/briefing/digest

    private static DailyBriefingResponse digestBriefing() {
        BriefingEventSummary sunset = new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of())
                .withWindow(new BriefingWindow(EVENT, DisplayVerdict.WORTH_IT, 4, Confidence.HIGH, null,
                        List.of(), null, null));
        return new DailyBriefingResponse(LocalDateTime.of(2999, 3, 25, 9, 0), "headline",
                List.of(new BriefingDay(DAY, List.of(sunset))), List.of(), null, null, false, false, 0, "Opus",
                List.of(), List.of());
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing/digest as Lite with enforcement on: the best star goes, the verdict stays")
    void digest_stripsTheBestRating() throws Exception {
        enforcementOnWithPicks();
        when(briefingService.getCachedBriefingForApi()).thenReturn(digestBriefing());

        MvcResult result = mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].verdict").value("WORTH_IT"))
                .andExpect(jsonPath("$.windows[0].confidence").value("high"))
                .andExpect(jsonPath("$.windows[0].bestRating").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing/digest as Lite with enforcement off keeps the best star and the ETag")
    void digest_flagOffIsUnchanged() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(digestBriefing());

        mockMvc.perform(get("/api/briefing/digest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].bestRating").value(4))
                .andExpect(header().exists(HttpHeaders.ETAG));
    }

    // ------------------------------------------------------------------ GET /api/briefing/evaluate/scores

    private void stubScores() {
        LocationEvaluationView picked = new LocationEvaluationView(PICKED_ID, PICKED, 3L, "North East", DAY,
                TargetType.SUNSET, LocationEvaluationView.Source.CACHED_EVALUATION, 4, "Why", 81, 66, null,
                null, "SONNET", Instant.parse("2999-03-24T12:00:00Z"), DisplayVerdict.WORTH_IT,
                EVENT.minusHours(1), EVENT.plusMinutes(10), null, null);
        LocationEvaluationView unpicked = new LocationEvaluationView(UNPICKED_ID, UNPICKED, 3L, "North East",
                DAY, TargetType.SUNSET, LocationEvaluationView.Source.FORECAST_EVALUATION_TRIAGE, null, null,
                null, null, TriageReason.HIGH_CLOUD, "Too cloudy", null,
                null, DisplayVerdict.STAND_DOWN, EVENT.minusHours(1), EVENT.plusMinutes(10), null, null);
        when(evaluationViewService.forDateRange(
                today().minusDays(ForecastHorizon.SERVE_PAST_DAYS),
                today().plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS),
                Set.of(TargetType.SUNRISE, TargetType.SUNSET))).thenReturn(List.of(picked, unpicked));
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing/evaluate/scores as Lite with enforcement on: unpicked rows are gated, no-store")
    void scores_gateUnpickedRows() throws Exception {
        enforcementOnWithPicks();
        stubScores();

        MvcResult result = mockMvc.perform(get("/api/briefing/evaluate/scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rating").value(4))
                .andExpect(jsonPath("$[0].gated").doesNotExist())
                .andExpect(jsonPath("$[1].gated").value(true))
                .andExpect(jsonPath("$[1].locationId").value(8))
                .andExpect(jsonPath("$[1].rating").doesNotExist())
                .andExpect(jsonPath("$[1].triageReason").doesNotExist())
                .andExpect(jsonPath("$[1].triageMessage").doesNotExist())
                .andExpect(jsonPath("$[1].displayVerdict").doesNotExist())
                .andExpect(jsonPath("$[1].source").value("NONE"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/briefing/evaluate/scores as Lite with enforcement off: nothing gated, ETag kept")
    void scores_flagOffIsUnchanged() throws Exception {
        stubScores();

        MvcResult result = mockMvc.perform(get("/api/briefing/evaluate/scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].triageReason").value("HIGH_CLOUD"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("gated");
    }

    // ------------------------------------------------------------------ GET /api/forecast

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/forecast as Lite with enforcement on: the unpicked row is gated, the picked one is not")
    void forecastList_gatesUnpickedRows() throws Exception {
        enforcementOnWithPicks();
        stubForecastList();

        MvcResult result = mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value(PICKED))
                .andExpect(jsonPath("$[0].rating").value(4))
                .andExpect(jsonPath("$[0].gated").doesNotExist())
                .andExpect(jsonPath("$[1].locationName").value(UNPICKED))
                .andExpect(jsonPath("$[1].gated").value(true))
                .andExpect(jsonPath("$[1].rating").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].windSpeed").value(3))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/forecast?date= for a past day (served as of its event) is gated by the same rule")
    void forecastPastDate_isGated() throws Exception {
        enforcementOnWithPicks();
        LocalDate yesterday = today().minusDays(1);
        when(locationService.findAllEnabled()).thenReturn(List.of(BAMBURGH, CRASTER));
        ForecastEvaluationEntity picked = entity(1L, BAMBURGH, yesterday);
        ForecastEvaluationEntity unpicked = entity(2L, CRASTER, yesterday);
        // Yesterday's sunset has passed for both places, so each is served as of its event.
        when(asOfEventResolver.resolveAll(eq(List.of(BAMBURGH, CRASTER)), eq(yesterday), eq(TargetType.SUNSET),
                any())).thenReturn(Map.of(
                        PICKED_ID, new AsOfEventResolver.AsOfEvent(null, 1L, null, null, 4, picked),
                        UNPICKED_ID, new AsOfEventResolver.AsOfEvent(null, 2L, null, null, 3, unpicked)));
        when(dtoMapper.toListDtoList(List.of(picked, unpicked), true)).thenReturn(
                List.of(listRow(1L, PICKED), listRow(2L, UNPICKED)));

        MvcResult result = mockMvc.perform(get("/api/forecast").param("date", yesterday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].locationName").value(PICKED))
                .andExpect(jsonPath("$[0].rating").value(4))
                .andExpect(jsonPath("$[1].locationName").value(UNPICKED))
                .andExpect(jsonPath("$[1].gated").value(true))
                .andExpect(jsonPath("$[1].rating").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/forecast sparse (cached-only) rows are gated too")
    void forecastSparseRows_areGated() throws Exception {
        enforcementOnWithPicks();
        when(locationService.findAllEnabled()).thenReturn(List.of(BAMBURGH, CRASTER));
        LocalDate from = today().minusDays(ForecastHorizon.SERVE_PAST_DAYS);
        LocalDate horizon = today().plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS);
        LocationEvaluationView view = new LocationEvaluationView(UNPICKED_ID, UNPICKED, 3L, "North East",
                DAY, TargetType.SUNSET, LocationEvaluationView.Source.CACHED_EVALUATION, 5, "Why", null, null,
                null, null, null, null, DisplayVerdict.WORTH_IT, null, null, null, null);
        when(evaluationViewService.cachedOnlyViewsForDateRange(from, horizon,
                Set.of(TargetType.SUNRISE, TargetType.SUNSET), List.of(BAMBURGH, CRASTER), Map.of()))
                .thenReturn(List.of(view));
        ForecastListDto sparse = new ForecastListDto(null, UNPICKED, BigDecimal.valueOf(55.47),
                BigDecimal.valueOf(-1.59), DAY, TargetType.SUNSET, null, EVENT, 270, 5, null, null, null,
                "Why", "Message", null, null, null, null, null, null);
        when(dtoMapper.toSparseListDto(view, CRASTER, true)).thenReturn(sparse);

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value(UNPICKED))
                .andExpect(jsonPath("$[0].gated").value(true))
                .andExpect(jsonPath("$[0].rating").doesNotExist())
                .andExpect(jsonPath("$[0].summary").doesNotExist())
                .andExpect(jsonPath("$[0].triageMessage").doesNotExist());
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/forecast as Lite with enforcement off: ratings kept, nothing gated, ETag kept")
    void forecastList_flagOffIsUnchanged() throws Exception {
        stubForecastList();

        MvcResult result = mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].rating").value(4))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("gated");
        verifyNoInteractions(userSubRegionService);
    }

    @Test
    @WithMockUser(username = "pat", roles = "PRO_USER")
    @DisplayName("GET /api/forecast as Pro with enforcement on: unchanged, nothing gated")
    void forecastList_proIsUnchanged() throws Exception {
        when(subRegionProperties.isEnforce()).thenReturn(true);
        stubForecastList();

        MvcResult result = mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].rating").value(4))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("gated");
        verifyNoInteractions(userSubRegionService);
    }

    // ------------------------------------------------------------------ GET /api/forecast/{id}

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/forecast/{id} for an id outside the picks serves the gated evaluation, never the full one")
    void forecastDetail_unpickedIdIsGated() throws Exception {
        enforcementOnWithPicks();
        ForecastEvaluationEntity unpicked = entity(42L, CRASTER, DAY);
        when(forecastEvaluationRepository.findById(42L)).thenReturn(Optional.of(unpicked));
        when(dtoMapper.toDto(unpicked, true)).thenReturn(detailDto(42L, UNPICKED));

        MvcResult result = mockMvc.perform(get("/api/forecast/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gated").value(true))
                .andExpect(jsonPath("$.locationName").value(UNPICKED))
                .andExpect(jsonPath("$.rating").doesNotExist())
                .andExpect(jsonPath("$.fierySkyPotential").doesNotExist())
                .andExpect(jsonPath("$.goldenHourPotential").doesNotExist())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.headline").doesNotExist())
                .andExpect(jsonPath("$.bluebellScore").doesNotExist())
                .andExpect(jsonPath("$.bluebellSummary").doesNotExist())
                .andExpect(jsonPath("$.mistPotential").doesNotExist())
                .andExpect(jsonPath("$.lowCloud").value(10))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/forecast/{id} for an id inside the picks is served as before (still no-store)")
    void forecastDetail_pickedIdIsFull() throws Exception {
        enforcementOnWithPicks();
        ForecastEvaluationEntity picked = entity(41L, BAMBURGH, DAY);
        when(forecastEvaluationRepository.findById(41L)).thenReturn(Optional.of(picked));
        when(dtoMapper.toDto(picked, true)).thenReturn(detailDto(41L, PICKED));

        MvcResult result = mockMvc.perform(get("/api/forecast/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gated").doesNotExist())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.bluebellScore").value(4))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/forecast/{id} as Lite with enforcement off: the full evaluation, ETag kept")
    void forecastDetail_flagOffIsUnchanged() throws Exception {
        ForecastEvaluationEntity unpicked = entity(42L, CRASTER, DAY);
        when(forecastEvaluationRepository.findById(42L)).thenReturn(Optional.of(unpicked));
        when(dtoMapper.toDto(unpicked, true)).thenReturn(detailDto(42L, UNPICKED));

        MvcResult result = mockMvc.perform(get("/api/forecast/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("gated");
    }

    // ------------------------------------------------------------------ GET /api/astro/conditions

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/astro/conditions as Lite with enforcement on: stars and prose go for unpicked places")
    void astro_gatesStarsAndProse() throws Exception {
        enforcementOnWithPicks();
        when(astroConditionsRepository.findByForecastDate(LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of(astro(BAMBURGH), astro(CRASTER)));

        MvcResult result = mockMvc.perform(get("/api/astro/conditions").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stars").value(4))
                .andExpect(jsonPath("$[0].summary").value("Clear and dark"))
                .andExpect(jsonPath("$[0].gated").doesNotExist())
                .andExpect(jsonPath("$[1].locationName").value(UNPICKED))
                .andExpect(jsonPath("$[1].gated").value(true))
                .andExpect(jsonPath("$[1].stars").doesNotExist())
                .andExpect(jsonPath("$[1].summary").doesNotExist())
                .andExpect(jsonPath("$[1].cloudExplanation").doesNotExist())
                .andExpect(jsonPath("$[1].visibilityExplanation").doesNotExist())
                .andExpect(jsonPath("$[1].moonExplanation").doesNotExist())
                .andExpect(jsonPath("$[1].moonPhase").value("NEW_MOON"))
                .andExpect(jsonPath("$[1].bortleClass").value(3))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET /api/astro/conditions as Lite with enforcement off: unchanged")
    void astro_flagOffIsUnchanged() throws Exception {
        when(astroConditionsRepository.findByForecastDate(LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of(astro(CRASTER)));

        MvcResult result = mockMvc.perform(get("/api/astro/conditions").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stars").value(4))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("gated");
        verifyNoInteractions(userSubRegionService);
    }
}
