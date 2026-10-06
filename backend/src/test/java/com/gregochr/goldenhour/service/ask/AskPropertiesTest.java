package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.EvaluationModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AskProperties}: every bound is enforced at, one under and one over. */
class AskPropertiesTest {

    private record Bound(String key, int min, int max, BiConsumer<AskProperties, Integer> setter) {
        @Override
        public String toString() {
            return key + " " + min + ".." + max;
        }
    }

    static Stream<Bound> bounds() {
        return Stream.of(
                new Bound("max-turns", 1, 6, AskProperties::setMaxTurns),
                new Bound("max-tokens", 200, 2000, AskProperties::setMaxTokens),
                new Bound("call-timeout-seconds", 1, 60, AskProperties::setCallTimeoutSeconds),
                new Bound("deadline-seconds", 1, 120, AskProperties::setDeadlineSeconds),
                new Bound("rate-per-minute", 1, 600, AskProperties::setRatePerMinute),
                new Bound("limit-lite", 0, 1000, AskProperties::setLimitLite),
                new Bound("limit-pro", 0, 1000, AskProperties::setLimitPro),
                new Bound("engine-ceiling-multiplier", 1, 20, AskProperties::setEngineCeilingMultiplier),
                new Bound("ready.max-cycles-per-day", 1, 48,
                        (p, v) -> p.getReady().setMaxCyclesPerDay(v)),
                new Bound("cache.max-entries", 1, 100_000, (p, v) -> p.getCache().setMaxEntries(v)),
                new Bound("cache.ttl-minutes", 1, 1440, (p, v) -> p.getCache().setTtlMinutes(v)),
                new Bound("log.retention-days", 1, 3650, (p, v) -> p.getLog().setRetentionDays(v)));
    }

    @Test
    @DisplayName("the defaults are the plan's: off, Haiku, 4 turns, 600 tokens, 20s/30s, 5/min, 3/30, "
            + "ceiling 3x, $0.50, 6 cycles, 2000 entries for 30 minutes, 90 days")
    void defaults() {
        AskProperties p = new AskProperties();

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.isStub()).isFalse();
        assertThat(p.isSeedLocalFixture()).isFalse();
        assertThat(p.getModel()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(p.getMaxTurns()).isEqualTo(4);
        assertThat(p.getMaxTokens()).isEqualTo(600);
        assertThat(p.getCallTimeoutSeconds()).isEqualTo(20);
        assertThat(p.getDeadlineSeconds()).isEqualTo(30);
        assertThat(p.getRatePerMinute()).isEqualTo(5);
        assertThat(p.getLimitLite()).isEqualTo(3);
        assertThat(p.getLimitPro()).isEqualTo(30);
        assertThat(p.getEngineCeilingMultiplier()).isEqualTo(3);
        assertThat(p.getDailySpendCapUsd()).isEqualTo(0.50);
        assertThat(p.getReady().getMaxCyclesPerDay()).isEqualTo(6);
        assertThat(p.getCache().getMaxEntries()).isEqualTo(2000);
        assertThat(p.getCache().getTtlMinutes()).isEqualTo(30);
        assertThat(p.getLog().getRetentionDays()).isEqualTo(90);
        assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("bounds")
    @DisplayName("each integer setting accepts its limits and refuses one beyond either")
    void boundsAreEnforced(Bound bound) {
        AskProperties p = new AskProperties();

        assertThatCode(() -> bound.setter().accept(p, bound.min())).doesNotThrowAnyException();
        assertThatCode(() -> bound.setter().accept(p, bound.max())).doesNotThrowAnyException();
        assertThatThrownBy(() -> bound.setter().accept(p, bound.min() - 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(bound.key());
        assertThatThrownBy(() -> bound.setter().accept(p, bound.max() + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(bound.key());
    }

    @ParameterizedTest
    @EnumSource(value = EvaluationModel.class, names = {"HAIKU", "SONNET"})
    @DisplayName("Haiku and Sonnet 4.6 are the only models Ask accepts")
    void modelAccepted(EvaluationModel model) {
        AskProperties p = new AskProperties();

        p.setModel(model);

        assertThat(p.getModel()).isEqualTo(model);
    }

    @ParameterizedTest
    @EnumSource(value = EvaluationModel.class, names = {"HAIKU", "SONNET"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every other model is refused: Sonnet 5.5 cannot disable thinking, which breaks the budgets")
    void modelRefused(EvaluationModel model) {
        assertThatThrownBy(() -> new AskProperties().setModel(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HAIKU or SONNET");
    }

    @Test
    @DisplayName("the allowance is LITE 3, PRO 30, ADMIN 30 (the PRO allowance); an unknown role gets the smallest")
    void allowanceByRole() {
        AskProperties p = new AskProperties();

        assertThat(p.limitFor(com.gregochr.goldenhour.entity.UserRole.LITE_USER)).isEqualTo(3);
        assertThat(p.limitFor(com.gregochr.goldenhour.entity.UserRole.PRO_USER)).isEqualTo(30);
        assertThat(p.limitFor(com.gregochr.goldenhour.entity.UserRole.ADMIN)).isEqualTo(30);
        assertThat(p.limitFor(null)).isEqualTo(3);
    }

    @Test
    @DisplayName("the engine ceiling is the multiplier (3) times the role's allowance, and follows both settings")
    void ceilingByRole() {
        AskProperties p = new AskProperties();

        assertThat(p.engineCeilingFor(com.gregochr.goldenhour.entity.UserRole.LITE_USER)).isEqualTo(9);
        assertThat(p.engineCeilingFor(com.gregochr.goldenhour.entity.UserRole.PRO_USER)).isEqualTo(90);
        assertThat(p.engineCeilingFor(com.gregochr.goldenhour.entity.UserRole.ADMIN)).isEqualTo(90);
        p.setLimitLite(4);
        p.setEngineCeilingMultiplier(5);
        assertThat(p.engineCeilingFor(com.gregochr.goldenhour.entity.UserRole.LITE_USER)).isEqualTo(20);
        assertThat(p.engineCeilingFor(null)).isEqualTo(20);
    }

    @Test
    @DisplayName("the spend cap is above 0 and at most 1000 dollars; NaN and infinity are refused")
    void spendCapBounds() {
        AskProperties p = new AskProperties();

        p.setDailySpendCapUsd(0.01);
        p.setDailySpendCapUsd(1000.0);
        assertThat(p.getDailySpendCapUsd()).isEqualTo(1000.0);
        for (double bad : new double[] {0.0, -0.01, 1000.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> p.setDailySpendCapUsd(bad)).as("cap %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the spend cap converts to micro-dollars exactly, with no floating-point drift")
    void spendCapInMicroDollars() {
        AskProperties p = new AskProperties();

        assertThat(p.dailySpendCapMicroDollars()).isEqualTo(500_000L);
        p.setDailySpendCapUsd(0.07);
        assertThat(p.dailySpendCapMicroDollars()).isEqualTo(70_000L);
        p.setDailySpendCapUsd(1000.0);
        assertThat(p.dailySpendCapMicroDollars()).isEqualTo(1_000_000_000L);
    }

    @Test
    @DisplayName("the per-call timeout may equal the deadline but never exceed it")
    void callTimeoutNeverExceedsTheDeadline() {
        AskProperties p = new AskProperties();
        p.setCallTimeoutSeconds(30);
        p.setDeadlineSeconds(30);
        assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();

        p.setDeadlineSeconds(29);
        assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("call-timeout-seconds");
    }

    @Test
    @DisplayName("bound from configuration, a good set of values is read, nested keys included")
    void bindsFromConfiguration() {
        AskProperties p = bind(Map.of(
                "photocast.ask.enabled", "true",
                "photocast.ask.stub", "true",
                "photocast.ask.model", "SONNET",
                "photocast.ask.max-turns", "5",
                "photocast.ask.daily-spend-cap-usd", "1.25",
                "photocast.ask.ready.max-cycles-per-day", "8",
                "photocast.ask.cache.ttl-minutes", "45",
                "photocast.ask.log.retention-days", "30"));

        assertThat(p.isEnabled()).isTrue();
        assertThat(p.isStub()).isTrue();
        assertThat(p.getModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(p.getMaxTurns()).isEqualTo(5);
        assertThat(p.getDailySpendCapUsd()).isEqualTo(1.25);
        assertThat(p.getReady().getMaxCyclesPerDay()).isEqualTo(8);
        assertThat(p.getCache().getTtlMinutes()).isEqualTo(45);
        assertThat(p.getLog().getRetentionDays()).isEqualTo(30);
    }

    @ParameterizedTest
    @MethodSource("badConfiguration")
    @DisplayName("an out-of-range or unusable value fails binding, which is what fails startup")
    void badConfigurationFailsBinding(Map<String, String> config) {
        assertThatThrownBy(() -> bind(config)).isInstanceOf(BindException.class);
    }

    static Stream<Map<String, String>> badConfiguration() {
        return Stream.of(
                Map.of("photocast.ask.model", "SONNET_55"),
                Map.of("photocast.ask.model", "OPUS"),
                Map.of("photocast.ask.model", "not-a-model"),
                Map.of("photocast.ask.max-turns", "7"),
                Map.of("photocast.ask.max-turns", "0"),
                Map.of("photocast.ask.daily-spend-cap-usd", "0"),
                Map.of("photocast.ask.rate-per-minute", "601"),
                Map.of("photocast.ask.cache.max-entries", "0"),
                Map.of("photocast.ask.ready.max-cycles-per-day", "49"));
    }

    private static AskProperties bind(Map<String, String> config) {
        return new Binder(new MapConfigurationPropertySource(config))
                .bind("photocast.ask", AskProperties.class).get();
    }
}
