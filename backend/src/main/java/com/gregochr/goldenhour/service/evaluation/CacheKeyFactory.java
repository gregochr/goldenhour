package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Builds and parses in-memory cache keys of the form {@code "regionName|date|targetType"}
 * (e.g. {@code "North East|2026-04-23|SUNRISE"}).
 *
 * <p>Centralising the key format eliminates a documented drift risk — prior to extraction
 * the same concatenation appeared in five call sites and the same {@code split("\\|")}
 * appeared in multiple parse sites across {@code BriefingEvaluationService},
 * {@code ScheduledBatchEvaluationService}, and {@code BatchResultProcessor}.
 *
 * <p>{@link #build} validates that the region name does not contain the separator character,
 * eliminating the silent failure mode where a {@code |} in a region name would corrupt every
 * parse site downstream.
 */
public final class CacheKeyFactory {

    private static final String SEPARATOR = "|";

    private CacheKeyFactory() {
    }

    /**
     * Builds a cache key for the supplied tuple.
     *
     * @param regionName the region name; must not contain the {@code |} separator
     * @param date       the forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return the canonical cache key
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code regionName} contains {@code |}
     */
    public static String build(String regionName, LocalDate date, TargetType targetType) {
        Objects.requireNonNull(regionName, "regionName");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        if (regionName.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "Region name cannot contain '" + SEPARATOR + "' — got: " + regionName);
        }
        return regionName + SEPARATOR + date + SEPARATOR + targetType.name();
    }

    /**
     * Parses a cache key produced by {@link #build}.
     *
     * @param key the key to parse
     * @return the parsed components
     * @throws NullPointerException     if {@code key} is null
     * @throws IllegalArgumentException if the key is malformed (wrong part count,
     *                                  invalid date, unknown target type)
     */
    public static CacheKey parse(String key) {
        Objects.requireNonNull(key, "key");
        String[] parts = key.split("\\" + SEPARATOR, -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Malformed cache key (expected 3 parts separated by '"
                            + SEPARATOR + "'): " + key);
        }
        try {
            LocalDate date = LocalDate.parse(parts[1]);
            TargetType targetType = TargetType.valueOf(parts[2]);
            return new CacheKey(parts[0], date, targetType);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed cache key: " + key, e);
        }
    }

    /**
     * Structured representation of a cache key.
     *
     * @param regionName the region name component
     * @param date       the forecast date component
     * @param targetType the target type component
     */
    public record CacheKey(String regionName, LocalDate date, TargetType targetType) {
    }
}
