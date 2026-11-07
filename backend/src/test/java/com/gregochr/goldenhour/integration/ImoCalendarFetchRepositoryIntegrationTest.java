package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.ImoCalendarFetchEntity;
import com.gregochr.goldenhour.entity.ImoCalendarFetchOutcome;
import com.gregochr.goldenhour.repository.ImoCalendarFetchRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the derived queries the IMO calendar refresh's alert rules depend on, against the Flyway-built schema:
 * the newest-first window, the "latest INVALID/STORED/UNCHANGED" lookup that skips FAILED and NOT_PUBLISHED rows,
 * and the not-published-since check.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine.
 */
class ImoCalendarFetchRepositoryIntegrationTest extends IntegrationTestBase {

    private static final int YEAR = 2031;
    private static final Instant T0 = Instant.parse("2031-01-01T00:00:00Z");

    @Autowired
    private ImoCalendarFetchRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private void log(int minutes, ImoCalendarFetchOutcome outcome, String sha) {
        ImoCalendarFetchEntity row = new ImoCalendarFetchEntity();
        row.setCalendarYear(YEAR);
        row.setAttemptedAt(T0.plusSeconds(minutes * 60L));
        row.setOutcome(outcome);
        row.setSha256(sha);
        repository.save(row);
    }

    @Test
    @DisplayName("the newest-first window returns the four latest rows of the year")
    void newestFirst() {
        log(1, ImoCalendarFetchOutcome.STORED, "a");
        log(2, ImoCalendarFetchOutcome.FAILED, null);
        log(3, ImoCalendarFetchOutcome.FAILED, null);
        log(4, ImoCalendarFetchOutcome.FAILED, null);
        log(5, ImoCalendarFetchOutcome.FAILED, null);

        List<ImoCalendarFetchEntity> latest = repository.findTop4ByCalendarYearOrderByAttemptedAtDescIdDesc(YEAR);

        assertThat(latest).hasSize(4);
        assertThat(latest).extracting(ImoCalendarFetchEntity::getOutcome).containsOnly(ImoCalendarFetchOutcome.FAILED);
        assertThat(latest.get(0).getAttemptedAt()).isEqualTo(T0.plusSeconds(300));
    }

    @Test
    @DisplayName("the latest parsed-outcome lookup skips FAILED and NOT_PUBLISHED rows")
    void latestParsedSkipsFailures() {
        log(1, ImoCalendarFetchOutcome.INVALID, "same");
        log(2, ImoCalendarFetchOutcome.FAILED, null);
        log(3, ImoCalendarFetchOutcome.NOT_PUBLISHED, null);

        ImoCalendarFetchEntity prev = repository.findFirstByCalendarYearAndOutcomeInOrderByAttemptedAtDescIdDesc(
                YEAR, EnumSet.of(ImoCalendarFetchOutcome.INVALID, ImoCalendarFetchOutcome.STORED,
                        ImoCalendarFetchOutcome.UNCHANGED)).orElseThrow();

        assertThat(prev.getOutcome()).isEqualTo(ImoCalendarFetchOutcome.INVALID);
        assertThat(prev.getSha256()).isEqualTo("same");
    }

    @Test
    @DisplayName("the not-published-since check sees a row at the boundary and not an earlier one")
    void notPublishedSince() {
        log(10, ImoCalendarFetchOutcome.NOT_PUBLISHED, null);

        assertThat(repository.existsByCalendarYearAndOutcomeAndAttemptedAtGreaterThanEqual(
                YEAR, ImoCalendarFetchOutcome.NOT_PUBLISHED, T0.plusSeconds(600))).isTrue();
        assertThat(repository.existsByCalendarYearAndOutcomeAndAttemptedAtGreaterThanEqual(
                YEAR, ImoCalendarFetchOutcome.NOT_PUBLISHED, T0.plusSeconds(601))).isFalse();
    }
}
