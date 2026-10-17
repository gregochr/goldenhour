package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.LocationPoint;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The coordinates and elevations of the enabled locations, by id, memoised for
 * {@value #TTL_SECONDS} seconds on the injected clock.
 *
 * <p>The neighbour guard judges every Plan-tab serve, and the serve path drops coordinates before
 * the assembler sees a slot, so the guard needs a second source. {@code LocationService.findAllEnabled}
 * is uncached, hence a memo rather than a roster read per serve: at most one narrow projection query
 * a minute. A location moved or disabled within the minute is judged on its old point once. A failed
 * read propagates (nothing is cached for it) and the caller fails open.
 */
@Component
public class LocationPointProvider {

    /** How long a read is reused, in seconds. */
    static final long TTL_SECONDS = 60L;

    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);

    private final LocationRepository locationRepository;
    private final Clock clock;
    private final AtomicReference<Memo> memo = new AtomicReference<>();

    /** A read and the instant it was taken. */
    private record Memo(Instant readAt, Map<Long, LocationPoint> points) {
    }

    /**
     * Creates the provider.
     *
     * @param locationRepository the location repository
     * @param clock              the application clock the memo is timed on
     */
    public LocationPointProvider(LocationRepository locationRepository, Clock clock) {
        this.locationRepository = locationRepository;
        this.clock = clock;
    }

    /**
     * The enabled locations' points keyed by id, from the memo while it is under a minute old.
     *
     * @return an unmodifiable map; never null
     */
    public Map<Long, LocationPoint> points() {
        Instant now = clock.instant();
        Memo current = memo.get();
        if (current != null && now.isBefore(current.readAt().plus(TTL))) {
            return current.points();
        }
        Map<Long, LocationPoint> fresh = new HashMap<>();
        for (LocationPoint point : locationRepository.findEnabledPoints()) {
            fresh.put(point.id(), point);
        }
        Memo next = new Memo(now, Map.copyOf(fresh));
        memo.set(next);
        return next.points();
    }
}
