package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The night in progress — or, in daylight, the next one — and the instant it stops being current.
 *
 * <p>Built by {@code AuroraForecastRunService.currentNight()} from ONE read of the clock, so the two
 * halves always describe the same night. Read separately, a date taken just before dawn and an end
 * taken just after would name last night and give tomorrow's dawn as its end.
 *
 * @param date   the date naming the night: the date its dusk falls on, which between midnight and
 *               dawn is yesterday's
 * @param endsAt the nautical dawn that closes that night's window, which is also the first instant
 *               at which the next night is the current one
 */
public record CurrentNight(LocalDate date, Instant endsAt) {
}
