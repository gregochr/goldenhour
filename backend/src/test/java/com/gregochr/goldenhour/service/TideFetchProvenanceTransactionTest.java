package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.WorldTidesResponse;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Real-transaction proof (H2, schema from the entities) that WorldTides provenance writes are
 * isolated from the tide fetch's own transaction: a provenance INSERT that fails does not roll the
 * stored extremes back, and a success row exists only if the extremes committed.
 *
 * <p>Runs outside the {@code @DataJpaTest} wrapper transaction ({@code NOT_SUPPORTED}) so that
 * {@code TideService.fetchAndStoreTideExtremes} opens, commits or rolls back a real transaction of
 * its own. A real INSERT failure is provoked with a CHECK constraint on {@code tide_fetch.atlas}.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TideService.class, TideFetchRecorder.class, TideFetchProvenanceTransactionTest.Config.class})
class TideFetchProvenanceTransactionTest {

    private static final long LOCATION_ID = 5L;

    /** Collaborators the tide service needs that are not part of the persistence slice. */
    @TestConfiguration
    static class Config {

        @Bean
        RestClient restClient() {
            return mock(RestClient.class);
        }

        @Bean
        JobRunService jobRunService() {
            return mock(JobRunService.class);
        }

        @Bean
        WorldTidesProperties worldTidesProperties() {
            WorldTidesProperties properties = new WorldTidesProperties();
            properties.setApiKey("test-key");
            return properties;
        }
    }

    @Autowired
    private TideService tideService;

    @Autowired
    private RestClient restClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("ALTER TABLE tide_fetch DROP CONSTRAINT IF EXISTS chk_no_boom_atlas");
        jdbc.update("DELETE FROM tide_extreme");
        jdbc.update("DELETE FROM tide_fetch");
    }

    private static LocationEntity location() {
        return LocationEntity.builder().id(LOCATION_ID).name("Transaction Bay").lat(54.5).lon(-0.8).build();
    }

    private static WorldTidesResponse response(int status, String atlas) {
        WorldTidesResponse response = new WorldTidesResponse();
        response.setStatus(status);
        response.setCallCount(2);
        response.setAtlas(atlas);
        WorldTidesResponse.Extreme high = new WorldTidesResponse.Extreme();
        high.setDt(1_790_000_000L);
        high.setHeight(1.5);
        high.setType("High");
        WorldTidesResponse.Extreme low = new WorldTidesResponse.Extreme();
        low.setDt(1_790_022_000L);
        low.setHeight(0.2);
        low.setType("Low");
        response.setExtremes(List.of(high, low));
        return response;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    @DisplayName("a committed fetch leaves the extremes and exactly one success row")
    void committedFetchWritesTheSuccessRow() {
        RestClientMocks.stubGet(restClient, WorldTidesResponse.class, response(200, "FES2022"));

        tideService.fetchAndStoreTideExtremes(location());

        assertThat(count("tide_extreme")).isEqualTo(2);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT reason, success, atlas, extremes_stored "
                + "FROM tide_fetch");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("REASON")).isEqualTo("SEED");
            assertThat(row.get("SUCCESS")).isEqualTo(true);
            assertThat(row.get("ATLAS")).isEqualTo("FES2022");
            assertThat(row.get("EXTREMES_STORED")).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a provenance INSERT that fails does not roll back or fail the stored extremes")
    void failingProvenanceWriteDoesNotRollBackTheExtremes() {
        jdbc.execute("ALTER TABLE tide_fetch ADD CONSTRAINT chk_no_boom_atlas"
                + " CHECK (atlas IS NULL OR atlas <> 'BOOM')");
        RestClientMocks.stubGet(restClient, WorldTidesResponse.class, response(200, "BOOM"));

        tideService.fetchAndStoreTideExtremes(location());

        assertThat(count("tide_extreme")).as("the extremes committed").isEqualTo(2);
        assertThat(count("tide_fetch")).as("the failed provenance write left nothing").isZero();
    }

    @Test
    @DisplayName("when the outer transaction rolls back there is no success row and no extremes")
    void rolledBackFetchLeavesNoSuccessRow() {
        RestClientMocks.stubGet(restClient, WorldTidesResponse.class, response(200, "FES2022"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            tideService.fetchAndStoreTideExtremes(location());
            status.setRollbackOnly();
        });

        assertThat(count("tide_extreme")).as("the extremes rolled back").isZero();
        assertThat(count("tide_fetch")).as("no success row for data that never committed").isZero();
    }

    @Test
    @DisplayName("a failed fetch's failure row is written even when the outer transaction rolls back")
    void failureRowSurvivesARollback() {
        RestClientMocks.stubGet(restClient, WorldTidesResponse.class, response(500, "FES2022"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            tideService.fetchAndStoreTideExtremes(location());
            status.setRollbackOnly();
        });

        assertThat(count("tide_extreme")).isZero();
        assertThat(jdbc.queryForList("SELECT success, extremes_stored FROM tide_fetch")).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("SUCCESS")).isEqualTo(false);
                    assertThat(row.get("EXTREMES_STORED")).isEqualTo(0);
                });
    }
}
