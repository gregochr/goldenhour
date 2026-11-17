package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.model.SimulateWarningRequest;
import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsAdminStatus;
import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.ServedBriefingEpoch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WarningService}: what the shared and admin views contain, staleness, and simulation.
 */
@ExtendWith(MockitoExtension.class)
class WarningServiceTest {

    private static final Instant NOW = Instant.parse("2031-03-10T12:00:00Z");
    private static final String BOX = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-2,54],[-1,54],[-1,55],[-2,55],"
            + "[-2,54]]]]}";

    @Mock
    private WeatherWarningVersionRepository repository;
    @Mock
    private LocationRepository locations;

    private final WarningsProperties properties = new WarningsProperties();
    private final WarningPollStatus status = new WarningPollStatus();
    private final ServedBriefingEpoch epoch = new ServedBriefingEpoch();
    private WarningService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new WarningService(repository, new WarningTimeline(clock), new WarningCoverage(), locations,
                status, properties, epoch, clock);
    }

    private static LocationEntity location(long id, double lat, double lon) {
        LocationEntity l = new LocationEntity();
        l.setId(id);
        l.setLat(lat);
        l.setLon(lon);
        return l;
    }

    private static WeatherWarningVersionEntity row(String id, int version, String level, boolean simulated) {
        WeatherWarningVersionEntity w = new WeatherWarningVersionEntity();
        w.setWarningId(id);
        w.setWarningVersion(java.math.BigDecimal.valueOf(version));
        w.setStatus(WeatherWarningVersionEntity.STATUS_ACTIVE);
        w.setWarningLevel(level);
        w.setWeatherTypes("[\"WIND\"]");
        w.setWhatToExpect("[\"Delays\"]");
        w.setAffectedAreas("[\"North East\"]");
        w.setHeadline("Headline " + id);
        w.setValidFrom(NOW.minus(Duration.ofHours(1)));
        w.setValidTo(NOW.plus(Duration.ofHours(5)));
        w.setReceivedAt(NOW.minus(Duration.ofHours(2)));
        w.setGeometry(BOX);
        w.setSimplifiedGeometry(BOX);
        w.setSimulated(simulated);
        return w;
    }

    private void recordSuccessAt(Instant at) {
        status.record(new WarningPollStatus.Attempt(at, true, null, 1, 0, 1, 0, 0));
    }

    private void stubStoreAndLocations(WeatherWarningVersionEntity... rows) {
        when(repository.findAllVersionsOfWarningsValidAfter(NOW)).thenReturn(List.of(rows));
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(location(7, 54.5, -1.5),
                location(8, 60.0, -1.5)));
    }

    // ── Shared view ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("with the feature off the shared view is empty, not enabled, stale, and reads nothing")
    void disabled() {
        properties.setEnabled(false);

        WarningsResponse response = service.served();

        assertThat(response.enabled()).isFalse();
        assertThat(response.stale()).isTrue();
        assertThat(response.warnings()).isEmpty();
        assertThat(response.lastCheckedAt()).isNull();
        verifyNoInteractions(repository, locations);
    }

    @Test
    @DisplayName("the response carries the configured Met Office link and attribution, even when switched off")
    void carriesLinkAndAttribution() {
        stubStoreAndLocations();
        WarningsResponse on = service.served();
        properties.setEnabled(false);
        WarningsResponse off = service.served();

        assertThat(on.metOfficeUrl()).isEqualTo("https://www.metoffice.gov.uk/public/weather/warnings/");
        assertThat(on.attribution()).isEqualTo("Met Office Weather Warnings © Crown Copyright 2026");
        assertThat(off.metOfficeUrl()).isEqualTo(on.metOfficeUrl());
        assertThat(off.attribution()).isEqualTo(on.attribution());
    }

    @Test
    @DisplayName("the DTO serves the raw integers, the update text, the advice, the updated flag and a decimal version")
    void servesRealFeedFields() {
        var v = row("W1", 2, "YELLOW", false);
        v.setWarningVersion(new java.math.BigDecimal("2.00"));
        v.setLikelihood(3);
        v.setImpact(2);
        v.setUpdateDescription("Trimmed the area.");
        v.setWhatShouldIDo("Plan your route.");
        stubStoreAndLocations(v);

        WarningDto dto = service.served().warnings().get(0);

        assertThat(dto.warningVersion()).isEqualTo("2.0");
        assertThat(dto.likelihood()).isEqualTo(3);
        assertThat(dto.impact()).isEqualTo(2);
        assertThat(dto.updateDescription()).isEqualTo("Trimmed the area.");
        assertThat(dto.whatShouldIDo()).isEqualTo("Plan your route.");
        assertThat(dto.updated()).isTrue();
    }

    @Test
    @DisplayName("version formatting: 3.00 is 3.0, 10.00 is 10.0, 2.25 stays, null stays null")
    void formatsVersions() {
        assertThat(WarningService.formatVersion(new java.math.BigDecimal("3.00"))).isEqualTo("3.0");
        assertThat(WarningService.formatVersion(new java.math.BigDecimal("10.00"))).isEqualTo("10.0");
        assertThat(WarningService.formatVersion(new java.math.BigDecimal("2.25"))).isEqualTo("2.25");
        assertThat(WarningService.formatVersion(new java.math.BigDecimal("100"))).isEqualTo("100.0");
        assertThat(WarningService.formatVersion(null)).isNull();
    }

    @Test
    @DisplayName("the shared view maps a warning with its properties, geometry, area ids and no upgrade flag")
    void servedMapsWarning() {
        stubStoreAndLocations(row("W1", 1, "Yellow", false));
        recordSuccessAt(NOW.minus(Duration.ofMinutes(5)));

        WarningsResponse response = service.served();

        assertThat(response.enabled()).isTrue();
        assertThat(response.stale()).isFalse();
        assertThat(response.lastCheckedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(response.warnings()).hasSize(1);
        WarningDto dto = response.warnings().get(0);
        assertThat(dto.warningId()).isEqualTo("W1");
        assertThat(dto.level()).isEqualTo("Yellow");
        assertThat(dto.weatherTypes()).containsExactly("WIND");
        assertThat(dto.whatToExpect()).containsExactly("Delays");
        assertThat(dto.affectedAreas()).containsExactly("North East");
        assertThat(dto.geometry()).containsEntry("type", "MultiPolygon");
        assertThat(dto.locationIds()).containsExactly(7L);
        assertThat(dto.upgradedFrom()).isNull();
        assertThat(dto.simulated()).isFalse();
    }

    @Test
    @DisplayName("a recent upgrade is served with its previous level and receipt time")
    void servedUpgradeFlag() {
        var v1 = row("W1", 1, "Yellow", false);
        v1.setReceivedAt(NOW.minus(Duration.ofHours(5)));
        var v2 = row("W1", 2, "Amber", false);
        v2.setReceivedAt(NOW.minus(Duration.ofHours(2)));
        stubStoreAndLocations(v1, v2);

        WarningDto dto = service.served().warnings().get(0);

        assertThat(dto.level()).isEqualTo("Amber");
        assertThat(dto.upgradedFrom()).isEqualTo("Yellow");
        assertThat(dto.upgradedAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("a simulated warning never appears in the shared view, even when it covers an enabled location")
    void simulatedNeverShared() {
        stubStoreAndLocations(row("SIM-1", 1, "Red", true), row("REAL", 1, "Yellow", false));

        WarningsResponse response = service.served();

        assertThat(response.warnings()).extracting(WarningDto::warningId).containsExactly("REAL");
    }

    @Test
    @DisplayName("a warning that covers no enabled location is stored but not served")
    void uncoveredNotServed() {
        var far = row("FAR", 1, "Red", false);
        far.setGeometry("{\"type\":\"MultiPolygon\",\"coordinates\":[[[[10,10],[11,10],[11,11],[10,11],[10,10]]]]}");
        stubStoreAndLocations(far);

        assertThat(service.served().warnings()).isEmpty();
    }

    @Test
    @DisplayName("the shared view is stale at 30 minutes and one second since the last good poll, not at 30 exactly")
    void staleBoundary() {
        stubStoreAndLocations();

        recordSuccessAt(NOW.minus(Duration.ofMinutes(30)));
        assertThat(service.served().stale()).isFalse();

        recordSuccessAt(NOW.minus(Duration.ofMinutes(30)).minusSeconds(1));
        assertThat(service.served().stale()).isTrue();
    }

    @Test
    @DisplayName("the shared view is stale when there has been no successful poll since startup, and honours "
            + "a configured limit")
    void staleWhenNeverChecked() {
        stubStoreAndLocations();
        assertThat(service.served().stale()).isTrue();

        properties.setPollStaleMinutes(120);
        recordSuccessAt(NOW.minus(Duration.ofMinutes(100)));
        assertThat(service.served().stale()).isFalse();
    }

    // ── Admin view ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the admin view includes simulated warnings and counts, and says whether a key and URL are set "
            + "without revealing them")
    void adminStatus() {
        properties.setApiKey("super-secret-key");
        properties.setBaseUrl("https://example.test/feed");
        stubStoreAndLocations(row("SIM-1", 1, "Red", true), row("REAL", 1, "Yellow", false));
        when(repository.count()).thenReturn(5L);
        when(repository.countBySimulated(true)).thenReturn(2L);
        status.countUnknownType("Volcanic Ash");
        recordSuccessAt(NOW.minus(Duration.ofMinutes(1)));

        WarningsAdminStatus admin = service.adminStatus();

        assertThat(admin.enabled()).isTrue();
        assertThat(admin.apiKeyConfigured()).isTrue();
        assertThat(admin.feedUrlConfigured()).isTrue();
        assertThat(admin.storedVersions()).isEqualTo(5L);
        assertThat(admin.simulatedVersions()).isEqualTo(2L);
        assertThat(admin.unknownTypes()).containsEntry("Volcanic Ash", 1);
        assertThat(admin.lastAttempt().succeeded()).isTrue();
        assertThat(admin.stale()).isFalse();
        assertThat(admin.activeWarnings()).extracting(WarningDto::warningId).containsExactly("SIM-1", "REAL");
        assertThat(admin.activeWarnings().get(0).simulated()).isTrue();
        assertThat(admin.toString()).doesNotContain("super-secret-key");
    }

    @Test
    @DisplayName("the admin view reports an unset key and URL as not configured, whether null or blank")
    void adminStatusUnconfigured() {
        stubStoreAndLocations();
        properties.setApiKey(null);
        properties.setBaseUrl("");

        WarningsAdminStatus admin = service.adminStatus();

        assertThat(admin.apiKeyConfigured()).isFalse();
        assertThat(admin.feedUrlConfigured()).isFalse();

        properties.setApiKey(" ");
        properties.setBaseUrl(null);
        WarningsAdminStatus blank = service.adminStatus();
        assertThat(blank.apiKeyConfigured()).isFalse();
        assertThat(blank.feedUrlConfigured()).isFalse();
    }

    // ── Simulation ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("simulate inserts a simulated version around the point, valid for the requested hours, "
            + "and bumps the epoch")
    void simulateInserts() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(location(7, 54.5, -1.5)));
        when(repository.save(org.mockito.ArgumentMatchers.argThat(w -> w != null && w.isSimulated())))
                .thenAnswer(inv -> inv.getArgument(0));
        long before = epoch.current();

        WarningDto dto = service.simulate(new SimulateWarningRequest("red", List.of("SNOW", "ICE"), "Test snow", 12,
                54.5, -1.5));

        ArgumentCaptor<WeatherWarningVersionEntity> saved = ArgumentCaptor.forClass(WeatherWarningVersionEntity.class);
        verify(repository).save(saved.capture());
        WeatherWarningVersionEntity row = saved.getValue();
        assertThat(row.getWarningId()).startsWith("SIM-");
        assertThat(row.getWarningVersion()).isEqualByComparingTo("1");
        assertThat(row.isSimulated()).isTrue();
        assertThat(row.getSourceStatus()).isEqualTo("SIMULATED");
        assertThat(row.getWarningLevel()).isEqualTo("RED");
        assertThat(WarningFeedParser.stringList(row.getWeatherTypes())).containsExactly("SNOW", "ICE");
        assertThat(row.getHeadline()).isEqualTo("Test snow");
        assertThat(row.getValidFrom()).isEqualTo(NOW);
        assertThat(row.getValidTo()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(row.getReceivedAt()).isEqualTo(NOW);
        assertThat(WarningGeometry.contains(WarningGeometry.parse(row.getGeometry()), -1.5, 54.5)).isTrue();
        assertThat(dto.simulated()).isTrue();
        assertThat(dto.locationIds()).containsExactly(7L);
        assertThat(epoch.current()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("simulate applies defaults: Amber, WIND, a labelled headline and six hours")
    void simulateDefaults() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(repository.save(org.mockito.ArgumentMatchers.argThat(w -> w != null && w.isSimulated())))
                .thenAnswer(inv -> inv.getArgument(0));

        service.simulate(new SimulateWarningRequest(null, null, " ", null, 54.5, -1.5));
        service.simulate(new SimulateWarningRequest(" ", List.of(), null, null, 54.5, -1.5));

        ArgumentCaptor<WeatherWarningVersionEntity> saved = ArgumentCaptor.forClass(WeatherWarningVersionEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        for (WeatherWarningVersionEntity row : saved.getAllValues()) {
            assertThat(row.getWarningLevel()).isEqualTo("AMBER");
            assertThat(WarningFeedParser.stringList(row.getWeatherTypes())).containsExactly("WIND");
            assertThat(row.getHeadline()).contains("Simulated");
            assertThat(row.getValidTo()).isEqualTo(NOW.plus(Duration.ofHours(6)));
        }
    }

    @Test
    @DisplayName("a simulated warning, once stored, is in the admin view and never in the shared one")
    void simulatedRoundTrip() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(location(7, 54.5, -1.5)));
        when(repository.save(org.mockito.ArgumentMatchers.argThat(w -> w != null && w.isSimulated())))
                .thenAnswer(inv -> inv.getArgument(0));
        service.simulate(new SimulateWarningRequest("Amber", null, null, 6, 54.5, -1.5));
        ArgumentCaptor<WeatherWarningVersionEntity> saved = ArgumentCaptor.forClass(WeatherWarningVersionEntity.class);
        verify(repository).save(saved.capture());
        when(repository.findAllVersionsOfWarningsValidAfter(NOW)).thenReturn(List.of(saved.getValue()));
        recordSuccessAt(NOW);

        assertThat(service.served().warnings()).isEmpty();
        assertThat(service.adminStatus().activeWarnings()).hasSize(1);
    }

    @Test
    @DisplayName("simulate refuses an unknown level, out-of-range coordinates and an out-of-range duration, "
            + "and stores nothing")
    void simulateValidation() {
        assertThatThrownBy(() -> service.simulate(new SimulateWarningRequest("Purple", null, null, 6, 54, -1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("level");
        assertThatThrownBy(() -> service.simulate(new SimulateWarningRequest("Red", null, null, 6, 91, -1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("out of range");
        assertThatThrownBy(() -> service.simulate(new SimulateWarningRequest("Red", null, null, 6, -91, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.simulate(new SimulateWarningRequest("Red", null, null, 6, 54, 181)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.simulate(new SimulateWarningRequest("Red", null, null, 0, 54, -1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("durationHours");
        assertThatThrownBy(() -> service.simulate(new SimulateWarningRequest("Red", null, null, 73, 54, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(w -> true));
    }

    @Test
    @DisplayName("the boundary values 90, 180, 1 and 72 are accepted")
    void simulateBoundariesAccepted() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(repository.save(org.mockito.ArgumentMatchers.argThat(w -> w != null && w.isSimulated())))
                .thenAnswer(inv -> inv.getArgument(0));

        WarningDto low = service.simulate(new SimulateWarningRequest("Yellow", null, null, 1, 90, 180));
        WarningDto high = service.simulate(new SimulateWarningRequest("Yellow", null, null, 72, -90, -180));

        assertThat(low.validTo()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(high.validTo()).isEqualTo(NOW.plus(Duration.ofHours(72)));
    }
}
