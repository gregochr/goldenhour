package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.util.Rewind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RewindAwareClock} — the system clock until a thread carries a {@link Rewind}, then that
 * instant, through every zoned copy.
 */
class RewindAwareClockTest {

    private static final Instant SYSTEM = Instant.parse("2026-10-04T09:30:00Z");
    private static final Instant REWOUND = Instant.parse("2026-10-04T05:15:00Z");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final RewindAwareClock clock = new RewindAwareClock(Clock.fixed(SYSTEM, ZoneOffset.UTC));

    @AfterEach
    void clearRewind() {
        Rewind.clear();
    }

    @Test
    @DisplayName("with no rewind set it answers with the system clock")
    void noRewind_system() {
        assertThat(clock.instant()).isEqualTo(SYSTEM);
        assertThat(clock.millis()).isEqualTo(SYSTEM.toEpochMilli());
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("with a rewind set it answers with the rewound instant")
    void rewind_rewound() {
        Rewind.set(REWOUND);
        assertThat(clock.instant()).isEqualTo(REWOUND);
        assertThat(clock.millis()).isEqualTo(REWOUND.toEpochMilli());
    }

    @Test
    @DisplayName("a zoned copy keeps the rewind — LocalDate.now(clock.withZone(LONDON)) reads the rewound day")
    void withZone_keepsRewind() {
        // 2026-10-03T23:30Z is 00:30 BST on the 4th: the UK date is the 4th, the UTC date the 3rd.
        Rewind.set(Instant.parse("2026-10-03T23:30:00Z"));
        Clock london = clock.withZone(LONDON);
        assertThat(london).isInstanceOf(RewindAwareClock.class);
        assertThat(london.getZone()).isEqualTo(LONDON);
        assertThat(LocalDate.now(london)).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(LocalDateTime.now(clock.withZone(ZoneOffset.UTC)))
                .isEqualTo(LocalDateTime.of(2026, 10, 3, 23, 30));
    }

    @Test
    @DisplayName("withZone for the clock's own zone returns the same instance")
    void withZone_sameZone_sameInstance() {
        assertThat(clock.withZone(ZoneOffset.UTC)).isSameAs(clock);
    }

    @Test
    @DisplayName("clearing the rewind returns the clock to the system instant")
    void clear_restoresSystem() {
        Rewind.set(REWOUND);
        Rewind.clear();
        assertThat(clock.instant()).isEqualTo(SYSTEM);
        assertThat(Rewind.isActive()).isFalse();
    }

    @Test
    @DisplayName("a rewind set on one thread is invisible to another")
    void rewind_isThreadLocal() throws Exception {
        Rewind.set(REWOUND);
        Instant[] seen = new Instant[1];
        Thread other = new Thread(() -> seen[0] = clock.instant());
        other.start();
        other.join();
        assertThat(seen[0]).isEqualTo(SYSTEM);
    }
}
