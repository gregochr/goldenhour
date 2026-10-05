package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;

/**
 * A validated event card: a hot topic or an almanac entry a tool actually returned.
 *
 * @param type  the served type, e.g. {@code AURORA}
 * @param label the served label or title
 * @param date  the served date
 * @param why   the cleaned, word-capped reason
 */
public record AskEvent(String type, String label, LocalDate date, String why) {
}
