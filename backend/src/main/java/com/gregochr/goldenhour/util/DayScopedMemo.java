package com.gregochr.goldenhour.util;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A small in-memory memo whose entries live for one calendar day and can be evicted by key or all
 * at once, safe against the one race an ordinary {@code Map} memo loses.
 *
 * <p><b>The race it closes.</b> A memo that is evicted when its source data changes can still serve
 * stale data: a reader loads the old value, the writer commits and evicts, and only then does the
 * reader put what it loaded — leaving the old value in the memo until the day rolls. Every eviction
 * therefore bumps a generation counter, and a load may only be stored if the generation it started
 * under is still current when it finishes. The check and the store happen under the same lock as the
 * eviction, so there is no window between them.
 *
 * <p>The loader itself runs outside the lock, so a slow load never blocks readers of other keys or
 * an eviction. Two threads that miss the same key at once both load and the later store wins; both
 * loaded from the same data, so neither answer is wrong. Values may not be null — wrap an absent
 * answer in {@link java.util.Optional} or a marker.
 *
 * @param <K> key type, needs value-based {@code equals}/{@code hashCode}
 * @param <V> value type, never null
 */
public final class DayScopedMemo<K, V> {

    private final Object lock = new Object();
    private final Map<K, V> entries = new HashMap<>();
    private LocalDate day;
    private long generation;

    /**
     * Returns the memoised value for a key on a day, loading and storing it on a miss.
     *
     * <p>Asking for a different day than the memo currently holds drops every entry first: the memo
     * only ever answers for one day, so yesterday's values are never served today.
     *
     * @param today  the day the answer is for
     * @param key    the memo key
     * @param loader computes the value on a miss; must not return null
     * @return the memoised or freshly loaded value
     */
    public V get(LocalDate today, K key, Supplier<V> loader) {
        long startedUnder;
        synchronized (lock) {
            rollTo(today);
            V hit = entries.get(key);
            if (hit != null) {
                return hit;
            }
            startedUnder = generation;
        }
        V loaded = loader.get();
        synchronized (lock) {
            // Not stored when an eviction or a day roll landed while this load was running: the value
            // may describe data that has since changed. It is still returned — it was correct when
            // the load began, which is all a caller that did not wait for the eviction can ask.
            if (generation == startedUnder && today.equals(day)) {
                entries.put(key, loaded);
            }
        }
        return loaded;
    }

    /**
     * Drops one key, and invalidates any load of any key that is still in flight.
     *
     * @param key the key whose source data changed
     */
    public void evict(K key) {
        synchronized (lock) {
            generation++;
            entries.remove(key);
        }
    }

    /** Drops every entry, and invalidates every load that is still in flight. */
    public void evictAll() {
        synchronized (lock) {
            generation++;
            entries.clear();
        }
    }

    /**
     * How many entries the memo holds right now.
     *
     * @return the entry count
     */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    private void rollTo(LocalDate today) {
        if (!today.equals(day)) {
            generation++;
            entries.clear();
            day = today;
        }
    }
}
