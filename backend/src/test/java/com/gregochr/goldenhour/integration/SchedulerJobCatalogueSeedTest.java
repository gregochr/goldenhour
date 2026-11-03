package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.service.SchedulerJobCatalogue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, against the schema Flyway really builds, that {@link SchedulerJobCatalogue} describes exactly the jobs the
 * migrations seed: every {@code scheduler_job_config} row has a catalogue entry and no entry is orphaned (an
 * entry for a job a migration retired would otherwise rot unseen).
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md). A catalogue-only agreement test that runs locally is {@code SchedulerJobCatalogueAgreementTest}.
 */
class SchedulerJobCatalogueSeedTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every seeded scheduler job has a catalogue entry")
    void everySeededJobIsCatalogued() {
        List<String> seeded = jdbc.queryForList("SELECT job_key FROM scheduler_job_config", String.class);

        assertThat(seeded).isNotEmpty();
        assertThat(SchedulerJobCatalogue.keys()).containsAll(seeded);
    }

    @Test
    @DisplayName("no catalogue entry describes a job the migrations do not seed")
    void noCatalogueEntryIsOrphaned() {
        List<String> seeded = jdbc.queryForList("SELECT job_key FROM scheduler_job_config", String.class);

        assertThat(seeded).containsAll(SchedulerJobCatalogue.keys());
    }
}
