package com.gregochr.goldenhour.model.comingup;

import java.time.LocalDate;

/**
 * One day a merged "Coming up" entry marks for one of the topics it holds (supermoon-tide-run-plan.md
 * §3) — the supermoon's {@code full moon} nights and the tide run's {@code peak} day, each on its own
 * date, so a card whose span is the union of two topics can still say whose day is whose.
 *
 * @param date   the marked day, within the entry's span
 * @param label  the short mark word the day chip prints, e.g. {@code full moon} or {@code peak}
 * @param family the marking topic's own family ({@code coastal}, {@code sun-moon}), for its colour
 */
public record ComingUpDayMark(LocalDate date, String label, String family) {
}
