package com.gregochr.goldenhour.model.comingup;

/**
 * One line of a coincidence card (plan §13, D10) — the topic whose score did <b>not</b> carry the
 * merged entry's {@code bits}, folded into the winner's row rather than kept as its own entry.
 *
 * @param family     the losing topic's own family, for its swatch colour — never the winner's
 * @param name       the losing topic's title
 * @param factsLabel a short summary of the losing topic's own key facts and date
 */
public record ComingUpCoincidenceLine(String family, String name, String factsLabel) {
}
