package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.TideFetchEntity;
import com.gregochr.goldenhour.repository.TideFetchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code tide_fetch} entity round-trips under {@code ddl-auto: validate} and that the
 * latest-forward-fetch query ignores {@code BACKFILL} rows and failures, whatever their age.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development
 * machine (see CLAUDE.md).
 */
class TideFetchRepositoryIntegrationTest extends IntegrationTestBase {

    private static final List<String> FORWARD = List.of("SEED", "RESEED", "TAIL", "POINT_CHANGE");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TideFetchRepository repository;

    private static TideFetchEntity row(long locationId, String reason, boolean success, LocalDateTime at,
            double lat) {
        return TideFetchEntity.builder().locationId(locationId).fetchedAt(at).reason(reason)
                .success(success).requestLat(lat).requestLng(-1.0).extremesStored(3).build();
    }

    @Test
    @DisplayName("the latest successful forward row wins; a later BACKFILL or failed row never does")
    void latestForwardRowIgnoresBackfillAndFailures() {
        jdbc.update("INSERT INTO locations (name, lat, lon) VALUES ('Tide Fetch Repo Test', 54.0, -1.0)");
        Long locationId = jdbc.queryForObject(
                "SELECT id FROM locations WHERE name = 'Tide Fetch Repo Test'", Long.class);
        LocalDateTime base = LocalDateTime.of(2026, 11, 1, 2, 0);
        repository.save(row(locationId, "TAIL", true, base, 54.1));
        repository.save(row(locationId, "POINT_CHANGE", true, base.plusDays(1), 54.2));
        repository.save(row(locationId, "BACKFILL", true, base.plusDays(2), 54.3));
        repository.save(row(locationId, "TAIL", false, base.plusDays(3), 54.4));

        Optional<TideFetchEntity> latest = repository
                .findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(locationId, FORWARD);

        assertThat(latest).isPresent();
        assertThat(latest.get().getReason()).isEqualTo("POINT_CHANGE");
        assertThat(latest.get().getRequestLat()).isEqualTo(54.2);
        assertThat(latest.get().getExtremesStored()).isEqualTo(3);

        jdbc.update("DELETE FROM locations WHERE id = ?", locationId);
        assertThat(repository.findAll()).noneMatch(r -> locationId.equals(r.getLocationId()));
    }
}
