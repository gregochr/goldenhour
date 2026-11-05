package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AskDryRunService}: what the admin dry-run refuses before it runs anything, and what it hands the engine and
 * reports back when it does run.
 */
@ExtendWith(MockitoExtension.class)
class AskDryRunServiceTest {

    @Mock
    private AskEngine engine;
    @Mock
    private AskSnapshotBuilder snapshotBuilder;
    @Mock
    private RegionRepository regionRepository;
    @Mock
    private UserSettingsService settingsService;
    @Mock
    private DriveTimeResolver driveTimeResolver;
    @Mock
    private Authentication auth;

    private final AskProperties properties = new AskProperties();
    private AskDryRunService service;

    @BeforeEach
    void setUp() {
        service = new AskDryRunService(properties, engine, snapshotBuilder, regionRepository, settingsService,
                driveTimeResolver);
    }

    private static AskSnapshot snapshot() {
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));
    }

    @Test
    @DisplayName("a missing request is refused with a sentence, and nothing is run")
    void nullRequest() {
        AskDryRunService.Result result = service.dryRun(null, auth);

        assertThat(result.error()).isEqualTo("A request body is required.");
        assertThat(result.response()).isNull();
        assertThat(result.noBriefing()).isFalse();
        verifyNoInteractions(engine, snapshotBuilder);
    }

    @Test
    @DisplayName("a blank question is refused with the sanitiser's sentence, and nothing is run")
    void blankQuestion() {
        AskDryRunService.Result result = service.dryRun(new AskDryRunService.Request("   ", null, null), auth);

        assertThat(result.error()).isEqualTo(AskQuestionSanitiser.sanitise("   ").error());
        assertThat(result.error()).isNotBlank();
        assertThat(result.response()).isNull();
        verifyNoInteractions(engine, snapshotBuilder);
    }

    @Test
    @DisplayName("an unknown region is refused with the regions sentence, and no snapshot is built")
    void unknownRegion() {
        when(regionRepository.findAllById(new java.util.LinkedHashSet<>(List.of(99L)))).thenReturn(List.of());

        AskDryRunService.Result result = service.dryRun(
                new AskDryRunService.Request("Best spot?", List.of(99L), null), auth);

        assertThat(result.error()).isEqualTo(AskScopes.INVALID_REGIONS);
        verifyNoInteractions(engine, snapshotBuilder);
    }

    @Test
    @DisplayName("with no briefing built yet the result says so and the engine is not run")
    void noBriefing() {
        when(snapshotBuilder.current()).thenReturn(Optional.empty());

        AskDryRunService.Result result = service.dryRun(new AskDryRunService.Request("Best spot?", null, null), auth);

        assertThat(result.noBriefing()).isTrue();
        assertThat(result.error()).isEqualTo("No briefing has been built yet.");
        assertThat(result.response()).isNull();
        // Never run at all, so no argument matters.
        verify(engine, never()).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a run is made as the calling admin, in the scope and window asked, and reported with the claude "
            + "label and its trace")
    void runsAsTheAdmin() {
        RegionEntity region = RegionEntity.builder().id(5L).name("Teesdale").enabled(true).build();
        when(regionRepository.findAllById(new java.util.LinkedHashSet<>(List.of(5L)))).thenReturn(List.of(region));
        AskSnapshot snapshot = snapshot();
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        when(settingsService.getUserId(auth)).thenReturn(42L);
        when(driveTimeResolver.hasDriveTimes(42L)).thenReturn(true);
        AskAnswer answer = new AskAnswer(false, "Can't tell.", List.of(), List.of(), "parking");
        AskTools.ToolCall call = new AskTools.ToolCall("list_windows", false, 100);
        AskRun run = new AskRun(new AskOutcome(AskOutcome.Status.CANT, answer, true, 2), List.of(call), null);
        when(engine.run(argThat(q -> "Best spot?".equals(q.sanitised())), eq(snapshot), argThat(u -> u.userId() == 42L),
                eq(AskRunOptions.none()))).thenReturn(run);

        AskDryRunService.Result result = service.dryRun(
                new AskDryRunService.Request("  Best spot?  ", List.of(5L), "2026-10-05_sunset"), auth);

        assertThat(result.error()).isNull();
        AskDryRunService.Response response = result.response();
        assertThat(response.engine()).isEqualTo("claude");
        assertThat(response.status()).isEqualTo(AskOutcome.Status.CANT);
        assertThat(response.answer()).isSameAs(answer);
        assertThat(response.personal()).isTrue();
        assertThat(response.turns()).isEqualTo(2);
        assertThat(response.reason()).isNull();
        assertThat(response.trace()).containsExactly(call);
        ArgumentCaptor<AskQuestion> question = ArgumentCaptor.forClass(AskQuestion.class);
        ArgumentCaptor<AskUserContext> user = ArgumentCaptor.forClass(AskUserContext.class);
        verify(engine).run(question.capture(), eq(snapshot), user.capture(), eq(AskRunOptions.none()));
        assertThat(question.getValue().sanitised()).isEqualTo("Best spot?");
        assertThat(question.getValue().windowId()).isEqualTo("2026-10-05_sunset");
        assertThat(question.getValue().scope().names()).containsExactly("Teesdale");
        assertThat(question.getValue().view()).isEqualTo("plan");
        assertThat(user.getValue().userId()).isEqualTo(42L);
        assertThat(user.getValue().role()).isEqualTo(UserRole.ADMIN);
        assertThat(user.getValue().hasDriveTimes()).isTrue();
    }

    @Test
    @DisplayName("a failed run reports its reason, and the stub engine is labelled 'stub'")
    void failedRunWithTheStub() {
        properties.setStub(true);
        AskSnapshot snapshot = snapshot();
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        when(settingsService.getUserId(auth)).thenReturn(42L);
        when(driveTimeResolver.hasDriveTimes(42L)).thenReturn(false);
        when(engine.run(argThat(q -> "Best spot?".equals(q.sanitised())), eq(snapshot),
                argThat(u -> u.userId() == 42L && !u.hasDriveTimes()), eq(AskRunOptions.none())))
                .thenReturn(AskRun.failed("the deadline passed", 1, false,
                List.of()));

        AskDryRunService.Response response = service.dryRun(new AskDryRunService.Request("Best spot?", null, null),
                auth).response();

        assertThat(response.engine()).isEqualTo("stub");
        assertThat(response.status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(response.answer()).isNull();
        assertThat(response.reason()).isEqualTo("the deadline passed");
        assertThat(response.turns()).isEqualTo(1);
    }
}
