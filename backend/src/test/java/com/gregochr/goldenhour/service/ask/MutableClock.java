package com.gregochr.goldenhour.service.ask;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock a test advances by hand, safe to read from several threads. */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    void advance(Duration by) {
        now.updateAndGet(i -> i.plus(by));
    }

    void set(Instant to) {
        now.set(to);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now.get(), zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
