package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.model.AlmanacEvent;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SupermoonAlmanacSource}: a supermoon is a full moon within three days of perigee, and
 * a span reaching the edge of the window is completed outwards (to a bounded length) and names its peak night.
 */
class SupermoonAlmanacSourceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 11);

    private LunarPhaseService lunar;
    private SupermoonAlmanacSource source;

    @BeforeEach
    void setUp() {
        lunar = mock(LunarPhaseService.class);
        source = new SupermoonAlmanacSource(lunar);
    }

    private void qualifying(LocalDate date, double daysFromPerigee) {
        when(lunar.isFullMoon(date)).thenReturn(true);
        when(lunar.daysFromNearestPerigee(date)).thenReturn(daysFromPerigee);
    }

    @Test
    @DisplayName("a span seen only in part of the window is completed outwards and names the night nearest perigee")
    void events_spanExtendedBeyondWindow_namesPeakNight() {
        qualifying(DAY.minusDays(2), 2.9);
        qualifying(DAY.minusDays(1), 2.0);
        qualifying(DAY, 1.0);
        qualifying(DAY.plusDays(1), 1.5);
        qualifying(DAY.plusDays(2), 2.5);

        List<AlmanacEvent> events = source.events(DAY.minusDays(1), DAY.plusDays(1));

        assertThat(events).hasSize(1);
        AlmanacEvent event = events.get(0);
        assertThat(event.startDate()).isEqualTo(DAY.minusDays(2));
        assertThat(event.endDate()).isEqualTo(DAY.plusDays(2));
        assertThat(event.type()).isEqualTo("supermoon");
        assertThat(event.title()).isEqualTo("Supermoon");
        assertThat(event.meta()).containsEntry("peakDate", DAY.toString());
    }

    @Test
    @DisplayName("a full moon more than three days from perigee is not a supermoon")
    void events_fullMoonFarFromPerigee_none() {
        when(lunar.isFullMoon(DAY)).thenReturn(true);
        when(lunar.daysFromNearestPerigee(DAY)).thenReturn(3.01);

        assertThat(source.events(DAY, DAY)).isEmpty();
    }

    @Test
    @DisplayName("a full moon exactly three days from perigee still counts")
    void events_fullMoonAtTheBoundary_counts() {
        qualifying(DAY, 3.0);

        List<AlmanacEvent> events = source.events(DAY, DAY);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).startDate()).isEqualTo(DAY);
        assertThat(events.get(0).endDate()).isEqualTo(DAY);
        assertThat(events.get(0).meta()).containsEntry("peakDate", DAY.toString());
    }

    @Test
    @DisplayName("two separate spans in one window each close and are reported in order")
    void events_twoSpans() {
        LocalDate later = DAY.plusDays(5);
        qualifying(DAY, 1.0);
        qualifying(later, 2.0);

        List<AlmanacEvent> events = source.events(DAY, later.plusDays(1));

        assertThat(events).extracting(AlmanacEvent::startDate).containsExactly(DAY, later);
    }

    @Test
    @DisplayName("completing a span never walks more than ten nights either way")
    void events_spanWalkIsBounded() {
        for (int offset = -11; offset <= 11; offset++) {
            qualifying(DAY.plusDays(offset), offset == 0 ? 0.5 : 1.0);
        }

        List<AlmanacEvent> events = source.events(DAY, DAY);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).startDate()).isEqualTo(DAY.minusDays(10));
        assertThat(events.get(0).endDate()).isEqualTo(DAY.plusDays(10));
        assertThat(events.get(0).meta()).containsEntry("peakDate", DAY.toString());
    }
}
