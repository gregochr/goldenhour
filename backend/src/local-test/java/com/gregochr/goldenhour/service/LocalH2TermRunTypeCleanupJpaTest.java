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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cleanup, end to end: an existing local database file that still holds term-type
 * {@code job_run} rows, the real Hibernate with the local profile's {@code update}, and both local
 * beans wired. Nothing is called by hand, and every remaining row then loads through JPA, which is
 * what throws on a removed enum constant.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("local")
@Import({LocalH2EnumWidener.class, LocalH2TermRunTypeCleanup.class})
class LocalH2TermRunTypeCleanupJpaTest {

    @DynamicPropertySource
    static void oldSchemaDatabase(DynamicPropertyRegistry registry) throws IOException, SQLException {
        String url = LocalH2OldSchemaFixtures.oldSchemaFileDatabase();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
                Statement s = c.createStatement()) {
            // Hibernate's update cannot add a NOT NULL column to a table that already has rows, so
            // the file carries the two it needs, as a real developer file would.
            s.execute("alter table job_run add column started_at timestamp not null default current_timestamp");
            s.execute("alter table job_run add column triggered_manually boolean not null default false");
            s.execute("insert into job_run (run_type) values "
                    + "('VERY_SHORT_TERM'), ('SHORT_TERM'), ('LONG_TERM'), ('SCHEDULED_BATCH')");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private JobRunRepository jobRuns;

    @Test
    @DisplayName("with the cleanup, an existing database holding term-type runs loads every remaining row "
            + "through JPA")
    void remainingRowsLoad() {
        List<JobRunEntity> all = jobRuns.findAll();

        assertThat(all).extracting(JobRunEntity::getRunType).containsExactly(RunType.SCHEDULED_BATCH);
    }
}
