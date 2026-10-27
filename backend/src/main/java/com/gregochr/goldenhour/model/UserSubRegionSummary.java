package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * One reader's sub-region standing as the admin user list shows it (sub-regions-plan S4b): the picked names
 * and the same status {@code GET /api/user/settings/sub-regions} reports, computed in one pass for every user.
 *
 * @param names        the picked sub-region names, by name; empty when none
 * @param status       {@code FIRST_CHOICE}, {@code FREE_UNTIL}, {@code OPEN}, {@code LOCKED} or
 *                     {@code NOT_APPLICABLE} (a Pro or Admin reader)
 * @param freeUntil    the last free-change day, while {@code status} is {@code FREE_UNTIL}; otherwise null
 * @param nextChangeOn the first day a swap is allowed, while {@code status} is {@code LOCKED}; otherwise null
 * @param overLimit    whether a Lite reader holds more picks than the admin-set limit
 */
public record UserSubRegionSummary(List<String> names, String status, LocalDate freeUntil, LocalDate nextChangeOn,
        boolean overLimit) {
}
