package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ForecastDtoMapper}.
 */
class ForecastDtoMapperTest {

    private final ForecastDtoMapper mapper = new ForecastDtoMapper();

    private static final LocationEntity LOCATION = LocationEntity.builder()
            .id(1L).name("Durham UK").lat(54.7753).lon(-1.5849).build();

    @Test
    @DisplayName("toDto() maps all entity fields to DTO for PRO user")
    void toDto_proUser_mapsAllFields() {
        ForecastEvaluationEntity entity = buildFullEntity();

        ForecastEvaluationDto dto = mapper.toDto(entity, false);

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.locationName()).isEqualTo("Durham UK");
        assertThat(dto.locationLat()).isEqualByComparingTo("54.775300");
        assertThat(dto.locationLon()).isEqualByComparingTo("-1.584900");
        assertThat(dto.targetDate()).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(dto.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(dto.daysAhead()).isEqualTo(0);
        assertThat(dto.evaluationModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(dto.rating()).isNull();
        assertThat(dto.fierySkyPotential()).isEqualTo(82);
        assertThat(dto.goldenHourPotential()).isEqualTo(76);
        assertThat(dto.summary()).isEqualTo("Enhanced summary.");
        assertThat(dto.lowCloud()).isEqualTo(15);
        assertThat(dto.windSpeed()).isEqualByComparingTo("4.20");
        assertThat(dto.pm25()).isEqualByComparingTo("8.50");
        assertThat(dto.aerosolOpticalDepth()).isEqualByComparingTo("0.120");
        assertThat(dto.temperatureCelsius()).isEqualTo(12.5);
        assertThat(dto.tideState()).isEqualTo(TideState.HIGH);
        assertThat(dto.tideAligned()).isTrue();
        assertThat(dto.solarLowCloud()).isEqualTo(5);
        assertThat(dto.antisolarHighCloud()).isEqualTo(90);
    }

    @Test
    @DisplayName("toDto() returns enhanced scores for PRO user even when basic scores exist")
    void toDto_proUser_usesEnhancedScores() {
        ForecastEvaluationEntity entity = buildFullEntity();

        ForecastEvaluationDto dto = mapper.toDto(entity, false);

        assertThat(dto.fierySkyPotential()).isEqualTo(82);
        assertThat(dto.goldenHourPotential()).isEqualTo(76);
        assertThat(dto.summary()).isEqualTo("Enhanced summary.");
    }

    @Test
    @DisplayName("toDto() returns basic scores for LITE user when basic scores exist")
    void toDto_liteUser_usesBasicScores() {
        ForecastEvaluationEntity entity = buildFullEntity();

        ForecastEvaluationDto dto = mapper.toDto(entity, true);

        assertThat(dto.fierySkyPotential()).isEqualTo(65);
        assertThat(dto.goldenHourPotential()).isEqualTo(60);
        assertThat(dto.summary()).isEqualTo("Basic summary.");
    }

    @Test
    @DisplayName("toDto() falls back to enhanced scores for LITE user when basic scores are null")
    void toDto_liteUser_fallsBackToEnhancedWhenBasicNull() {
        ForecastEvaluationEntity entity = buildFullEntity();
        entity.setBasicFierySkyPotential(null);
        entity.setBasicGoldenHourPotential(null);
        entity.setBasicSummary(null);

        ForecastEvaluationDto dto = mapper.toDto(entity, true);

        assertThat(dto.fierySkyPotential()).isEqualTo(82);
        assertThat(dto.goldenHourPotential()).isEqualTo(76);
        assertThat(dto.summary()).isEqualTo("Enhanced summary.");
    }

    @Test
    @DisplayName("toDto() handles entity with null location gracefully")
    void toDto_nullLocation_returnsNullLocationName() {
        ForecastEvaluationEntity entity = ForecastEvaluationEntity.builder()
                .id(2L)
                .locationLat(BigDecimal.valueOf(54.77))
                .locationLon(BigDecimal.valueOf(-1.58))
                .targetDate(LocalDate.of(2026, 3, 8))
                .targetType(TargetType.SUNRISE)
                .forecastRunAt(LocalDateTime.of(2026, 3, 8, 6, 0))
                .daysAhead(0)
                .build();

        ForecastEvaluationDto dto = mapper.toDto(entity, false);

        assertThat(dto.locationName()).isNull();
    }

    @Test
    @DisplayName("toDtoList() maps multiple entities preserving order")
    void toDtoList_mapsMultipleEntities() {
        ForecastEvaluationEntity e1 = buildFullEntity();
        ForecastEvaluationEntity e2 = buildFullEntity();
        e2.setId(2L);
        e2.setTargetType(TargetType.SUNRISE);

        List<ForecastEvaluationDto> dtos = mapper.toDtoList(List.of(e1, e2), false);

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(dtos.get(1).targetType()).isEqualTo(TargetType.SUNRISE);
    }

    @Test
    @DisplayName("toDtoList() returns empty list for empty input")
    void toDtoList_emptyInput_returnsEmptyList() {
        List<ForecastEvaluationDto> dtos = mapper.toDtoList(List.of(), false);

        assertThat(dtos).isEmpty();
    }

    @Test
    @DisplayName("toDto() does not expose basic_* fields in the DTO")
    void toDto_basicFieldsNotInDto() {
        // The DTO record has no basic_* fields — this is enforced by compilation.
        // This test verifies the mapping produces a valid DTO without basic_* leaking through.
        ForecastEvaluationEntity entity = buildFullEntity();

        ForecastEvaluationDto dto = mapper.toDto(entity, false);

        // The DTO should have exactly the enhanced values, not basic ones
        assertThat(dto.fierySkyPotential()).isEqualTo(82);
        assertThat(dto.goldenHourPotential()).isEqualTo(76);
        assertThat(dto.summary()).isEqualTo("Enhanced summary.");
    }

    private ForecastEvaluationEntity buildFullEntity() {
        return ForecastEvaluationEntity.builder()
                .id(1L)
                .location(LOCATION)
                .locationLat(BigDecimal.valueOf(54.7753))
                .locationLon(BigDecimal.valueOf(-1.5849))
                .targetDate(LocalDate.of(2026, 3, 8))
                .targetType(TargetType.SUNSET)
                .forecastRunAt(LocalDateTime.of(2026, 3, 8, 12, 0))
                .daysAhead(0)
                .evaluationModel(EvaluationModel.SONNET)
                .fierySkyPotential(82)
                .goldenHourPotential(76)
                .summary("Enhanced summary.")
                .basicFierySkyPotential(65)
                .basicGoldenHourPotential(60)
                .basicSummary("Basic summary.")
                .solarEventTime(LocalDateTime.of(2026, 3, 8, 17, 45))
                .azimuthDeg(255)
                .lowCloud(15)
                .midCloud(40)
                .highCloud(80)
                .visibility(22000)
                .windSpeed(new BigDecimal("4.20"))
                .windDirection(210)
                .precipitation(BigDecimal.ZERO)
                .humidity(65)
                .weatherCode(2)
                .boundaryLayerHeight(800)
                .shortwaveRadiation(new BigDecimal("180.00"))
                .pm25(new BigDecimal("8.50"))
                .dust(new BigDecimal("3.20"))
                .aerosolOpticalDepth(new BigDecimal("0.120"))
                .temperatureCelsius(12.5)
                .apparentTemperatureCelsius(9.8)
                .precipitationProbabilityPercent(10)
                .tideState(TideState.HIGH)
                .nextHighTideTime(LocalDateTime.of(2026, 3, 8, 18, 30))
                .nextHighTideHeightMetres(new BigDecimal("4.50"))
                .nextLowTideTime(LocalDateTime.of(2026, 3, 8, 12, 15))
                .nextLowTideHeightMetres(new BigDecimal("1.20"))
                .tideAligned(true)
                .solarLowCloud(5)
                .solarMidCloud(10)
                .solarHighCloud(70)
                .antisolarLowCloud(30)
                .antisolarMidCloud(50)
                .antisolarHighCloud(90)
                .build();
    }
}
