package com.gregochr.goldenhour.model.comingup;

import java.time.LocalDate;

/**
 * One occurrence in a standing condition's expansion panel (plan §13).
 *
 * <p>{@code status} carries a precedence, D11: {@code promoted} outranks {@code insidePlan}.
 * {@code promoted} is derived FROM membership of the assembled chronology and carries that entry's
 * {@code entryId}; {@code insidePlan} applies only to an occurrence with no entry (wholly inside
 * Plan's window); else {@code heldBack}.
 *
 * @param date       the occurrence's date
 * @param dateLabel  the occurrence's formatted date
 * @param valueLabel the occurrence's formatted intensity
 * @param label      which kind of occurrence this is within a condition that mixes kinds under one
 *                   row (D11's "one row for both spring and king runs") — e.g. {@code "Spring tide"}
 *                   / {@code "King tide"}, read straight from the source event's own type, never
 *                   re-derived; null where a condition has only one kind (dust, inversion)
 * @param bits       the occurrence's surprise score
 * @param reason     names the topic whose score won the max rule, or null when this occurrence's
 *                   own magnitude was used
 * @param status     {@code heldBack} | {@code promoted} | {@code insidePlan}
 * @param entryId    the chronology entry this occurrence promoted into; non-null iff
 *                   {@code status == promoted}, and must resolve to a real entry
 */
public record ComingUpConditionOccurrence(
        LocalDate date,
        String dateLabel,
        String valueLabel,
        String label,
        double bits,
        String reason,
        String status,
        String entryId) {
}
