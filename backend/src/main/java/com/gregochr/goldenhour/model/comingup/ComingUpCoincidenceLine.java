package com.gregochr.goldenhour.model.comingup;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One line of a coincidence card (plan §13, D10) — one per merged topic, host first, each with its
 * own dates (supermoon-tide-run-plan.md R5).
 *
 * @param family     the topic's own family, for its swatch colour
 * @param name       the topic's own title
 * @param factsLabel a short summary of the topic's own key facts and dates, one plain string
 * @param type       the topic's own wire type ({@code spring-tide}, {@code king-tide},
 *                   {@code supermoon}), so a consumer can tell which topics a card holds without
 *                   parsing names; null only where a fixture does not set it
 */
public record ComingUpCoincidenceLine(String family, String name, String factsLabel,
        @JsonInclude(JsonInclude.Include.NON_NULL) String type) {
}
