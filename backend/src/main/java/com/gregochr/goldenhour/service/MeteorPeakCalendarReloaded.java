package com.gregochr.goldenhour.service;

/**
 * Published by {@link MeteorPeakCalendar#reload()} after it swaps in a new snapshot, so caches built from the
 * peak dates (the Coming up feed) can drop what they hold. An event rather than a call avoids a dependency
 * cycle between the calendar and its readers.
 */
public record MeteorPeakCalendarReloaded() { }
