package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * A validated pick: a location at a window. Every field except {@code why} is joined from served
 * data by the validator; Claude supplies only the location id, the window id and the reason.
 *
 * @param rank            1-based rank, renumbered by the validator
 * @param locationId      the location's id
 * @param locationName    the served location name
 * @param regionName      the served region name
 * @param date            the window's date
 * @param targetType      the window's event
 * @param windowId        the window id
 * @param why             the cleaned, word-capped reason
 * @param ratingAtAnswer  the served rating when the answer was written
 * @param verdictAtAnswer the served display verdict name when the answer was written
 */
public record AskPick(int rank, long locationId, String locationName, String regionName,
        LocalDate date, TargetType targetType, String windowId, String why,
        Integer ratingAtAnswer, String verdictAtAnswer) {
}
