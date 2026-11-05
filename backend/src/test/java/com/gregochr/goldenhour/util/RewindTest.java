package com.gregochr.goldenhour.util;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link Rewind}, the per-thread rewind instant holder. */
class RewindTest {

    @AfterEach
    void clearThread() {
        Rewind.clear();
    }

    @Test
    @DisplayName("is inactive by default")
    void inactiveByDefault() {
        assertThat(Rewind.isActive()).isFalse();
        assertThat(Rewind.current()).isNull();
    }

    @Test
    @DisplayName("set stores the instant and marks the thread active")
    void setStoresInstant() {
        Instant at = Instant.parse("2026-11-01T06:30:00Z");

        Rewind.set(at);

        assertThat(Rewind.isActive()).isTrue();
        assertThat(Rewind.current()).isEqualTo(at);
    }

    @Test
    @DisplayName("set(null) clears a previously set instant")
    void setNullClears() {
        Rewind.set(Instant.parse("2026-11-01T06:30:00Z"));

        Rewind.set(null);

        assertThat(Rewind.isActive()).isFalse();
        assertThat(Rewind.current()).isNull();
    }

    @Test
    @DisplayName("clear removes the instant")
    void clearRemoves() {
        Rewind.set(Instant.parse("2026-11-01T06:30:00Z"));

        Rewind.clear();

        assertThat(Rewind.isActive()).isFalse();
    }

    @Test
    @DisplayName("the instant is thread-local")
    void threadLocal() throws InterruptedException {
        Rewind.set(Instant.parse("2026-11-01T06:30:00Z"));
        AtomicBoolean otherActive = new AtomicBoolean(true);

        Thread t = new Thread(() -> otherActive.set(Rewind.isActive()));
        t.start();
        t.join();

        assertThat(otherActive.get()).isFalse();
    }
}
