package com.gregochr.goldenhour.service.aurora;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.AuroraAlertSnapshotEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.repository.AuroraAlertSnapshotRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraAlertSnapshotStore}: the JSON round trip, the once-a-night confirm, the
 * hold, and that no database failure ever escapes.
 */
@ExtendWith(MockitoExtension.class)
class AuroraAlertSnapshotStoreTest {

    private static final Short ID = AuroraAlertSnapshotEntity.ONLY_ID;
    private static final Instant NOW = Instant.parse("2027-01-14T22:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2027, 1, 14);

    @Mock
    private AuroraAlertSnapshotRepository repository;
    @Mock
    private LocationRepository locationRepository;

    private AuroraAlertSnapshotStore store;

    private final LocationEntity kielder = LocationEntity.builder()
            .id(1L).name("Kielder").lat(55.23).lon(-2.58).bortleClass(2).build();
    private final LocationEntity galloway = LocationEntity.builder()
            .id(2L).name("Galloway").lat(55.0).lon(-4.4).bortleClass(2).enabled(false).build();

    @BeforeEach
    void setUp() {
        store = new AuroraAlertSnapshotStore(repository, locationRepository, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuroraStateCache.RealAlertView view() {
        List<AuroraForecastScore> scores = List.of(
                new AuroraForecastScore(kielder, 4, AlertLevel.MODERATE, 10, "Clear", "✓ Clear\n– Kp"),
                new AuroraForecastScore(galloway, 1, AlertLevel.MODERATE, 100, "Overcast", "✗ Cloud"));
        return new AuroraStateCache.RealAlertView(AlertLevel.MODERATE, Instant.parse("2027-01-14T19:30:00Z"),
                TriggerType.FORECAST_LOOKAHEAD, 5.67, 2, 1, scores);
    }

    private AuroraAlertSnapshotEntity saved() {
        ArgumentCaptor<AuroraAlertSnapshotEntity> captor = ArgumentCaptor.forClass(AuroraAlertSnapshotEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("save writes row 1 from the view, with no hold and the clock's saved_at")
    void save_writesRowOne() {
        store.save(view(), NIGHT);

        AuroraAlertSnapshotEntity row = saved();
        assertThat(row.getId()).isEqualTo(ID);
        assertThat(row.getNightDate()).isEqualTo(NIGHT);
        assertThat(row.getAlertLevel()).isEqualTo("MODERATE");
        assertThat(row.getActiveSince()).isEqualTo(Instant.parse("2027-01-14T19:30:00Z"));
        assertThat(row.getTriggerType()).isEqualTo("FORECAST_LOOKAHEAD");
        assertThat(row.getTriggerKp()).isEqualTo(5.67);
        assertThat(row.getDarkSkyCount()).isEqualTo(2);
        assertThat(row.getClearCount()).isEqualTo(1);
        assertThat(row.getHeldLevel()).isNull();
        assertThat(row.getHeldTrigger()).isNull();
        assertThat(row.getSavedAt()).isEqualTo(NOW);
        assertThat(row.getScores()).contains("\"locationId\":1").contains("Clear");
    }

    @Test
    @DisplayName("save then load round-trips the scores, keeps a disabled location, drops a deleted one")
    void saveThenLoad_roundTrips_droppingAMissingLocation() {
        store.save(view(), NIGHT);
        AuroraAlertSnapshotEntity row = saved();
        row.setHeldLevel("QUIET");
        row.setHeldTrigger("REALTIME");
        when(repository.findById(ID)).thenReturn(Optional.of(row));
        // Galloway (id 2) was deleted since; Kielder is still there.
        when(locationRepository.findAllById(any())).thenReturn(List.of(kielder));

        AuroraAlertSnapshotStore.Snapshot loaded = store.load().orElseThrow();

        assertThat(loaded.nightDate()).isEqualTo(NIGHT);
        assertThat(loaded.heldLevel()).isEqualTo(AlertLevel.QUIET);
        assertThat(loaded.heldTrigger()).isEqualTo(TriggerType.REALTIME);
        AuroraStateCache.RealAlertView v = loaded.view();
        assertThat(v.level()).isEqualTo(AlertLevel.MODERATE);
        assertThat(v.activeSince()).isEqualTo(Instant.parse("2027-01-14T19:30:00Z"));
        assertThat(v.triggerType()).isEqualTo(TriggerType.FORECAST_LOOKAHEAD);
        assertThat(v.triggerKp()).isEqualTo(5.67);
        assertThat(v.darkSkyCount()).isEqualTo(2);
        assertThat(v.clearCount()).isEqualTo(1);
        assertThat(v.scores()).containsExactly(
                new AuroraForecastScore(kielder, 4, AlertLevel.MODERATE, 10, "Clear", "✓ Clear\n– Kp"));
    }

    @Test
    @DisplayName("load keeps a disabled location's score, as the in-memory cache would")
    void load_keepsADisabledLocation() {
        store.save(view(), NIGHT);
        AuroraAlertSnapshotEntity row = saved();
        when(repository.findById(ID)).thenReturn(Optional.of(row));
        when(locationRepository.findAllById(any())).thenReturn(List.of(kielder, galloway));

        assertThat(store.load().orElseThrow().view().scores()).hasSize(2);
    }

    @Test
    @DisplayName("load restores an alert with no trigger and no hold")
    void load_nullableColumnsNull() {
        AuroraAlertSnapshotEntity row = new AuroraAlertSnapshotEntity();
        row.setNightDate(NIGHT);
        row.setAlertLevel("STRONG");
        row.setActiveSince(NOW);
        row.setScores("[]");
        row.setSavedAt(NOW);
        when(repository.findById(ID)).thenReturn(Optional.of(row));
        when(locationRepository.findAllById(any())).thenReturn(List.of());

        AuroraStateCache.RealAlertView v = store.load().orElseThrow().view();

        assertThat(v.level()).isEqualTo(AlertLevel.STRONG);
        assertThat(v.triggerType()).isNull();
        assertThat(v.triggerKp()).isNull();
        assertThat(v.clearCount()).isNull();
        assertThat(v.scores()).isEmpty();
        assertThat(store.load().orElseThrow().heldLevel()).isNull();
    }

    @Test
    @DisplayName("load is empty when there is no row")
    void load_noRow_isEmpty() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("load is empty, not an exception, when the database fails")
    void load_databaseFails_isEmpty() {
        when(repository.findById(ID)).thenThrow(new IllegalStateException("db down"));

        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("load is empty when the stored scores are not valid JSON")
    void load_corruptScores_isEmpty() {
        AuroraAlertSnapshotEntity row = new AuroraAlertSnapshotEntity();
        row.setNightDate(NIGHT);
        row.setAlertLevel("MODERATE");
        row.setActiveSince(NOW);
        row.setScores("{not json");
        row.setSavedAt(NOW);
        when(repository.findById(ID)).thenReturn(Optional.of(row));

        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("save swallows a database failure")
    void save_databaseFails_isSwallowed() {
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

        store.save(view(), NIGHT);

        verify(repository).save(any());
    }

    @Test
    @DisplayName("confirm moves the night forward with one UPDATE, then skips the database for the same night")
    void confirm_onceANight() {
        when(repository.confirmNight(NIGHT)).thenReturn(1);

        store.confirm(NIGHT, () -> Optional.of(view()));
        store.confirm(NIGHT, () -> Optional.of(view()));
        store.confirm(NIGHT, () -> Optional.of(view()));

        verify(repository, times(1)).confirmNight(NIGHT);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("confirm for a night already stored (UPDATE matched nothing) writes nothing and reads no view")
    void confirm_sameNight_writesNothing() {
        when(repository.confirmNight(NIGHT)).thenReturn(0);
        when(repository.existsById(ID)).thenReturn(true);

        store.confirm(NIGHT, () -> {
            throw new AssertionError("the view must not be read");
        });

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("confirm upserts from the view when row 1 is missing, repairing an earlier failed save")
    void confirm_rowMissing_upsertsFromTheView() {
        when(repository.confirmNight(NIGHT)).thenReturn(0);
        when(repository.existsById(ID)).thenReturn(false);

        store.confirm(NIGHT, () -> Optional.of(view()));

        assertThat(saved().getNightDate()).isEqualTo(NIGHT);
    }

    @Test
    @DisplayName("confirm with a missing row and no standing alert (a simulation landed) writes nothing")
    void confirm_rowMissingNoView_writesNothing() {
        when(repository.confirmNight(NIGHT)).thenReturn(0);
        when(repository.existsById(ID)).thenReturn(false);

        store.confirm(NIGHT, Optional::empty);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a hold makes the next confirm of the same night hit the database again, to drop the hold")
    void hold_thenConfirm_dropsTheHold() {
        when(repository.confirmNight(NIGHT)).thenReturn(1);
        store.confirm(NIGHT, Optional::empty);

        store.hold(AlertLevel.QUIET, TriggerType.REALTIME);
        store.confirm(NIGHT, Optional::empty);

        verify(repository).setHold("QUIET", "REALTIME");
        verify(repository, times(2)).confirmNight(NIGHT);
    }

    @Test
    @DisplayName("hold with no row is a no-op (the UPDATE matches nothing) and never throws")
    void hold_noRow_isHarmless() {
        when(repository.setHold(anyString(), anyString())).thenReturn(0);

        store.hold(AlertLevel.MINOR, TriggerType.FORECAST_LOOKAHEAD);

        verify(repository).setHold("MINOR", "FORECAST_LOOKAHEAD");
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("confirm and hold swallow a database failure")
    void confirmAndHold_databaseFails_areSwallowed() {
        when(repository.confirmNight(NIGHT)).thenThrow(new IllegalStateException("db down"));
        when(repository.setHold(anyString(), anyString())).thenThrow(new IllegalStateException("db down"));

        store.confirm(NIGHT, Optional::empty);
        store.hold(AlertLevel.QUIET, TriggerType.REALTIME);

        verify(repository).confirmNight(NIGHT);
        verify(repository).setHold("QUIET", "REALTIME");
    }

    @Test
    @DisplayName("clear deletes row 1 when it exists")
    void clear_deletesTheRow() {
        when(repository.existsById(ID)).thenReturn(true);

        store.clear();

        verify(repository).deleteById(ID);
    }

    @Test
    @DisplayName("clear does nothing when there is no row")
    void clear_noRow_doesNothing() {
        when(repository.existsById(ID)).thenReturn(false);

        store.clear();

        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("clear swallows a database failure")
    void clear_databaseFails_isSwallowed() {
        when(repository.existsById(ID)).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(repository).deleteById(ID);

        store.clear();

        verify(repository).deleteById(ID);
    }

    @Test
    @DisplayName("load asks for exactly the stored location ids")
    void load_asksForTheStoredIds() {
        store.save(view(), NIGHT);
        AuroraAlertSnapshotEntity stored = saved();
        when(repository.findById(ID)).thenReturn(Optional.of(stored));
        when(locationRepository.findAllById(anyIterable())).thenReturn(List.of(kielder, galloway));

        store.load();

        verify(locationRepository).findAllById(List.of(1L, 2L));
    }
}
