package com.gregochr.goldenhour.model;

import java.time.ZonedDateTime;

/**
 * Represents tonight's dark period — from nautical dusk to nautical dawn.
 *
 * <p>Used by the forecast-lookahead path to pin Kp forecast matching to the
 * upcoming (or current) dark period rather than a rolling lookahead window.
 *
 * @param dusk  nautical dusk (start of dark period)
 * @param dawn  nautical dawn (end of dark period)
 */
public record TonightWindow(ZonedDateTime dusk, ZonedDateTime dawn) {

    /**
     * Returns {@code true} if the given forecast window overlaps with tonight's dark period.
     *
     * <p>Uses half-open interval semantics: [from, to) overlaps [dusk, dawn) when
     * {@code from < dawn && to > dusk}.
     *
     * @param from  start of the forecast window (inclusive)
     * @param to    end of the forecast window (exclusive)
     * @return {@code true} if there is any overlap
     */
    public boolean overlaps(ZonedDateTime from, ZonedDateTime to) {
        return from.isBefore(dawn) && to.isAfter(dusk);
    }
}
