package com.gregochr.goldenhour.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RatingBand}: membership, miss-direction classification at the band
 * boundaries, the factory helpers, the label, and the validation guard. Pure logic — no Claude.
 */
class RatingBandTest {

    @Test
    void containsIsInclusiveOfBothBounds() {
        RatingBand band = new RatingBand(3, 4);
        assertTrue(band.contains(3), "lower bound is inclusive");
        assertTrue(band.contains(4), "upper bound is inclusive");
        assertFalse(band.contains(2), "below the band is out");
        assertFalse(band.contains(5), "above the band is out");
    }

    @Test
    void classifyReturnsBelowForRatingsUnderTheBand() {
        RatingBand band = new RatingBand(4, 5);
        assertEquals(MissDirection.BELOW, band.classify(3));
        assertEquals(MissDirection.BELOW, band.classify(1));
    }

    @Test
    void classifyReturnsAboveForRatingsOverTheBand() {
        RatingBand band = new RatingBand(2, 3);
        assertEquals(MissDirection.ABOVE, band.classify(4));
        assertEquals(MissDirection.ABOVE, band.classify(5));
    }

    @Test
    void classifyReturnsInBandAtTheExactBoundaries() {
        RatingBand band = new RatingBand(2, 4);
        assertEquals(MissDirection.IN_BAND, band.classify(2), "lower boundary is in band");
        assertEquals(MissDirection.IN_BAND, band.classify(3));
        assertEquals(MissDirection.IN_BAND, band.classify(4), "upper boundary is in band");
    }

    @Test
    void atMostMirrorsALessThanOrEqualAssertion() {
        RatingBand band = RatingBand.atMost(2);
        assertEquals(1, band.min());
        assertEquals(2, band.max());
        assertEquals(MissDirection.IN_BAND, band.classify(1));
        assertEquals(MissDirection.ABOVE, band.classify(3));
    }

    @Test
    void atLeastMirrorsAGreaterThanOrEqualAssertion() {
        RatingBand band = RatingBand.atLeast(4);
        assertEquals(4, band.min());
        assertEquals(5, band.max());
        assertEquals(MissDirection.IN_BAND, band.classify(5));
        assertEquals(MissDirection.BELOW, band.classify(3));
    }

    @Test
    void exactlyProducesAOneWideBand() {
        RatingBand band = RatingBand.exactly(3);
        assertEquals(3, band.min());
        assertEquals(3, band.max());
        assertTrue(band.contains(3));
        assertEquals(MissDirection.BELOW, band.classify(2));
        assertEquals(MissDirection.ABOVE, band.classify(4));
    }

    @Test
    void labelCollapsesAOneWideBand() {
        assertEquals("{3}", RatingBand.exactly(3).label());
        assertEquals("{4–5}", new RatingBand(4, 5).label());
    }

    @Test
    void rejectsInvertedBand() {
        assertThrows(IllegalArgumentException.class, () -> new RatingBand(4, 3));
    }

    @Test
    void rejectsOutOfRangeBounds() {
        assertThrows(IllegalArgumentException.class, () -> new RatingBand(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new RatingBand(3, 6));
    }
}
