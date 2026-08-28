package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastDtoMapper;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/**
 * Proves {@code GET /api/forecast/history}'s no-location-filter branch issues a single SQL
 * statement regardless of how many locations are enabled — not one per location.
 *
 * <p>{@link ForecastControllerTest} mocks {@code ForecastEvaluationRepository}, so it can only
 * assert the repository method was invoked once; it never touches a database and would pass
 * identically whether that one invocation ran one query or (via a lazily-loaded association)
 * several. This test constructs the real {@link ForecastController} — same pattern as
 * {@link ForecastWindowAnchorTest} — wired to a real repository backed by H2 (as
 * {@code ForecastEvaluationRepositoryTest} does), with Hibernate statistics enabled, so the
 * assertion is against the actual number of statements sent to the database.
 */
@ExtendWith(MockitoExtension.class)
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ForecastHistoryQueryCountTest {

    @Autowired
    private ForecastEvaluationRepository forecastEvaluationRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Mock
    private LocationService locationService;

    @Mock
    private ForecastCommandFactory commandFactory;

    @Mock
    private ForecastCommandExecutor commandExecutor;

    @Mock
    private ScheduledForecastService scheduledForecastService;

    @Mock
    private ForecastDtoMapper dtoMapper;

    @Mock
    private EvaluationViewService evaluationViewService;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private RunProgressTracker progressTracker;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private ForecastController buildController() {
        return new ForecastController(forecastEvaluationRepository, locationService, commandFactory,
                commandExecutor, scheduledForecastService, dtoMapper, evaluationViewService,
                jobRunService, progressTracker, Runnable::run, Clock.systemUTC().withZone(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("history fetch across many locations issues a CONSTANT number of queries, not one per location")
    void historyFetch_acrossManyLocations_issuesConstantQueryCount() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        ForecastController controller = buildController();
        when(dtoMapper.toDtoList(any(), anyBoolean())).thenReturn(List.of());

        List<LocationEntity> fewLocations = seedLocations(3);
        seedEvaluations(fewLocations, today);
        long queriesForFew = runHistoryFetchAndCountQueries(controller, fewLocations, today);

        List<LocationEntity> manyLocations = seedLocations(15);
        seedEvaluations(manyLocations, today);
        // findAllEnabled() must now return the full roster (few + many) for the second call, the
        // way the real LocationService would once the new locations are saved and enabled.
        List<LocationEntity> allLocations = new ArrayList<>(fewLocations);
        allLocations.addAll(manyLocations);
        long queriesForMany = runHistoryFetchAndCountQueries(controller, allLocations, today);

        // The old per-location loop would have issued one query per enabled location — 3 vs 18
        // would have differed. The fixed bulk query issues exactly one statement either way.
        assertThat(queriesForFew).isEqualTo(1L);
        assertThat(queriesForMany).isEqualTo(1L);
    }

    private long runHistoryFetchAndCountQueries(
            ForecastController controller, List<LocationEntity> enabledLocations, LocalDate today) {
        when(locationService.findAllEnabled()).thenReturn(enabledLocations);
        statistics().clear();

        controller.getHistory(today, today.plusDays(7), null, null);

        return statistics().getPrepareStatementCount();
    }

    private List<LocationEntity> seedLocations(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> locationRepository.save(LocationEntity.builder()
                        .name("Location " + UUID.randomUUID())
                        .lat(54.0 + i * 0.01)
                        .lon(-1.5 + i * 0.01)
                        .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                        .build()))
                .toList();
    }

    private void seedEvaluations(List<LocationEntity> locations, LocalDate today) {
        LocalDateTime run = LocalDateTime.of(2026, 2, 18, 6, 0);
        for (LocationEntity location : locations) {
            forecastEvaluationRepository.save(ForecastEvaluationEntity.builder()
                    .locationLat(BigDecimal.valueOf(location.getLat()))
                    .locationLon(BigDecimal.valueOf(location.getLon()))
                    .location(location)
                    .targetDate(today)
                    .targetType(TargetType.SUNSET)
                    .solarEventTime(today.atTime(18, 0))
                    .forecastRunAt(run)
                    .daysAhead(2)
                    .evaluationModel(EvaluationModel.SONNET)
                    .fierySkyPotential(65)
                    .goldenHourPotential(72)
                    .summary("Query-count fixture row.")
                    .build());
        }
    }
}
