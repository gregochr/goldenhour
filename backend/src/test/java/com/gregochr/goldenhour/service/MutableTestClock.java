package com.gregochr.goldenhour.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock a test moves by hand, so time-based rules (eviction after a keep) are exercised by advancing
 * it and never by sleeping.
 */
final class MutableTestClock extends Clock {

    private final AtomicReference<Instant> now;

    MutableTestClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    /** Moves the clock forward. */
    void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
