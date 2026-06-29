package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TravelDayEntity;
import com.gregochr.goldenhour.model.TravelDayRequest;
import com.gregochr.goldenhour.model.TravelDayResponse;
import com.gregochr.goldenhour.repository.TravelDayRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TravelDayService} — containment check, validation, and CRUD.
 */
@ExtendWith(MockitoExtension.class)
class TravelDayServiceTest {

    @Mock
    private TravelDayRepository repository;

    @InjectMocks
    private TravelDayService service;

    @Test
    @DisplayName("isTravelDay delegates to the repository containment query")
    void isTravelDayDelegates() {
        LocalDate date = LocalDate.of(2026, 7, 1);
        when(repository.existsForDate(date)).thenReturn(true);

        assertThat(service.isTravelDay(date)).isTrue();
        verify(repository).existsForDate(date);
    }

    @Test
    @DisplayName("list maps current/future ranges to responses (repository supplies the order)")
    void listMapsEntities() {
        TravelDayEntity entity = TravelDayEntity.builder()
                .id(7L)
                .startDate(LocalDate.of(2026, 7, 1))
                .endDate(LocalDate.of(2026, 7, 3))
                .note("London")
                .build();
        when(repository.findByEndDateGreaterThanEqualOrderByStartDateDesc(any()))
                .thenReturn(List.of(entity));

        List<TravelDayResponse> result = service.list();

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(7L);
            assertThat(r.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(r.endDate()).isEqualTo(LocalDate.of(2026, 7, 3));
            assertThat(r.note()).isEqualTo("London");
        });
    }

    @Test
    @DisplayName("list filters by end date >= today (Europe/London)")
    void listFiltersPastRangesByToday() {
        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        when(repository.findByEndDateGreaterThanEqualOrderByStartDateDesc(cutoff.capture()))
                .thenReturn(List.of());

        service.list();

        assertThat(cutoff.getValue())
                .isEqualTo(LocalDate.now(java.time.ZoneId.of("Europe/London")));
    }

    @Test
    @DisplayName("add persists a valid range and returns its view")
    void addPersistsValidRange() {
        TravelDayRequest request = new TravelDayRequest(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), "London");
        when(repository.save(any(TravelDayEntity.class))).thenAnswer(inv -> {
            TravelDayEntity e = inv.getArgument(0);
            e.setId(99L);
            return e;
        });

        TravelDayResponse result = service.add(request);

        ArgumentCaptor<TravelDayEntity> captor = ArgumentCaptor.forClass(TravelDayEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStartDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().getEndDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(result.id()).isEqualTo(99L);
        assertThat(result.note()).isEqualTo("London");
    }

    @Test
    @DisplayName("add rejects a range whose end precedes its start")
    void addRejectsInvertedRange() {
        TravelDayRequest request = new TravelDayRequest(
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 1), null);

        assertThatThrownBy(() -> service.add(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endDate");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("add rejects missing dates")
    void addRejectsMissingDates() {
        assertThatThrownBy(() -> service.add(new TravelDayRequest(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("delete removes an existing range")
    void deleteRemovesExisting() {
        when(repository.existsById(5L)).thenReturn(true);

        service.delete(5L);

        verify(repository).deleteById(5L);
    }

    @Test
    @DisplayName("delete throws when the range does not exist")
    void deleteThrowsWhenMissing() {
        when(repository.existsById(5L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(NoSuchElementException.class);
        verify(repository, never()).deleteById(any());
    }
}
