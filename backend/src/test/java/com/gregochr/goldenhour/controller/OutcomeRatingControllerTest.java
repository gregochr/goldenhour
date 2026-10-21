package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OutcomeRatingDto;
import com.gregochr.goldenhour.model.SeenChoice;
import com.gregochr.goldenhour.model.SlotRatingsDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three outcome-rating endpoints through the real security chain and the real global exception
 * handler, with {@code OutcomeRatingService} mocked: the role matrix on each endpoint, the 400
 * bodies, the range cap and the exact arguments that reach the service.
 */
class OutcomeRatingControllerTest extends AbstractControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Instant RECORDED = Instant.parse("2026-10-14T09:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    private static OutcomeRatingDto dto(Integer rating) {
        return new OutcomeRatingDto(10L, "Hartside", DAY, TargetType.SUNRISE, rating, RECORDED,
                Instant.parse("2026-10-14T01:05:00Z"), 7L, 4);
    }

    private static final String BODY = "{\"locationId\":10,\"eventDate\":\"2026-10-14\","
            + "\"eventType\":\"SUNRISE\",\"rating\":4}";

    // ── GET /api/outcome/ratings ────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "lite", roles = "LITE_USER")
    @DisplayName("GET: a LITE account may read - it has no rows, so it gets []")
    void getMine_liteAnswersEmptyList() throws Exception {
        when(outcomeRatingService.myRatings("lite", DAY, DAY)).thenReturn(List.of());

        mockMvc.perform(get("/api/outcome/ratings").param("from", "2026-10-14").param("to", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("GET: serves the caller's rows with every field, instants as ISO strings, and asks for "
            + "the caller's own username")
    void getMine_proServesOwnRows() throws Exception {
        when(outcomeRatingService.myRatings("pro", DAY.minusDays(1), DAY))
                .thenReturn(List.of(dto(4), dto(null)));

        mockMvc.perform(get("/api/outcome/ratings").param("from", "2026-10-13").param("to", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].locationId").value(10))
                .andExpect(jsonPath("$[0].locationName").value("Hartside"))
                .andExpect(jsonPath("$[0].eventDate").value("2026-10-14"))
                .andExpect(jsonPath("$[0].eventType").value("SUNRISE"))
                .andExpect(jsonPath("$[0].rating").value(4))
                .andExpect(jsonPath("$[0].updatedAt").value("2026-10-14T09:00:00Z"))
                .andExpect(jsonPath("$[0].forecastRunAt").value("2026-10-14T01:05:00Z"))
                .andExpect(jsonPath("$[0].pipelineRunId").value(7))
                .andExpect(jsonPath("$[0].forecastRating").value(4))
                .andExpect(jsonPath("$[1].rating").doesNotExist());
    }

    @Test
    @DisplayName("GET without authentication is 401")
    void getMine_anonymous401() throws Exception {
        mockMvc.perform(get("/api/outcome/ratings").param("from", "2026-10-14").param("to", "2026-10-14"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(outcomeRatingService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET: from after to is a 400 with the error body, and the service is not asked")
    void getMine_fromAfterTo() throws Exception {
        mockMvc.perform(get("/api/outcome/ratings").param("from", "2026-10-15").param("to", "2026-10-14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'from' must not be after 'to'"));
        verifyNoInteractions(outcomeRatingService);
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("GET: 31 days inclusive is accepted, 32 is a 400")
    void getMine_rangeCap() throws Exception {
        when(outcomeRatingService.myRatings(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/outcome/ratings").param("from", "2026-09-14").param("to", "2026-10-14"))
                .andExpect(status().isOk());
        verify(outcomeRatingService).myRatings("pro", LocalDate.of(2026, 9, 14), DAY);

        mockMvc.perform(get("/api/outcome/ratings").param("from", "2026-09-13").param("to", "2026-10-14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Requested range spans 32 days; the maximum is 31 days"));
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("GET: a missing parameter is a 400, not a 500")
    void getMine_missingParam() throws Exception {
        mockMvc.perform(get("/api/outcome/ratings").param("from", "2026-10-14"))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /api/outcome/ratings ────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "lite", roles = "LITE_USER")
    @DisplayName("PUT: a LITE account is 403 and the service is never called")
    void put_lite403() throws Exception {
        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(outcomeRatingService);
    }

    @Test
    @DisplayName("PUT without authentication is 401")
    void put_anonymous401() throws Exception {
        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(outcomeRatingService);
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: PRO records the rating and gets the stored row back")
    void put_proRecords() throws Exception {
        when(outcomeRatingService.rate("pro", 10L, DAY, TargetType.SUNRISE, 4, null)).thenReturn(dto(4));

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.locationName").value("Hartside"))
                .andExpect(jsonPath("$.forecastRating").value(4));
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("PUT: ADMIN records too; a rating absent from the body means 'didn't see it' (null)")
    void put_adminRecordsNotSeen() throws Exception {
        when(outcomeRatingService.rate("boss", 10L, DAY, TargetType.SUNSET, null, null)).thenReturn(dto(null));

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":10,\"eventDate\":\"2026-10-14\",\"eventType\":\"SUNSET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").doesNotExist());
        verify(outcomeRatingService).rate("boss", 10L, DAY, TargetType.SUNSET, null, null);
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: an explicit null rating is also 'didn't see it'")
    void put_explicitNullRating() throws Exception {
        when(outcomeRatingService.rate("pro", 10L, DAY, TargetType.SUNRISE, null, null)).thenReturn(dto(null));

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":10,\"eventDate\":\"2026-10-14\","
                                + "\"eventType\":\"SUNRISE\",\"rating\":null}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: a service refusal (out of range, not happened yet, too old) is a 400 with its sentence")
    void put_serviceRefusalIs400() throws Exception {
        when(outcomeRatingService.rate(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("That sunrise has not happened yet"));

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("That sunrise has not happened yet"));
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: an unknown location is a 404")
    void put_unknownLocation404() throws Exception {
        when(outcomeRatingService.rate(any(), any(), any(), any(), any(), any()))
                .thenThrow(new NoSuchElementException("Location not found: 10"));

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Location not found: 10"));
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: an unknown event type or a malformed body is a 400, never reaching the service")
    void put_unreadableBody400() throws Exception {
        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":10,\"eventDate\":\"2026-10-14\",\"eventType\":\"NOON\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(outcomeRatingService);
    }

    // ── GET /api/outcome/ratings/all ────────────────────────────────────────────

    private static SlotRatingsDto slot() {
        return new SlotRatingsDto(Instant.parse("2026-10-14T05:41:00Z"), Instant.parse("2026-10-14T01:05:00Z"),
                7L, List.of(new SlotRatingsDto.Row("boss", 4, RECORDED, true),
                        new SlotRatingsDto.Row("pro", null, RECORDED.minusSeconds(60), false)));
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("ALL: ADMIN gets every user's row with 'mine' on the caller's, and the forecast head")
    void all_adminServesTheSlot() throws Exception {
        when(outcomeRatingService.slotRatings("boss", 10L, DAY, TargetType.SUNRISE)).thenReturn(slot());

        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "10").param("date", "2026-10-14")
                        .param("eventType", "SUNRISE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventTime").value("2026-10-14T05:41:00Z"))
                .andExpect(jsonPath("$.forecastRunAt").value("2026-10-14T01:05:00Z"))
                .andExpect(jsonPath("$.pipelineRunId").value(7))
                .andExpect(jsonPath("$.ratings.length()").value(2))
                .andExpect(jsonPath("$.ratings[0].username").value("boss"))
                .andExpect(jsonPath("$.ratings[0].mine").value(true))
                .andExpect(jsonPath("$.ratings[0].rating").value(4))
                .andExpect(jsonPath("$.ratings[1].mine").value(false))
                .andExpect(jsonPath("$.ratings[1].rating").doesNotExist());
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("ALL: a window with no ratings still carries its run")
    void all_noRowsStillCarriesRun() throws Exception {
        when(outcomeRatingService.slotRatings("boss", 10L, DAY, TargetType.SUNSET)).thenReturn(
                new SlotRatingsDto(Instant.parse("2026-10-14T16:20:00Z"), Instant.parse("2026-10-14T14:00:00Z"),
                        null, List.of()));

        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "10").param("date", "2026-10-14")
                        .param("eventType", "SUNSET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratings.length()").value(0))
                .andExpect(jsonPath("$.forecastRunAt").value("2026-10-14T14:00:00Z"));
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("ALL: PRO is 403")
    void all_pro403() throws Exception {
        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "10").param("date", "2026-10-14")
                        .param("eventType", "SUNRISE"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(outcomeRatingService);
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("ALL: LITE is 403")
    void all_lite403() throws Exception {
        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "10").param("date", "2026-10-14")
                        .param("eventType", "SUNRISE"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(outcomeRatingService);
    }

    @Test
    @DisplayName("ALL without authentication is 401")
    void all_anonymous401() throws Exception {
        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "10").param("date", "2026-10-14")
                        .param("eventType", "SUNRISE"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ALL: an unknown event type is the write's own 400 sentence, and the service is not asked")
    void all_unknownEventType400() throws Exception {
        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "10").param("date", "2026-10-14")
                        .param("eventType", "NOON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("eventType must be SUNRISE or SUNSET"));
        verify(outcomeRatingService, never()).slotRatings(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ALL: HOURLY is a valid enum value the service refuses (400 in its words); an unknown "
            + "location is a 404")
    void all_serviceRefusals() throws Exception {
        when(outcomeRatingService.slotRatings(any(), eq(10L), eq(DAY), eq(TargetType.HOURLY)))
                .thenThrow(new IllegalArgumentException("eventType must be SUNRISE or SUNSET"));
        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "10").param("date", "2026-10-14")
                        .param("eventType", "HOURLY"))
                .andExpect(status().isBadRequest());

        when(outcomeRatingService.slotRatings(any(), eq(99L), eq(DAY), eq(TargetType.SUNRISE)))
                .thenThrow(new NoSuchElementException("Location not found: 99"));
        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "99").param("date", "2026-10-14")
                        .param("eventType", "SUNRISE"))
                .andExpect(status().isNotFound());
    }

    // ── the seen tag on the wire (valley-mist-plan M3) ───────────────────────────

    private static String bodyWith(String seenJson) {
        return "{\"locationId\":10,\"eventDate\":\"2026-10-14\",\"eventType\":\"SUNRISE\","
                + "\"rating\":4" + seenJson + "}";
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: a seen tag reaches the service as a choice holding it, and is echoed back")
    void put_seenTagReachesTheService() throws Exception {
        when(outcomeRatingService.rate("pro", 10L, DAY, TargetType.SUNRISE, 4, new SeenChoice(SeenTag.MIST)))
                .thenReturn(new OutcomeRatingDto(10L, "Hartside", DAY, TargetType.SUNRISE, 4, RECORDED,
                        null, null, null, SeenTag.MIST));

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(",\"seen\":\"MIST\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seen").value("MIST"));
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: seen ABSENT reaches the service as null (keep), an explicit null as CLEAR - the "
            + "two are different requests")
    void put_absentAndExplicitNullSeenAreDistinct() throws Exception {
        when(outcomeRatingService.rate("pro", 10L, DAY, TargetType.SUNRISE, 4, null)).thenReturn(dto(4));
        when(outcomeRatingService.rate("pro", 10L, DAY, TargetType.SUNRISE, 4, SeenChoice.CLEAR))
                .thenReturn(dto(4));

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith("")))
                .andExpect(status().isOk());
        verify(outcomeRatingService).rate("pro", 10L, DAY, TargetType.SUNRISE, 4, null);
        verify(outcomeRatingService, never()).rate("pro", 10L, DAY, TargetType.SUNRISE, 4, SeenChoice.CLEAR);

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(",\"seen\":null")))
                .andExpect(status().isOk());
        verify(outcomeRatingService).rate("pro", 10L, DAY, TargetType.SUNRISE, 4, SeenChoice.CLEAR);
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: an unknown seen tag is a 400 that never reaches the service")
    void put_unknownSeenTag400() throws Exception {
        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(",\"seen\":\"FOG\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(outcomeRatingService);
    }

    @Test
    @WithMockUser(username = "lite", roles = "LITE_USER")
    @DisplayName("PUT: a LITE account stays 403 whatever it tags")
    void put_liteWithSeen403() throws Exception {
        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(",\"seen\":\"MIST\"")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(outcomeRatingService);
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("PUT: the service's seen refusal is a 400 with its sentence")
    void put_seenRefusalIs400() throws Exception {
        when(outcomeRatingService.rate(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("seen NOT_SEEN goes with \"didn't see it\""));

        mockMvc.perform(put("/api/outcome/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith(",\"seen\":\"NOT_SEEN\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("seen NOT_SEEN goes with \"didn't see it\""));
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("GET: a tagged row serves seen, an untagged row omits it")
    void getMine_servesSeenWhenPresent() throws Exception {
        when(outcomeRatingService.myRatings("pro", DAY, DAY)).thenReturn(List.of(
                new OutcomeRatingDto(10L, "Hartside", DAY, TargetType.SUNRISE, 4, RECORDED, null, null, null,
                        SeenTag.INVERSION),
                dto(3)));

        mockMvc.perform(get("/api/outcome/ratings").param("from", "2026-10-14").param("to", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seen").value("INVERSION"))
                .andExpect(jsonPath("$[1].seen").doesNotExist());
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("ALL: each row serves its tag, omitted when none")
    void all_servesSeenPerRow() throws Exception {
        when(outcomeRatingService.slotRatings("boss", 10L, DAY, TargetType.SUNRISE)).thenReturn(
                new SlotRatingsDto(null, null, null, List.of(
                        new SlotRatingsDto.Row("boss", 4, RECORDED, true, SeenTag.COLOUR),
                        new SlotRatingsDto.Row("pro", 2, RECORDED, false))));

        mockMvc.perform(get("/api/outcome/ratings/all").param("locationId", "10").param("date", "2026-10-14")
                        .param("eventType", "SUNRISE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratings[0].seen").value("COLOUR"))
                .andExpect(jsonPath("$.ratings[1].seen").doesNotExist());
    }
}
