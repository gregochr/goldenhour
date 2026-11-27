package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.util.PointSnowpack;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared reading of {@code snow_state} rows by the three snow hot-topic strategies
 * (snow-topics-truth-plan §3.3): SUNRISE rows only, indexed by point and date, with the one "lying" rule.
 *
 * <p>A place with no row here is <em>unknown</em>, never "no snow": callers must not read an absent entry as bare
 * ground.
 */
final class SnowRows {

    /**
     * A point (a place or a summit) on one date.
     *
     * @param pointKey {@code loc:<id>} or {@code summit:<key>}
     * @param date     the UK civil date
     */
    record PointDay(String pointKey, LocalDate date) {
    }

    private SnowRows() {
    }

    /**
     * Indexes the SUNRISE rows by point and date.
     *
     * @param rows the fresh rows
     * @return the SUNRISE row for each (point, date)
     */
    static Map<PointDay, SnowStateEntity> sunriseByPointDay(List<SnowStateEntity> rows) {
        Map<PointDay, SnowStateEntity> byPointDay = new HashMap<>();
        for (SnowStateEntity row : rows) {
            if (row.getEventType() == TargetType.SUNRISE) {
                byPointDay.putIfAbsent(new PointDay(row.getPointKey(), row.getEvaluationDate()), row);
            }
        }
        return byPointDay;
    }

    /**
     * Whether the row says snow is lying at the place: modelled snow water at or above
     * {@link PointSnowpack#LYING_MM}. A null value is not lying (and never fires a topic).
     *
     * @param row the place's own row, or {@code null}
     * @return true when lying
     */
    static boolean isLying(SnowStateEntity row) {
        return row != null && row.getSnowWaterMm() != null && row.getSnowWaterMm() >= PointSnowpack.LYING_MM;
    }

    /**
     * The {@code snow_state} point key of a place.
     *
     * @param place a location
     * @return {@code loc:<id>}, or {@code null} when the location has no id
     */
    static String keyOf(LocationEntity place) {
        return place == null || place.getId() == null ? null : SnowStateEntity.locationKey(place.getId());
    }
}
