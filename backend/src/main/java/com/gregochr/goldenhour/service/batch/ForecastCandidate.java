package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Lightweight (location, date, targetType) triple emitted by the first
 * briefing pass before weather pre-fetch and triage are applied.
 */
record ForecastCandidate(LocationEntity location, LocalDate date,
        TargetType targetType) {
}
