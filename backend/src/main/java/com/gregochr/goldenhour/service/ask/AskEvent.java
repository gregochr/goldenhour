package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * A validated event card: a hot topic or an almanac entry a tool actually returned.
 *
 * @param type       the served type, e.g. {@code AURORA}
 * @param label      the served label or title
 * @param date       the served date
 * @param why        the cleaned, word-capped reason
 * @param safetyNote the served warning the card must show, or null. Never written by the model: the
 *                   validator re-joins it from the topic a tool returned, and an event whose served
 *                   topic has one always carries it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskEvent(String type, String label, LocalDate date, String why, String safetyNote) {
}
