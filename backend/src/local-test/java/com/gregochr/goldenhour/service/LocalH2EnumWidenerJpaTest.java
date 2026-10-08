package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fix, end to end: the same existing-database file as
 * {@link LocalH2EnumOldSchemaReproductionTest}, the real Hibernate with the local profile's
 * {@code update}, and the widener wired as a bean. Nothing is called by hand — the widener runs as
 * the context starts — and an {@code ASK} and an {@code ASK_READY} run are then written through the
 * repository.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("local")
@Import(LocalH2EnumWidener.class)
class LocalH2EnumWidenerJpaTest {

    @DynamicPropertySource
    static void oldSchemaDatabase(DynamicPropertyRegistry registry) throws IOException, SQLException {
        String url = LocalH2OldSchemaFixtures.oldSchemaFileDatabase();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private JobRunRepository jobRuns;

    private static JobRunEntity run(RunType type) {
        return JobRunEntity.builder().runType(type).startedAt(LocalDateTime.of(2026, 10, 5, 12, 0))
                .triggeredManually(false).build();
    }

    @Test
    @DisplayName("with the widener, an existing local database accepts ASK and ASK_READY runs, and every "
            + "earlier run type still")
    void existingDatabaseAcceptsAskRuns() {
        JobRunEntity ask = jobRuns.saveAndFlush(run(RunType.ASK));
        JobRunEntity ready = jobRuns.saveAndFlush(run(RunType.ASK_READY));
        JobRunEntity briefing = jobRuns.saveAndFlush(run(RunType.BRIEFING));

        assertThat(jobRuns.findById(ask.getId())).get().extracting(JobRunEntity::getRunType)
                .isEqualTo(RunType.ASK);
        assertThat(jobRuns.findById(ready.getId())).get().extracting(JobRunEntity::getRunType)
                .isEqualTo(RunType.ASK_READY);
        assertThat(briefing.getId()).isNotNull();
    }
}
