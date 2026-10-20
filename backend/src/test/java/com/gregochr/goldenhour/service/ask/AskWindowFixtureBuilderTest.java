package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three weekend fixtures of {@link AskWindowContextRegressionTest}, checked against the figures the plan
 * gives for them without an API key: the regression itself is owner-run, so a fixture that does not say what
 * its name claims would otherwise go unseen until it had spent money.
 */
class AskWindowFixtureBuilderTest {

    private static final String SAT_SUNSET = "2026-10-17_sunset";
    private static final String SUN_SUNRISE = "2026-10-18_sunrise";
    private static final String SUN_SUNSET = "2026-10-18_sunset";

    private static AskSnapshot snapshot(String name) throws IOException {
        return AskWindowFixtureBuilder.snapshot(AskWindowFixtureBuilder.load("/prompt-regression/ask/" + name));
    }

    private static AskSnapshot.Spread spread(AskSnapshot snapshot, String windowId) {
        return snapshot.spread(windowId, AskScope.ALL).orElseThrow();
    }

    @Test
    @DisplayName("the stay-home weekend is the owner's example: three windows at the plan's bands, 12 unrated on "
            + "Sunday sunrise, the game-on places named coastal spots, and a BEST BET on Saturday's 4★")
    void stayHomeWeekendIsTheOwnersExample() throws IOException {
        AskSnapshot snapshot = snapshot("ask-stay-home-weekend.json");

        assertThat(snapshot.windows()).extracting(AskSnapshot.Window::id)
                .containsExactly(SAT_SUNSET, SUN_SUNRISE, SUN_SUNSET);
        assertThat(spread(snapshot, SAT_SUNSET).bands()).containsExactly(0, 56, 122, 2, 0);
        assertThat(spread(snapshot, SUN_SUNRISE).bands()).containsExactly(1, 98, 100, 2, 0);
        assertThat(spread(snapshot, SUN_SUNRISE).unrated()).isEqualTo(12);
        assertThat(spread(snapshot, SUN_SUNSET).bands()).containsExactly(0, 43, 133, 4, 0);
        assertThat(spread(snapshot, SAT_SUNSET).rated()).isEqualTo(180);
        assertThat(snapshot.candidates(snapshot.window(SAT_SUNSET).orElseThrow(), AskScope.ALL))
                .filteredOn(c -> c.slot().rating() >= 4).extracting(c -> c.slot().name())
                .containsExactlyInAnyOrder("Bamburgh", "Seahouses");
        assertThat(snapshot.window(SAT_SUNSET).orElseThrow().pick().locationName()).isEqualTo("Bamburgh");
        assertThat(ReadyQuestion.BEST_WEEKEND.offer(snapshot, AskScope.ALL)).isPresent();
    }

    @Test
    @DisplayName("the game-on weekend has the plan's Saturday bands and a 5★ coastal BEST BET")
    void gameOnWeekend() throws IOException {
        AskSnapshot snapshot = snapshot("ask-game-on-weekend.json");

        assertThat(spread(snapshot, SAT_SUNSET).bands()).containsExactly(0, 5, 40, 110, 25);
        assertThat(spread(snapshot, SAT_SUNSET).gameOnPct()).isEqualTo(75);
        assertThat(snapshot.candidate(SAT_SUNSET, 1L).orElseThrow().slot().rating()).isEqualTo(5);
        assertThat(snapshot.window(SAT_SUNSET).orElseThrow().pick().locationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the mixed weekend has an all-blue Saturday (every region stood down) beside a broadly game-on "
            + "Sunday, and BEST_WEEKEND covers Saturday although no candidate is on it")
    void mixedWeekend() throws IOException {
        AskSnapshot snapshot = snapshot("ask-mixed-weekend.json");

        AskSnapshot.Spread saturday = spread(snapshot, SAT_SUNSET);
        assertThat(saturday.rated()).isZero();
        assertThat(saturday.regions().stoodDown()).isEqualTo(4);
        assertThat(spread(snapshot, SUN_SUNSET).gameOnPct()).isGreaterThan(40);
        ReadyQuestion.Offer offer = ReadyQuestion.BEST_WEEKEND.offer(snapshot, AskScope.ALL).orElseThrow();
        assertThat(offer.windowIds()).doesNotContain(SAT_SUNSET);
        assertThat(offer.coveredWindowIds()).contains(SAT_SUNSET, SUN_SUNRISE, SUN_SUNSET);
    }

    @Test
    @DisplayName("synthetic places are named so an assertion can tell them from the named ones, and the "
            + "fixtures' BEST BET windows are found")
    void syntheticNamesAndBestBets() throws IOException {
        JsonNode stay = AskWindowFixtureBuilder.load("/prompt-regression/ask/ask-stay-home-weekend.json");
        AskSnapshot snapshot = AskWindowFixtureBuilder.snapshot(stay);

        assertThat(snapshot.candidates(snapshot.window(SAT_SUNSET).orElseThrow(), AskScope.ALL))
                .filteredOn(c -> c.slot().rating() == 3)
                .allSatisfy(c -> assertThat(c.slot().name()).contains(AskWindowFixtureBuilder.SYNTHETIC_MARK));
        assertThat(AskWindowFixtureBuilder.bestBetWindowId(stay)).isEqualTo(SAT_SUNSET);
        assertThat(AskWindowFixtureBuilder.bestBetWindowId(AskWindowFixtureBuilder.load(
                "/prompt-regression/ask/ask-mixed-weekend.json"))).isEqualTo(SUN_SUNSET);
    }
}
