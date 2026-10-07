package com.gregochr.goldenhour.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The three columns of a stored high water that a per-day maximum needs, and nothing else.
 *
 * <p>{@code TideSizeIndex} takes one number per location per local day out of tens of thousands of
 * rows; hydrating a full {@code TideExtremeEntity} for each of them (id, type, fetch stamp, a
 * persistence-context entry) was most of that read's cost. Returned by
 * {@link TideExtremeRepository#findHighWatersInWindow}.
 *
 * @param locationId   the location the extreme belongs to
 * @param eventTime    when it happens, UTC, as stored
 * @param heightMetres its height in metres
 */
public record TideHighWater(Long locationId, LocalDateTime eventTime, BigDecimal heightMetres) { }
