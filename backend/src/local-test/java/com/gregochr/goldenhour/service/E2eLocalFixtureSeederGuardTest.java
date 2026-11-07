package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The bean condition that keeps {@link E2eLocalFixtureSeeder} and its readiness indicator out of production:
 * both exist only under the {@code local} profile (never beside {@code prod}) with
 * {@code photocast.e2e.seed-fixture} exactly {@code true}. The indicator's bean name is the contract the
 * smoke script's {@code ...container.include=db,ping,e2eFixture} relies on.
 */
class E2eLocalFixtureSeederGuardTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(RegionRepository.class, () -> mock(RegionRepository.class))
                .withBean(LocationRepository.class, () -> mock(LocationRepository.class))
                .withBean(TideExtremeRepository.class, () -> mock(TideExtremeRepository.class))
                .withBean(ForecastEvaluationRepository.class, () -> mock(ForecastEvaluationRepository.class))
                .withBean(DailyBriefingCacheRepository.class, () -> mock(DailyBriefingCacheRepository.class))
                .withBean(BriefingEvaluationService.class, () -> mock(BriefingEvaluationService.class))
                .withBean(BriefingService.class, () -> mock(BriefingService.class))
                .withBean(BriefingSlotBuilder.class, () -> mock(BriefingSlotBuilder.class))
                .withBean(BriefingHierarchyBuilder.class, () -> mock(BriefingHierarchyBuilder.class))
                .withBean(BriefingRegionEvaluationRollup.class, () -> mock(BriefingRegionEvaluationRollup.class))
                .withBean(BriefingHeadlineGenerator.class, () -> mock(BriefingHeadlineGenerator.class))
                .withBean(EvaluationViewService.class, () -> mock(EvaluationViewService.class))
                .withBean(AlmanacService.class, () -> mock(AlmanacService.class))
                .withBean(SolarEventFreshness.class, () -> mock(SolarEventFreshness.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2031-07-14T12:00:00Z"), ZoneOffset.UTC))
                .withUserConfiguration(E2eLocalFixtureSeeder.class, E2eFixtureHealthIndicator.class);
    }

    @Test
    @DisplayName("local with the flag exactly true creates the seeder and the indicator named for the health group")
    void localWithFlagCreatesBoth() {
        runner().withPropertyValues("spring.profiles.active=local", "photocast.e2e.seed-fixture=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(E2eLocalFixtureSeeder.class);
                    assertThat(context).hasBean("e2eFixtureHealthIndicator");
                });
    }

    @Test
    @DisplayName("without the local profile there is neither bean, flag or not: prod, dev, and no profile at all")
    void noLocalProfileNoBeans() {
        runner().withPropertyValues("photocast.e2e.seed-fixture=true").run(this::assertNeither);
        runner().withPropertyValues("spring.profiles.active=prod", "photocast.e2e.seed-fixture=true")
                .run(this::assertNeither);
        runner().withPropertyValues("spring.profiles.active=dev", "photocast.e2e.seed-fixture=true")
                .run(this::assertNeither);
    }

    @Test
    @DisplayName("local together with prod still creates nothing: prod always wins")
    void localAndProdCreatesNothing() {
        runner().withPropertyValues("spring.profiles.active=local,prod", "photocast.e2e.seed-fixture=true")
                .run(this::assertNeither);
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "yes", "on", "1", "TRUE-ish", ""})
    @DisplayName("the local profile with the flag off or anything but true creates nothing")
    void flagMustBeExactlyTrue(String value) {
        runner().withPropertyValues("spring.profiles.active=local", "photocast.e2e.seed-fixture=" + value)
                .run(this::assertNeither);
    }

    @Test
    @DisplayName("local with the flag absent creates nothing, and the Ask seeder's flag does not turn it on")
    void flagAbsentCreatesNothing() {
        runner().withPropertyValues("spring.profiles.active=local").run(this::assertNeither);
        runner().withPropertyValues("spring.profiles.active=local", "photocast.ask.seed-local-fixture=true")
                .run(this::assertNeither);
    }

    private void assertNeither(AssertableApplicationContext context) {
        assertThat(context).doesNotHaveBean(E2eLocalFixtureSeeder.class);
        assertThat(context).doesNotHaveBean("e2eFixtureHealthIndicator");
    }
}
