package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>The reproduction of the claim B2a's report made</b>, as a test: Hibernate maps an enum column to
 * a native H2 {@code ENUM(...)} fixed when the table is created, and {@code ddl-auto: update} (the
 * local profile's setting) does not alter it — so a developer's <em>existing</em> local database
 * refuses an {@code ASK} job run until the file is deleted.
 *
 * <p>The database is a file created with the pre-Ask {@code job_run} shape
 * ({@link LocalH2OldSchemaFixtures}); the context is the real Hibernate with {@code update}, and
 * nothing widens the column. Production is unaffected either way: its {@code job_run.run_type} is a
 * plain {@code VARCHAR(20)} with no check constraint (V29, and no later migration touches it), so a
 * new enum value needs no migration. This class documents why the local fix,
 * {@link LocalH2EnumWidener}, exists; its own tests prove the fix.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LocalH2EnumOldSchemaReproductionTest {

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
    @DisplayName("an existing local database accepts a pre-Ask run type but refuses ASK and ASK_READY: "
            + "Hibernate update does not widen a native H2 enum")
    void existingDatabaseRefusesAskRunTypes() {
        jobRuns.saveAndFlush(run(RunType.BRIEFING));

        // H2: Value not permitted for column "('VERY_SHORT_TERM', ... 'BATCH_FAR_TERM')": "ASK"
        assertThatThrownBy(() -> jobRuns.saveAndFlush(run(RunType.ASK)))
                .hasMessageContaining("Value not permitted").hasMessageContaining("\"ASK\"");
        assertThatThrownBy(() -> jobRuns.saveAndFlush(run(RunType.ASK_READY)))
                .hasMessageContaining("Value not permitted").hasMessageContaining("\"ASK_READY\"");
    }
}
