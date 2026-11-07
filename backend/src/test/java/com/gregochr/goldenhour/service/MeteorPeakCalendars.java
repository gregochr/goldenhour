package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.MeteorShowerPeakEntity;
import com.gregochr.goldenhour.repository.MeteorShowerPeakRepository;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test factory for a {@link MeteorPeakCalendar} over a mocked repository. Mocking the repository is not mocking the
 * rule: the calendar's own fallback and lookup code runs for real.
 */
final class MeteorPeakCalendars {

    private MeteorPeakCalendars() {
    }

    /**
     * A calendar over an empty table, so every lookup falls back to the code's {@code SHOWERS}.
     *
     * @return the calendar, loaded
     */
    static MeteorPeakCalendar empty() {
        return over(List.of());
    }

    /**
     * A calendar over the given rows, loaded.
     *
     * @param rows the stored rows
     * @return the calendar
     */
    static MeteorPeakCalendar over(List<MeteorShowerPeakEntity> rows) {
        MeteorShowerPeakRepository repository = mock(MeteorShowerPeakRepository.class);
        when(repository.findAll()).thenReturn(rows);
        MeteorPeakCalendar calendar = new MeteorPeakCalendar(repository, mock(ApplicationEventPublisher.class));
        calendar.load();
        return calendar;
    }

    /**
     * A stored row with no maximum time.
     *
     * @param code  the shower code
     * @param year  the calendar year
     * @param night the peak night
     * @param zhr   the rate
     * @return the entity
     */
    static MeteorShowerPeakEntity row(String code, int year, LocalDate night, int zhr) {
        return new MeteorShowerPeakEntity(code, year, night, null, zhr,
                "https://www.imo.net/files/meteor-shower/cal" + year + ".pdf", null,
                Instant.parse("2026-11-07T00:00:00Z"));
    }
}
