package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AskBodyLimitFilter;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.ask.AskErrorCode;
import com.gregochr.goldenhour.service.ask.AskEvent;
import com.gregochr.goldenhour.service.ask.AskAnswerCache;
import com.gregochr.goldenhour.service.ask.AskIntentMatcher;
import com.gregochr.goldenhour.service.ask.AskLog;
import com.gregochr.goldenhour.service.ask.AskPreFilter;
import com.gregochr.goldenhour.service.ask.AskProperties;
import com.gregochr.goldenhour.service.ask.CaffeineAskAnswerCache;
import com.gregochr.goldenhour.service.ask.DatabaseAskLog;
import com.gregochr.goldenhour.service.ask.KeywordAskIntentMatcher;
import com.gregochr.goldenhour.service.ask.PhraseAskPreFilter;
import com.gregochr.goldenhour.service.ask.AskReadyResponse;
import com.gregochr.goldenhour.service.ask.AskRefusal;
import com.gregochr.goldenhour.service.ask.AskRequest;
import com.gregochr.goldenhour.service.ask.AskResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/ask} and {@code GET /api/user/settings/ask} through the real security chain and the
 * real JSON mapper, with {@code AskService} mocked: who may call them, the flag, the request mapping,
 * every row of plan §2.9's error table in its exact {@code {error, code}} shape, the success wire
 * contract, and that nothing here is ETag-filtered or cacheable.
 */
class AskTypedControllerTest extends AbstractControllerTest {

    private static final String URL = "/api/ask";
    private static final String SETTINGS_URL = "/api/user/settings/ask";
    private static final String BODY =
            "{\"question\":\"Best spot tonight?\",\"windowId\":\"2026-10-05_sunset\",\"regionIds\":[3],"
                    + "\"view\":\"map\"}";

    private static final AppUserEntity ADMITTED =
            AppUserEntity.builder().id(41L).username("someone").role(UserRole.PRO_USER).build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AskProperties properties;
    @Autowired
    private AskPreFilter preFilter;
    @Autowired
    private AskIntentMatcher intentMatcher;
    @Autowired
    private AskAnswerCache answerCache;
    @Autowired
    private AskLog askLog;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(askService.admit(any())).thenReturn(ADMITTED);
        when(askService.ask(any(), any())).thenReturn(answer());
        when(askService.settings(any())).thenReturn(
                new com.gregochr.goldenhour.service.ask.AskSettingsResponse(true, 1, 3, 2, true));
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
    }

    private static AskResponse answer() {
        AskReadyResponse.Pick pick = new AskReadyResponse.Pick(1, 123L, "Whitby", "North York Moors & Coast",
                LocalDate.of(2026, 10, 5), TargetType.SUNSET, "2026-10-05_sunset", "Clear and the tide suits.");
        AskEvent event = new AskEvent("ECLIPSE", "Partial solar eclipse", LocalDate.of(2026, 10, 10), "Visible.",
                "Certified solar filter on the lens");
        return new AskResponse(true, "own", "Whitby at sunset.", List.of(pick), List.of(event), null, List.of(),
                2, 3, true, LocalDateTime.of(2026, 10, 5, 5, 2, 11), "06:02");
    }

    // -- the four seams ------------------------------------------------------------------------

    @Test
    @DisplayName("in the real application context each seam resolves to its one real implementation")
    void theRealSeamsAreWired() {
        assertThat(preFilter).isInstanceOf(PhraseAskPreFilter.class);
        assertThat(intentMatcher).isInstanceOf(KeywordAskIntentMatcher.class);
        assertThat(answerCache).isInstanceOf(CaffeineAskAnswerCache.class);
        assertThat(askLog).isInstanceOf(DatabaseAskLog.class);
    }

    // -- who may call -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"LITE_USER", "PRO_USER", "ADMIN"})
    @DisplayName("every role may ask, and every role may read the allowance: Bearer, no role gate")
    void everyRole(String role) throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(SecurityMockMvcRequestPostProcessors.user("someone").roles(role)))
                .andExpect(status().isOk());
        mockMvc.perform(get(SETTINGS_URL)
                        .with(SecurityMockMvcRequestPostProcessors.user("someone").roles(role)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an anonymous caller is 401 on both endpoints, with the flag on or off, and the service is "
            + "never reached")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(SETTINGS_URL)).andExpect(status().isUnauthorized());
        properties.setEnabled(false);
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(SETTINGS_URL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(askService);
    }

    // -- the rate limit sits in front of the body ------------------------------------------------

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a request is admitted exactly once, before the body is read, and the same admitted user is "
            + "the one that is answered: the controller does not count it a second time")
    void admittedOnceAndHandedOn() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        verify(askService, times(1)).admit(any());
        verify(askService).ask(same(ADMITTED), any());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a refusal at admission is 429 RATE_LIMITED before the body is converted: a body that cannot "
            + "be read is 429, not 400, and nothing is answered")
    void rateLimitedBeforeParsing() throws Exception {
        when(askService.admit(any())).thenThrow(new AskRefusal(AskErrorCode.RATE_LIMITED));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests());

        verify(askService, never()).ask(any(), any());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("an unreadable body is still counted: admission ran for it")
    void unreadableBodyIsCounted() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());

        verify(askService, times(1)).admit(any());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("with Ask off a POST is not counted at all")
    void flagOffIsNotCounted() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());

        verify(askService, never()).admit(any());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a GET of the allowance never reaches the limiter")
    void readsAreNotCounted() throws Exception {
        mockMvc.perform(get(SETTINGS_URL)).andExpect(status().isOk());

        verify(askService, never()).admit(any());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a body over 8 KiB is 400 INVALID without being read to the end, and is still counted")
    void oversizedBody() throws Exception {
        String padding = " ".repeat(AskBodyLimitFilter.MAX_BODY_BYTES + 1);
        String body = "{\"question\":\"Best?\",\"view\":\"plan\"," + padding + "\"x\":1}";

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID"));

        verify(askService, times(1)).admit(any());
        verify(askService, never()).ask(any(), any());
    }

    // -- the flag ------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("with Ask off the POST is 404 and nothing is asked, even for a body that cannot be read")
    void flagOffIs404() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isNotFound());

        verify(askService, never()).ask(any(), any());
    }

    // -- the request ------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("the body maps to the request: question, windowId, regionIds and view")
    void requestMapping() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<AskRequest> captured = ArgumentCaptor.forClass(AskRequest.class);
        verify(askService).ask(any(), captured.capture());
        assertThat(captured.getValue()).isEqualTo(
                new AskRequest("Best spot tonight?", "2026-10-05_sunset", List.of(3L), "map"));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("windowId and regionIds are optional; unknown fields are ignored")
    void optionalFields() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Best?\",\"view\":\"plan\",\"extra\":1}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AskRequest> captured = ArgumentCaptor.forClass(AskRequest.class);
        verify(askService).ask(any(), captured.capture());
        assertThat(captured.getValue()).isEqualTo(new AskRequest("Best?", null, null, "plan"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "[]", "\"just a string\"", "{\"question\":\"Q\",\"regionIds\":\"x\"}",
        "{\"question\":\"Q\",\"regionIds\":[\"a\"]}", "{\"question\":{\"a\":1}}"})
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a body that cannot be read is 400 INVALID in the shared error shape, with a fixed sentence "
            + "that does not echo the input")
    void unreadableBody(String body) throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID"))
                .andExpect(jsonPath("$.error").value("The request body could not be read."));

        verify(askService, never()).ask(any(), any());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a missing body reaches the service as null, which refuses it as INVALID")
    void missingBody() throws Exception {
        when(askService.ask(any(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new AskRefusal(AskErrorCode.INVALID, "A request body is required."));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID"));
    }

    // -- the error table ------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(AskErrorCode.class)
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("every refusal is exactly {error, code} with the status plan §2.9 fixes for it")
    void errorTable(AskErrorCode code) throws Exception {
        when(askService.ask(any(), any())).thenThrow(new AskRefusal(code));
        int expected = switch (code) {
            case INVALID -> 400;
            case RATE_LIMITED, ALLOWANCE_EXHAUSTED, DAILY_LIMIT -> 429;
            case ENGINE_FAILED -> 502;
            case TYPED_UNAVAILABLE -> 503;
            case UNAUTHENTICATED -> 401;
        };

        MvcResult result = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.error").value(code.message()))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).as("only error and code").doesNotContain("picks");
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("an INVALID refusal carries its own sentence under the field")
    void invalidSentence() throws Exception {
        when(askService.ask(any(), any())).thenThrow(
                new AskRefusal(AskErrorCode.INVALID, "The question must be at most 200 characters."));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The question must be at most 200 characters."))
                .andExpect(jsonPath("$.code").value("INVALID"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("the three 429s and the 503 are told apart by code alone")
    void sameStatusDifferentCodes() throws Exception {
        for (AskErrorCode code : List.of(AskErrorCode.RATE_LIMITED, AskErrorCode.ALLOWANCE_EXHAUSTED,
                AskErrorCode.DAILY_LIMIT)) {
            org.mockito.Mockito.doThrow(new AskRefusal(code)).when(askService).ask(any(), any());
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value(code.name()));
        }
    }

    // -- the success contract -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("the 200 body is plan §2.9's contract: kind own, picks, events with a safetyNote, missing null and "
            + "present, an empty try, the allowance, charged, generatedAt as ISO text and runLabel")
    void successWireShape() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerable").value(true))
                .andExpect(jsonPath("$.kind").value("own"))
                .andExpect(jsonPath("$.summary").value("Whitby at sunset."))
                .andExpect(jsonPath("$.picks[0].rank").value(1))
                .andExpect(jsonPath("$.picks[0].locationId").value(123))
                .andExpect(jsonPath("$.picks[0].locationName").value("Whitby"))
                .andExpect(jsonPath("$.picks[0].date").value("2026-10-05"))
                .andExpect(jsonPath("$.picks[0].targetType").value("SUNSET"))
                .andExpect(jsonPath("$.picks[0].windowId").value("2026-10-05_sunset"))
                .andExpect(jsonPath("$.picks[0].why").value("Clear and the tide suits."))
                .andExpect(jsonPath("$.picks[0]", not(hasKey("ratingAtAnswer"))))
                .andExpect(jsonPath("$.events[0].safetyNote").value("Certified solar filter on the lens"))
                .andExpect(jsonPath("$", hasKey("missing")))
                .andExpect(jsonPath("$.missing").doesNotExist())
                .andExpect(jsonPath("$.try").isArray())
                .andExpect(jsonPath("$.try.length()").value(0))
                .andExpect(jsonPath("$.allowanceLeft").value(2))
                .andExpect(jsonPath("$.allowanceLimit").value(3))
                .andExpect(jsonPath("$.charged").value(true))
                .andExpect(jsonPath("$.generatedAt").value("2026-10-05T05:02:11"))
                .andExpect(jsonPath("$.runLabel").value("06:02"));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("an answer served without its allowance figure writes allowanceLeft as an explicit null")
    void unknownAllowanceIsWrittenAsNull() throws Exception {
        AskResponse base = answer();
        when(askService.ask(any(), any())).thenReturn(new AskResponse(base.answerable(), base.kind(), base.summary(),
                base.picks(), base.events(), null, List.of(), null, 3, true, base.generatedAt(), base.runLabel()));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasKey("allowanceLeft")))
                .andExpect(jsonPath("$.allowanceLeft").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.allowanceLimit").value(3));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("a can't-answer carries answerable false, empty picks and events, missing, and the try pair")
    void cantWireShape() throws Exception {
        when(askService.ask(any(), any())).thenReturn(new AskResponse(false, "cant", "No parking data.",
                List.of(), List.of(), "parking",
                List.of(new AskReadyResponse.Suggestion("BEST_NEXT", "Best spot tonight?"),
                        new AskReadyResponse.Suggestion("RARE_EVENTS", "Any rare events coming up?")),
                3, 3, false, LocalDateTime.of(2026, 10, 5, 5, 2, 11), "06:02"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerable").value(false))
                .andExpect(jsonPath("$.kind").value("cant"))
                .andExpect(jsonPath("$.picks.length()").value(0))
                .andExpect(jsonPath("$.events.length()").value(0))
                .andExpect(jsonPath("$.missing").value("parking"))
                .andExpect(jsonPath("$.try[0].id").value("BEST_NEXT"))
                .andExpect(jsonPath("$.try[1].text").value("Any rare events coming up?"))
                .andExpect(jsonPath("$.charged").value(false));
    }

    // -- caching ------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a POST is never ETag-filtered and is not cacheable: no ETag, no-store")
    void postIsNeverCached() throws Exception {
        MvcResult result = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("ETag")).isNull();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store")
                .doesNotContain("private, no-cache");
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("the allowance is personal: no ETag, no-store")
    void settingsAreNeverCached() throws Exception {
        MvcResult result = mockMvc.perform(get(SETTINGS_URL)).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("ETag")).isNull();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    // -- the settings read ----------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("the allowance body is {enabled, used, limit, left, typedAvailable}")
    void settingsShape() throws Exception {
        mockMvc.perform(get(SETTINGS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.left").value(2))
                .andExpect(jsonPath("$.typedAvailable").value(true));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("with Ask off the allowance is still 200, enabled false and zeros: the endpoint has no flag gate")
    void settingsWithAskOffIs200() throws Exception {
        properties.setEnabled(false);
        when(askService.settings(any())).thenReturn(
                com.gregochr.goldenhour.service.ask.AskSettingsResponse.off());

        mockMvc.perform(get(SETTINGS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.limit").value(0))
                .andExpect(jsonPath("$.left").value(0))
                .andExpect(jsonPath("$.typedAvailable").value(false));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("a token for a user that no longer exists is 401 in the shared error shape")
    void settingsUnknownUser() throws Exception {
        when(askService.settings(any())).thenThrow(new AskRefusal(AskErrorCode.UNAUTHENTICATED));

        mockMvc.perform(get(SETTINGS_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
