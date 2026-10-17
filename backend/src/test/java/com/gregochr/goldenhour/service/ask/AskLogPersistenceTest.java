package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AskLogEntity;
import com.gregochr.goldenhour.repository.AskLogRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ask_log} on H2 with the schema generated from the entity: what {@link DatabaseAskLog} stores
 * and, more to the point, what it never does (a question for an outcome the engine did not answer),
 * the aggregates the metrics read, and {@link AskLogCleanupJob}'s retention boundary. The check
 * constraint and the user foreign key are Postgres's and are proved by V167's Testcontainers test in
 * CI.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AskLogPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Autowired
    private AskLogRepository repository;

    private final MutableClock clock = new MutableClock(NOW);

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private DatabaseAskLog log() {
        return new DatabaseAskLog(repository, clock);
    }

    private static AskLog.Entry entry(AskLog.Outcome outcome, String question, String missing) {
        return new AskLog.Entry(7L, "ALL", "map", outcome, question, missing, 123L);
    }

    private List<AskLogEntity> rows() {
        return repository.findAll();
    }

    // -- what is stored ----------------------------------------------------------------------------

    @Test
    @DisplayName("an engine-answered question is stored with its scope, view, user, duration and time")
    void storesAnAnsweredQuestion() {
        log().record(entry(AskLog.Outcome.CLAUDE_OK, "best spot saturday sunset", null));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.getUserId()).isEqualTo(7L);
            assertThat(row.getScopeKey()).isEqualTo("ALL");
            assertThat(row.getView()).isEqualTo("map");
            assertThat(row.getOutcome()).isEqualTo("CLAUDE_OK");
            assertThat(row.getNormalisedQuestion()).isEqualTo("best spot saturday sunset");
            assertThat(row.getMissing()).isNull();
            assertThat(row.getDurationMs()).isEqualTo(123L);
            assertThat(row.getCreatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("the question is stored ONLY for CLAUDE_OK and CLAUDE_CANT: every other outcome stores none, "
            + "whatever the caller passed")
    void theQuestionIsStoredOnlyWhenTheEngineAnswered() {
        for (AskLog.Outcome outcome : AskLog.Outcome.values()) {
            log().record(entry(outcome, "a private question", "something"));
        }

        Map<String, String> stored = rows().stream().collect(Collectors.toMap(AskLogEntity::getOutcome,
                r -> String.valueOf(r.getNormalisedQuestion())));
        assertThat(stored).containsEntry("CLAUDE_OK", "a private question")
                .containsEntry("CLAUDE_CANT", "a private question")
                .containsEntry("READY_MATCH", "null").containsEntry("PREFILTER_CANT", "null")
                .containsEntry("CACHE_HIT", "null").containsEntry("CLAUDE_FAILED", "null");
        assertThat(rows()).hasSize(AskLog.Outcome.values().length);
    }

    @Test
    @DisplayName("missing is stored only for a can't-answer (PREFILTER_CANT, CLAUDE_CANT)")
    void missingIsStoredOnlyForACant() {
        for (AskLog.Outcome outcome : AskLog.Outcome.values()) {
            log().record(entry(outcome, "q", "car park information"));
        }

        Map<String, String> stored = rows().stream().collect(Collectors.toMap(AskLogEntity::getOutcome,
                r -> String.valueOf(r.getMissing())));
        assertThat(stored).containsEntry("PREFILTER_CANT", "car park information")
                .containsEntry("CLAUDE_CANT", "car park information").containsEntry("CLAUDE_OK", "null")
                .containsEntry("READY_MATCH", "null").containsEntry("CACHE_HIT", "null")
                .containsEntry("CLAUDE_FAILED", "null");
    }

    @Test
    @DisplayName("the question is capped at 200 characters and missing at 60: N-1, N and N+1")
    void caps() {
        String q199 = "q".repeat(199);
        String q200 = "q".repeat(200);
        String q201 = "q".repeat(201);
        String m59 = "m".repeat(59);
        String m60 = "m".repeat(60);
        String m61 = "m".repeat(61);

        log().record(entry(AskLog.Outcome.CLAUDE_CANT, q199, m59));
        log().record(entry(AskLog.Outcome.CLAUDE_CANT, q200, m60));
        log().record(entry(AskLog.Outcome.CLAUDE_CANT, q201, m61));

        List<AskLogEntity> stored = rows();
        assertThat(stored).extracting(r -> r.getNormalisedQuestion().length()).containsExactlyInAnyOrder(199,
                200, 200);
        assertThat(stored).extracting(r -> r.getMissing().length()).containsExactlyInAnyOrder(59, 60, 60);
    }

    @Test
    @DisplayName("a cap never splits a surrogate pair, and blank text is stored as absent")
    void capIsCodePointSafe() {
        String clef = "𝄞";
        assertThat(DatabaseAskLog.cap(clef.repeat(201), 200)).isEqualTo(clef.repeat(200));
        assertThat(DatabaseAskLog.cap(clef.repeat(200), 200)).isEqualTo(clef.repeat(200));
        assertThat(DatabaseAskLog.cap("   ", 60)).isNull();
        assertThat(DatabaseAskLog.cap(null, 60)).isNull();
        assertThat(DatabaseAskLog.cap("  trimmed  ", 60)).isEqualTo("trimmed");
    }

    @Test
    @DisplayName("a row without a user (a deleted user's) is allowed, and a negative duration is stored as zero")
    void nullUserAndNegativeDuration() {
        log().record(new AskLog.Entry(0L, "ALL", "plan", AskLog.Outcome.READY_MATCH, null, null, -5L));

        assertThat(rows()).singleElement().extracting(AskLogEntity::getDurationMs).isEqualTo(0L);
        repository.deleteAll();
        repository.insertRow(NOW, null, "ALL", "plan", "CACHE_HIT", null, null, 4L);
        assertThat(rows()).singleElement().extracting(AskLogEntity::getUserId).isNull();
    }

    @Test
    @DisplayName("a failing write is swallowed: the log never fails the response")
    void aFailingWriteNeverThrows() {
        AskLogRepository broken = mock(AskLogRepository.class);
        when(broken.insertRow(any(), any(), any(), any(), any(), any(), any(),
                anyLong())).thenThrow(new IllegalStateException("db down"));

        new DatabaseAskLog(broken, clock).record(entry(AskLog.Outcome.CLAUDE_OK, "q", null));

        verify(broken).insertRow(any(), any(), any(), any(), any(), any(), any(),
                anyLong());
    }

    // -- the aggregates ----------------------------------------------------------------------------

    @Test
    @DisplayName("counts by outcome since a moment: the boundary is inclusive and older rows are out")
    void countsByOutcome() {
        Instant since = NOW.minus(Duration.ofDays(7));
        repository.insertRow(since.minusSeconds(1), 1L, "ALL", "map", "CLAUDE_OK", "q", null, 1L);
        repository.insertRow(since, 1L, "ALL", "map", "CLAUDE_OK", "q", null, 1L);
        repository.insertRow(since.plusSeconds(1), 1L, "ALL", "map", "CLAUDE_OK", "q", null, 1L);
        repository.insertRow(NOW, 1L, "ALL", "map", "CACHE_HIT", null, null, 1L);

        Map<String, Long> counts = repository.countByOutcomeSince(since).stream().collect(
                Collectors.toMap(AskLogRepository.OutcomeCount::getOutcome,
                        AskLogRepository.OutcomeCount::getTotal));

        assertThat(counts).containsEntry("CLAUDE_OK", 2L).containsEntry("CACHE_HIT", 1L).hasSize(2);
    }

    @Test
    @DisplayName("the top missing phrases come most common first, ties by phrase, null ignored, limited")
    void topMissing() {
        for (int i = 0; i < 3; i++) {
            repository.insertRow(NOW, 1L, "ALL", "map", "PREFILTER_CANT", null, "car park information", 1L);
        }
        for (int i = 0; i < 2; i++) {
            repository.insertRow(NOW, 1L, "ALL", "map", "PREFILTER_CANT", null, "opening times", 1L);
            repository.insertRow(NOW, 1L, "ALL", "map", "CLAUDE_CANT", "q", "bus times", 1L);
        }
        repository.insertRow(NOW, 1L, "ALL", "map", "CLAUDE_OK", "q", null, 1L);
        repository.insertRow(NOW.minus(Duration.ofDays(30)), 1L, "ALL", "map", "CLAUDE_CANT", "q", "old", 1L);

        List<AskLogRepository.MissingCount> top = repository.topMissingSince(NOW.minus(Duration.ofDays(7)),
                PageRequest.of(0, 10));

        assertThat(top).extracting(AskLogRepository.MissingCount::getMissing)
                .containsExactly("car park information", "bus times", "opening times");
        assertThat(top).extracting(AskLogRepository.MissingCount::getTotal).containsExactly(3L, 2L, 2L);
        assertThat(repository.topMissingSince(NOW.minus(Duration.ofDays(7)), PageRequest.of(0, 2))).hasSize(2);
    }

    // -- retention ---------------------------------------------------------------------------------

    @Test
    @DisplayName("the nightly prune keeps N-1 and exactly N days of rows and deletes N+1 (90 days by default)")
    void retentionBoundary() {
        AskLogCleanupJob job = new AskLogCleanupJob(repository, mock(DynamicSchedulerService.class), clock,
                new AskProperties());
        Duration ninety = Duration.ofDays(90);
        repository.insertRow(NOW.minus(ninety).plusSeconds(1), 1L, "ALL", "map", "CACHE_HIT", null, null, 1L);
        repository.insertRow(NOW.minus(ninety), 1L, "ALL", "map", "CACHE_HIT", null, null, 1L);
        repository.insertRow(NOW.minus(ninety).minusSeconds(1), 1L, "ALL", "map", "CACHE_HIT", null, null, 1L);
        repository.insertRow(NOW.minus(Duration.ofDays(200)), 1L, "ALL", "map", "CACHE_HIT", null, null, 1L);
        repository.insertRow(NOW, 1L, "ALL", "map", "CACHE_HIT", null, null, 1L);

        int deleted = job.prune();

        assertThat(deleted).isEqualTo(2);
        assertThat(rows()).extracting(AskLogEntity::getCreatedAt).containsExactlyInAnyOrder(
                NOW.minus(ninety).plusSeconds(1), NOW.minus(ninety), NOW);
    }

    @Test
    @DisplayName("the retention follows photocast.ask.log.retention-days, and an empty table prunes zero")
    void retentionFollowsTheProperty() {
        AskProperties properties = new AskProperties();
        properties.getLog().setRetentionDays(1);
        AskLogCleanupJob job = new AskLogCleanupJob(repository, mock(DynamicSchedulerService.class), clock,
                properties);

        assertThat(job.prune()).isZero();
        repository.insertRow(NOW.minus(Duration.ofHours(23)), 1L, "ALL", "map", "CACHE_HIT", null, null, 1L);
        repository.insertRow(NOW.minus(Duration.ofHours(25)), 1L, "ALL", "map", "CACHE_HIT", null, null, 1L);

        assertThat(job.prune()).isEqualTo(1);
        assertThat(rows()).hasSize(1);
    }

    @Test
    @DisplayName("the job registers itself with the scheduler under its seeded key")
    void registersWithTheScheduler() {
        DynamicSchedulerService scheduler = mock(DynamicSchedulerService.class);

        new AskLogCleanupJob(repository, scheduler, clock, new AskProperties()).registerJob();

        verify(scheduler).registerJobTarget(eq("ask_log_cleanup"),
                any(Runnable.class));
        assertThat(AskLogCleanupJob.JOB_KEY).isEqualTo("ask_log_cleanup");
    }
}
