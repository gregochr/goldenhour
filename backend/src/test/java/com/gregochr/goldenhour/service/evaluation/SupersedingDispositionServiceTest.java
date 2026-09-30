package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SupersedingDispositionService} — round 14 of the verdict-minimum-sample
 * review series, replacing round 13's design after a Codex review tested it against production
 * (pipeline run 249, 2026-09-29) and found both its discriminator and its disposition allow-list
 * wrong.
 */
@ExtendWith(MockitoExtension.class)
class SupersedingDispositionServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);
    private static final TargetType SUNSET = TargetType.SUNSET;

    // The real production timeline: nightly ~01:05, intraday 14:00 (pipeline run 249's own
    // trigger), next nightly ~01:05 the following day.
    private static final Instant NIGHTLY = Instant.parse("2026-09-29T01:05:00Z");
    private static final Instant INTRADAY = Instant.parse("2026-09-29T14:00:00Z");
    private static final Instant NEXT_NIGHTLY = Instant.parse("2026-09-30T01:05:00Z");

    @Mock
    private PipelineRunRepository pipelineRunRepository;
    @Mock
    private ForecastRunDispositionRepository forecastRunDispositionRepository;

    private SupersedingDispositionService service() {
        return new SupersedingDispositionService(
                pipelineRunRepository, forecastRunDispositionRepository);
    }

    // ── the production case ──────────────────────────────────────────────────

    @Test
    @DisplayName("production case: a later cycle wrote EVALUATED for the slot and no result ever "
            + "arrives — the late older result is NOT superseded (written to every sink) — must "
            + "fail against 20922233's design, which treated EVALUATED as superseding")
    void productionCase_laterCycleWroteEvaluated_notSuperseded() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of(INTRADAY));
        // The real query excludes EVALUATED by construction (proven in
        // ForecastRunDispositionRepositoryTest) — false is what it actually returns for this
        // scenario, which is what this mock represents.
        when(forecastRunDispositionRepository.existsSupersedingDisposition(
                eq("X"), eq(DATE), eq("SUNSET"), eq(INTRADAY)))
                .thenReturn(false);

        boolean superseded = service().isSuperseded("X", DATE, SUNSET, NIGHTLY);

        assertThat(superseded).isFalse();
    }

    @Test
    @DisplayName("production case, bulk form: same result, via supersededLocations")
    void productionCase_bulkForm_notSuperseded() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of(INTRADAY));
        when(forecastRunDispositionRepository.findSupersedingDispositions(
                eq(DATE), eq("SUNSET"), eq(List.of("X")), eq(INTRADAY)))
                .thenReturn(List.of());

        Set<String> superseded = service().supersededLocations(
                List.of(new SupersedingDispositionService.LocatedSubmission("X", NIGHTLY)),
                DATE, SUNSET);

        assertThat(superseded).isEmpty();
    }

    // ── the anchor-run shape: no forecast_batch dependency at all ────────────

    @Test
    @DisplayName("anchor-run shape: a later cycle wrote SKIPPED_TRIAGED with no forecast_batch row "
            + "for its job run at all — supersedes anyway, because this service never touches "
            + "forecast_batch or job_run_id — must fail against 20922233's three-entity join")
    void anchorRunShape_triagedWithNoBatchRow_supersedes() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of(INTRADAY));
        when(forecastRunDispositionRepository.existsSupersedingDisposition(
                eq("X"), eq(DATE), eq("SUNSET"), eq(INTRADAY)))
                .thenReturn(true);

        boolean superseded = service().isSuperseded("X", DATE, SUNSET, NIGHTLY);

        assertThat(superseded).isTrue();
    }

    @Test
    @DisplayName("anchor-run shape with SKIPPED_STABILITY instead: the same result")
    void anchorRunShape_stabilitySkipWithNoBatchRow_supersedes() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of(INTRADAY));
        when(forecastRunDispositionRepository.existsSupersedingDisposition(
                eq("X"), eq(DATE), eq("SUNSET"), eq(INTRADAY)))
                .thenReturn(true);

        boolean superseded = service().isSuperseded("X", DATE, SUNSET, NIGHTLY);

        assertThat(superseded).isTrue();
    }

    // ── every excluded disposition value, individually ───────────────────────
    // (the repository's own allow-list query is what actually excludes these — see
    // ForecastRunDispositionRepositoryTest for the SQL-level proof; these tests pin the SERVICE's
    // behaviour when the repository correctly reports "nothing found" for each one.)

    @Test
    @DisplayName("EVALUATED does not supersede")
    void evaluated_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("FORCE_EVALUATED does not supersede")
    void forceEvaluated_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("SKIPPED_HARD_CONSTRAINT does not supersede (not on the two-item allow-list)")
    void skippedHardConstraint_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("SKIPPED_NO_PROMPT does not supersede")
    void skippedNoPrompt_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("SKIPPED_CACHED does not supersede")
    void skippedCached_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("SKIPPED_PAST_DATE does not supersede")
    void skippedPastDate_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("SKIPPED_TRAVEL_DAY does not supersede")
    void skippedTravelDay_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("SKIPPED_UNKNOWN_LOCATION does not supersede")
    void skippedUnknownLocation_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("SKIPPED_ERROR does not supersede")
    void skippedError_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    @Test
    @DisplayName("SKIPPED_NO_REFRESH_NEEDED does not supersede")
    void skippedNoRefreshNeeded_doesNotSupersede() {
        assertExcludedCategoryDoesNotSupersede();
    }

    /**
     * Common shape for every excluded-category test: the repository (correctly, per its own
     * allow-list) returns nothing for a slot whose only later-cycle disposition is one of the ten
     * excluded categories, and the service must report "not superseded".
     */
    private void assertExcludedCategoryDoesNotSupersede() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of(INTRADAY));
        when(forecastRunDispositionRepository.existsSupersedingDisposition(
                eq("X"), eq(DATE), eq("SUNSET"), eq(INTRADAY)))
                .thenReturn(false);

        assertThat(service().isSuperseded("X", DATE, SUNSET, NIGHTLY)).isFalse();
    }

    // ── same-cycle safety ─────────────────────────────────────────────────────

    @Test
    @DisplayName("same cycle: a SKIPPED_STABILITY/SKIPPED_TRIAGED row written minutes after this "
            + "cycle's own trigger does not supersede this cycle's own result — no later pipeline "
            + "run exists at all, so Phase 1 short-circuits and the disposition repository is never "
            + "even consulted")
    void sameCycle_doesNotSupersedeOwnResult() {
        // This IS the cycle's own trigger — no run has been triggered since.
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of());

        boolean superseded = service().isSuperseded("X", DATE, SUNSET, NIGHTLY);

        assertThat(superseded).isFalse();
        verifyNoInteractions(forecastRunDispositionRepository);
    }

    // ── retry ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a retry batch's result, with its precursor cycle's own rows present: written — "
            + "a later cycle exists somewhere, but nothing was decided against THIS slot")
    void retryBatchResult_withPrecursorCycleRowsPresent_written() {
        // The retry shares its precursor's submittedAt (NIGHTLY) by construction (round 12). A
        // later, unrelated cycle (INTRADAY) exists, but has nothing to say about this slot.
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of(INTRADAY));
        when(forecastRunDispositionRepository.existsSupersedingDisposition(
                eq("X"), eq(DATE), eq("SUNSET"), eq(INTRADAY)))
                .thenReturn(false);

        boolean superseded = service().isSuperseded("X", DATE, SUNSET, NIGHTLY);

        assertThat(superseded).isFalse();
    }

    // ── two results, different submittedAt, in one bulk call ────────────────

    @Test
    @DisplayName("two results in one call with different submittedAt: A (older) is superseded by a "
            + "disposition at its own next trigger; B (newer) is not superseded by the same load, "
            + "because its own next trigger is later still and nothing was decided against it there")
    void twoResultsDifferentSubmittedAt_oneSupersededOneNot() {
        // A submitted at NIGHTLY, B submitted at INTRADAY (already later than A).
        // The trigger-time load starts from the EARLIEST (NIGHTLY) and returns every later cycle:
        // INTRADAY, then NEXT_NIGHTLY.
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY))
                .thenReturn(List.of(INTRADAY, NEXT_NIGHTLY));
        // A's own next trigger is INTRADAY (first entry after NIGHTLY) — a disposition exists there.
        // B's own next trigger is NEXT_NIGHTLY (first entry after INTRADAY) — nothing exists there.
        // One bulk query loads dispositions from the MINIMUM next-trigger needed (INTRADAY).
        when(forecastRunDispositionRepository.findSupersedingDispositions(
                eq(DATE), eq("SUNSET"), eq(List.of("A", "B")), eq(INTRADAY)))
                .thenReturn(List.<Object[]>of(new Object[] {"A", INTRADAY}));

        Set<String> superseded = service().supersededLocations(
                List.of(
                        new SupersedingDispositionService.LocatedSubmission("A", NIGHTLY),
                        new SupersedingDispositionService.LocatedSubmission("B", INTRADAY)),
                DATE, SUNSET);

        assertThat(superseded).containsExactly("A");
    }

    // ── query count, precise verify(), no any() ──────────────────────────────

    @Test
    @DisplayName("common case (no later run): exactly one query, and the disposition repository is "
            + "never touched — bulk form")
    void commonCase_bulk_costsExactlyOneQuery() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of());

        Set<String> superseded = service().supersededLocations(
                List.of(new SupersedingDispositionService.LocatedSubmission("X", NIGHTLY)),
                DATE, SUNSET);

        assertThat(superseded).isEmpty();
        verify(pipelineRunRepository, times(1)).findTriggerTimesAfter(NIGHTLY);
        verifyNoInteractions(forecastRunDispositionRepository);
    }

    @Test
    @DisplayName("common case (no later run): exactly one query — single-location form")
    void commonCase_single_costsExactlyOneQuery() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of());

        boolean superseded = service().isSuperseded("X", DATE, SUNSET, NIGHTLY);

        assertThat(superseded).isFalse();
        verify(pipelineRunRepository, times(1)).findTriggerTimesAfter(NIGHTLY);
        verifyNoInteractions(forecastRunDispositionRepository);
    }

    @Test
    @DisplayName("later run exists: exactly two queries, the second with precise, literal arguments")
    void laterRunExists_costsExactlyTwoQueriesWithPreciseArguments() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of(INTRADAY));
        when(forecastRunDispositionRepository.findSupersedingDispositions(
                DATE, "SUNSET", List.of("X"), INTRADAY))
                .thenReturn(List.of());

        service().supersededLocations(
                List.of(new SupersedingDispositionService.LocatedSubmission("X", NIGHTLY)),
                DATE, SUNSET);

        verify(pipelineRunRepository, times(1)).findTriggerTimesAfter(NIGHTLY);
        verify(forecastRunDispositionRepository, times(1))
                .findSupersedingDispositions(DATE, "SUNSET", List.of("X"), INTRADAY);
        verify(forecastRunDispositionRepository, never())
                .existsSupersedingDisposition(eq("X"), eq(DATE), eq("SUNSET"), eq(INTRADAY));
    }

    // ── unknown submission instants ───────────────────────────────────────────

    @Test
    @DisplayName("null submittedAt: never superseded, no repository interaction at all")
    void nullSubmittedAt_neverSuperseded_noInteraction() {
        boolean superseded = service().isSuperseded("X", DATE, SUNSET, null);

        assertThat(superseded).isFalse();
        verifyNoInteractions(pipelineRunRepository);
        verifyNoInteractions(forecastRunDispositionRepository);
    }

    @Test
    @DisplayName("bulk: every submission has a null submittedAt — empty result, no interaction")
    void bulk_allNullSubmittedAt_noInteraction() {
        Set<String> superseded = service().supersededLocations(
                List.of(new SupersedingDispositionService.LocatedSubmission("X", null)),
                DATE, SUNSET);

        assertThat(superseded).isEmpty();
        verifyNoInteractions(pipelineRunRepository);
        verifyNoInteractions(forecastRunDispositionRepository);
    }

    @Test
    @DisplayName("bulk: a mix of known and null submittedAt — the null one is simply excluded, the "
            + "known one is still checked correctly")
    void bulk_mixOfKnownAndNullSubmittedAt() {
        when(pipelineRunRepository.findTriggerTimesAfter(NIGHTLY)).thenReturn(List.of());

        Set<String> superseded = service().supersededLocations(
                List.of(
                        new SupersedingDispositionService.LocatedSubmission("X", NIGHTLY),
                        new SupersedingDispositionService.LocatedSubmission("Y", null)),
                DATE, SUNSET);

        assertThat(superseded).isEmpty();
        verify(pipelineRunRepository, times(1)).findTriggerTimesAfter(NIGHTLY);
    }
}
