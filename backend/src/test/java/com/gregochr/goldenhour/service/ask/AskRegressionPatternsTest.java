package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the accept and reject cases of the owner-approved regexes and checks in the Ask prompt-regression classes
 * (ask-regression-triage-2026-11-27 plan, A1-A8), using the real answers the 2026-11-27 run printed. A plain unit
 * test: no API key, not tagged prompt-regression, so it runs in {@code mvn verify}.
 */
class AskRegressionPatternsTest {

    // -- A1: STAY_HOME -------------------------------------------------------------------------

    @Test
    void stayHomeAcceptsTheHyphenAndTheSanctionedPhrase() {
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher(
                "This weekend is a stay-home window with rare exceptions.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher("A stay-home weekend overall.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher("Time to stay at home.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher(
                "Most places are not worth going out for this weekend, but Bamburgh is.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher("Not a weekend worth going out for.").find())
                .isTrue();
    }

    @Test
    void stayHomeRejectsNotWorthMissingAndMarginal() {
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher(
                "Sunday sunrise is not worth missing at Bamburgh.").find()).isFalse();
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher(
                "This weekend is marginal across every region.").find()).isFalse();
    }

    @Test
    void firstSentenceStopsAtTheFirstFullStopAndSpace() {
        assertThat(AskWindowContextRegressionTest.firstSentence(
                "Saturday evening is on. Sunday is a stay-home window.")).isEqualTo("Saturday evening is on");
        assertThat(AskWindowContextRegressionTest.firstSentence("No full stop here")).isEqualTo("No full stop here");
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher(AskWindowContextRegressionTest.firstSentence(
                "Saturday evening is on. Sunday is a stay-home window.")).find()).isFalse();
        assertThat(AskWindowContextRegressionTest.STAY_HOME.matcher(AskWindowContextRegressionTest.firstSentence(
                "A stay-home weekend. Bamburgh is the exception.")).find()).isTrue();
    }

    // -- A2: MIXED and the Saturday name ------------------------------------------------------------

    @Test
    void mixedAcceptsNowhereNoPlaceAndTomorrow() {
        assertThat(AskWindowContextRegressionTest.MIXED.matcher(
                "Saturday has nowhere worth going to, but Sunday sunset is on.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.MIXED.matcher(
                "Saturday has no place worth going to; Sunday is the day.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.MIXED.matcher(
                "Sunday sunset is the forecast's best bet this weekend. Tomorrow sunset is not worth going out for "
                        + "across every region.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.MIXED.matcher("A mixed weekend.").find()).isTrue();
    }

    @Test
    void mixedRejectsAnAnswerThatReadsTheWeekendAsSimplyGood() {
        assertThat(AskWindowContextRegressionTest.MIXED.matcher(
                "Sunday sunset is the forecast's best bet this weekend at Bamburgh.").find()).isFalse();
        assertThat(AskWindowContextRegressionTest.MIXED.matcher(
                "Saturday looks great. Sunday has no place left unrated.").find()).isFalse();
    }

    @Test
    void namesSaturdayAcceptsTomorrow() {
        assertThat(AskWindowContextRegressionTest.NAMES_SATURDAY.matcher("Tomorrow sunset is poor.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.NAMES_SATURDAY.matcher("Saturday has nothing.").find()).isTrue();
        assertThat(AskWindowContextRegressionTest.NAMES_SATURDAY.matcher("Sunday is the best bet.").find()).isFalse();
    }

    // -- A3: SAME and the tie sentence ----------------------------------------------------------------

    @Test
    void sameAcceptsBothAndAsWell() {
        assertThat(AskFavouritesRegressionTest.SAME.matcher("Mary's Lighthouse and Bamburgh are both rated 4★.")
                .find()).isTrue();
        assertThat(AskFavouritesRegressionTest.SAME.matcher("Bamburgh is 4★ as well.").find()).isTrue();
        assertThat(AskFavouritesRegressionTest.SAME.matcher("Bamburgh also scores 4★.").find()).isTrue();
        assertThat(AskFavouritesRegressionTest.SAME.matcher("Bamburgh is 5★.").find()).isFalse();
    }

    @Test
    void tieIsStatedInTheSentenceThatNamesBamburgh() {
        assertThat(AskFavouritesRegressionTest.tieStatedWith(
                "Mary's Lighthouse and Bamburgh are both rated 4★ for Saturday morning.", "Bamburgh")).isTrue();
        assertThat(AskFavouritesRegressionTest.tieStatedWith(
                "Mary's Lighthouse is your pick. Bamburgh scores the same.", "Bamburgh")).isTrue();
        // "two places rate 4★" names nobody, and "both" in another sentence does not tie Bamburgh to Mary's.
        assertThat(AskFavouritesRegressionTest.tieStatedWith(
                "Two places rate 4★ for Saturday morning.", "Bamburgh")).isFalse();
        assertThat(AskFavouritesRegressionTest.tieStatedWith(
                "Both skies are clear. Bamburgh is the other place.", "Bamburgh")).isFalse();
    }

    // -- A5: the Hadrian's Wall checks -------------------------------------------------------------------

    @Test
    void areaSizeIsDerivedFromTheMembers() {
        assertThat(AskNamedAreaRegressionTest.AREA_SIZE.matcher("All three rated places are 4★ or better.").find())
                .isTrue();
        assertThat(AskNamedAreaRegressionTest.AREA_SIZE.matcher("All 3 places are 4★.").find()).isTrue();
        assertThat(AskNamedAreaRegressionTest.AREA_SIZE.matcher("Five places are rated 4★ or better.").find())
                .isFalse();
        assertThat(AskNamedAreaRegressionTest.sizePattern(2).matcher("two places").find()).isTrue();
    }

    @Test
    void wholeRegionCountIsRejected() {
        assertThat(AskNamedAreaRegressionTest.WHOLE_REGION_COUNT.matcher(
                "Five places rated 4★ or better look good.").find()).isTrue();
        assertThat(AskNamedAreaRegressionTest.WHOLE_REGION_COUNT.matcher("5 rated places on Saturday.").find())
                .isTrue();
        assertThat(AskNamedAreaRegressionTest.WHOLE_REGION_COUNT.matcher(
                "All three rated places are 4★ or better.").find()).isFalse();
    }

    // -- A8: the radius wording -----------------------------------------------------------------------------

    @Test
    void radiusAcceptsMilesOrMinutesOfHome() {
        assertThat(AskNightRegressionTest.AT_RADIUS.matcher("Within 25 miles of home there is Allenheads.").find())
                .isTrue();
        assertThat(AskNightRegressionTest.AT_RADIUS.matcher("Within 40 minutes of home, Allenheads is 4★.").find())
                .isTrue();
        assertThat(AskNightRegressionTest.AT_RADIUS.matcher("Closer to home there is Allenheads.").find()).isFalse();
    }

    // -- A6: the overstated alert level ------------------------------------------------------------------

    @Test
    void overstatedAlertRejectsStrongOrSevereDescribingTheAurora() {
        assertThat(AskNightRegressionTest.OVERSTATED_ALERT.matcher("strong geomagnetic activity").find()).isTrue();
        assertThat(AskNightRegressionTest.OVERSTATED_ALERT.matcher("Strong aurora tonight").find()).isTrue();
        assertThat(AskNightRegressionTest.OVERSTATED_ALERT.matcher("the alert is severe").find()).isTrue();
        assertThat(AskNightRegressionTest.OVERSTATED_ALERT.matcher("severe storm").find()).isTrue();
    }

    @Test
    void overstatedAlertAllowsStrongStargazingAndNeutralWording() {
        assertThat(AskNightRegressionTest.OVERSTATED_ALERT.matcher("strong stargazing conditions").find()).isFalse();
        assertThat(AskNightRegressionTest.OVERSTATED_ALERT.matcher("active geomagnetic conditions").find())
                .isFalse();
        assertThat(AskNightRegressionTest.OVERSTATED_ALERT.matcher("Kp 6.0").find()).isFalse();
    }
}
