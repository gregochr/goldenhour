package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.util.List;

/**
 * The aurora nights a Pro reader's window control would offer a row for right now — the role-free
 * "an aurora night exists" answer behind the Lite reader's Pro door.
 *
 * <p>Carries only dates: no rating, location, Kp or summary.
 *
 * @param nights             ISO dates, ascending and distinct: every stored (non-simulated) night from
 *                           the current night on, plus the current night when a real alert is live at
 *                           MODERATE or STRONG
 * @param currentNightEndsAt the instant the current night stops being the current one, from the same
 *                           read of the clock as the night the list was filtered against — so a client
 *                           can drop that night once it is over without refetching
 */
public record AuroraNightsResponse(List<String> nights, Instant currentNightEndsAt) {
}
