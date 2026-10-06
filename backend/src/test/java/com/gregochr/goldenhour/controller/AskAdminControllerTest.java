package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.UserSettingsService.HomeLocation;
import com.gregochr.goldenhour.service.ask.AskAnswer;
import com.gregochr.goldenhour.service.ask.AskEngine;
import com.gregochr.goldenhour.service.ask.AskOutcome;
import com.gregochr.goldenhour.service.ask.AskPick;
import com.gregochr.goldenhour.service.ask.AskProperties;
import com.gregochr.goldenhour.service.ask.AskQuestion;
import com.gregochr.goldenhour.service.ask.AskReadyService;
import com.gregochr.goldenhour.service.ask.AskRun;
import com.gregochr.goldenhour.service.ask.AskRunOptions;
import com.gregochr.goldenhour.service.ask.AskSnapshot;
import com.gregochr.goldenhour.service.ask.AskSnapshotBuilder;
import com.gregochr.goldenhour.service.ask.AskTools;
import com.gregochr.goldenhour.service.ask.AskUserContext;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AskAdminController} through the real security chain: who may call the dry-run, what it
 * refuses, and that it hands the active engine the cleaned question and the calling admin as the
 * asker.
 */
class AskAdminControllerTest extends AbstractControllerTest {

    private static final String URL = "/api/admin/ask/dry-run";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AskProperties properties;

    @MockitoBean
    private AskEngine engine;
    @MockitoBean
    private AskSnapshotBuilder snapshotBuilder;
    @MockitoBean
    private RegionRepository regionRepository;
    @MockitoBean
    private AskReadyService readyService;

    private final AskSnapshot snapshot = new AskSnapshot(null, null, LocalDate.of(2026, 10, 5), List.of(),
            List.of(), List.of());

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        when(settingsService.getHomeLocation(any())).thenReturn(new HomeLocation(7L, null, null, null, null));
        when(driveTimeResolver.hasDriveTimes(7L)).thenReturn(true);
        AskPick pick = new AskPick(1, 5L, "Bamburgh", "Coast", LocalDate.of(2026, 10, 5), TargetType.SUNSET,
                "2026-10-05_sunset", "Clear sky.", 5, "WORTH_IT");
        AskAnswer answer = new AskAnswer(true, "Bamburgh looks best.", List.of(pick), List.of(), null);
        when(engine.run(any(), any(), any(), any())).thenReturn(new AskRun(
                new AskOutcome(AskOutcome.Status.OK, answer, false, 2),
                List.of(new AskTools.ToolCall("rank_spots", false, 120),
                        new AskTools.ToolCall("submit_answer", false, 0)), null));
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
        properties.setStub(false);
    }

    private static String body(String question, String extra) {
        return "{\"question\":" + quote(question) + extra + "}";
    }

    /** The text as a JSON string literal: quotes, backslashes and every control character escaped. */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    // -- the role matrix --------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/ask/dry-run: ADMIN gets 200 with the outcome, the answer and the tool trace")
    void admin_ok() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot tonight?", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engine").value("claude"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.turns").value(2))
                .andExpect(jsonPath("$.personal").value(false))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.answer.summary").value("Bamburgh looks best."))
                .andExpect(jsonPath("$.answer.picks[0].locationName").value("Bamburgh"))
                .andExpect(jsonPath("$.answer.picks[0].date").value("2026-10-05"))
                .andExpect(jsonPath("$.answer.picks[0].targetType").value("SUNSET"))
                .andExpect(jsonPath("$.trace.length()").value(2))
                .andExpect(jsonPath("$.trace[0].tool").value("rank_spots"))
                .andExpect(jsonPath("$.trace[1].tool").value("submit_answer"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/admin/ask/dry-run: 403 for PRO_USER, and the engine is never reached")
    void pro_forbidden() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(engine, snapshotBuilder);
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("POST /api/admin/ask/dry-run: 403 for LITE_USER, and the engine is never reached")
    void lite_forbidden() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(engine, snapshotBuilder);
    }

    @Test
    @DisplayName("POST /api/admin/ask/dry-run: 401 without authentication")
    void anonymous_unauthorised() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(engine, snapshotBuilder);
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("with Ask off, a non-admin is still 403: the role check comes before the flag")
    void flagOff_nonAdminStill403() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isForbidden());
    }

    // -- the flag ---------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("with photocast.ask.enabled false an admin gets 404 and nothing runs")
    void flagOff_adminGets404() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isNotFound());

        verifyNoInteractions(engine, snapshotBuilder);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("the response says which engine answered: stub when the stub flag is on")
    void engineLabelFollowsTheStubFlag() throws Exception {
        properties.setStub(true);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.engine").value("stub"));
    }

    // -- what the engine is given -----------------------------------------------------------

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("the engine is given the cleaned question, the regions, the window, the admin as the asker "
            + "(with their drive-time fact) and no Ready options")
    void engineReceivesTheCleanedQuestionAndTheAdmin() throws Exception {
        when(regionRepository.findAllById(Set.of(2L, 3L))).thenReturn(List.of(
                RegionEntity.builder().id(2L).name("A").enabled(true).build(),
                RegionEntity.builder().id(3L).name("B").enabled(true).build()));

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(
                        body("  Best\u0000   spot\n tonight?  ",
                                ",\"regionIds\":[3,2,3],\"windowId\":\"2026-10-05_sunset\"")))
                .andExpect(status().isOk());

        ArgumentCaptor<AskQuestion> question = ArgumentCaptor.forClass(AskQuestion.class);
        ArgumentCaptor<AskUserContext> user = ArgumentCaptor.forClass(AskUserContext.class);
        ArgumentCaptor<AskRunOptions> options = ArgumentCaptor.forClass(AskRunOptions.class);
        verify(engine).run(question.capture(), any(AskSnapshot.class), user.capture(), options.capture());
        assertThat(question.getValue().sanitised()).isEqualTo("Best spot tonight?");
        assertThat(question.getValue().normalised()).isEqualTo("best spot tonight?");
        assertThat(question.getValue().regionIds()).containsExactly(3L, 2L);
        assertThat(question.getValue().windowId()).isEqualTo("2026-10-05_sunset");
        assertThat(user.getValue()).isEqualTo(new AskUserContext(7L, UserRole.ADMIN, true));
        assertThat(options.getValue()).isEqualTo(AskRunOptions.none());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an admin with no stored drive times is told to the engine as such")
    void adminWithoutDriveTimes() throws Exception {
        when(driveTimeResolver.hasDriveTimes(7L)).thenReturn(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isOk());

        ArgumentCaptor<AskUserContext> user = ArgumentCaptor.forClass(AskUserContext.class);
        verify(engine).run(any(), any(), user.capture(), any());
        assertThat(user.getValue().hasDriveTimes()).isFalse();
    }

    // -- refusals ---------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t", "\u0000​"})
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("a blank question, or one that is blank once control characters are stripped, is 400")
    void blankQuestion_400(String question) throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body(question, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The question must not be blank."));
        verifyNoInteractions(engine);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("over HTTP a JSON null body and no body at all are both Spring's own 400, before the controller")
    void nullOrAbsentBody_400() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body could not be read"));
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body could not be read"));
        verifyNoInteractions(engine);
    }

    @Autowired
    private AskAdminController controller;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("called with a null request, the controller itself answers 400 and never reaches the engine")
    void nullRequest_guardedInTheController() {
        var response = controller.dryRun(null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("error", "A request body is required."));
        verifyNoInteractions(engine, snapshotBuilder);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("a missing question is 400")
    void missingQuestion_400() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(engine);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("a question of 200 characters is accepted, one of 201 is 400")
    void lengthCap() throws Exception {
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("a".repeat(200), "")))
                .andExpect(status().isOk());
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("a".repeat(201), "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The question must be at most 200 characters."));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an unknown or disabled region id is 400, and so is a null id or more than twenty ids")
    void regionIds_400() throws Exception {
        when(regionRepository.findAllById(Set.of(9L))).thenReturn(List.of());
        when(regionRepository.findAllById(Set.of(4L))).thenReturn(List.of(
                RegionEntity.builder().id(4L).name("Off").enabled(false).build()));
        List<String> twentyOne = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            twentyOne.add(String.valueOf(i));
        }

        for (String ids : List.of("[9]", "[4]", "[1,null]", "[" + String.join(",", twentyOne) + "]")) {
            mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                            .content(body("Best spot?", ",\"regionIds\":" + ids)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Unknown, disabled or too many region ids."));
        }
        verifyNoInteractions(engine);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("exactly twenty region ids is allowed, and an empty list means every region")
    void regionIds_boundary() throws Exception {
        List<RegionEntity> twenty = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            twenty.add(RegionEntity.builder().id(i).name("R" + i).enabled(true).build());
            ids.add(String.valueOf(i));
        }
        when(regionRepository.findAllById(any())).thenReturn(twenty);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .content(body("Best spot?", ",\"regionIds\":[" + String.join(",", ids) + "]")))
                .andExpect(status().isOk());
        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", ",\"regionIds\":[]")))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("no briefing built yet is 409, and the engine is not asked")
    void noBriefing_409() throws Exception {
        when(snapshotBuilder.current()).thenReturn(Optional.empty());

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("No briefing has been built yet."));
        verify(engine, never()).run(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("a FAILED run is a 200 carrying the status, the reason and the trace, not an error")
    void failedRun_isReportedNotThrown() throws Exception {
        when(engine.run(any(), any(), any(), any())).thenReturn(new AskRun(
                new AskOutcome(AskOutcome.Status.FAILED, null, false, 4),
                List.of(new AskTools.ToolCall("rank_spots", true, 0)), "no submit_answer within 4 turns"));

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(body("Best spot?", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.reason").value("no submit_answer within 4 turns"))
                .andExpect(jsonPath("$.trace[0].error").value(true));
    }

    // -- the Ready precompute ---------------------------------------------------------------

    private static final String PRECOMPUTE_URL = "/api/admin/ask/ready/precompute";

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/ask/ready/precompute: ADMIN runs it on demand and gets {written, skipped, failed}")
    void precompute_admin_ok() throws Exception {
        when(readyService.precomputeOnDemand()).thenReturn(new AskReadyService.Result(17, 4, 1, null));

        mockMvc.perform(post(PRECOMPUTE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(17))
                .andExpect(jsonPath("$.skipped").value(4))
                .andExpect(jsonPath("$.failed").value(1));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("the precompute is 403 for PRO_USER and never runs")
    void precompute_pro_forbidden() throws Exception {
        mockMvc.perform(post(PRECOMPUTE_URL)).andExpect(status().isForbidden());
        verifyNoInteractions(readyService);
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("the precompute is 403 for LITE_USER and never runs")
    void precompute_lite_forbidden() throws Exception {
        mockMvc.perform(post(PRECOMPUTE_URL)).andExpect(status().isForbidden());
        verifyNoInteractions(readyService);
    }

    @Test
    @DisplayName("the precompute is 401 without authentication")
    void precompute_anonymous_unauthorised() throws Exception {
        mockMvc.perform(post(PRECOMPUTE_URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(readyService);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("with Ask off the precompute is 404 and nothing runs")
    void precompute_flagOff_404() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(post(PRECOMPUTE_URL)).andExpect(status().isNotFound());
        verifyNoInteractions(readyService);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("a precompute refused as a whole (no fresh briefing, a simulation, one already running) is "
            + "409 with the reason")
    void precompute_refused_409() throws Exception {
        when(readyService.precomputeOnDemand()).thenReturn(
                new AskReadyService.Result(0, 0, 0, "a precompute is already running"));

        mockMvc.perform(post(PRECOMPUTE_URL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("a precompute is already running"));
    }
}
