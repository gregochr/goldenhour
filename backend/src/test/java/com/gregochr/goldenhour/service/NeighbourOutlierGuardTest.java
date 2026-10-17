package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.repository.LocationPoint;
import com.gregochr.goldenhour.util.GeoUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.D;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.at;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.day;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.dayWithUnregioned;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.points;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.region;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.sky;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.unrated;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.wood;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The neighbour guard's rule (docs/engineering/neighbour-guard-plan.md §2), one clause at a time.
 * Each test fails if the clause it names is removed. The ids are the place numbers in the fixtures.
 */
class NeighbourOutlierGuardTest {

    private static final NeighbourGuardProperties DEFAULTS = new NeighbourGuardProperties();

    private static Set<Long> flaggedIds(NeighbourGuardProperties config, Map<Long, LocationPoint> points,
            BriefingSlot... slots) {
        return ids(NeighbourOutlierGuard.flag(List.of(day(D, TargetType.SUNRISE,
                region("North", slots))), points, config));
    }

    private static Set<Long> ids(NeighbourOutlierGuard.Result result) {
        return result.flagged().stream().map(NeighbourOutlierGuard.Key::locationId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static NeighbourGuardProperties config(int minNeighbours, int margin, int minRating) {
        NeighbourGuardProperties p = new NeighbourGuardProperties();
        p.setMinNeighbours(minNeighbours);
        p.setMargin(margin);
        p.setMinRating(minRating);
        return p;
    }

    /** A candidate (id 1, 4 stars) with two 2-star neighbours 1-2 km away, none with an elevation. */
    private static Set<Long> lone4WithTwoLow(NeighbourGuardProperties config) {
        return flaggedIds(config,
                points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2));
    }

    @Test
    @DisplayName("a 4-star slot above two nearby 2-star places is flagged, with its window key")
    void flagsALoneHighAmongTwoLowerNeighbours() {
        NeighbourOutlierGuard.Result result = NeighbourOutlierGuard.flag(
                List.of(day(D, TargetType.SUNSET, region("North",
                        sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))),
                points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)), DEFAULTS);

        assertThat(result.flagged()).containsExactly(
                new NeighbourOutlierGuard.Key(1L, D, TargetType.SUNSET));
        assertThat(result.flaggedRatings()).containsValue(4);
    }

    @Nested
    @DisplayName("who is a sky slot")
    class WhoIsASkySlot {

        @Test
        @DisplayName("a canopy candidate is never flagged")
        void canopyCandidateNeverFlagged() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                    wood(1L, "Wood", 4), sky(2L, "B", 2), sky(3L, "C", 2))).isEmpty();
        }

        @Test
        @DisplayName("a canopy neighbour is not a witness: one low wood and one low sky place do not flag")
        void canopyNeighbourIsNotAWitness() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), wood(3L, "Wood", 1))).isEmpty();
        }

        @Test
        @DisplayName("a canopy neighbour is not a supporter: a 4-star wood beside the slot does not clear it")
        void canopyNeighbourIsNotASupporter() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null), at(4, 2, 1, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2), wood(4L, "Wood", 4)))
                    .containsExactly(1L);
        }

        @Test
        @DisplayName("an unregioned slot is neither a candidate nor a neighbour")
        void unregionedIsNeitherCandidateNorNeighbour() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null),
                    at(3, 0, 2, null), at(4, 2, 1, null));
            // A candidate in the unregioned list is not judged.
            assertThat(ids(NeighbourOutlierGuard.flag(List.of(dayWithUnregioned(D, TargetType.SUNRISE,
                    List.of(sky(1L, "A", 4)),
                    region("North", sky(2L, "B", 2), sky(3L, "C", 2)))), pts, DEFAULTS))).isEmpty();
            // Unregioned neighbours are not witnesses: one regioned and two unregioned lows.
            assertThat(ids(NeighbourOutlierGuard.flag(List.of(dayWithUnregioned(D, TargetType.SUNRISE,
                    List.of(sky(3L, "C", 2), sky(4L, "D", 2)),
                    region("North", sky(1L, "A", 4), sky(2L, "B", 2)))), pts, DEFAULTS))).isEmpty();
            // And an unregioned supporter does not clear a slot two regioned lows flag.
            assertThat(ids(NeighbourOutlierGuard.flag(List.of(dayWithUnregioned(D, TargetType.SUNRISE,
                    List.of(sky(4L, "D", 4)),
                    region("North", sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))), pts, DEFAULTS)))
                    .containsExactly(1L);
        }

        @Test
        @DisplayName("a summary that is neither sunrise nor sunset is ignored")
        void nonSolarSummaryIsIgnored() {
            assertThat(ids(NeighbourOutlierGuard.flag(List.of(day(D, TargetType.HOURLY, region("North",
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))),
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)), DEFAULTS))).isEmpty();
        }

        @Test
        @DisplayName("a null location id neither flags nor counts")
        void nullLocationIdNeitherFlagsNorCounts() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null));
            assertThat(flaggedIds(DEFAULTS, pts, sky(null, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .isEmpty();
            // A null-id neighbour is not a witness (one witness is not enough)...
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "A", 4), sky(2L, "B", 2), sky(null, "C", 2)))
                    .isEmpty();
            // ...and not a supporter (it would clear the slot if it counted).
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2),
                    sky(null, "Legacy", 4))).containsExactly(1L);
        }

        @Test
        @DisplayName("a slot with no known point neither flags nor counts")
        void missingPointNeitherFlagsNorCounts() {
            Map<Long, LocationPoint> noCandidate = points(at(2, 1, 0, null), at(3, 0, 2, null));
            assertThat(flaggedIds(DEFAULTS, noCandidate, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .isEmpty();
            Map<Long, LocationPoint> noWitness = points(at(1, 0, 0, null), at(2, 1, 0, null));
            assertThat(flaggedIds(DEFAULTS, noWitness, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .isEmpty();
            Map<Long, LocationPoint> noSupporter = points(at(1, 0, 0, null), at(2, 1, 0, null),
                    at(3, 0, 2, null));
            assertThat(flaggedIds(DEFAULTS, noSupporter, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2),
                    sky(4L, "Unplaced", 4))).containsExactly(1L);
        }

        @Test
        @DisplayName("the same location is never its own neighbour")
        void sameLocationIsNeverItsOwnNeighbour() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null));
            assertThat(ids(NeighbourOutlierGuard.flag(List.of(day(D, TargetType.SUNRISE,
                    region("North", sky(1L, "A", 4), sky(2L, "B", 2)),
                    region("South", sky(1L, "A again", 2)))), pts, DEFAULTS))).isEmpty();
        }
    }

    @Nested
    @DisplayName("sky rating, not the served star")
    class SkyRatingNotServedStar {

        @Test
        @DisplayName("a null sky rating on the candidate fails open, whatever its served star")
        void nullSkyRatingOnCandidateFailsOpen() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                    sky(1L, "A", 4, null), sky(2L, "B", 2), sky(3L, "C", 2))).isEmpty();
        }

        @Test
        @DisplayName("a neighbour with no sky rating is not a witness and not a supporter")
        void nullSkyRatingOnNeighbourFailsOpen() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null),
                    at(3, 0, 2, null), at(4, 2, 1, null));
            // Not a witness: one real witness and one with a null sky star is one witness.
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2, null)))
                    .isEmpty();
            // Not a supporter: a served 4 with no sky star cannot clear the slot.
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2),
                    sky(4L, "D", 4, null))).containsExactly(1L);
        }

        @Test
        @DisplayName("a tidal slot whose served star differs but whose sky star agrees is not flagged")
        void servedStarHighButSkyStarAgrees() {
            // Served 4 (tide lifted it), sky 2 like both neighbours: the sky agrees, nothing to guard.
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                    sky(1L, "Tidal", 4, 2), sky(2L, "B", 2), sky(3L, "C", 2))).isEmpty();
        }

        @Test
        @DisplayName("a slot whose sky star stands alone is flagged although its served star is low")
        void skyStarAloneFlaggedAlthoughServedStarLow() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                    sky(1L, "Tidal", 2, 4), sky(2L, "B", 2), sky(3L, "C", 2))).containsExactly(1L);
        }

        @Test
        @DisplayName("a neighbour's served star does not clear a slot its sky star does not support")
        void neighbourServedStarDoesNotSupport() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                    sky(1L, "A", 4), sky(2L, "B", 4, 2), sky(3L, "C", 2))).containsExactly(1L);
        }
    }

    @Nested
    @DisplayName("the rating thresholds")
    class RatingThresholds {

        @Test
        @DisplayName("min-rating: a 4 is flagged and a 3 is not when the floor is 4; both are at the default")
        void minRatingFloorAtAndBelow() {
            NeighbourGuardProperties four = config(2, 2, 4);
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null));

            assertThat(flaggedIds(four, pts, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .containsExactly(1L);
            assertThat(flaggedIds(four, pts, sky(1L, "A", 3), sky(2L, "B", 1), sky(3L, "C", 1)))
                    .as("one below the floor").isEmpty();
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "A", 3), sky(2L, "B", 1), sky(3L, "C", 1)))
                    .as("the default floor of 3 reaches it").containsExactly(1L);
        }

        @Test
        @DisplayName("margin 2: a neighbour two stars below is a witness, one star below is a supporter")
        void marginTwoBoundary() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null));

            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .as("exactly the margin below").containsExactly(1L);
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 3)))
                    .as("one star short of the margin supports").isEmpty();
        }

        @Test
        @DisplayName("margin 3: two stars below now supports; three stars below flags")
        void marginThreeBoundary() {
            NeighbourGuardProperties three = config(2, 3, 3);
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null));

            assertThat(flaggedIds(three, pts, sky(1L, "A", 4), sky(2L, "B", 1), sky(3L, "C", 1)))
                    .as("exactly the margin below").containsExactly(1L);
            assertThat(flaggedIds(three, pts, sky(1L, "A", 4), sky(2L, "B", 1), sky(3L, "C", 2)))
                    .as("two below is within margin - 1 of margin 3").isEmpty();
        }

        @Test
        @DisplayName("a neighbour rated above the slot clears it: a 3 beside two 5s is not an outlier")
        void higherNeighbourClears() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                    sky(1L, "A", 3), sky(2L, "B", 5), sky(3L, "C", 5))).isEmpty();
        }

        @Test
        @DisplayName("a neighbour rated equal clears it")
        void equalNeighbourClears() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null), at(4, 2, 1, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2), sky(4L, "D", 4))).isEmpty();
        }
    }

    @Nested
    @DisplayName("distance")
    class Distance {

        @Test
        @DisplayName("just inside 5 km flags and just outside does not")
        void radiusInsideAndOutside() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 4.9, 0, null), at(3, 0, 4.9, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2))).containsExactly(1L);
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 5.1, 0, null), at(3, 0, 5.1, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2))).isEmpty();
        }

        @Test
        @DisplayName("a place inside the bounding box but beyond the radius is not a neighbour")
        void insideBoxButBeyondRadius() {
            // 4 km north and 4 km east: inside the 5 km box on both axes, 5.66 km away.
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 4, 4, null), at(3, -4, -4, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2))).isEmpty();
        }

        @Test
        @DisplayName("the radius is inclusive: a neighbour exactly on it counts, a hair beyond does not")
        void radiusIsInclusive() {
            LocationPoint a = at(1, 0, 0, null);
            LocationPoint b = at(2, 3, 0, null);
            LocationPoint c = at(3, 0, 3, null);
            double radiusKm = radiusKmEqualTo(Math.max(
                    GeoUtils.distanceMiles(a.lat(), a.lon(), b.lat(), b.lon()),
                    GeoUtils.distanceMiles(a.lat(), a.lon(), c.lat(), c.lon())));
            NeighbourGuardProperties exact = new NeighbourGuardProperties();
            exact.setRadiusKm(Math.max(1.0, radiusKm));

            assertThat(flaggedIds(exact, points(a, b, c),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .as("on the boundary").containsExactly(1L);
            NeighbourGuardProperties hairShort = new NeighbourGuardProperties();
            hairShort.setRadiusKm(radiusKm - 0.001);
            assertThat(flaggedIds(hairShort, points(a, b, c),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .as("a hair inside the boundary").isEmpty();
        }

        @Test
        @DisplayName("an east-west pair 4.5 km apart at 55 degrees north is inside 5 km (needs the cosine of latitude)")
        void eastWestPairNeedsTheCosineOfLatitude() {
            // 4.5 km east is 0.0705 degrees of longitude here; without the cosine term it would read
            // as 7.8 km, and a box 5 km / 111 km wide would also reject it.
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 0, 4.5, null), at(3, 0, -4.5, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2))).containsExactly(1L);
        }

        @Test
        @DisplayName("latitude and longitude are not swapped: a north-south pair beyond 5 km but a "
                + "swapped reading inside it stays out")
        void latitudeAndLongitudeAreNotSwapped() {
            // 5.5 km north: outside. Swapped, the 0.0495 degree gap would be read as longitude at -2
            // degrees latitude, 5.5 km, still outside - so pair it with a pair that only the right
            // reading keeps inside: 4.5 km east (inside; swapped reads 7.8 km, outside).
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 5.5, 0, null), at(3, 0, 4.5, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .as("only one neighbour is inside").isEmpty();
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 4.5, 0, null), at(3, 0, 4.5, null)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .as("both are inside").containsExactly(1L);
        }

        /** The radius in kilometres whose conversion to miles equals {@code miles} exactly. */
        private double radiusKmEqualTo(double miles) {
            double km = miles / NeighbourOutlierGuard.MILES_PER_KM;
            for (int i = 0; i < 8 && km * NeighbourOutlierGuard.MILES_PER_KM != miles; i++) {
                km = km * NeighbourOutlierGuard.MILES_PER_KM < miles ? Math.nextUp(km) : Math.nextDown(km);
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(km * NeighbourOutlierGuard.MILES_PER_KM == miles,
                    "no radius converts to exactly this distance");
            return km;
        }
    }

    @Nested
    @DisplayName("how many witnesses")
    class Witnesses {

        @Test
        @DisplayName("one low neighbour does not flag at the default; two do")
        void oneNeighbourDoesNotFlagByDefault() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null));

            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "A", 4), sky(2L, "B", 2))).isEmpty();
            assertThat(lone4WithTwoLow(DEFAULTS)).containsExactly(1L);
        }

        @Test
        @DisplayName("min-neighbours 1: one low neighbour flags; min-neighbours 3: two do not")
        void minNeighboursIsConfigurable() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null));

            assertThat(flaggedIds(config(1, 2, 3), pts, sky(1L, "A", 4), sky(2L, "B", 2)))
                    .containsExactly(1L);
            assertThat(lone4WithTwoLow(config(3, 2, 3))).isEmpty();
        }

        @Test
        @DisplayName("a supporter clears the slot however many low neighbours it has (min-neighbours 1)")
        void supporterBeatsAnyNumberOfWitnesses() {
            assertThat(flaggedIds(config(1, 2, 3),
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null), at(4, 2, 1, null)),
                    sky(1L, "A", 4), sky(2L, "B", 1), sky(3L, "C", 1), sky(4L, "D", 3))).isEmpty();
        }

        @Test
        @DisplayName("the Latrigg case: a 3-star place 158 m lower clears a fell top the two valley-high "
                + "neighbours would flag")
        void supporterAtAnyHeightClears() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, 368), at(2, 1, 0, 360), at(3, 0, 2, 375),
                    at(4, 1.7, 0, 210));
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "Latrigg", 4), sky(2L, "High A", 2),
                    sky(3L, "High B", 2))).as("without Castlerigg").containsExactly(1L);
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "Latrigg", 4), sky(2L, "High A", 2),
                    sky(3L, "High B", 2), sky(4L, "Castlerigg", 3)))
                    .as("Castlerigg, 158 m lower, rated 3: a supporter at any height").isEmpty();
        }

        @Test
        @DisplayName("neighbours 200 m lower are not witnesses: a fell top is not outvoted by the valley")
        void dissimilarHeightIsNotAWitness() {
            Map<Long, LocationPoint> pts = points(at(1, 0, 0, 400), at(2, 1, 0, 200), at(3, 0, 2, 200),
                    at(4, 2, 1, 390), at(5, 1, 1, 410));
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "Top", 4), sky(2L, "V1", 2), sky(3L, "V2", 2)))
                    .as("two valley neighbours only").isEmpty();
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "Top", 4), sky(2L, "V1", 2), sky(4L, "T2", 2)))
                    .as("one valley and one similar-height").isEmpty();
            assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "Top", 4), sky(2L, "V1", 2), sky(4L, "T2", 2),
                    sky(5L, "T3", 2))).as("two similar-height are enough").containsExactly(1L);
        }

        @Test
        @DisplayName("an unknown elevation is not a witness for a known one, and is for another unknown")
        void unknownElevationWitnessCases() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, 300), at(2, 1, 0, null), at(3, 0, 2, null)),
                    sky(1L, "Known", 4), sky(2L, "U1", 2), sky(3L, "U2", 2)))
                    .as("known candidate, unknown witnesses").isEmpty();
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, 300), at(3, 0, 2, 300)),
                    sky(1L, "Unknown", 4), sky(2L, "K1", 2), sky(3L, "K2", 2)))
                    .as("unknown candidate, known witnesses").isEmpty();
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)),
                    sky(1L, "Unknown", 4), sky(2L, "U1", 2), sky(3L, "U2", 2)))
                    .as("unknown beside unknown").containsExactly(1L);
        }

        @Test
        @DisplayName("the elevation gap is inclusive at 150 m and honours max-elevation-gap-m")
        void elevationGapBoundary() {
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, 300), at(2, 1, 0, 450), at(3, 0, 2, 150)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .as("exactly 150 m either side").containsExactly(1L);
            assertThat(flaggedIds(DEFAULTS,
                    points(at(1, 0, 0, 300), at(2, 1, 0, 451), at(3, 0, 2, 150)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .as("151 m above").isEmpty();
            NeighbourGuardProperties wide = new NeighbourGuardProperties();
            wide.setMaxElevationGapM(200);
            assertThat(flaggedIds(wide,
                    points(at(1, 0, 0, 300), at(2, 1, 0, 451), at(3, 0, 2, 150)),
                    sky(1L, "A", 4), sky(2L, "B", 2), sky(3L, "C", 2)))
                    .as("151 m is similar under a 200 m gap").containsExactly(1L);
        }
    }

    @Nested
    @DisplayName("windows")
    class Windows {

        private final Map<Long, LocationPoint> pts =
                points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null));

        @Test
        @DisplayName("neighbours on another date are not neighbours")
        void differentDateNeverNeighbours() {
            BriefingDay today = day(D, TargetType.SUNRISE, region("North", sky(1L, "A", 4)));
            BriefingDay tomorrow = day(D.plusDays(1), TargetType.SUNRISE,
                    region("North", sky(2L, "B", 2), sky(3L, "C", 2)));

            assertThat(NeighbourOutlierGuard.flag(List.of(today, tomorrow), pts, DEFAULTS).flagged())
                    .isEmpty();
        }

        @Test
        @DisplayName("the other target type on the same date is not a neighbour")
        void otherTargetTypeNeverNeighbours() {
            BriefingDay sunrise = day(D, TargetType.SUNRISE, region("North", sky(1L, "A", 4)));
            BriefingDay sunset = day(D, TargetType.SUNSET,
                    region("North", sky(2L, "B", 2), sky(3L, "C", 2)));
            BriefingDay merged = new BriefingDay(D, List.of(sunrise.eventSummaries().get(0),
                    sunset.eventSummaries().get(0)));

            assertThat(NeighbourOutlierGuard.flag(List.of(merged), pts, DEFAULTS).flagged()).isEmpty();
        }

        @Test
        @DisplayName("regions do not separate neighbours: a candidate is judged against other regions' slots")
        void regionsDoNotSeparateNeighbours() {
            BriefingDay split = day(D, TargetType.SUNRISE, region("North", sky(1L, "A", 4)),
                    region("South", sky(2L, "B", 2)), region("East", sky(3L, "C", 2)));

            assertThat(ids(NeighbourOutlierGuard.flag(List.of(split), pts, DEFAULTS))).containsExactly(1L);
        }

        @Test
        @DisplayName("the same place in two windows is judged per window")
        void judgedPerWindow() {
            BriefingDay sunrise = day(D, TargetType.SUNRISE, region("North", sky(1L, "A", 4),
                    sky(2L, "B", 2), sky(3L, "C", 2)));
            BriefingDay nextDay = day(D.plusDays(1), TargetType.SUNRISE, region("North", sky(1L, "A", 4),
                    sky(2L, "B", 4), sky(3L, "C", 2)));

            assertThat(NeighbourOutlierGuard.flag(List.of(sunrise, nextDay), pts, DEFAULTS).flagged())
                    .containsExactly(new NeighbourOutlierGuard.Key(1L, D, TargetType.SUNRISE));
        }
    }

    @Test
    @DisplayName("the result counts rated sky slots, those with a sky rating, windows and coverage")
    void resultCounts() {
        NeighbourOutlierGuard.Result result = NeighbourOutlierGuard.flag(List.of(
                day(D, TargetType.SUNRISE, region("North", sky(1L, "A", 4), sky(2L, "B", 2),
                        sky(3L, "C", 2), sky(4L, "No sky star", 3, null), unrated(5L, "Unrated"),
                        wood(6L, "Wood", 5))),
                day(D, TargetType.SUNSET, region("North", unrated(7L, "Nothing")))),
                points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)), DEFAULTS);

        assertThat(result.ratedSkySlots()).as("claude-rated, non-canopy").isEqualTo(4);
        assertThat(result.withSkyRating()).isEqualTo(3);
        assertThat(result.windows()).as("windows holding a rated sky slot").isEqualTo(1);
        assertThat(result.coveragePercent()).isEqualTo(75);
    }

    @Test
    @DisplayName("with nothing rated the coverage reads 100 and nothing is flagged")
    void nothingRated() {
        NeighbourOutlierGuard.Result result = NeighbourOutlierGuard.flag(List.of(), Map.of(), DEFAULTS);

        assertThat(result.flagged()).isEmpty();
        assertThat(result.ratedSkySlots()).isZero();
        assertThat(result.coveragePercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("Caw Gap, 18 Oct: a 4-star slot with a 1-star neighbour 2.25 km away, a 2-star one at "
            + "2.2 km and four more low places inside 5 km is flagged; Cawfields alone is not")
    void cawGapRealNumbers() {
        Map<Long, LocationPoint> pts = points(
                at(1, 0, 0, 240),       // Caw Gap
                at(2, 2.25, 0, 235),    // Cawfields, 1 star
                at(3, 0, 2.2, 250),     // Steel Rigg, 2 stars
                at(4, -2.8, 1.0, 230), at(5, 1.5, -3.0, 245), at(6, -1.0, -3.5, 238),
                at(7, 3.0, 2.5, 225));
        BriefingSlot[] slots = {sky(1L, "Caw Gap", 4), sky(2L, "Cawfields", 1), sky(3L, "Steel Rigg", 2),
            sky(4L, "Walltown", 2), sky(5L, "Housesteads", 1), sky(6L, "Sycamore Gap", 2),
            sky(7L, "Hotbank", 1)};

        assertThat(flaggedIds(DEFAULTS, pts, slots)).containsExactly(1L);
        assertThat(flaggedIds(DEFAULTS, pts, sky(1L, "Caw Gap", 4), sky(2L, "Cawfields", 1)))
                .as("one neighbour is no evidence").isEmpty();
    }

    @Test
    @DisplayName("the date of a flag is the day's, not the slot's solar time")
    void keyUsesTheDaysDate() {
        LocalDate other = LocalDate.of(2026, 10, 19);
        NeighbourOutlierGuard.Result result = NeighbourOutlierGuard.flag(List.of(
                day(other, TargetType.SUNRISE, region("North", sky(1L, "A", 4), sky(2L, "B", 2),
                        sky(3L, "C", 2)))),
                points(at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null)), DEFAULTS);

        assertThat(result.flagged()).containsExactly(
                new NeighbourOutlierGuard.Key(1L, other, TargetType.SUNRISE));
    }
}
