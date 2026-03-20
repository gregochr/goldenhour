package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.util.List;

/**
 * Optional request body for {@code POST /api/forecast/run}.
 *
 * <p>Any field may be {@code null}:
 * <ul>
 *   <li>If {@code dates} is null or empty, today's UTC date is used.</li>
 *   <li>If {@code location} is null, forecasts are run for all configured locations.</li>
 *   <li>If {@code targetType} is null, both SUNRISE and SUNSET are evaluated.</li>
 *   <li>If {@code excludedSlots} is null or empty, all slots are evaluated.</li>
 * </ul>
 *
 * <p>Dates must be ISO format strings ({@code yyyy-MM-dd}), e.g. {@code ["2026-03-01","2026-03-02"]}.
 *
 * @param dates         ISO date strings to forecast (null or empty = today only)
 * @param location      the name of the location to forecast (null = all configured locations)
 * @param targetType    SUNRISE or SUNSET (null = both)
 * @param excludedSlots specific (date, targetType) slots to skip; null or empty = skip none
 */
public record ForecastRunRequest(
        List<String> dates,
        String location,
        TargetType targetType,
        List<SlotFilter> excludedSlots) {

    /** Defensive copies — ensures list fields are immutable. */
    public ForecastRunRequest {
        dates = (dates != null) ? List.copyOf(dates) : null;
        excludedSlots = (excludedSlots != null) ? List.copyOf(excludedSlots) : null;
    }

    /**
     * A specific (date, targetType) slot to exclude from a run.
     *
     * @param date       ISO date string (yyyy-MM-dd)
     * @param targetType "SUNRISE" or "SUNSET"
     */
    public record SlotFilter(String date, String targetType) {}
}
