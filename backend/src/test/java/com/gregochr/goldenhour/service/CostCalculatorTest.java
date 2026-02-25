package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ServiceName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CostCalculator}.
 */
class CostCalculatorTest {

    @Test
    @DisplayName("calculateCost() returns Sonnet cost when model is SONNET")
    void calculateCost_returnsSonnetCost_whenModelIsSonnet() {
        CostProperties props = new CostProperties();
        props.setAnthropicSonnetPence(130);
        CostCalculator calculator = new CostCalculator(props);

        int cost = calculator.calculateCost(ServiceName.ANTHROPIC, EvaluationModel.SONNET);

        assertThat(cost).isEqualTo(130);
    }

    @Test
    @DisplayName("calculateCost() returns Haiku cost when model is HAIKU")
    void calculateCost_returnsHaikuCost_whenModelIsHaiku() {
        CostProperties props = new CostProperties();
        props.setAnthropicHaikuPence(50);
        CostCalculator calculator = new CostCalculator(props);

        int cost = calculator.calculateCost(ServiceName.ANTHROPIC, EvaluationModel.HAIKU);

        assertThat(cost).isEqualTo(50);
    }

    @Test
    @DisplayName("calculateCost() returns WorldTides cost for WORLD_TIDES service")
    void calculateCost_returnsWorldTidesCost_forWorldTidesService() {
        CostProperties props = new CostProperties();
        props.setWorldTidesPence(20);
        CostCalculator calculator = new CostCalculator(props);

        int cost = calculator.calculateCost(ServiceName.WORLD_TIDES);

        assertThat(cost).isEqualTo(20);
    }

    @Test
    @DisplayName("calculateCost() returns zero cost for OPEN_METEO_FORECAST")
    void calculateCost_returnsZeroCost_forOpenMeteoForecast() {
        CostProperties props = new CostProperties();
        props.setOpenMeteoPence(0);
        CostCalculator calculator = new CostCalculator(props);

        int cost = calculator.calculateCost(ServiceName.OPEN_METEO_FORECAST);

        assertThat(cost).isZero();
    }

    @Test
    @DisplayName("calculateCost() returns zero cost for OPEN_METEO_AIR_QUALITY")
    void calculateCost_returnsZeroCost_forOpenMeteoAirQuality() {
        CostProperties props = new CostProperties();
        props.setOpenMeteoPence(0);
        CostCalculator calculator = new CostCalculator(props);

        int cost = calculator.calculateCost(ServiceName.OPEN_METEO_AIR_QUALITY);

        assertThat(cost).isZero();
    }

    @Test
    @DisplayName("calculateCost() returns Sonnet cost for Anthropic when model is null")
    void calculateCost_returnsSonnetCost_whenModelIsNull() {
        CostProperties props = new CostProperties();
        props.setAnthropicSonnetPence(130);
        CostCalculator calculator = new CostCalculator(props);

        // When model is null, it defaults to Sonnet (the more expensive option)
        int cost = calculator.calculateCost(ServiceName.ANTHROPIC, null);

        assertThat(cost).isEqualTo(130);
    }

    @Test
    @DisplayName("calculateCost() respects custom cost values from properties")
    void calculateCost_respectsCustomCostValues() {
        CostProperties props = new CostProperties();
        props.setAnthropicHaikuPence(75);
        props.setAnthropicSonnetPence(200);
        props.setWorldTidesPence(25);
        CostCalculator calculator = new CostCalculator(props);

        assertThat(calculator.calculateCost(ServiceName.ANTHROPIC, EvaluationModel.HAIKU)).isEqualTo(75);
        assertThat(calculator.calculateCost(ServiceName.ANTHROPIC, EvaluationModel.SONNET)).isEqualTo(200);
        assertThat(calculator.calculateCost(ServiceName.WORLD_TIDES)).isEqualTo(25);
    }

    @Test
    @DisplayName("calculateCost() overload without model works for non-Anthropic services")
    void calculateCost_overloadWithoutModel_worksForNonAnthropicServices() {
        CostProperties props = new CostProperties();
        props.setWorldTidesPence(20);
        CostCalculator calculator = new CostCalculator(props);

        int cost = calculator.calculateCost(ServiceName.WORLD_TIDES);

        assertThat(cost).isEqualTo(20);
    }
}
