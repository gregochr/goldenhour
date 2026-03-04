package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ActualOutcome;
import com.gregochr.goldenhour.repository.ActualOutcomeRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutcomeService}.
 */
@ExtendWith(MockitoExtension.class)
class OutcomeServiceTest {

    @Mock
    private ActualOutcomeRepository repository;

    @Mock
    private LocationRepository locationRepository;

    @InjectMocks
    private OutcomeService outcomeService;

    private static final LocationEntity DURHAM = LocationEntity.builder()
            .id(1L).name("Durham UK").lat(54.7753).lon(-1.5849).build();

    @Test
    @DisplayName("record() maps DTO fields to entity and saves")
    void record_mapsFieldsToEntity_andSaves() {
        LocalDate date = LocalDate.of(2026, 2, 20);
        ActualOutcome outcome = new ActualOutcome(
                54.7753, -1.5849, "Durham UK", date, TargetType.SUNSET,
                true, 68, 75, "Beautiful warm light.");

        when(locationRepository.findByName(eq("Durham UK"))).thenReturn(Optional.of(DURHAM));
        ActualOutcomeEntity savedEntity = ActualOutcomeEntity.builder()
                .id(1L).location(DURHAM).build();
        when(repository.save(any())).thenReturn(savedEntity);

        ActualOutcomeEntity result = outcomeService.record(outcome);

        ArgumentCaptor<ActualOutcomeEntity> captor = ArgumentCaptor.forClass(ActualOutcomeEntity.class);
        verify(repository).save(captor.capture());
        ActualOutcomeEntity captured = captor.getValue();

        assertThat(captured.getLocationLat()).isEqualByComparingTo(BigDecimal.valueOf(54.7753));
        assertThat(captured.getLocationLon()).isEqualByComparingTo(BigDecimal.valueOf(-1.5849));
        assertThat(captured.getLocationName()).isEqualTo("Durham UK");
        assertThat(captured.getOutcomeDate()).isEqualTo(date);
        assertThat(captured.getTargetType()).isEqualTo(TargetType.SUNSET);
        assertThat(captured.getWentOut()).isTrue();
        assertThat(captured.getFierySkyActual()).isEqualTo(68);
        assertThat(captured.getGoldenHourActual()).isEqualTo(75);
        assertThat(captured.getNotes()).isEqualTo("Beautiful warm light.");
        assertThat(captured.getRecordedAt()).isNotNull();
        assertThat(result).isSameAs(savedEntity);
    }

    @Test
    @DisplayName("record() throws NoSuchElementException when location name is not found")
    void record_unknownLocation_throwsNoSuchElementException() {
        ActualOutcome outcome = new ActualOutcome(
                54.7753, -1.5849, "Unknown Place", LocalDate.of(2026, 2, 20),
                TargetType.SUNSET, true, 68, 75, null);

        when(locationRepository.findByName(eq("Unknown Place"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> outcomeService.record(outcome))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("record() throws IllegalArgumentException when fierySkyActual is out of range")
    void record_invalidFierySkyScore_throwsIllegalArgumentException() {
        ActualOutcome outcome = new ActualOutcome(
                54.7753, -1.5849, "Durham UK", LocalDate.of(2026, 2, 20),
                TargetType.SUNSET, true, 150, null, null);

        assertThatThrownBy(() -> outcomeService.record(outcome))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fierySkyActual");
    }

    @Test
    @DisplayName("record() throws IllegalArgumentException when goldenHourActual is out of range")
    void record_invalidGoldenHourScore_throwsIllegalArgumentException() {
        ActualOutcome outcome = new ActualOutcome(
                54.7753, -1.5849, "Durham UK", LocalDate.of(2026, 2, 20),
                TargetType.SUNSET, true, null, -1, null);

        assertThatThrownBy(() -> outcomeService.record(outcome))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("goldenHourActual");
    }
}
