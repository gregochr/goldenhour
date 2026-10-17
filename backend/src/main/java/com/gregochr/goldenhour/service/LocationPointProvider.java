package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.LocationPoint;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The coordinates and elevations of the enabled locations, by id, memoised for
 * {@value #TTL_SECONDS} seconds.
 *
 * <p>The neighbour guard judges every Plan-tab serve, and the serve path drops coordinates before
 * the assembler sees a slot, so the guard needs a second source. {@code LocationService.findAllEnabled}
 * is uncached, hence a memo rather than a roster read per serve: at most one narrow projection query
 * a minute. A location moved or disabled within the minute is judged on its old point once.
 *
 * <p><b>The memo is timed on a monotonic source, not the application clock.</b> The {@code Clock}
 * bean is rewind-aware: an admin's rewound GET answers with a past instant, which would make a fresh
 * memo look expired (or an old one look fresh) for everyone sharing this singleton. {@code nanoTime}
 * cannot be moved by a request.
 *
 * <p><b>A failed read is memoised too</b>, as an empty map, for the same minute, and logged once at
 * WARN: a broken projection would otherwise be re-queried and re-logged on every serve. An empty
 * map is how the caller knows to skip the guard; no real roster is empty.
 */
@Component
public class LocationPointProvider {

    private static final Logger LOG = LoggerFactory.getLogger(LocationPointProvider.class);

    /** How long a read, successful or failed, is reused, in seconds. */
    static final long TTL_SECONDS = 60L;

    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(TTL_SECONDS);

    private final LocationRepository locationRepository;
    private final LongSupplier nanoClock;
    private final AtomicReference<Memo> memo = new AtomicReference<>();

    /** A read and the monotonic instant it was taken. */
    private record Memo(long readAtNanos, Map<Long, LocationPoint> points) {
    }

    /**
     * Creates the provider on {@link System#nanoTime}.
     *
     * @param locationRepository the location repository
     */
    @Autowired
    public LocationPointProvider(LocationRepository locationRepository) {
        this(locationRepository, System::nanoTime);
    }

    /**
     * Creates the provider on a chosen monotonic source (tests).
     *
     * @param locationRepository the location repository
     * @param nanoClock          nanoseconds from an arbitrary origin, monotonic
     */
    LocationPointProvider(LocationRepository locationRepository, LongSupplier nanoClock) {
        this.locationRepository = locationRepository;
        this.nanoClock = nanoClock;
    }

    /**
     * The enabled locations' points keyed by id, from the memo while it is under a minute old. Empty
     * when the last read failed.
     *
     * @return an unmodifiable map; never null
     */
    public Map<Long, LocationPoint> points() {
        long now = nanoClock.getAsLong();
        Memo current = memo.get();
        if (current != null && now - current.readAtNanos() < TTL_NANOS) {
            return current.points();
        }
        Map<Long, LocationPoint> fresh = new HashMap<>();
        try {
            for (LocationPoint point : locationRepository.findEnabledPoints()) {
                fresh.put(point.id(), point);
            }
        } catch (RuntimeException e) {
            LOG.warn("Neighbour guard could not read the location points; the guard is skipped for the next"
                    + " {} seconds: {}", TTL_SECONDS, e.getMessage());
            fresh.clear();
        }
        Memo next = new Memo(now, Map.copyOf(fresh));
        memo.set(next);
        return next.points();
    }
}
