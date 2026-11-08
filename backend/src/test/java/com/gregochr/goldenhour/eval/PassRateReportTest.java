package com.gregochr.goldenhour.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PassRateReport} — the pass^k accumulator and its direction-bucketing.
 *
 * <p>This is the unit verification the harness build depends on: the bucketing must correctly
 * classify forced below-band and above-band ratings <em>without</em> calling real Claude. The
 * scores here are stubbed, so a CI run exercises the diagnostic logic that the gated, real-Claude
 * {@code SkyRatingRegressionTest} relies on.
 */
class PassRateReportTest {

    @Test
    void allInBandReportsAPerfectPassRateAndNoMisses() {
        PassRateReport report = new PassRateReport("strong", RatingBand.atLeast(4));
        feed(report, 4, 5, 4, 5, 4, 5, 4, 5);

        assertEquals(8, report.runs());
        assertEquals(8, report.passes());
        assertEquals(0, report.belowMisses());
        assertEquals(0, report.aboveMisses());
        assertEquals(1.0, report.passRate());
        assertTrue(report.allPassed());
    }

    @Test
    void belowBandMissesBucketAsTooCautious() {
        // Band {4,5}: ratings of 3 and 2 are DOWN misses (scorer too cautious).
        PassRateReport report = new PassRateReport("strong", RatingBand.atLeast(4));
        feed(report, 5, 4, 3, 5, 2, 4, 4, 5);

        assertEquals(8, report.runs());
        assertEquals(6, report.passes());
        assertEquals(2, report.belowMisses(), "3 and 2 are below the band");
        assertEquals(0, report.aboveMisses());
        assertEquals(0.75, report.passRate());
        assertFalse(report.allPassed());
    }

    @Test
    void aboveBandMissesBucketAsTooGenerous() {
        // Band {2,3}: ratings of 4 and 5 are UP misses (scorer too generous).
        PassRateReport report = new PassRateReport("flat", new RatingBand(2, 3));
        feed(report, 2, 3, 4, 2, 5, 3, 3, 2);

        assertEquals(8, report.runs());
        assertEquals(6, report.passes());
        assertEquals(0, report.belowMisses());
        assertEquals(2, report.aboveMisses(), "4 and 5 are above the band");
        assertEquals(0.75, report.passRate());
        assertFalse(report.allPassed());
    }

    @Test
    void missesInBothDirectionsAreBucketedSeparately() {
        // Band {3}: a 2 is DOWN, a 4 is UP — the accumulator must split them.
        PassRateReport report = new PassRateReport("middling", RatingBand.exactly(3));
        feed(report, 3, 2, 3, 4, 3);

        assertEquals(5, report.runs());
        assertEquals(3, report.passes());
        assertEquals(1, report.belowMisses());
        assertEquals(1, report.aboveMisses());
        assertEquals(0.6, report.passRate());
        assertFalse(report.allPassed());
    }

    @Test
    void emptyReportHasZeroPassRateAndDoesNotClaimSuccess() {
        PassRateReport report = new PassRateReport("unused", RatingBand.atLeast(4));
        assertEquals(0, report.runs());
        assertEquals(0.0, report.passRate());
        assertFalse(report.allPassed(), "a report with no runs has not passed");
    }

    @Test
    void renderMentionsBandPassRateAndBothMissDirections() {
        PassRateReport report = new PassRateReport("example-strong-fixture", RatingBand.atLeast(4));
        feed(report, 5, 4, 3, 5);

        String rendered = report.render();
        assertTrue(rendered.contains("example-strong-fixture"), "names the fixture");
        assertTrue(rendered.contains("{4–5}"), "shows the band");
        assertTrue(rendered.contains("passRate=75.0%"), "shows the pass rate");
        assertTrue(rendered.contains("1 DOWN"), "shows the below-band miss count");
        assertTrue(rendered.contains("0 UP"), "shows the above-band miss count");
    }

    @Test
    void recordOutcome_countsOnlyFullyPassingRunsAsPassesAndBucketsBoundMissesSeparately() {
        PassRateReport report = new PassRateReport("bounded", RatingBand.exactly(4));
        MissDirection in = MissDirection.IN_BAND;
        report.record(new RunOutcome(4, in, in, in, false));
        report.record(new RunOutcome(4, in, MissDirection.BELOW, in, false));
        report.record(new RunOutcome(4, in, in, MissDirection.ABOVE, false));
        report.record(new RunOutcome(5, MissDirection.ABOVE, in, in, false));
        report.record(new RunOutcome(3, MissDirection.BELOW, in, in, false));
        report.record(RunOutcome.malformedRun(null));

        assertEquals(6, report.runs());
        assertEquals(1, report.passes());
        assertEquals(1, report.belowMisses());
        assertEquals(1, report.aboveMisses());
        assertEquals(2, report.boundMisses());
        assertEquals(1, report.malformed());
        assertFalse(report.allPassed());
        assertTrue(report.render().contains("bound misses: 2"));
        assertTrue(report.render().contains("fiery 1 DOWN/0 UP, golden 0 DOWN/1 UP"));
    }

    @Test
    void recordOutcome_allPassingRunsIsAllPassedAndRendersNoBoundsLine() {
        PassRateReport report = new PassRateReport("ok", RatingBand.exactly(4));
        MissDirection in = MissDirection.IN_BAND;
        report.record(new RunOutcome(4, in, in, in, false));
        report.record(new RunOutcome(4, in, in, in, false));

        assertTrue(report.allPassed());
        assertEquals(2, report.passes());
        assertFalse(report.render().contains("bound misses"));
    }

    private static void feed(PassRateReport report, int... ratings) {
        for (int rating : ratings) {
            report.record(rating);
        }
    }
}
