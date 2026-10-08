package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AskReadyAnswerEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.repository.AskReadyAnswerRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AskReadyStore} and the two repositories B3 reads, on H2 with the schema generated from the
 * entity annotations (no Flyway, no Docker; V165 itself is proved by the Testcontainers test in CI):
 * the unique key makes the write an idempotent upsert, a stored answer keeps its freshness record, a
 * bad row is dropped rather than thrown, and the per-day ceiling's count is exact at its boundaries.
 */
@DataJpaTest
class AskReadyStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 5, 5, 2, 11);

    @Autowired
    private AskReadyAnswerRepository repository;
    @Autowired
    private JobRunRepository jobRuns;

    private AskReadyStore store() {
        return new AskReadyStore(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AskAnswer answer(String summary) {
        AskPick pick = new AskPick(1, 1L, "Bamburgh", "Northumberland", LocalDate.of(2026, 10, 10),
                TargetType.SUNSET, "2026-10-10_sunset", "Clear sky.", 5, DisplayVerdict.WORTH_IT.name());
        AskEvent event = new AskEvent("ECLIPSE", "Partial solar eclipse", LocalDate.of(2026, 10, 14),
                "Visible.", "Certified solar filter on the lens");
        return new AskAnswer(true, summary, List.of(pick), List.of(event), null);
    }

    private static ReadyQuestion.Offer offer(String text) {
        return new ReadyQuestion.Offer(text, List.of("2026-10-10_sunrise", "2026-10-10_sunset"), null);
    }

    @Test
    @DisplayName("an answer round-trips with each pick's rating and verdict at answer time, the event's "
            + "safety note, the question text and its windows")
    void roundTrip() {
        store().upsert("ALL", ReadyQuestion.BEST_WEEKEND, offer("Best spot this weekend?"), answer("First."),
                BUILT, 42L);

        List<AskReadyStore.Stored> found = store().findScope("ALL");

        assertThat(found).singleElement().satisfies(s -> {
            assertThat(s.questionId()).isEqualTo("BEST_WEEKEND");
            assertThat(s.questionText()).isEqualTo("Best spot this weekend?");
            assertThat(s.windowIds()).containsExactly("2026-10-10_sunrise", "2026-10-10_sunset");
            assertThat(s.briefingGeneratedAt()).isEqualTo(BUILT);
            assertThat(s.answer().picks().getFirst().ratingAtAnswer()).isEqualTo(5);
            assertThat(s.answer().picks().getFirst().verdictAtAnswer()).isEqualTo("WORTH_IT");
            assertThat(s.answer().picks().getFirst().date().toString()).isEqualTo("2026-10-10");
            assertThat(s.answer().events().getFirst().safetyNote())
                    .isEqualTo("Certified solar filter on the lens");
        });
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getPipelineRunId()).isEqualTo(42L);
            assertThat(row.getCreatedAt()).isEqualTo(NOW);
            assertThat(row.getAnswerJson()).contains("\"2026-10-10\"");
        });
    }

    @Test
    @DisplayName("writing the same scope and question twice replaces the row: still one row, the newer "
            + "answer, the newer text and windows")
    void upsertIsIdempotent() {
        store().upsert("ALL", ReadyQuestion.BEST_NEXT, offer("Best spot tonight?"), answer("First."), BUILT, 1L);
        store().upsert("ALL", ReadyQuestion.BEST_NEXT,
                new ReadyQuestion.Offer("Best spot tomorrow morning?", List.of("2026-10-06_sunrise"), null),
                answer("Second."), BUILT.plusHours(12), null);

        List<AskReadyAnswerEntity> rows = repository.findAll();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getQuestionText()).isEqualTo("Best spot tomorrow morning?");
            assertThat(row.getWindowIds()).isEqualTo("2026-10-06_sunrise");
            assertThat(row.getBriefingGeneratedAt()).isEqualTo(BUILT.plusHours(12));
            assertThat(row.getPipelineRunId()).isNull();
            assertThat(row.getAnswerJson()).contains("Second.");
        });
    }

    @Test
    @DisplayName("scopes and questions are separate rows; a scope's read returns only its own")
    void scopesAreSeparate() {
        store().upsert("ALL", ReadyQuestion.BEST_NEXT, offer("a"), answer("all"), BUILT, 1L);
        store().upsert("3", ReadyQuestion.BEST_NEXT, offer("b"), answer("region"), BUILT, 1L);
        store().upsert("3", ReadyQuestion.SNOW_TOPS, offer("c"), answer("region snow"), BUILT, 1L);

        assertThat(store().findScope("3")).extracting(AskReadyStore.Stored::questionId)
                .containsExactlyInAnyOrder("BEST_NEXT", "SNOW_TOPS");
        assertThat(store().findScope("ALL")).hasSize(1);
        assertThat(store().findScope("9")).isEmpty();
    }

    @Test
    @DisplayName("a row that cannot be decoded is dropped, never thrown, so one bad row cannot fail a serve")
    void badRowIsDropped() {
        AskReadyAnswerEntity bad = new AskReadyAnswerEntity();
        bad.setScopeKey("ALL");
        bad.setQuestionId("BEST_NEXT");
        bad.setQuestionText("x");
        bad.setWindowIds("");
        bad.setBriefingGeneratedAt(BUILT);
        bad.setAnswerJson("{not json");
        bad.setCreatedAt(NOW);
        repository.save(bad);

        assertThat(store().findScope("ALL")).isEmpty();
    }

    @Test
    @DisplayName("a question with no windows is stored with an empty window list and read back as one")
    void emptyWindows() {
        store().upsert("ALL", ReadyQuestion.RARE_EVENTS, new ReadyQuestion.Offer("Any?", List.of(), null),
                answer("Events."), BUILT, null);

        assertThat(store().findScope("ALL").getFirst().windowIds()).isEmpty();
        assertThat(repository.findByScopeKeyAndQuestionId("ALL", "RARE_EVENTS"))
                .map(AskReadyAnswerEntity::getWindowIds).contains("");
        assertThat(repository.findByScopeKeyAndQuestionId("ALL", "SNOW_TOPS")).isEqualTo(Optional.empty());
    }

    // -- the daily ceiling's count ----------------------------------------------------------

    private void run(RunType type, boolean manual, LocalDateTime startedAt) {
        jobRuns.save(JobRunEntity.builder().runType(type).triggeredManually(manual).startedAt(startedAt)
                .build());
    }

    @Test
    @DisplayName("the ceiling's count: scheduled ASK_READY runs started at or after UK midnight; not manual "
            + "ones, not other run types, not the day before (BST midnight is 23:00 UTC)")
    void ceilingCount() {
        LocalDateTime ukMidnight = ForecastHorizon.ukDayStartUtc(LocalDate.of(2026, 10, 5));
        assertThat(ukMidnight).isEqualTo(LocalDateTime.of(2026, 10, 4, 23, 0));
        run(RunType.ASK_READY, false, ukMidnight.minusSeconds(1));
        run(RunType.ASK_READY, false, ukMidnight);
        run(RunType.ASK_READY, false, ukMidnight.plusHours(6));
        run(RunType.ASK_READY, true, ukMidnight.plusHours(7));
        run(RunType.ASK, false, ukMidnight.plusHours(8));

        assertThat(jobRuns.countByRunTypeAndTriggeredManuallyAndStartedAtGreaterThanEqual(RunType.ASK_READY,
                false, ukMidnight)).isEqualTo(2);
        assertThat(jobRuns.countByRunTypeAndTriggeredManuallyAndStartedAtGreaterThanEqual(RunType.ASK_READY,
                true, ukMidnight)).isEqualTo(1);
    }
}
