package com.gregochr.goldenhour.model;

import java.time.ZonedDateTime;

/**
 * A single Kp index reading from the NOAA planetary K-index endpoint.
 *
 * @param timestamp when the reading was taken
 * @param kp        planetary K-index value (0–9)
 */
public record KpReading(ZonedDateTime timestamp, double kp) {}
