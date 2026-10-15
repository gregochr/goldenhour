package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.util.List;

/**
 * Every user's rating of one window — the admin's "all ratings" read ({@code GET
 * /api/outcome/ratings/all}). The forecast the ratings were recorded against rides the head, so a
 * slot with no ratings still shows its run.
 *
 * @param eventTime     the solar event instant for the location
 * @param forecastRunAt the age of the forecast the readers saw — its submission instant — or null
 * @param pipelineRunId the cycle that forecast came from, or null
 * @param ratings       one row per user, newest first
 */
public record SlotRatingsDto(
        Instant eventTime,
        Instant forecastRunAt,
        Long pipelineRunId,
        List<Row> ratings
) {

    /**
     * One user's rating of the window.
     *
     * @param username  the rating user
     * @param rating    1–5 stars, or {@code null} for "didn't see it"
     * @param updatedAt when the rating was last recorded
     * @param mine      true on the caller's own row
     */
    public record Row(String username, Integer rating, Instant updatedAt, boolean mine) {
    }
}
