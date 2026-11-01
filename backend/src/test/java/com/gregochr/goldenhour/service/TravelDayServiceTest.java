package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TravelDayEntity;
import com.gregochr.goldenhour.model.TravelDayRequest;
import com.gregochr.goldenhour.model.TravelDayResponse;
import com.gregochr.goldenhour.repository.TravelDayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

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

    @Mock
    private ServedBriefingEpoch servedEpoch;

    private TravelDayService service;

    @BeforeEach
    void setUp() {
        // 23:30 UTC on 30 Jun is 00:30 BST on 1 Jul: UK "today" is 2026-07-01
        Clock clock = Clock.fixed(Instant.parse("2026-06-30T23:30:00Z"), ZoneOffset.UTC);
        service = new TravelDayService(repository, servedEpoch, clock);
    }

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
    @DisplayName("list filters by end date >= today (UK civil date from the injected clock)")
    void listFiltersPastRangesByToday() {
        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        when(repository.findByEndDateGreaterThanEqualOrderByStartDateDesc(cutoff.capture()))
                .thenReturn(List.of());

        service.list();

        assertThat(cutoff.getValue())
                .isEqualTo(LocalDate.of(2026, 7, 1));
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
    @DisplayName("listAll returns every range, past included, without the today filter")
    void listAllReturnsEverything() {
        TravelDayEntity past = TravelDayEntity.builder().id(1L)
                .startDate(LocalDate.of(2026, 5, 1)).endDate(LocalDate.of(2026, 5, 2)).build();
        when(repository.findAll(Sort.by(Sort.Direction.DESC, "startDate"))).thenReturn(List.of(past));

        assertThat(service.listAll()).extracting(TravelDayResponse::id).containsExactly(1L);
        verify(repository, never()).findByEndDateGreaterThanEqualOrderByStartDateDesc(any());
    }

    @Test
    @DisplayName("add rejects a note over 200 characters and accepts exactly 200")
    void addRejectsLongNote() {
        LocalDate d = LocalDate.of(2026, 7, 1);
        assertThatThrownBy(() -> service.add(new TravelDayRequest(d, d, "x".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("note");
        verify(repository, never()).save(any());

        when(repository.save(any(TravelDayEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service.add(new TravelDayRequest(d, d, "x".repeat(200)));
        verify(repository).save(any(TravelDayEntity.class));
        verify(servedEpoch).bump();
    }

    @Test
    @DisplayName("update replaces dates and note, bumps the epoch and returns the view")
    void updateReplacesFields() {
        TravelDayEntity existing = TravelDayEntity.builder().id(4L)
                .startDate(LocalDate.of(2026, 7, 1)).endDate(LocalDate.of(2026, 7, 2)).note("old").build();
        when(repository.findById(4L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        TravelDayResponse result = service.update(4L, new TravelDayRequest(
                LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 6), "new"));

        assertThat(result.startDate()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(result.endDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(result.note()).isEqualTo("new");
        verify(servedEpoch).bump();
    }

    @Test
    @DisplayName("update throws NoSuchElementException when the range is missing")
    void updateThrowsWhenMissing() {
        when(repository.findById(4L)).thenReturn(Optional.empty());
        LocalDate d = LocalDate.of(2026, 7, 1);

        assertThatThrownBy(() -> service.update(4L, new TravelDayRequest(d, d, null)))
                .isInstanceOf(NoSuchElementException.class);
        verify(repository, never()).save(any());
        verify(servedEpoch, never()).bump();
    }

    @Test
    @DisplayName("update shares add's validation: missing dates, inverted range, long note")
    void updateValidates() {
        LocalDate a = LocalDate.of(2026, 7, 3);
        LocalDate b = LocalDate.of(2026, 7, 1);
        assertThatThrownBy(() -> service.update(4L, new TravelDayRequest(null, a, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(4L, new TravelDayRequest(a, b, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endDate");
        assertThatThrownBy(() -> service.update(4L, new TravelDayRequest(b, a, "x".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("note");
        verify(repository, never()).findById(any());
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
