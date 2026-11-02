package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalResultEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.MissDirection;
import com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFirstSeen;
import com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFixtureAggregate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, on H2, the JPQL the Sky Eval overview relies on: the per (run, fixture) aggregate, the first-seen query
 * and the newest-first run window.
 */
@DataJpaTest
class SkyRatingEvalResultRepositoryAggregateTest {

    private static final LocalDateTime EARLY = LocalDateTime.of(2026, 10, 1, 3, 0);
    private static final LocalDateTime LATE = LocalDateTime.of(2026, 10, 8, 3, 0);

    @Autowired
    private TestEntityManager em;
    @Autowired
    private SkyRatingEvalResultRepository results;
    @Autowired
    private SkyRatingEvalRunRepository runs;

    private SkyRatingEvalRunEntity run(LocalDateTime at) {
        return em.persist(SkyRatingEvalRunEntity.builder().runTimestamp(at).startedAt(at)
                .model(EvaluationModel.HAIKU).runsPerFixture(4).triggerSource(SkyRatingEvalTrigger.SCHEDULED)
                .status(SkyRatingEvalStatus.COMPLETED).build());
    }

    private void result(SkyRatingEvalRunEntity run, String fixture, int index, Integer rating, MissDirection dir) {
        em.persist(SkyRatingEvalResultEntity.builder().runId(run.getId()).fixtureName(fixture).runIndex(index)
                .rating(rating).expectedMin(3).expectedMax(4).missDirection(dir).build());
    }

    @Test
    @DisplayName("aggregateByRunAndFixture counts attempts, directions and rating statistics per run and fixture")
    void aggregatesPerRunAndFixture() {
        SkyRatingEvalRunEntity a = run(EARLY);
        SkyRatingEvalRunEntity b = run(LATE);
        SkyRatingEvalRunEntity excluded = run(LATE.plusDays(1));
        result(a, "fx-one", 1, 3, MissDirection.IN_BAND);
        result(a, "fx-one", 2, 4, MissDirection.IN_BAND);
        result(a, "fx-one", 3, 1, MissDirection.BELOW);
        result(a, "fx-one", 4, 5, MissDirection.ABOVE);
        result(a, "fx-two", 1, null, null);
        result(a, "fx-two", 2, 2, MissDirection.BELOW);
        result(b, "fx-one", 1, 4, MissDirection.IN_BAND);
        result(b, "fx-two", 1, null, null);
        result(excluded, "fx-one", 1, 3, MissDirection.IN_BAND);
        em.flush();
        em.clear();

        List<SkyRatingEvalFixtureAggregate> out = results.aggregateByRunAndFixture(List.of(a.getId(), b.getId()));

        assertThat(out).hasSize(4);
        assertThat(out).extracting(SkyRatingEvalFixtureAggregate::runId).doesNotContain(excluded.getId());
        assertThat(find(out, a, "fx-one")).isEqualTo(new SkyRatingEvalFixtureAggregate(
                a.getId(), "fx-one", 4L, 2L, 1L, 1L, 4L, 13L, 1, 5));
        assertThat(find(out, a, "fx-two")).isEqualTo(new SkyRatingEvalFixtureAggregate(
                a.getId(), "fx-two", 2L, 0L, 1L, 0L, 1L, 2L, 2, 2));
        assertThat(find(out, b, "fx-one")).isEqualTo(new SkyRatingEvalFixtureAggregate(
                b.getId(), "fx-one", 1L, 1L, 0L, 0L, 1L, 4L, 4, 4));
        SkyRatingEvalFixtureAggregate allFailed = find(out, b, "fx-two");
        assertThat(allFailed.attempts()).isEqualTo(1L);
        assertThat(allFailed.inBand()).isZero();
        assertThat(allFailed.ratedCount()).isZero();
        assertThat(allFailed.ratingSum()).isNull();
        assertThat(allFailed.minRating()).isNull();
        assertThat(allFailed.maxRating()).isNull();
    }

    @Test
    @DisplayName("firstSeenByFixture returns the earliest run timestamp that scored each fixture")
    void firstSeenIsTheEarliestRun() {
        SkyRatingEvalRunEntity early = run(EARLY);
        SkyRatingEvalRunEntity late = run(LATE);
        result(late, "fx-one", 1, 3, MissDirection.IN_BAND);
        result(early, "fx-one", 1, 3, MissDirection.IN_BAND);
        result(late, "fx-two", 1, 3, MissDirection.IN_BAND);
        em.flush();
        em.clear();

        List<SkyRatingEvalFirstSeen> out = results.firstSeenByFixture();

        assertThat(out).containsExactlyInAnyOrder(
                new SkyRatingEvalFirstSeen("fx-one", EARLY), new SkyRatingEvalFirstSeen("fx-two", LATE));
    }

    @Test
    @DisplayName("findTop500ByOrderByRunTimestampDesc returns the newest run first")
    void runWindowIsNewestFirst() {
        run(LATE);
        run(EARLY);
        run(LATE.plusDays(3));
        em.flush();
        em.clear();

        List<SkyRatingEvalRunEntity> out = runs.findTop500ByOrderByRunTimestampDesc();

        assertThat(out).extracting(SkyRatingEvalRunEntity::getRunTimestamp)
                .containsExactly(LATE.plusDays(3), LATE, EARLY);
    }

    private static SkyRatingEvalFixtureAggregate find(List<SkyRatingEvalFixtureAggregate> all,
            SkyRatingEvalRunEntity run, String fixture) {
        return all.stream().filter(x -> x.runId().equals(run.getId()) && x.fixtureName().equals(fixture))
                .findFirst().orElseThrow();
    }
}
