package com.gregochr.goldenhour.entity;

/**
 * Exposure type for bluebell locations. Determines which weather
 * conditions are considered ideal for photography.
 *
 * <p>WOODLAND: under tree canopy. Soft overcast light preferred,
 * wind less critical (canopy shelters flowers), mist is the
 * dream condition.</p>
 *
 * <p>OPEN_FELL: exposed hillside (e.g. Rannerdale Knotts).
 * Golden hour directional light works beautifully, calm wind
 * is critical (no canopy protection), mist against a lake
 * backdrop is extraordinary.</p>
 */
public enum BluebellExposure {

    /** Woodland location — sheltered under tree canopy. */
    WOODLAND,

    /** Open fell location — exposed hillside, no canopy. */
    OPEN_FELL
}
