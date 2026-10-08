package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.ask.AskEvent;
import com.gregochr.goldenhour.service.ask.AskProperties;
import com.gregochr.goldenhour.service.ask.AskReadyResponse;
import com.gregochr.goldenhour.service.ask.AskReadyService;
import com.gregochr.goldenhour.service.ask.AskScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/ask/ready} through the real security chain and the real JSON mapper: who may read it
 * (Bearer, no role gate), what the scope parameter accepts, the wire shape of plan §2.9, and that it is
 * ETag-revalidated.
 */
class AskControllerTest extends AbstractControllerTest {

    private static final String URL = "/api/ask/ready";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AskProperties properties;

    @MockitoBean
    private AskReadyService readyService;
    @MockitoBean
    private RegionRepository regionRepository;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(readyService.serve(any())).thenAnswer(inv -> {
            AskScope scope = inv.getArgument(0);
            return new AskReadyResponse(scope.isEverywhere() ? "all" : scope.key(), List.of(question()));
        });
        when(regionRepository.findAllById(Set.of(3L))).thenReturn(List.of(
                RegionEntity.builder().id(3L).name("Northumberland").enabled(true).build()));
        when(regionRepository.findAllById(Set.of(4L))).thenReturn(List.of(
                RegionEntity.builder().id(4L).name("Retired").enabled(false).build()));
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
    }

    private static AskReadyResponse.Question question() {
        AskReadyResponse.Pick pick = new AskReadyResponse.Pick(1, 123L, "Whitby", "North York Moors & Coast",
                LocalDate.of(2026, 10, 5), TargetType.SUNSET, "2026-10-05_sunset", "Clear and the tide suits.");
        AskEvent noWarning = new AskEvent("AURORA", "Aurora tonight", LocalDate.of(2026, 10, 5), "Kp 6.", null);
        AskEvent warning = new AskEvent("ECLIPSE", "Partial solar eclipse", LocalDate.of(2026, 10, 10),
                "Visible.", "Certified solar filter on the lens");
        AskReadyResponse.Answer answer = new AskReadyResponse.Answer(true, "ready", "Whitby at sunset.",
                List.of(pick), List.of(noWarning, warning), null,
                List.of(new AskReadyResponse.Suggestion("RARE_EVENTS", "Any rare events coming up?")));
        return new AskReadyResponse.Question("BEST_NEXT", "Best spot tonight?", List.of("plan", "map"),
                LocalDateTime.of(2026, 10, 5, 5, 2, 11), "06:02", answer);
    }

    // -- who may read it --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"LITE_USER", "PRO_USER", "ADMIN"})
    @DisplayName("every role may read the Ready answers: Bearer, no role gate")
    void everyRoleMayRead(String role) throws Exception {
        mockMvc.perform(get(URL).with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("someone").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(1));
    }

    @Test
    @DisplayName("an unauthenticated request is 401, whatever the flag says, and nothing is read")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        properties.setEnabled(false);
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(readyService);
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("with Ask off the endpoint is 404 and nothing is read")
    void flagOffIs404() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(get(URL)).andExpect(status().isNotFound());
        verifyNoInteractions(readyService);
    }

    // -- the wire shape ---------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("the body is the plan's wire contract: scope, questions with tabs, generatedAt as ISO text and "
            + "runLabel, an answer of kind ready with picks, events, missing null and try")
    void wireShape() throws Exception {
        mockMvc.perform(get(URL).param("scope", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"))
                .andExpect(jsonPath("$.questions[0].id").value("BEST_NEXT"))
                .andExpect(jsonPath("$.questions[0].text").value("Best spot tonight?"))
                .andExpect(jsonPath("$.questions[0].tabs[0]").value("plan"))
                .andExpect(jsonPath("$.questions[0].tabs[1]").value("map"))
                .andExpect(jsonPath("$.questions[0].generatedAt").value("2026-10-05T05:02:11"))
                .andExpect(jsonPath("$.questions[0].runLabel").value("06:02"))
                .andExpect(jsonPath("$.questions[0].answer.answerable").value(true))
                .andExpect(jsonPath("$.questions[0].answer.kind").value("ready"))
                .andExpect(jsonPath("$.questions[0].answer.summary").value("Whitby at sunset."))
                .andExpect(jsonPath("$.questions[0].answer.picks[0].rank").value(1))
                .andExpect(jsonPath("$.questions[0].answer.picks[0].locationId").value(123))
                .andExpect(jsonPath("$.questions[0].answer.picks[0].locationName").value("Whitby"))
                .andExpect(jsonPath("$.questions[0].answer.picks[0].regionName").value("North York Moors & Coast"))
                .andExpect(jsonPath("$.questions[0].answer.picks[0].date").value("2026-10-05"))
                .andExpect(jsonPath("$.questions[0].answer.picks[0].targetType").value("SUNSET"))
                .andExpect(jsonPath("$.questions[0].answer.picks[0].windowId").value("2026-10-05_sunset"))
                .andExpect(jsonPath("$.questions[0].answer.picks[0].why").value("Clear and the tide suits."))
                .andExpect(jsonPath("$.questions[0].answer.picks[0]", not(hasKey("ratingAtAnswer"))))
                .andExpect(jsonPath("$.questions[0].answer.picks[0]", not(hasKey("verdictAtAnswer"))))
                .andExpect(jsonPath("$.questions[0].answer", hasKey("missing")))
                .andExpect(jsonPath("$.questions[0].answer.missing").doesNotExist())
                .andExpect(jsonPath("$.questions[0].answer.try[0].id").value("RARE_EVENTS"))
                .andExpect(jsonPath("$.questions[0].answer.try[0].text").value("Any rare events coming up?"));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("an event's safetyNote is on the wire when the served topic has one and omitted when it has "
            + "none")
    void safetyNoteIsOnTheWireWhenPresent() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].answer.events[0]", not(hasKey("safetyNote"))))
                .andExpect(jsonPath("$.questions[0].answer.events[1].safetyNote")
                        .value("Certified solar filter on the lens"));
    }

    // -- the scope parameter ----------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("no scope means all; 'all' is accepted in any case; an enabled region id serves that region "
            + "under its own name")
    void scopeValues() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("all"));
        mockMvc.perform(get(URL).param("scope", "ALL")).andExpect(status().isOk());
        mockMvc.perform(get(URL).param("scope", " all ")).andExpect(status().isOk());
        // An empty value is Spring's own "missing", so it takes the default too.
        mockMvc.perform(get(URL).param("scope", "")).andExpect(status().isOk());
        verify(readyService, org.mockito.Mockito.times(4)).serve(AskScope.ALL);

        mockMvc.perform(get(URL).param("scope", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("3"));
        ArgumentCaptor<AskScope> served = ArgumentCaptor.forClass(AskScope.class);
        verify(readyService, org.mockito.Mockito.times(5)).serve(served.capture());
        assertThat(served.getValue().key()).isEqualTo("3");
        assertThat(served.getValue().names()).containsExactly("Northumberland");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "99", "abc", " ", "3x", "-1", "1.5", "99999999999999999999"})
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a scope that is neither 'all' nor an enabled region's id is 400, and nothing is served")
    void badScope(String scope) throws Exception {
        mockMvc.perform(get(URL).param("scope", scope))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        verify(readyService, never()).serve(any(AskScope.class));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a plus-signed id is the same region as the plain one and is served under the plain key")
    void signedIdIsNormalised() throws Exception {
        mockMvc.perform(get(URL).param("scope", "+3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("3"));
    }

    // -- revalidation -----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("the response is ETag-revalidated: private, no-cache with an ETag, and an unchanged body "
            + "revalidates as 304 — with the scope in the query string")
    void etagRevalidation() throws Exception {
        MvcResult first = mockMvc.perform(get(URL).param("scope", "all"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-cache"))
                .andExpect(header().exists("ETag"))
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();

        mockMvc.perform(get(URL).param("scope", "all").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }
}
