package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * API response for {@code GET /api/nlc/sighting} — the noctilucent-cloud sighting signal.
 *
 * <p>A REACTIVE community signal, never a forecast. When there is a fresh, relevant sighting
 * <em>and</em> local skies are clear tonight, {@link #active} is {@code true} and the frontend
 * shows the NLC banner; otherwise {@link #inactive()} returns a body with {@code active: false}
 * and the banner stays dark. Deliberately carries no Kp / Bz / probability — see the honesty
 * note in {@code NlcProperties}.
 *
 * @param active               the only hard requirement; {@code false} → no banner
 * @param reportedAt           ISO instant the sighting was logged; also the client's dismissal key
 * @param observerLocation     free-text observer place, e.g. {@code "Elgin"}
 * @param region               broader area used in the description sentence, e.g. {@code "Scotland"}
 * @param source               attribution shown as "… via {source}", e.g. {@code "NLCNET"}
 * @param clearTonight         local clear-sky gate; the client hides the banner when {@code false}
 * @param darkSkyLocationCount dark-sky sites currently clear on the northern horizon; 0 hides the sub-line
 * @param lookDirection        where to look tonight, e.g. {@code "N–NW"}
 * @param hexColour            NLC violet driving {@code --nlc-accent}
 * @param description          factual, reactive headline, e.g. "Noctilucent cloud reported over Scotland"
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NlcSightingResponse(
        boolean active,
        Instant reportedAt,
        String observerLocation,
        String region,
        String source,
        Boolean clearTonight,
        Integer darkSkyLocationCount,
        String lookDirection,
        String hexColour,
        String description) {

    /**
     * Returns the "nothing to show" response — {@code {"active": false}}. The banner renders null.
     *
     * @return an inactive response with all optional fields omitted
     */
    public static NlcSightingResponse inactive() {
        return new NlcSightingResponse(false, null, null, null, null, null, null, null, null, null);
    }
}
