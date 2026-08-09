package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.ServiceName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CostCalculator#calculateMeteredCostMicroDollars}.
 *
 * <p>The behaviour worth protecting is that an unknown credit count is not a free call. WorldTides
 * bills by the size of the window requested, so a flat per-call price silently stops tracking the
 * moment the fetch horizon changes — which is exactly what happened here.
 */
class CostCalculatorMeteredTest {

    private final CostProperties properties = new CostProperties();
    private final CostCalculator calculator = new CostCalculator(properties);

    @Test
    @DisplayName("WorldTides is priced per credit, so the cost moves with the fetch window")
    void pricesWorldTidesPerCredit() {
        // One credit per seven days of data. The two windows this project has actually used:
        // 14 days is 2 credits, 97 days is 14 — a 7x difference a flat price cannot express.
        assertThat(calculator.calculateMeteredCostMicroDollars(ServiceName.WORLD_TIDES, 2))
                .isEqualTo(500);
        assertThat(calculator.calculateMeteredCostMicroDollars(ServiceName.WORLD_TIDES, 14))
                .isEqualTo(3500);
    }

    @Test
    @DisplayName("a single credit costs the configured per-credit rate")
    void oneCreditIsTheUnitRate() {
        assertThat(calculator.calculateMeteredCostMicroDollars(ServiceName.WORLD_TIDES, 1))
                .isEqualTo(properties.getWorldTidesMicroDollarsPerCredit());
    }

    @Test
    @DisplayName("an unreported credit count falls back to the flat estimate, never to zero — a "
            + "call whose credits are unknown is not a free call")
    void unknownCreditsFallBackRatherThanZero() {
        long flat = properties.getWorldTidesMicroDollars();

        assertThat(calculator.calculateMeteredCostMicroDollars(ServiceName.WORLD_TIDES, null))
                .isEqualTo(flat)
                .isNotZero();
    }

    @Test
    @DisplayName("a zero or negative credit count also falls back — the provider reporting 0 is "
            + "indistinguishable from it reporting nothing, and both mean 'unknown'")
    void nonPositiveCreditsFallBack() {
        long flat = properties.getWorldTidesMicroDollars();

        assertThat(calculator.calculateMeteredCostMicroDollars(ServiceName.WORLD_TIDES, 0))
                .isEqualTo(flat);
        assertThat(calculator.calculateMeteredCostMicroDollars(ServiceName.WORLD_TIDES, -3))
                .isEqualTo(flat);
    }

    @Test
    @DisplayName("services that are not credit-metered ignore any credit count and stay flat")
    void nonMeteredServicesAreUnaffected() {
        // Open-Meteo is free and Anthropic is priced from tokens elsewhere. Passing a credit count
        // for either must not invent a charge.
        assertThat(calculator.calculateMeteredCostMicroDollars(
                ServiceName.OPEN_METEO_FORECAST, 14)).isZero();
        assertThat(calculator.calculateMeteredCostMicroDollars(ServiceName.ANTHROPIC, 14)).isZero();
    }

    @Test
    @DisplayName("the per-credit rate matches WorldTides' cheapest paid tier")
    void perCreditRateMatchesPublishedPricing() {
        // $4.99 for 20,000 credits = $0.00024950 = 249.5 micro-dollars, rounded to 250.
        // Restated here so a silent edit to the default has to justify itself against the tier.
        assertThat(properties.getWorldTidesMicroDollarsPerCredit()).isEqualTo(250);
    }
}
