package com.gregochr.goldenhour.model.comingup;

import java.time.LocalDate;

/**
 * A chronology entry's single action (plan §13) — exactly one per entry, into the rest of the app.
 *
 * <p>The server picks the destination because it is the server that knows what an entry actually
 * is; the client's job (P3b) is to wire {@code kind} to a concrete navigation, never to decide it.
 *
 * @param label the link text, already carrying its own arrow, e.g. {@code "Show coastal spots for
 *              12 Sept →"}
 * @param kind  {@code plan} | {@code coastal-spots} | {@code dark-sky-spots}
 * @param date  the date the action is about
 */
public record ComingUpAction(String label, String kind, LocalDate date) {
}
