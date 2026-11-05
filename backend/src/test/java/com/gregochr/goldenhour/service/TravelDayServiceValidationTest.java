package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.TravelDayEntity;
import com.gregochr.goldenhour.model.TravelDayRequest;
import com.gregochr.goldenhour.model.TravelDayResponse;
import com.gregochr.goldenhour.repository.TravelDayRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The request validation shared by {@link TravelDayService#add} and update, and an add without a note.
 */
class TravelDayServiceValidationTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 3);

    private TravelDayRepository repository;
    private TravelDayService service;

    @BeforeEach
    void setUp() {
        repository = mock(TravelDayRepository.class);
        service = new TravelDayService(repository, mock(ServedBriefingEpoch.class),
                Clock.fixed(Instant.parse("2026-06-30T23:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a null request, or one missing either date, is refused before anything is saved")
    void add_missingDates_refused() {
        assertThatThrownBy(() -> service.add(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startDate and endDate are required");
        assertThatThrownBy(() -> service.add(new TravelDayRequest(null, END, "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("startDate and endDate are required");
        assertThatThrownBy(() -> service.add(new TravelDayRequest(START, null, "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("startDate and endDate are required");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a range without a note is saved and returned with a null note")
    void add_withoutNote_saved() {
        TravelDayEntity saved = TravelDayEntity.builder().id(5L).startDate(START).endDate(END).note(null).build();
        when(repository.save(org.mockito.ArgumentMatchers.argThat(e -> e.getStartDate().equals(START)
                && e.getEndDate().equals(END) && e.getNote() == null))).thenReturn(saved);

        TravelDayResponse response = service.add(new TravelDayRequest(START, END, null));

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.note()).isNull();
    }
}
