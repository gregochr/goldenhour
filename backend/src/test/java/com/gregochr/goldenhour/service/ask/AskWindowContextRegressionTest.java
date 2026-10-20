package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prompt regression for the window-context change (ask-window-context plan §2.6): does Ask judge a window
 * the way the owner reads the heat map, from the rating distribution {@code list_windows} now serves and
 * the criteria in the prompt? The real {@link ClaudeAskEngine}, real prompt, schemas, tools and validator,
 * a fixed forecast, a real API call, three weekends:
 * <ul>
 *   <li><b>stay-home</b> ({@code ask-stay-home-weekend.json}): the owner's example of 17-18 October 2026,
 *       almost everything 3★ or below with a few coastal 4★ exceptions and a BEST BET on one of them;</li>
 *   <li><b>game-on</b> ({@code ask-game-on-weekend.json}): the same roster with a broadly game-on Saturday
 *       evening and a 5★ BEST BET;</li>
 *   <li><b>mixed</b> ({@code ask-mixed-weekend.json}): an all-blue Saturday (every region stood down)
 *       beside a broadly game-on Sunday, the case that proves the Ready prompt names every window "this
 *       weekend" covers and not only the ones holding a candidate.</li>
 * </ul>
 *
 * <p><b>The assertions are pending the owner's approval.</b> They encode what a good answer must be under
 * the plan's framing reading of "must not lead with a 4★ pick" (owner decision Q7): a stay-home window's
 * exceptions may still be carried as picks, so long as every pick is game on (4★ or better), none is a
 * synthetic filler place, there are at most three, and the summary says "stay home". They are written
 * without an API key and have never been run. They assert structure and the owner's own words, never an
 * ordering of tool calls (a parallel {@code rank_spots} and {@code get_hot_topics} turn is a good
 * investigation), and only the one wording rule the plan names, which the owner may keep or strike
 * ({@link #STAY_HOME}). Under the other reading of Q7 (no picks at all on a stay-home window) replace
 * {@link #gameOnPicksOnly} with an assertion that the picks are empty.
 *
 * <p>Each script is run {@link #RUNS} times and must pass {@link #PASS_BAR} of them: a model's answer
 * varies, and one run proves nothing. Tune the prompt against a paired control, never against a recorded
 * baseline. The three existing Ask regression classes are neither edited nor shared with; they are re-run
 * unchanged afterwards and must still pass.
 *
 * <p>Excluded from {@code mvn verify} (tagged "prompt-regression") and skipped without an API key. Run on
 * demand, spending real money (a few pence a run; about sixty calls), with:
 * <pre>
 *   cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression \
 *       -Dtest=AskWindowContextRegressionTest
 * </pre>
 */
@Tag("prompt-regression")
class AskWindowContextRegressionTest {

    private static final String DIR = "/prompt-regression/ask/";

    /** How many times each script is run. */
    private static final int RUNS = 10;

    /** How many of those runs must pass. */
    private static final int PASS_BAR = 9;

    /**
     * The owner's words for a stay-home window, and the plan's "not a weekend to go out for". {@code not
     * worth} alone is deliberately not in it ("not worth missing" would match). Owner's to keep or strike
     * (plan Q3).
     */
    private static final Pattern STAY_HOME = Pattern.compile(
            "stay(ing)? (at )?home|not (a |the )?(weekend|window|morning|evening|day|night|one) (worth|to go)",
            Pattern.CASE_INSENSITIVE);

    private static final String SAT_SUNSET = "2026-10-17_sunset";
    private static final String SUN_SUNRISE = "2026-10-18_sunrise";
    private static final String SUN_SUNSET = "2026-10-18_sunset";

    private static final long DAILY_RUN_ID = 1L;
    private static final long READY_RUN_ID = 2L;

    /** A signed-in Pro asker with no stored drive times: user-bound, not personal. */
    private static final AskUserContext ASKER = new AskUserContext(7L, UserRole.PRO_USER, false);

    private static JsonNode stayHomeFixture;
    private static JsonNode gameOnFixture;
    private static JsonNode mixedFixture;
    private static AskSnapshot stayHome;
    private static AskSnapshot gameOn;
    private static AskSnapshot mixed;
    private static ClaudeAskEngine engine;

    @BeforeAll
    static void setUp() throws IOException {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY is not set: the Ask window-context regression makes real API calls");

        stayHomeFixture = AskWindowFixtureBuilder.load(DIR + "ask-stay-home-weekend.json");
        gameOnFixture = AskWindowFixtureBuilder.load(DIR + "ask-game-on-weekend.json");
        mixedFixture = AskWindowFixtureBuilder.load(DIR + "ask-mixed-weekend.json");
        stayHome = AskWindowFixtureBuilder.snapshot(stayHomeFixture);
        gameOn = AskWindowFixtureBuilder.snapshot(gameOnFixture);
        mixed = AskWindowFixtureBuilder.snapshot(mixedFixture);

        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        AskJobRunService jobRuns = mock(AskJobRunService.class);
        when(jobRuns.dailyRunId()).thenReturn(DAILY_RUN_ID);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        engine = new ClaudeAskEngine(new AnthropicApiClient(client), new AskProperties(), jobRuns,
                mock(DriveTimeResolver.class), new AskAnswerValidator(), new AskPromptBuilder(),
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), AskLocationNames.NONE);
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    // -- the stay-home weekend --------------------------------------------------------------

    /** (a) "Best spot this weekend?" as the shared Ready conversation: user-less, anchored on the BEST BET. */
    @Test
    void stayHome_readyBestWeekend() {
        passRate("stay-home / Ready BEST_WEEKEND", () -> {
            AskRun run = ready(ReadyQuestion.BEST_WEEKEND, stayHome);
            List<String> faults = answered(run);
            faults.addAll(gameOnPicksOnly(run, true));
            faults.addAll(saysStayHome(run));
            if (!run.trace().stream().anyMatch(call -> AskToolSchemas.LIST_WINDOWS.equals(call.tool()))) {
                faults.add("list_windows was never called, so the spread was never read");
            }
            AskAnswer answer = run.outcome().answer();
            if (answer != null && !answer.picks().isEmpty() && !answer.picks().getFirst().windowId()
                    .equals(AskWindowFixtureBuilder.bestBetWindowId(stayHomeFixture))) {
                faults.add("pick 1 is not on the BEST BET window");
            }
            return faults;
        });
    }

    /** (b) The same question typed by a Pro asker with no drive times. */
    @Test
    void stayHome_typedBestWeekend() {
        passRate("stay-home / typed best weekend", () -> {
            AskRun run = typed("Best spot this weekend?", stayHome);
            List<String> faults = answered(run);
            faults.addAll(gameOnPicksOnly(run, true));
            faults.addAll(saysStayHome(run));
            return faults;
        });
    }

    /** (c) One window of the weekend. */
    @Test
    void stayHome_typedSundaySunrise() {
        passRate("stay-home / typed Sunday sunrise", () -> {
            AskRun run = typed("Anything for Sunday sunrise?", stayHome);
            List<String> faults = answered(run);
            faults.addAll(gameOnPicksOnly(run, true));
            faults.addAll(saysStayHome(run));
            return faults;
        });
    }

    // -- the game-on weekend ----------------------------------------------------------------

    /** (d) "Best spot this weekend?" when Saturday evening is broadly game on. */
    @Test
    void gameOn_readyBestWeekend() {
        passRate("game-on / Ready BEST_WEEKEND", () -> {
            AskRun run = ready(ReadyQuestion.BEST_WEEKEND, gameOn);
            List<String> faults = answered(run);
            faults.addAll(gameOnPicksOnly(run, false));
            AskAnswer answer = run.outcome().answer();
            if (answer != null) {
                if (answer.picks().size() < 2 || answer.picks().size() > 3) {
                    faults.add("expected 2 or 3 picks on a game-on weekend, got " + answer.picks().size());
                }
                if (!answer.picks().isEmpty() && !answer.picks().getFirst().windowId()
                        .equals(AskWindowFixtureBuilder.bestBetWindowId(gameOnFixture))) {
                    faults.add("pick 1 is not on the BEST BET window");
                }
            }
            faults.addAll(doesNotSayStayHome(run));
            return faults;
        });
    }

    /** (e) A yes/no question about a window that is broadly game on. */
    @Test
    void gameOn_typedSaturdayEvening() {
        passRate("game-on / typed Saturday evening", () -> {
            AskRun run = typed("Is Saturday evening worth going out for?", gameOn);
            List<String> faults = answered(run);
            faults.addAll(gameOnPicksOnly(run, false));
            faults.addAll(doesNotSayStayHome(run));
            return faults;
        });
    }

    // -- the mixed weekend ------------------------------------------------------------------

    /**
     * An all-blue Saturday beside a game-on Sunday. The Ready prompt names every window the question
     * covers, so the answer is about BOTH days: it carries the server's figure for the stood-down Saturday
     * (a structural fact, joined by the validator from the covered set) and its summary names Saturday
     * rather than reading "this weekend" as Sunday alone.
     */
    @Test
    void mixed_readyBestWeekendNamesBothDays() {
        passRate("mixed / Ready BEST_WEEKEND", () -> {
            AskRun run = ready(ReadyQuestion.BEST_WEEKEND, mixed);
            List<String> faults = answered(run);
            faults.addAll(gameOnPicksOnly(run, false));
            AskAnswer answer = run.outcome().answer();
            if (answer != null) {
                answer.windows().stream().filter(w -> SAT_SUNSET.equals(w.windowId())).findFirst().ifPresentOrElse(
                        w -> {
                            if (w.spread().regions().stoodDown() == null) {
                                faults.add("the carried Saturday spread does not show the stood-down regions");
                            }
                        }, () -> faults.add("Saturday's spread is not carried"));
                answer.picks().stream().filter(p -> !p.windowId().startsWith("2026-10-18"))
                        .forEach(p -> faults.add("a pick on a window that is not Sunday's: " + p.windowId()));
                if (!Pattern.compile("saturday", Pattern.CASE_INSENSITIVE).matcher(answer.summary()).find()) {
                    faults.add("the summary does not name Saturday: " + answer.summary());
                }
            }
            return faults;
        });
    }

    // -- checks -----------------------------------------------------------------------------

    private static List<String> answered(AskRun run) {
        List<String> faults = new ArrayList<>();
        if (run.outcome().status() != AskOutcome.Status.OK) {
            faults.add("status " + run.outcome().status() + " (engine reason: " + run.reason() + ")");
            return faults;
        }
        AskAnswer answer = run.outcome().answer();
        if (!answer.answerable()) {
            faults.add("not answerable");
        }
        int words = answer.summary().trim().split("\\s+").length;
        if (words > AskAnswerValidator.SUMMARY_WORDS) {
            faults.add("summary is " + words + " words");
        }
        return faults;
    }

    /**
     * Picks are at most three and every one is game on (4★ or better). With {@code noFiller}, none is one of
     * the fixture's synthetic places, which the stay-home weekend rates 3★ or below.
     */
    private static List<String> gameOnPicksOnly(AskRun run, boolean noFiller) {
        List<String> faults = new ArrayList<>();
        AskAnswer answer = run.outcome().answer();
        if (answer == null) {
            return faults;
        }
        if (answer.picks().size() > AskAnswerValidator.MAX_PICKS) {
            faults.add(answer.picks().size() + " picks");
        }
        for (AskPick pick : answer.picks()) {
            if (pick.ratingAtAnswer() == null || pick.ratingAtAnswer() < AskSnapshot.Spread.GAME_ON_FROM) {
                faults.add(pick.locationName() + " is a " + pick.ratingAtAnswer() + "★ pick, not game on");
            }
            if (noFiller && pick.locationName().contains(AskWindowFixtureBuilder.SYNTHETIC_MARK)) {
                faults.add(pick.locationName() + " is a filler place");
            }
        }
        return faults;
    }

    private static List<String> saysStayHome(AskRun run) {
        AskAnswer answer = run.outcome().answer();
        List<String> faults = new ArrayList<>();
        if (answer != null && !STAY_HOME.matcher(answer.summary()).find()) {
            faults.add("the summary does not call it a stay-home window: " + answer.summary());
        }
        return faults;
    }

    private static List<String> doesNotSayStayHome(AskRun run) {
        AskAnswer answer = run.outcome().answer();
        List<String> faults = new ArrayList<>();
        if (answer != null && STAY_HOME.matcher(answer.summary()).find()) {
            faults.add("the summary calls a game-on window stay-home: " + answer.summary());
        }
        return faults;
    }

    // -- running ----------------------------------------------------------------------------

    private static AskRun typed(String text, AskSnapshot snapshot) {
        AskQuestion question = new AskQuestion(text, text.toLowerCase(Locale.ROOT), null, AskScope.ALL, "plan");
        return log(text, engine.run(question, snapshot, ASKER, AskRunOptions.none()));
    }

    private static AskRun ready(ReadyQuestion asked, AskSnapshot snapshot) {
        ReadyQuestion.Offer offer = asked.offer(snapshot, AskScope.ALL)
                .orElseThrow(() -> new AssertionError(asked + " is not offered on this fixture"));
        AskQuestion question = AskQuestion.of(offer.text(), offer.contextWindowId(), AskScope.ALL, "plan");
        return log(offer.text() + " [Ready]", engine.run(question, snapshot, AskUserContext.userLess(),
                AskRunOptions.ready(READY_RUN_ID, asked, offer)));
    }

    private static AskRun log(String label, AskRun run) {
        System.out.println("[ask-window-regression] " + label + " -> " + run.outcome().status() + ": "
                + (run.outcome().answer() == null ? run.reason() : run.outcome().answer().summary())
                + " | picks: " + (run.outcome().answer() == null ? "-" : run.outcome().answer().picks().stream()
                        .map(p -> p.locationName() + " " + p.ratingAtAnswer() + "★").collect(Collectors.joining(", ")))
                + " | trace: " + run.trace().stream().map(AskTools.ToolCall::tool).collect(Collectors.joining(", ")));
        return run;
    }

    /** Runs a script {@value #RUNS} times; at least {@value #PASS_BAR} must pass, and the faults are reported. */
    private static void passRate(String script, Supplier<List<String>> attempt) {
        int passed = 0;
        Map<String, Integer> faults = new LinkedHashMap<>();
        for (int i = 0; i < RUNS; i++) {
            List<String> found = attempt.get();
            if (found.isEmpty()) {
                passed++;
            }
            found.forEach(f -> faults.merge(f, 1, Integer::sum));
        }
        assertThat(passed).as("%s: passed %d of %d runs (bar %d); faults: %s", script, passed, RUNS, PASS_BAR, faults)
                .isGreaterThanOrEqualTo(PASS_BAR);
    }
}
