package com.gregochr.goldenhour.model.skyeval;

import java.time.LocalDateTime;

/**
 * Query projection: the earliest run time at which a fixture has a result row.
 *
 * @param fixtureName the fixture
 * @param firstRunAt  the earliest {@code run_timestamp} (naive UTC) of a run that scored it
 */
public record SkyRatingEvalFirstSeen(String fixtureName, LocalDateTime firstRunAt) {
}
