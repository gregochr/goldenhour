package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.MeteorShowerPeakEntity;
import com.gregochr.goldenhour.repository.MeteorShowerPeakRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;

import static com.gregochr.goldenhour.service.MeteorPeakCalendars.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeteorPeakCalendar} over a mocked repository: the lookup, fallback and range code is real.
 */
@ExtendWith(MockitoExtension.class)
class MeteorPeakCalendarTest {

    @Mock
    private MeteorShowerPeakRepository repository;

    @Mock
    private ApplicationEventPublisher publisher;

    private MeteorPeakCalendar calendar;

    @BeforeEach
    void setUp() {
        calendar = new MeteorPeakCalendar(repository, publisher);
    }

    private static MeteorHotTopicStrategy.Shower shower(String code) {
        return MeteorHotTopicStrategy.SHOWERS.stream()
                .filter(s -> s.code().equals(code)).findFirst().orElseThrow();
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private List<LocalDate> nights(boolean majorOnly, LocalDate from, LocalDate to) {
        return calendar.peakNightsIn(from, to, majorOnly).stream()
                .map(MeteorPeakCalendar.ShowerNight::night).toList();
    }

    @Test
    @DisplayName("with no row for the year, the code's MonthDay and catalogue ZHR apply")
    void noRow_fallbackApplies() {
        when(repository.findAll()).thenReturn(List.of());
        calendar.load();

        assertThat(calendar.peakNight(shower("PER"), 2026)).isEqualTo(d(2026, 8, 12));
        assertThat(calendar.zhr(shower("PER"), 2026)).isEqualTo(100);
        assertThat(calendar.peakNight(shower("ORI"), 2030)).isEqualTo(d(2030, 10, 20));
    }

    @Test
    @DisplayName("a row overrides the fallback for its year only")
    void row_overridesFallback() {
        when(repository.findAll()).thenReturn(List.of(
                row("ORI", 2027, d(2027, 10, 21), 22),
                row("NTA", 2027, d(2027, 11, 13), 6)));
        calendar.load();

        assertThat(calendar.peakNight(shower("ORI"), 2027)).isEqualTo(d(2027, 10, 21));
        assertThat(calendar.zhr(shower("ORI"), 2027)).isEqualTo(22);
        assertThat(calendar.peakNight(shower("NTA"), 2027)).isEqualTo(d(2027, 11, 13));
        assertThat(calendar.zhr(shower("NTA"), 2027)).isEqualTo(6);
        assertThat(calendar.peakNight(shower("ORI"), 2026)).isEqualTo(d(2026, 10, 20));
        assertThat(calendar.zhr(shower("ORI"), 2026)).isEqualTo(20);
    }

    @Test
    @DisplayName("a failed load keeps the code fallback and does not throw")
    void failedLoad_keepsFallback() {
        when(repository.findAll()).thenThrow(new IllegalStateException("table missing"));

        calendar.load();

        assertThat(calendar.peakNight(shower("GEM"), 2026)).isEqualTo(d(2026, 12, 14));
        assertThat(calendar.zhr(shower("GEM"), 2026)).isEqualTo(150);
    }

    @Test
    @DisplayName("reload swaps the snapshot and publishes the reload event")
    void reload_swapsSnapshotAndPublishes() {
        when(repository.findAll()).thenReturn(List.of(), List.of(row("GEM", 2026, d(2026, 12, 13), 140)));
        calendar.load();
        assertThat(calendar.peakNight(shower("GEM"), 2026)).isEqualTo(d(2026, 12, 14));

        calendar.reload();

        assertThat(calendar.peakNight(shower("GEM"), 2026)).isEqualTo(d(2026, 12, 13));
        assertThat(calendar.zhr(shower("GEM"), 2026)).isEqualTo(140);
        verify(publisher).publishEvent(new MeteorPeakCalendarReloaded());
    }

    @Test
    @DisplayName("a failed reload keeps the held snapshot and publishes nothing")
    void failedReload_keepsSnapshot_noEvent() {
        when(repository.findAll())
                .thenReturn(List.of(row("GEM", 2026, d(2026, 12, 13), 150)))
                .thenThrow(new IllegalStateException("db down"));
        calendar.load();

        calendar.reload();

        assertThat(calendar.peakNight(shower("GEM"), 2026)).isEqualTo(d(2026, 12, 13));
        verify(publisher, never()).publishEvent(new MeteorPeakCalendarReloaded());
    }

    @Test
    @DisplayName("a window across the year boundary finds December and January peaks, majors only when asked")
    void peakNightsIn_acrossYearBoundary() {
        MeteorShowerPeakEntity gem = row("GEM", 2026, d(2026, 12, 13), 150);
        when(repository.findAll()).thenReturn(List.of(gem, row("QUA", 2027, d(2027, 1, 3), 80)));
        calendar.load();

        assertThat(nights(true, d(2026, 12, 10), d(2027, 1, 5)))
                .containsExactly(d(2026, 12, 13), d(2027, 1, 3));
        assertThat(nights(false, d(2026, 12, 10), d(2027, 1, 5)))
                .containsExactly(d(2026, 12, 13), d(2026, 12, 22), d(2027, 1, 3));
        assertThat(calendar.peakNightsIn(d(2026, 12, 10), d(2027, 1, 5), true))
                .extracting(MeteorPeakCalendar.ShowerNight::zhr).containsExactly(150, 80);
    }

    @Test
    @DisplayName("a stored night that falls in the previous calendar year's December is still found")
    void peakNightsIn_nightOutsideItsOwnYear() {
        when(repository.findAll()).thenReturn(List.of(row("QUA", 2027, d(2026, 12, 31), 80)));
        calendar.load();

        assertThat(nights(true, d(2026, 12, 30), d(2026, 12, 31))).containsExactly(d(2026, 12, 31));
    }

    @Test
    @DisplayName("a major peak (Leonids, 17 Nov) is returned and a minor one (12 Nov) is not")
    void peakNightsIn_majorOnly() {
        when(repository.findAll()).thenReturn(List.of());
        calendar.load();

        assertThat(nights(true, d(2026, 11, 12), d(2026, 11, 17))).containsExactly(d(2026, 11, 17));
        assertThat(nights(true, d(2026, 5, 6), d(2026, 5, 6))).isEmpty();
        assertThat(nights(false, d(2026, 11, 12), d(2026, 11, 17)))
                .containsExactly(d(2026, 11, 12), d(2026, 11, 17));
    }

    @Test
    @DisplayName("only the nights that fall on a shower peak are returned, none in a quiet fortnight")
    void peakNightsIn_quietWindowIsEmpty() {
        when(repository.findAll()).thenReturn(List.of());
        calendar.load();

        assertThat(nights(true, d(2026, 8, 10), d(2026, 8, 13))).containsExactly(d(2026, 8, 12));
        assertThat(nights(true, d(2026, 6, 17), d(2026, 6, 19))).isEmpty();
    }

    @Test
    @DisplayName("results are sorted by night and carry the shower")
    void peakNightsIn_sortedWithShower() {
        when(repository.findAll()).thenReturn(List.of());
        calendar.load();

        List<MeteorPeakCalendar.ShowerNight> found = calendar.peakNightsIn(d(2026, 10, 1), d(2026, 12, 31), false);

        assertThat(found).extracting(n -> n.shower().code())
                .containsExactly("DRA", "ORI", "STA", "NTA", "LEO", "GEM", "URS");
        assertThat(found).extracting(MeteorPeakCalendar.ShowerNight::night).isSorted();
    }
}
