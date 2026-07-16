package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend.SolarCloudSlot;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the embedded value objects' hand-written factories and null-safe views.
 * The Lombok-generated accessors are exercised transitively; these tests pin the
 * from()/orEmpty() contracts the entity write and read paths depend on.
 */
class ForecastEvaluationDetailsTest {

    @Nested
    @DisplayName("TideDetails")
    class TideDetailsFactory {

        @Test
        @DisplayName("from(null) returns null — inland locations persist no tide columns")
        void fromNull_returnsNull() {
            assertThat(TideDetails.from(null)).isNull();
        }

        @Test
        @DisplayName("from() maps every tide field")
        void from_mapsAllFields() {
            LocalDateTime high = LocalDateTime.of(2026, 7, 16, 18, 30);
            LocalDateTime low = LocalDateTime.of(2026, 7, 16, 12, 15);
            TideSnapshot snapshot = new TideSnapshot(TideState.HIGH,
                    high, new BigDecimal("4.50"), low, new BigDecimal("1.20"), true,
                    null, null, null, null, null, null);

            TideDetails details = TideDetails.from(snapshot);

            assertThat(details.getState()).isEqualTo(TideState.HIGH);
            assertThat(details.getNextHighTime()).isEqualTo(high);
            assertThat(details.getNextHighHeightMetres()).isEqualByComparingTo("4.50");
            assertThat(details.getNextLowTime()).isEqualTo(low);
            assertThat(details.getNextLowHeightMetres()).isEqualByComparingTo("1.20");
            assertThat(details.getAligned()).isTrue();
        }

        @Test
        @DisplayName("orEmpty(null) yields all-null fields; orEmpty(x) returns x")
        void orEmpty_contract() {
            assertThat(TideDetails.orEmpty(null).getState()).isNull();
            TideDetails real = TideDetails.builder().aligned(false).build();
            assertThat(TideDetails.orEmpty(real)).isSameAs(real);
        }
    }

    @Nested
    @DisplayName("DirectionalCloudDetails")
    class DirectionalCloudFactory {

        @Test
        @DisplayName("from(null) returns null")
        void fromNull_returnsNull() {
            assertThat(DirectionalCloudDetails.from(null)).isNull();
        }

        @Test
        @DisplayName("from() maps all seven samples")
        void from_mapsAllFields() {
            DirectionalCloudData dc = new DirectionalCloudData(35, 45, 20, 40, 50, 15, 55);

            DirectionalCloudDetails details = DirectionalCloudDetails.from(dc);

            assertThat(details.getSolarLow()).isEqualTo(35);
            assertThat(details.getSolarMid()).isEqualTo(45);
            assertThat(details.getSolarHigh()).isEqualTo(20);
            assertThat(details.getAntisolarLow()).isEqualTo(40);
            assertThat(details.getAntisolarMid()).isEqualTo(50);
            assertThat(details.getAntisolarHigh()).isEqualTo(15);
            assertThat(details.getFarSolarLow()).isEqualTo(55);
        }

        @Test
        @DisplayName("orEmpty(null) yields all-null fields; orEmpty(x) returns x")
        void orEmpty_contract() {
            assertThat(DirectionalCloudDetails.orEmpty(null).getSolarLow()).isNull();
            DirectionalCloudDetails real = DirectionalCloudDetails.builder().solarLow(5).build();
            assertThat(DirectionalCloudDetails.orEmpty(real)).isSameAs(real);
        }
    }

    @Nested
    @DisplayName("CloudApproachDetails")
    class CloudApproachFactory {

        @Test
        @DisplayName("from(null) returns null")
        void fromNull_returnsNull() {
            assertThat(CloudApproachDetails.from(null)).isNull();
        }

        @Test
        @DisplayName("from() flattens earliest/event trend slots and the upwind sample")
        void from_mapsTrendAndUpwind() {
            SolarCloudTrend trend = new SolarCloudTrend(List.of(
                    new SolarCloudSlot(3, 10),
                    new SolarCloudSlot(2, 25),
                    new SolarCloudSlot(0, 40)));
            UpwindCloudSample upwind = new UpwindCloudSample(80, 225, 65, 35);

            CloudApproachDetails details =
                    CloudApproachDetails.from(new CloudApproachData(trend, upwind));

            assertThat(details.getSolarTrendEarliestLowCloud()).isEqualTo(10);
            assertThat(details.getSolarTrendEventLowCloud()).isEqualTo(40);
            assertThat(details.getSolarTrendBuilding()).isTrue();
            assertThat(details.getUpwindCurrentLowCloud()).isEqualTo(65);
            assertThat(details.getUpwindEventLowCloud()).isEqualTo(35);
            assertThat(details.getUpwindDistanceKm()).isEqualTo(80);
        }

        @Test
        @DisplayName("empty trend slots leave the slot-derived fields null but keep isBuilding")
        void from_emptyTrendSlots() {
            SolarCloudTrend trend = new SolarCloudTrend(List.of());

            CloudApproachDetails details =
                    CloudApproachDetails.from(new CloudApproachData(trend, null));

            assertThat(details.getSolarTrendEarliestLowCloud()).isNull();
            assertThat(details.getSolarTrendEventLowCloud()).isNull();
            assertThat(details.getSolarTrendBuilding()).isFalse();
            assertThat(details.getUpwindCurrentLowCloud()).isNull();
        }

        @Test
        @DisplayName("null trend with an upwind sample keeps the upwind fields")
        void from_nullTrendWithUpwind() {
            UpwindCloudSample upwind = new UpwindCloudSample(60, 180, 45, 20);

            CloudApproachDetails details =
                    CloudApproachDetails.from(new CloudApproachData(null, upwind));

            assertThat(details.getSolarTrendBuilding()).isNull();
            assertThat(details.getUpwindCurrentLowCloud()).isEqualTo(45);
            assertThat(details.getUpwindDistanceKm()).isEqualTo(60);
        }

        @Test
        @DisplayName("orEmpty(null) yields all-null fields; orEmpty(x) returns x")
        void orEmpty_contract() {
            assertThat(CloudApproachDetails.orEmpty(null).getSolarTrendBuilding()).isNull();
            CloudApproachDetails real = CloudApproachDetails.builder().upwindDistanceKm(1).build();
            assertThat(CloudApproachDetails.orEmpty(real)).isSameAs(real);
        }
    }

    @Nested
    @DisplayName("StormSurgeDetails")
    class StormSurgeFactory {

        @Test
        @DisplayName("from(null, null, null) returns null — nothing to persist")
        void allNull_returnsNull() {
            assertThat(StormSurgeDetails.from(null, null, null)).isNull();
        }

        @Test
        @DisplayName("from() maps breakdown components and both range figures")
        void from_mapsAllFields() {
            StormSurgeBreakdown surge = new StormSurgeBreakdown(
                    0.08, 0.05, 0.13, 1000.0, 8.0, 270.0, 0.9, TideRiskLevel.LOW, "Low surge");

            StormSurgeDetails details = StormSurgeDetails.from(surge, 3.5, 3.3);

            assertThat(details.getTotalMetres()).isEqualTo(0.13);
            assertThat(details.getPressureMetres()).isEqualTo(0.08);
            assertThat(details.getWindMetres()).isEqualTo(0.05);
            assertThat(details.getRiskLevel()).isEqualTo("LOW");
            assertThat(details.getAdjustedRangeMetres()).isEqualTo(3.5);
            assertThat(details.getAstronomicalRangeMetres()).isEqualTo(3.3);
        }

        @Test
        @DisplayName("range figures without a breakdown still persist — surge components stay null")
        void rangesWithoutBreakdown() {
            StormSurgeDetails details = StormSurgeDetails.from(null, 3.5, 3.3);

            assertThat(details).isNotNull();
            assertThat(details.getTotalMetres()).isNull();
            assertThat(details.getRiskLevel()).isNull();
            assertThat(details.getAdjustedRangeMetres()).isEqualTo(3.5);
            assertThat(details.getAstronomicalRangeMetres()).isEqualTo(3.3);
        }

        @Test
        @DisplayName("orEmpty(null) yields all-null fields; orEmpty(x) returns x")
        void orEmpty_contract() {
            assertThat(StormSurgeDetails.orEmpty(null).getTotalMetres()).isNull();
            StormSurgeDetails real = StormSurgeDetails.builder().totalMetres(0.1).build();
            assertThat(StormSurgeDetails.orEmpty(real)).isSameAs(real);
        }
    }

    @Nested
    @DisplayName("InversionDetails")
    class InversionFactory {

        @Test
        @DisplayName("ineligible location returns null even when Claude returned a score")
        void ineligible_returnsNull() {
            assertThat(InversionDetails.from(false, 8, "STRONG")).isNull();
        }

        @Test
        @DisplayName("eligible location carries Claude's score and classification")
        void eligible_mapsFields() {
            InversionDetails details = InversionDetails.from(true, 7, "STRONG");

            assertThat(details.getScore()).isEqualTo(7);
            assertThat(details.getPotential()).isEqualTo("STRONG");
        }

        @Test
        @DisplayName("orEmpty(null) yields all-null fields; orEmpty(x) returns x")
        void orEmpty_contract() {
            assertThat(InversionDetails.orEmpty(null).getScore()).isNull();
            InversionDetails real = InversionDetails.builder().score(3).build();
            assertThat(InversionDetails.orEmpty(real)).isSameAs(real);
        }
    }

    @Nested
    @DisplayName("TriageDetails")
    class TriageFactory {

        @Test
        @DisplayName("orEmpty(null) yields all-null fields; orEmpty(x) returns x")
        void orEmpty_contract() {
            assertThat(TriageDetails.orEmpty(null).getReason()).isNull();
            assertThat(TriageDetails.orEmpty(null).getMessage()).isNull();
            TriageDetails real = TriageDetails.builder().message("Rain 80%").build();
            assertThat(TriageDetails.orEmpty(real)).isSameAs(real);
        }
    }
}
