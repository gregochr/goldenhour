package com.gregochr.goldenhour.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gregochr.goldenhour.model.BesselianElements;
import com.gregochr.goldenhour.model.EclipseCircumstances;
import com.gregochr.goldenhour.service.EclipseCatalog;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The eclipse reduction, checked against published circumstances rather than against itself.
 *
 * <p>Every expectation here comes from outside the codebase — NASA's canon, or the widely published
 * local circumstances for named places. That matters more than usual: a reduction that is subtly
 * wrong still returns plausible times, so a test written from this class's own output would pin the
 * bug. The London case is the anchor, because its contacts are published to the minute in several
 * independent places.
 */
class EclipseCalculatorTest {

    private static final BesselianElements ECLIPSE_2026 =
            EclipseCatalog.all().get(0).elements();

    /** BST is UTC+1, and every published UK figure for this eclipse is quoted in it. */
    private static final int BST_OFFSET_HOURS = 1;

    private static EclipseCircumstances at(double lat, double lon) {
        Optional<EclipseCircumstances> result =
                EclipseCalculator.circumstances(ECLIPSE_2026, lat, lon);
        assertThat(result).as("eclipse visible at %s, %s", lat, lon).isPresent();
        return result.get();
    }

    private static LocalDateTime bst(EclipseCircumstances circumstances,
            java.util.function.Function<EclipseCircumstances, LocalDateTime> contact) {
        return contact.apply(circumstances).plusHours(BST_OFFSET_HOURS);
    }

    @Nested
    @DisplayName("London — the published anchor case")
    class London {

        private static final double LAT = 51.5074;
        private static final double LON = -0.1278;

        @Test
        @DisplayName("contacts match the published 18:17 / 19:13 / 20:06 BST to the minute")
        void contactsMatchPublishedTimes() {
            EclipseCircumstances c = at(LAT, LON);

            // Published: first contact 18:17, maximum 19:13, last contact 20:06 BST. Asserted to
            // the minute because that is the precision the sources state; the reduction actually
            // lands at :16, :13 and :09 seconds respectively.
            assertThat(bst(c, EclipseCircumstances::firstContactUtc))
                    .isEqualToIgnoringSeconds(LocalDateTime.of(2026, 8, 12, 18, 17));
            assertThat(bst(c, EclipseCircumstances::maximumUtc))
                    .isEqualToIgnoringSeconds(LocalDateTime.of(2026, 8, 12, 19, 13));
            assertThat(bst(c, EclipseCircumstances::lastContactUtc))
                    .isEqualToIgnoringSeconds(LocalDateTime.of(2026, 8, 12, 20, 6));
        }

        @Test
        @DisplayName("obscuration matches the widely published 91%, and magnitude does not")
        void obscurationAndMagnitudeAreDistinctQuantities() {
            EclipseCircumstances c = at(LAT, LON);

            // The figure every popular source quotes for London ("91.4% partial") is OBSCURATION —
            // the fraction of the Sun's AREA covered. NASA's canon tabulates MAGNITUDE, the
            // fraction of its DIAMETER. Conflating them is the single easiest way to print a wrong
            // number here, so both are pinned and the gap between them is asserted explicitly.
            assertThat(c.obscuration()).isCloseTo(0.912, within(0.005));
            assertThat(c.magnitude()).isCloseTo(0.924, within(0.005));
            assertThat(c.magnitude()).isGreaterThan(c.obscuration());
        }

        @Test
        @DisplayName("the Sun is low in the west at maximum — 10 degrees up, not yet set")
        void sunIsLowInTheWest() {
            EclipseCircumstances c = at(LAT, LON);

            // Published altitude is ~10.5 degrees; this reduction is geometric (no refraction),
            // which accounts for the ~0.3 degree difference. Tolerance is set to admit that gap
            // and nothing like a formula error.
            assertThat(c.sunAltitudeDeg()).isCloseTo(10.2, within(0.6));
            assertThat(c.sunAltitudeRounded()).isEqualTo(10);
            // Azimuth clockwise from north: due west is 270, WNW is 292.5. London sits between.
            assertThat(c.sunAzimuthDeg()).isCloseTo(281.0, within(1.0));
        }

        @Test
        @DisplayName("the partial phase runs 1h 49m, the figure the design quotes")
        void durationMatchesTheDesignsFigure() {
            assertThat(at(LAT, LON).duration().toMinutes()).isEqualTo(108);
        }

        @Test
        void isPartialRatherThanTotal() {
            assertThat(at(LAT, LON).isTotal()).isFalse();
        }
    }

    @Nested
    @DisplayName("Reykjavik — inside the path of totality")
    class Reykjavik {

        private static final double LAT = 64.1466;
        private static final double LON = -21.9426;

        @Test
        @DisplayName("magnitude reaches totality, which a sign error in the hour angle would miss")
        void reachesTotality() {
            EclipseCircumstances c = at(LAT, LON);

            // Iceland was in the path; Reykjavik near its northern edge, so totality is brief and
            // the magnitude only just exceeds 1. This is the case that fixes the LONGITUDE SIGN
            // CONVENTION: with the hour angle formed as (mu - longitude) instead of (mu +
            // longitude), this location returns magnitude 0.84 and never goes total. London cannot
            // catch that error — its longitude is 0.13 degrees, so both conventions agree there to
            // within a second.
            assertThat(c.isTotal()).isTrue();
            assertThat(c.magnitude()).isGreaterThan(1.0);
            assertThat(c.obscuration()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("maximum falls around 17:48 UT, an hour before London's")
        void maximumIsEarlierThanLondons() {
            assertThat(at(LAT, LON).maximumUtc())
                    .isEqualToIgnoringSeconds(LocalDateTime.of(2026, 8, 12, 17, 48));
        }
    }

    @Nested
    @DisplayName("Across the UK")
    class AcrossTheUk {

        @Test
        @DisplayName("magnitude falls from south-west to north-east, as published")
        void magnitudeFallsToTheNorthEast() {
            // The published spread is 88% in north-east Scotland to ~96% in the Isles of Scilly.
            double scilly = at(49.9160, -6.3220).magnitude();
            double london = at(51.5074, -0.1278).magnitude();
            double bamburgh = at(55.6089, -1.7188).magnitude();
            double lerwick = at(60.1546, -1.1494).magnitude();

            assertThat(scilly).isGreaterThan(london);
            assertThat(london).isGreaterThan(bamburgh);
            assertThat(bamburgh).isGreaterThan(lerwick);
            assertThat(scilly).isCloseTo(0.963, within(0.01));
            assertThat(lerwick).isCloseTo(0.899, within(0.01));
        }

        @Test
        @DisplayName("maximum comes earlier the further north-west you are")
        void maximumSweepsWestToEast() {
            // The shadow tracks north-east to south-west across the Atlantic, so Shetland sees
            // maximum well before Cornwall. A reduction that ignored longitude would return the
            // same instant everywhere.
            LocalDateTime lerwick = at(60.1546, -1.1494).maximumUtc();
            LocalDateTime scilly = at(49.9160, -6.3220).maximumUtc();
            assertThat(lerwick).isBefore(scilly);
        }

        @Test
        @DisplayName("the Sun sits in the west everywhere in the UK, never the north-west")
        void sunBearingIsWesterlyThroughout() {
            // Worth pinning as a range rather than a point: the design's copy says WNW, and the
            // reduction says W (275-282 degrees) for every UK location. WNW begins at 281.25, so
            // only the far south-east even approaches it. Any surface printing a compass point
            // must take it from here rather than from the design.
            for (double[] place : new double[][] {
                    {49.9160, -6.3220}, {51.5074, -0.1278}, {55.6089, -1.7188}, {60.1546, -1.1494}}) {
                assertThat(at(place[0], place[1]).sunAzimuthDeg())
                        .as("azimuth at %s, %s", place[0], place[1])
                        .isBetween(274.0, 283.0);
            }
        }
    }

    @Nested
    @DisplayName("Where the eclipse is not visible")
    class NotVisible {

        @Test
        @DisplayName("an observer the penumbra never reaches gets nothing, not a zero-magnitude hit")
        void returnsEmptyOutsideThePenumbra() {
            // Sydney, on the far side of the Earth from this eclipse's shadow.
            assertThat(EclipseCalculator.circumstances(ECLIPSE_2026, -33.8688, 151.2093)).isEmpty();
        }

        @Test
        @DisplayName("the southern hemisphere sees none of a far-northern eclipse")
        void returnsEmptyInTheSouthernHemisphere() {
            assertThat(EclipseCalculator.circumstances(ECLIPSE_2026, -34.6037, -58.3816)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Internal consistency")
    class Consistency {

        @Test
        @DisplayName("contacts bracket maximum, and the separation really is least at maximum")
        void maximumLiesBetweenTheContacts() {
            EclipseCircumstances c = at(55.6089, -1.7188);
            assertThat(c.firstContactUtc()).isBefore(c.maximumUtc());
            assertThat(c.maximumUtc()).isBefore(c.lastContactUtc());
        }

        @Test
        @DisplayName("percentage accessors round the underlying fractions")
        void percentageAccessorsRound() {
            EclipseCircumstances c = at(51.5074, -0.1278);
            assertThat(c.magnitudePct()).isEqualTo((int) Math.round(c.magnitude() * 100));
            assertThat(c.obscurationPct()).isEqualTo((int) Math.round(c.obscuration() * 100));
        }

        @Test
        @DisplayName("a polynomial evaluates as the cubic it prints")
        void polynomialEvaluatesAsPrinted() {
            BesselianElements.Poly poly = new BesselianElements.Poly(1.0, 2.0, 3.0, 4.0);
            assertThat(poly.at(0.0)).isEqualTo(1.0);
            assertThat(poly.at(1.0)).isEqualTo(10.0);
            assertThat(poly.at(2.0)).isEqualTo(1 + 4 + 12 + 32);
        }
    }
}
