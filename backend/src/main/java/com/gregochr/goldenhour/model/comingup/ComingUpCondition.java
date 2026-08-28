package com.gregochr.goldenhour.model.comingup;

import java.util.List;

/**
 * One standing-conditions strip row (plan §13) — a topic that occurs too often to announce, with
 * every occurrence in the window available on request.
 *
 * <p>P4 builds these (Coastal tides, Saharan dust, Valley inversions at first ship — D11); P1
 * declares the shape only, and {@link ComingUpResponse#conditions()} ships an empty list until then,
 * so the response never has to grow a new top-level component later.
 *
 * @param type        machine-readable discriminator, e.g. {@code COASTAL_TIDES}
 * @param name        the strip row's name, e.g. {@code "Coastal tides"}
 * @param cadence     {@code persistent} | {@code recurrent} | {@code deterministic}
 * @param interim     true while this condition's scoring is interim (D4) — drives the strip's
 *                    provisional marker
 * @param rateLabel   server-formatted rarity sentence
 * @param quantLabel  server-formatted magnitude sentence
 * @param peak        the gated peak occurrence in the window, or null when none passes the gate
 * @param occurrences every occurrence in the window
 */
public record ComingUpCondition(
        String type,
        String name,
        String cadence,
        boolean interim,
        String rateLabel,
        String quantLabel,
        ComingUpConditionPeak peak,
        List<ComingUpConditionOccurrence> occurrences) {

    /**
     * Normalises {@code occurrences} so a consumer never has to null-check it.
     */
    public ComingUpCondition {
        occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
    }
}
