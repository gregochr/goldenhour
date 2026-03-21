package com.gregochr.goldenhour.model;

import java.time.ZonedDateTime;

/**
 * A single 3-hour window from the NOAA 3-day Kp forecast.
 *
 * @param from start of the forecast window (UTC)
 * @param to   end of the forecast window (UTC)
 * @param kp   forecast Kp index for this window
 */
public record KpForecast(ZonedDateTime from, ZonedDateTime to, double kp) {}
