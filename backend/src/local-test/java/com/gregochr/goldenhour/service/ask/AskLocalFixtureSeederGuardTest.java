package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two independent guards that keep {@link AskLocalFixtureSeeder} out of production: the bean
 * exists only under the {@code local} profile with the flag on, and at run time it refuses unless
 * the profile and an H2 database really are what it needs.
 */
class AskLocalFixtureSeederGuardTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(RegionRepository.class, () -> mock(RegionRepository.class))
                .withBean(LocationRepository.class, () -> mock(LocationRepository.class))
                .withBean(TideExtremeRepository.class, () -> mock(TideExtremeRepository.class))
                .withBean(BriefingEvaluationService.class, () -> mock(BriefingEvaluationService.class))
                .withBean(BriefingService.class, () -> mock(BriefingService.class))
                .withBean(AskSnapshotBuilder.class, () -> mock(AskSnapshotBuilder.class))
                .withBean(SolarEventFreshness.class, () -> mock(SolarEventFreshness.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(Clock.class, () -> CLOCK)
                .withUserConfiguration(AskLocalFixtureSeeder.class);
    }

    private static DataSource databaseCalled(String product) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn(product);
        return ds;
    }

    private static AskLocalFixtureSeeder seeder(DataSource ds, String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return new AskLocalFixtureSeeder(mock(RegionRepository.class), mock(LocationRepository.class),
                mock(TideExtremeRepository.class), mock(BriefingEvaluationService.class),
                mock(BriefingService.class), mock(AskSnapshotBuilder.class), mock(SolarEventFreshness.class), ds,
                env, CLOCK);
    }

    // -- the bean condition -----------------------------------------------------------------

    @Test
    @DisplayName("the local profile with the flag exactly true creates the seeder")
    void localWithFlagCreatesTheBean() {
        runner().withPropertyValues("spring.profiles.active=local", "photocast.ask.seed-local-fixture=true")
                .run(context -> assertThat(context).hasSingleBean(AskLocalFixtureSeeder.class));
    }

    @Test
    @DisplayName("without the local profile there is no seeder, flag or not: prod, dev, and no profile at all")
    void noLocalProfileNoBean() {
        runner().withPropertyValues("photocast.ask.seed-local-fixture=true")
                .run(context -> assertThat(context).doesNotHaveBean(AskLocalFixtureSeeder.class));
        runner().withPropertyValues("spring.profiles.active=prod", "photocast.ask.seed-local-fixture=true")
                .run(context -> assertThat(context).doesNotHaveBean(AskLocalFixtureSeeder.class));
        runner().withPropertyValues("spring.profiles.active=dev", "photocast.ask.seed-local-fixture=true")
                .run(context -> assertThat(context).doesNotHaveBean(AskLocalFixtureSeeder.class));
    }

    @Test
    @DisplayName("local together with prod still creates nothing: prod always wins")
    void localAndProdCreatesNothing() {
        runner().withPropertyValues("spring.profiles.active=local,prod", "photocast.ask.seed-local-fixture=true")
                .run(context -> assertThat(context).doesNotHaveBean(AskLocalFixtureSeeder.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "yes", "on", "1", "TRUE-ish", ""})
    @DisplayName("the local profile with the flag off, missing or anything but true creates nothing")
    void flagMustBeExactlyTrue(String value) {
        runner().withPropertyValues("spring.profiles.active=local", "photocast.ask.seed-local-fixture=" + value)
                .run(context -> assertThat(context).doesNotHaveBean(AskLocalFixtureSeeder.class));
    }

    @Test
    @DisplayName("the local profile with the flag absent creates nothing")
    void flagAbsentCreatesNothing() {
        runner().withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).doesNotHaveBean(AskLocalFixtureSeeder.class));
    }

    // -- the run-time refusal ---------------------------------------------------------------

    @Test
    @DisplayName("on H2 under the local profile the check passes")
    void localH2Passes() throws SQLException {
        assertThatCode(() -> seeder(databaseCalled("H2"), "local").requireLocalH2()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("it refuses a database that is not H2, naming it, whatever the profile says")
    void refusesAnythingButH2() throws SQLException {
        assertThatThrownBy(() -> seeder(databaseCalled("PostgreSQL"), "local").requireLocalH2())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PostgreSQL");
        assertThatThrownBy(() -> seeder(databaseCalled(""), "local").requireLocalH2())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("it refuses when the local profile is not active, and when prod is, before asking the database")
    void refusesWithoutTheLocalProfile() {
        DataSource ds = mock(DataSource.class);

        assertThatThrownBy(() -> seeder(ds).requireLocalH2()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local profile");
        assertThatThrownBy(() -> seeder(ds, "prod").requireLocalH2()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seeder(ds, "local", "prod").requireLocalH2())
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(ds);
    }

    @Test
    @DisplayName("it refuses when the database cannot even be asked")
    void refusesWhenTheDatabaseCannotBeAsked() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("down"));

        assertThatThrownBy(() -> seeder(ds, "local").requireLocalH2())
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("on start-up a refusal stops the application and writes nothing")
    void startupRefusalPropagatesAndWritesNothing() throws SQLException {
        RegionRepository regions = mock(RegionRepository.class);
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("local");
        AskLocalFixtureSeeder seeder = new AskLocalFixtureSeeder(regions, mock(LocationRepository.class),
                mock(TideExtremeRepository.class), mock(BriefingEvaluationService.class),
                mock(BriefingService.class), mock(AskSnapshotBuilder.class), mock(SolarEventFreshness.class),
                databaseCalled("PostgreSQL"), env, CLOCK);

        assertThatThrownBy(seeder::onApplicationReady).isInstanceOf(IllegalStateException.class);

        verify(regions, never()).save(org.mockito.ArgumentMatchers.any());
        verify(regions, never()).findByName(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("on start-up a failure after the checks is logged and the application carries on")
    void startupSeedingFailureIsSwallowed() throws SQLException {
        RegionRepository regions = mock(RegionRepository.class);
        when(regions.findByName(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("database is shutting down"));
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("local");
        AskLocalFixtureSeeder seeder = new AskLocalFixtureSeeder(regions, mock(LocationRepository.class),
                mock(TideExtremeRepository.class), mock(BriefingEvaluationService.class),
                mock(BriefingService.class), mock(AskSnapshotBuilder.class), mock(SolarEventFreshness.class),
                databaseCalled("H2"), env, CLOCK);

        assertThatCode(seeder::onApplicationReady).doesNotThrowAnyException();
    }
}
