package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.service.comingup.ComingUpScoringProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the retention setting end to end through Spring's own binding: the key
 * {@code photocast.slot-atmosphere.retention-days}, its 180-day default, the documented value in
 * {@code application-example.yml}, and the fail-fast startup guard against the REAL Coming up
 * trailing-window settings (the code default and the example file's), so shortening retention
 * below a reader's window can never pass silently.
 */
class SlotAtmosphereRetentionConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SlotAtmosphereRepository.class, () -> Mockito.mock(SlotAtmosphereRepository.class))
            .withBean(DynamicSchedulerService.class, () -> Mockito.mock(DynamicSchedulerService.class))
            .withBean(Clock.class,
                    () -> Clock.fixed(Instant.parse("2026-12-15T12:00:00Z"), ZoneOffset.UTC))
            .withBean(ComingUpScoringProperties.class, ComingUpScoringProperties::new)
            .withBean(SlotAtmosphereCleanupJob.class);

    private static Binder exampleYamlBinder() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-example", new ClassPathResource("application-example.yml"));
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    @Test
    @DisplayName("with no setting the context starts and the job is built with the 180-day default")
    void noSetting_startsWithTheDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SlotAtmosphereCleanupJob.class);
            context.getBean(SlotAtmosphereCleanupJob.class).prune();
            // 2026-12-15 minus 180 days: the default retention reached the delete.
            Mockito.verify(context.getBean(SlotAtmosphereRepository.class))
                    .deleteByEvaluationDateBefore(java.time.LocalDate.of(2026, 6, 18));
        });
    }

    @Test
    @DisplayName("photocast.slot-atmosphere.retention-days overrides the default")
    void explicitSetting_isHonoured() {
        runner.withPropertyValues("photocast.slot-atmosphere.retention-days=90").run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(SlotAtmosphereCleanupJob.class).prune();
            Mockito.verify(context.getBean(SlotAtmosphereRepository.class))
                    .deleteByEvaluationDateBefore(java.time.LocalDate.of(2026, 9, 16));
        });
    }

    @Test
    @DisplayName("a setting below the real default trailing window stops the application starting")
    void settingBelowTheTrailingWindow_failsStartup() {
        runner.withPropertyValues("photocast.slot-atmosphere.retention-days=30").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("retention-days=30")
                    .hasMessageContaining("60 days");
        });
    }

    @Test
    @DisplayName("application-example.yml documents retention-days: 180, which clears the longest "
            + "reader window that same file configures")
    void exampleYaml_retentionClearsItsOwnTrailingWindow() throws IOException {
        Binder binder = exampleYamlBinder();

        int documented = binder.bind("photocast.slot-atmosphere.retention-days", Integer.class).get();
        ComingUpScoringProperties scoring = binder
                .bind("coming-up.scoring", ComingUpScoringProperties.class).get();

        assertThat(documented).isEqualTo(180);
        assertThat(scoring.getRecurrent().getTrailingWindowDays()).isEqualTo(60);
        assertThat(documented)
                .isGreaterThanOrEqualTo(SlotAtmosphereCleanupJob.longestReadBackDays(scoring));
    }
}
