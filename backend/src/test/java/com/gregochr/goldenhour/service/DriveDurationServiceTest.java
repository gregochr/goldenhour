package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DriveDurationService}.
 *
 * <p>The service only measures, so every test asserts what it RETURNS. Storing — and the guard
 * that stores only while the home is still the one measured from — belongs to
 * {@link UserDriveTimeWriter}, and is pinned in its own tests.
 */
@ExtendWith(MockitoExtension.class)
class DriveDurationServiceTest {

    @Mock
    private OpenRouteServiceClient orsClient;

    @Mock
    private OrsProperties orsProperties;

    @Mock
    private LocationRepository locationRepository;

    private DriveDurationService service;

    private static final Long USER_ID = 42L;
    private static final double SOURCE_LAT = 54.778;
    private static final double SOURCE_LON = -1.600;

    @BeforeEach
    void setUp() {
        service = new DriveDurationService(orsClient, orsProperties, locationRepository);
    }

    /** Configured ORS, the given roster, and ORS answering {@code durations} for exactly it. */
    private void orsAnswers(List<LocationEntity> roster, List<Double> durations) {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(roster);
        when(orsClient.fetchDurations(eq(SOURCE_LAT), eq(SOURCE_LON), destinationsOf(roster)))
                .thenReturn(durations);
    }

    /**
     * Matches the destination list built from {@code roster}, by content: {@code double[]} has
     * identity equality, so {@code eq} could never match a list the service built itself.
     */
    private static List<double[]> destinationsOf(List<LocationEntity> roster) {
        return argThat(actual -> actual != null
                && actual.size() == roster.size()
                && IntStream.range(0, roster.size()).allMatch(i ->
                        actual.get(i)[0] == roster.get(i).getLat()
                                && actual.get(i)[1] == roster.get(i).getLon()));
    }

    @Test
    @DisplayName("no answer when ORS is not configured — and ORS is not called")
    void measureForUser_orsNotConfigured_noAnswer() {
        when(orsProperties.isConfigured()).thenReturn(false);

        Optional<List<UserDriveTimeEntity>> result =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEmpty();
        verifyNoInteractions(orsClient, locationRepository);
    }

    @Test
    @DisplayName("no answer when no locations exist — and ORS is not called")
    void measureForUser_noLocations_noAnswer() {
        when(orsProperties.isConfigured()).thenReturn(true);
        when(locationRepository.findAll()).thenReturn(List.of());

        Optional<List<UserDriveTimeEntity>> result =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isEmpty();
        verifyNoInteractions(orsClient);
    }

    @Test
    @DisplayName("no answer when ORS returns an empty response — stored drive times should stay")
    void measureForUser_emptyDurations_noAnswer() {
        // Distinct from an answer with no valid duration (below): nothing was learned here, so a
        // caller must not treat it as "no location is reachable" and clear what it has.
        orsAnswers(List.of(location(1L, "Durham UK", 54.78, -1.58)), List.of());

        assertThat(service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON)).isEmpty();
    }

    @Test
    @DisplayName("converts an ORS duration into a row for that user and location")
    void measureForUser_convertsDuration() {
        orsAnswers(List.of(location(1L, "Durham UK", 54.78, -1.58)), List.of(2700.0));

        List<UserDriveTimeEntity> rows =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON).orElseThrow();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(rows.get(0).getLocationId()).isEqualTo(1L);
        assertThat(rows.get(0).getDriveDurationSeconds()).isEqualTo(2700);
    }

    @Test
    @DisplayName("an answer whose only duration is null is an answer with no rows, not no answer")
    void measureForUser_nullDuration_answerWithNoRows() {
        // Present-but-empty is what tells the manual refresh to clear: ORS answered, and nothing
        // was reachable. Collapsing it into Optional.empty() would keep drive times ORS has just
        // declined to confirm.
        orsAnswers(List.of(location(1L, "Durham UK", 54.78, -1.58)), Arrays.asList((Double) null));

        Optional<List<UserDriveTimeEntity>> result =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    @DisplayName("a negative duration is skipped")
    void measureForUser_negativeDuration_skipped() {
        orsAnswers(List.of(location(1L, "Durham UK", 54.78, -1.58)), List.of(-1.0));

        assertThat(service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON).orElseThrow()).isEmpty();
    }

    @Test
    @DisplayName("every location with a duration gets a row, in roster order")
    void measureForUser_multipleLocations_allMeasured() {
        orsAnswers(List.of(location(1L, "Durham UK", 54.78, -1.58),
                location(2L, "Whitley Bay", 55.04, -1.44)), List.of(2700.0, 3600.0));

        List<UserDriveTimeEntity> rows =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON).orElseThrow();

        assertThat(rows).extracting(UserDriveTimeEntity::getLocationId).containsExactly(1L, 2L);
        assertThat(rows).extracting(UserDriveTimeEntity::getDriveDurationSeconds)
                .containsExactly(2700, 3600);
    }

    @Test
    @DisplayName("passes the origin and every destination, in order, to the ORS client")
    void measureForUser_passesCorrectCoordinatesToOrs() {
        orsAnswers(List.of(location(1L, "Durham", 54.78, -1.58),
                location(2L, "Bamburgh", 55.61, -1.71)), List.of(2700.0, 5400.0));

        service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> destCaptor = ArgumentCaptor.forClass(List.class);
        verify(orsClient).fetchDurations(eq(SOURCE_LAT), eq(SOURCE_LON), destCaptor.capture());
        List<double[]> destinations = destCaptor.getValue();
        assertThat(destinations).hasSize(2);
        assertThat(destinations.get(0)).containsExactly(54.78, -1.58);
        assertThat(destinations.get(1)).containsExactly(55.61, -1.71);
    }

    @Test
    @DisplayName("mix of valid, null, and negative durations — only the valid one becomes a row")
    void measureForUser_mixedDurations_onlyValidMeasured() {
        orsAnswers(List.of(location(1L, "Durham", 54.78, -1.58),
                location(2L, "Bamburgh", 55.61, -1.71),
                location(3L, "Kielder", 55.23, -2.58)), Arrays.asList(2700.0, null, -5.0));

        List<UserDriveTimeEntity> rows =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON).orElseThrow();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLocationId()).isEqualTo(1L);
        assertThat(rows.get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(rows.get(0).getDriveDurationSeconds()).isEqualTo(2700);
    }

    @Test
    @DisplayName("rows carry the userId they were measured for")
    void measureForUser_rowsHaveTheRequestingUsersId() {
        orsAnswers(List.of(location(1L, "Durham", 54.78, -1.58)), List.of(1800.0));

        Long differentUserId = 99L;
        List<UserDriveTimeEntity> rows =
                service.measureForUser(differentUserId, SOURCE_LAT, SOURCE_LON).orElseThrow();

        assertThat(rows.get(0).getUserId()).isEqualTo(differentUserId);
    }

    @Test
    @DisplayName("a fractional ORS duration is rounded to the nearest second")
    void measureForUser_roundsFractionalSeconds() {
        orsAnswers(List.of(location(1L, "Durham", 54.78, -1.58)), List.of(2700.7));

        List<UserDriveTimeEntity> rows =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON).orElseThrow();

        assertThat(rows.get(0).getDriveDurationSeconds()).isEqualTo(2701);
    }

    // The interrupt case moved with the semaphore. It used to live here, because the permit was
    // acquired in this class; the heat-field plan's P7 hoisted the limit into
    // com.gregochr.goldenhour.client.OrsRateLimiter so the per-user sweep and the shared region
    // matrix queue on ONE pair of permits rather than on one pair each. Both the interrupted-on-
    // entry case and the interrupted-while-waiting case are pinned in OrsRateLimiterTest, against
    // the class that now owns the behaviour — asserting it here would only prove that a mocked ORS
    // client does not acquire a semaphore.

    @Test
    @DisplayName("an ORS client exception propagates")
    void measureForUser_orsClientThrows_propagates() {
        when(orsProperties.isConfigured()).thenReturn(true);
        List<LocationEntity> roster = List.of(location(1L, "Durham", 54.78, -1.58));
        when(locationRepository.findAll()).thenReturn(roster);
        when(orsClient.fetchDurations(eq(SOURCE_LAT), eq(SOURCE_LON), destinationsOf(roster)))
                .thenThrow(new RuntimeException("ORS 503 Service Unavailable"));

        assertThatThrownBy(() -> service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("a zero-second duration is valid and measured (>= 0 boundary)")
    void measureForUser_zeroDuration_measured() {
        // 0.0 is a valid duration (>= 0). Mutating >= to > would skip it.
        orsAnswers(List.of(location(1L, "Local", 54.78, -1.58)), List.of(0.0));

        List<UserDriveTimeEntity> rows =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON).orElseThrow();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDriveDurationSeconds()).isZero();
    }

    @Test
    @DisplayName("more durations than locations — only location-indexed entries are measured")
    void measureForUser_moreDurationsThanLocations_onlyMatchingMeasured() {
        // Math.min(locations, durations) loop bound — mutating to durations.size() causes IOOBE.
        orsAnswers(List.of(location(1L, "Durham", 54.78, -1.58),
                location(2L, "Bamburgh", 55.61, -1.71)), List.of(1800.0, 3600.0, 5400.0, 7200.0));

        List<UserDriveTimeEntity> rows =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON).orElseThrow();

        assertThat(rows).extracting(UserDriveTimeEntity::getLocationId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("fewer durations than locations — only the answered locations are measured")
    void measureForUser_fewerDurationsThanLocations_onlyAnsweredMeasured() {
        // The other side of the same bound: mutating it to locations.size() reads past the end of
        // a short ORS list.
        orsAnswers(List.of(location(1L, "Durham", 54.78, -1.58),
                location(2L, "Bamburgh", 55.61, -1.71)), List.of(1800.0));

        List<UserDriveTimeEntity> rows =
                service.measureForUser(USER_ID, SOURCE_LAT, SOURCE_LON).orElseThrow();

        assertThat(rows).extracting(UserDriveTimeEntity::getLocationId).containsExactly(1L);
    }

    private static LocationEntity location(Long id, String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(lat)
                .lon(lon)
                .build();
    }
}
