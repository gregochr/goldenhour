package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;

/**
 * A validated pick: a location at a window. Every field except {@code why} is joined from served
 * data by the validator; Claude supplies only the location id, the window id and the reason.
 *
 * @param rank            1-based rank, renumbered by the validator
 * @param locationId      the location's id
 * @param locationName    the served location name
 * @param regionName      the served region name
 * @param date            the window's date (a night's date is the date whose dusk opens it)
 * @param targetType      the window's kind: {@code SUNRISE}, {@code SUNSET}, {@code ASTRO} or
 *                        {@code AURORA}; serialised by name, so a solar pick reads as it always did
 * @param windowId        the window id
 * @param why             the cleaned, word-capped reason
 * @param ratingAtAnswer  the served rating when the answer was written (for a night pick, the
 *                        eligible figure: the astro or the aurora stars)
 * @param verdictAtAnswer the served display verdict name when the answer was written; null for a night
 *                        pick, which has no verdict word
 */
public record AskPick(int rank, long locationId, String locationName, String regionName,
        LocalDate date, AskWindowKind targetType, String windowId, String why,
        Integer ratingAtAnswer, String verdictAtAnswer) {
}
