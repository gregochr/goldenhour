package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ExchangeRateEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pricing runs from stored rates only: no exchange-rate service, so no network, whatever is missing.
 */
class RunFxTest {

    private static ExchangeRateEntity rate(String day, double gbpPerUsd) {
        return ExchangeRateEntity.builder().rateDate(LocalDate.parse(day)).gbpPerUsd(gbpPerUsd)
                .fetchedAt(LocalDateTime.parse(day + "T12:00:00")).build();
    }

    @Test
    @DisplayName("a run's own rate wins; otherwise the nearest earlier stored day")
    void ownThenNearestEarlier() {
        RunFx fx = new RunFx(List.of(rate("2026-10-20", 0.80), rate("2026-10-30", 0.76)), 0.70);

        assertThat(fx.rateFor(0.5, LocalDate.parse("2026-11-01"))).isEqualTo(0.5);
        assertThat(fx.rateFor(null, LocalDate.parse("2026-11-01"))).isEqualTo(0.76);
        assertThat(fx.rateOn(LocalDate.parse("2026-10-25"))).isEqualTo(0.80);
        assertThat(fx.rateOn(LocalDate.parse("2026-10-30"))).isEqualTo(0.76);
    }

    @Test
    @DisplayName("before the first stored day it falls to the latest rate a run recorded, then to 0.79")
    void fallbacks() {
        RunFx noEarlier = new RunFx(List.of(rate("2026-10-30", 0.76)), 0.71);
        RunFx nothingStored = new RunFx(List.of(), null);

        assertThat(noEarlier.rateOn(LocalDate.parse("2026-10-01"))).isEqualTo(0.71);
        assertThat(nothingStored.rateOn(LocalDate.parse("2026-10-01"))).isEqualTo(RunFx.FALLBACK_GBP_PER_USD);
        assertThat(RunFx.FALLBACK_GBP_PER_USD).isEqualTo(0.79);
    }

    @Test
    @DisplayName("pounds are converted per run unrounded, and rounded to the penny once")
    void conversion() {
        double a = RunFx.gbp(1_234_567, 0.8);
        double b = RunFx.gbp(1_234_567, 0.7);

        assertThat(RunFx.pence(a + b)).isEqualTo(new BigDecimal("1.85"));
        assertThat(RunFx.dollars(1_234_567)).isEqualTo(new BigDecimal("1.23"));
        assertThat(RunFx.dollars(1_235_000)).isEqualTo(new BigDecimal("1.24"));
    }
}
