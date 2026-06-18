package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for the hot-topic detector queries on
 * {@link ForecastEvaluationRepository}: inversion, storm surge and Saharan dust.
 * Validates the JPQL against an H2 schema generated from the JPA entities — the
 * detector unit tests mock the repository, so this is the only coverage that exercises
 * the actual queries.
 */
@DataJpaTest
class ForecastEvaluationHotTopicQueriesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final LocalDateTime RUN = LocalDateTime.of(2026, 6, 16, 6, 0);

    @Autowired
    private ForecastEvaluationRepository repository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private RegionRepository regionRepository;

    private LocationEntity moors;

    @BeforeEach
    void setUp() {
        RegionEntity savedRegion = regionRepository.save(RegionEntity.builder()
                .name("The North York Moors")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());

        moors = locationRepository.save(LocationEntity.builder()
                .name("Sutton Bank")
                .lat(54.23)
                .lon(-1.21)
                .region(savedRegion)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());
    }

    @Test
    @DisplayName("findInversionDaysByPotential returns only STRONG rows in range with the region name")
    void findInversionDaysByPotential_strongInRange() {
        repository.save(row(FROM, TargetType.SUNRISE).inversionPotential("STRONG").build());
        repository.save(row(FROM, TargetType.SUNSET).inversionPotential("MODERATE").build());
        repository.save(row(TO.plusDays(5), TargetType.SUNRISE).inversionPotential("STRONG").build());

        List<Object[]> rows = repository.findInversionDaysByPotential(FROM, TO, "STRONG");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(FROM);
        assertThat(rows.get(0)[1]).isEqualTo("The North York Moors");
    }

    @Test
    @DisplayName("findSurgeDaysByRiskLevel returns only HIGH rows in range")
    void findSurgeDaysByRiskLevel_highInRange() {
        repository.save(row(FROM, TargetType.SUNRISE).surgeRiskLevel("HIGH").build());
        repository.save(row(FROM, TargetType.SUNSET).surgeRiskLevel("MODERATE").build());

        List<Object[]> rows = repository.findSurgeDaysByRiskLevel(FROM, TO, "HIGH");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(FROM);
        assertThat(rows.get(0)[1]).isEqualTo("The North York Moors");
    }

    @Test
    @DisplayName("findDustDays matches the badge proxy: elevated AOD or dust with low PM2.5")
    void findDustDays_matchesBadgeProxy() {
        // Matches: high AOD, low PM2.5
        repository.save(row(FROM, TargetType.SUNSET)
                .aerosolOpticalDepth(new BigDecimal("0.400")).pm25(new BigDecimal("10.00")).build());
        // Excluded: high AOD but PM2.5 too high (smoke/haze)
        repository.save(row(FROM.plusDays(1), TargetType.SUNSET)
                .aerosolOpticalDepth(new BigDecimal("0.400")).pm25(new BigDecimal("40.00")).build());
        // Matches: high surface dust, PM2.5 absent
        repository.save(row(FROM.plusDays(2), TargetType.SUNSET)
                .dust(new BigDecimal("60.00")).build());
        // Excluded: neither AOD nor dust elevated
        repository.save(row(TO, TargetType.SUNSET)
                .aerosolOpticalDepth(new BigDecimal("0.200")).dust(new BigDecimal("10.00")).build());

        List<Object[]> rows = repository.findDustDays(FROM, TO,
                new BigDecimal("0.3"), new BigDecimal("50"), new BigDecimal("35"));

        assertThat(rows).extracting(r -> r[0])
                .containsExactly(FROM, FROM.plusDays(2));
    }

    @Test
    @DisplayName("findSnowFreshDays returns rows at or above the depth threshold with humidity")
    void findSnowFreshDays_depthThresholdAndHumidity() {
        // Matches: depth exactly at 0.02 m, humid (mist)
        repository.save(row(FROM, TargetType.SUNRISE)
                .snowDepthMetres(0.02).humidity(95).build());
        // Excluded: depth just below threshold
        repository.save(row(FROM.plusDays(1), TargetType.SUNRISE)
                .snowDepthMetres(0.019).humidity(80).build());
        // Excluded: no snow depth recorded
        repository.save(row(FROM.plusDays(2), TargetType.SUNRISE).humidity(99).build());

        List<Object[]> rows = repository.findSnowFreshDays(FROM, TO, 0.02);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(FROM);
        assertThat(rows.get(0)[1]).isEqualTo("The North York Moors");
        assertThat(rows.get(0)[2]).isEqualTo(95);
    }

    @Test
    @DisplayName("findSnowTopsDays fires only when freezing level is the margin below summit elevation")
    void findSnowTopsDays_freezingLevelBelowElevation() {
        RegionEntity lakes = regionRepository.save(RegionEntity.builder()
                .name("The Lake District")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());
        LocationEntity catBells = locationRepository.save(LocationEntity.builder()
                .name("Cat Bells").lat(54.56).lon(-3.21).region(lakes)
                .elevationMetres(451)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());

        // Matches: freezing level exactly 100 m below the 451 m summit (== elevation - margin)
        repository.save(fellRow(catBells, FROM, TargetType.SUNRISE).freezingLevelMetres(351.0).build());
        // Excluded: freezing level one metre above the margin
        repository.save(fellRow(catBells, FROM.plusDays(1), TargetType.SUNRISE)
                .freezingLevelMetres(352.0).build());
        // Excluded: location with no elevation (the moors fixture) even with a low freezing level
        repository.save(row(FROM.plusDays(2), TargetType.SUNRISE).freezingLevelMetres(0.0).build());

        List<Object[]> rows = repository.findSnowTopsDays(FROM, TO, 100);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(FROM);
        assertThat(rows.get(0)[1]).isEqualTo("The Lake District");
    }

    @Test
    @DisplayName("queries tolerate a location with no region (LEFT JOIN yields a null region name)")
    void queries_nullRegion_returnsNullName() {
        LocationEntity noRegion = locationRepository.save(LocationEntity.builder()
                .name("Lone Fell").lat(54.5).lon(-2.0)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
        repository.save(ForecastEvaluationEntity.builder()
                .location(noRegion).locationLat(new BigDecimal("54.50"))
                .locationLon(new BigDecimal("-2.00")).targetDate(FROM)
                .targetType(TargetType.SUNRISE).forecastRunAt(RUN).daysAhead(1)
                .evaluationModel(EvaluationModel.SONNET).surgeRiskLevel("HIGH").build());

        List<Object[]> rows = repository.findSurgeDaysByRiskLevel(FROM, TO, "HIGH");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isNull();
    }

    private ForecastEvaluationEntity.ForecastEvaluationEntityBuilder row(
            LocalDate date, TargetType type) {
        return ForecastEvaluationEntity.builder()
                .location(moors)
                .locationLat(new BigDecimal("54.23"))
                .locationLon(new BigDecimal("-1.21"))
                .targetDate(date)
                .targetType(type)
                .forecastRunAt(RUN)
                .daysAhead(1)
                .evaluationModel(EvaluationModel.SONNET);
    }

    private ForecastEvaluationEntity.ForecastEvaluationEntityBuilder fellRow(
            LocationEntity fell, LocalDate date, TargetType type) {
        return ForecastEvaluationEntity.builder()
                .location(fell)
                .locationLat(new BigDecimal("54.56"))
                .locationLon(new BigDecimal("-3.21"))
                .targetDate(date)
                .targetType(type)
                .forecastRunAt(RUN)
                .daysAhead(1)
                .evaluationModel(EvaluationModel.SONNET);
    }
}
