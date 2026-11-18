package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.FavouriteLocations;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prompt regression for favourites in Ask (find-and-favourites-plan D4b, F4b): does Ask answer a favourite and the
 * favourite-blind answer as a labelled pair, in the plan's fixed words, and leave an unaffected answer alone? The
 * real {@link ClaudeAskEngine}, real prompt, schemas, tools and validator, a fixed forecast, a real API call:
 * <ol>
 *   <li><b>Tie pair.</b> Mary's Lighthouse (the reader's favourite) and Bamburgh both 4★: the answer leads with
 *       Mary's and names Bamburgh as scoring the same;</li>
 *   <li><b>Lower-rated favourite.</b> Bamburgh 5★, the favourite Mary's 4★: the answer leads with Bamburgh and
 *       says Mary's is 4★, never that Mary's is the best;</li>
 *   <li><b>Clear best.</b> The favourite is the clear best: one answer, no "also scores the same";</li>
 *   <li><b>No favourites.</b> A reader with none: the answer mentions no favourite.</li>
 * </ol>
 *
 * <p>Every answer must also avoid the word "wildcard". <b>New cases only.</b> The existing Ask regression
 * assertions are untouched. <b>The assertions are pending the owner's approval</b>: they are written without an API
 * key, have never been run, and assert structure and the plan's wording rules, never an ordering of tool calls. Each
 * script is run {@link #RUNS} times and must pass {@link #PASS_BAR} of them.
 *
 * <p>Excluded from {@code mvn verify} (tagged "prompt-regression") and skipped without an API key. Run on
 * demand, spending real money, with:
 * <pre>
 *   cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression \
 *       -Dtest=AskFavouritesRegressionTest
 * </pre>
 */
@Tag("prompt-regression")
class AskFavouritesRegressionTest {

    /** How many times each script is run. */
    private static final int RUNS = 5;

    /** How many of those runs must pass. */
    private static final int PASS_BAR = 4;

    private static final String QUESTION_BEST = "Where is best on Saturday morning?";
    private static final String QUESTION_BEST_AND_MINE = "Where is best on Saturday morning, and how is my "
            + "favourite place looking?";

    private static final long BAMBURGH = 1L;
    private static final long MARYS = 2L;
    private static final long CULLERCOATS = 3L;

    private static final Pattern WILDCARD = Pattern.compile("wild ?card", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAME = Pattern.compile("same|equal|tie|also", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARYS_FOUR = Pattern.compile("Mary'?s[^.]{0,60}4\\s?★|4\\s?★[^.]{0,60}Mary'?s",
            Pattern.CASE_INSENSITIVE);

    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final long DAILY_RUN_ID = 1L;

    private static final AskUserContext WITH_MARYS = asker(Set.of(MARYS));
    private static final AskUserContext NO_STARS = asker(Set.of());

    private static ClaudeAskEngine engine;

    private static AskUserContext asker(Set<Long> favourites) {
        return new AskUserContext(7L, UserRole.PRO_USER, false, null, new FavouriteLocations(favourites, Map.of()));
    }

    private static AskSnapshot forecast(int bamburgh, int marys, int cullercoats) {
        BriefingRegion region = AskFixtures.region("Northumberland & Tyneside", true,
                AskFixtures.slot(BAMBURGH, "Bamburgh", bamburgh),
                AskFixtures.slot(MARYS, "Mary's Lighthouse", marys),
                AskFixtures.slot(CULLERCOATS, "Cullercoats", cullercoats));
        BriefingWindow window = AskFixtures.window(LocalDateTime.of(2026, 10, 10, 6, 0), DisplayVerdict.WORTH_IT,
                Math.max(bamburgh, Math.max(marys, cullercoats)), null);
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.day(SATURDAY,
                AskFixtures.summary(TargetType.SUNRISE, window, region))), List.of()));
    }

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY is not set: the Ask favourites regression makes real API calls");
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        AskJobRunService jobRuns = mock(AskJobRunService.class);
        when(jobRuns.dailyRunId()).thenReturn(DAILY_RUN_ID);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        engine = new ClaudeAskEngine(new AnthropicApiClient(client), new AskProperties(),
                AskModelFixtures.haiku(), jobRuns,
                mock(DriveTimeResolver.class), new AskAnswerValidator(), new AskPromptBuilder(),
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), AskLocationNames.NONE);
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    // -- the scripts ------------------------------------------------------------------------

    /** (1) A tie: the favourite leads, the favourite-blind winner is named as scoring the same. */
    @Test
    void aTieIsALeadAndACompanion() {
        AskSnapshot tie = forecast(4, 4, 3);
        passRate("tie pair", () -> {
            AskRun run = typed(QUESTION_BEST, tie, WITH_MARYS);
            List<String> faults = answered(run);
            if (!faults.isEmpty()) {
                return faults;
            }
            String summary = run.outcome().answer().summary();
            List<AskPick> picks = run.outcome().answer().picks();
            if (picks.isEmpty() || picks.getFirst().locationId() != MARYS) {
                faults.add("the lead is not Mary's Lighthouse: " + picks);
            }
            if (!summary.contains("Bamburgh")) {
                faults.add("Bamburgh, the tied place, is not named: " + summary);
            }
            if (!SAME.matcher(summary).find()) {
                faults.add("the tie is not said to score the same: " + summary);
            }
            addWildcardFault(summary, faults);
            return faults;
        });
    }

    /** (2) A lower-rated favourite is a companion, "your Mary's is 4★", never the best. */
    @Test
    void aLowerRatedFavouriteIsNamedAtItsOwnRating() {
        AskSnapshot clearBest = forecast(5, 4, 3);
        passRate("lower-rated favourite", () -> {
            AskRun run = typed(QUESTION_BEST_AND_MINE, clearBest, WITH_MARYS);
            List<String> faults = answered(run);
            if (!faults.isEmpty()) {
                return faults;
            }
            String summary = run.outcome().answer().summary();
            List<AskPick> picks = run.outcome().answer().picks();
            if (picks.isEmpty() || picks.getFirst().locationId() != BAMBURGH) {
                faults.add("the lead is not Bamburgh: " + picks);
            }
            if (!MARYS_FOUR.matcher(summary).find()) {
                faults.add("Mary's is not given its own 4★: " + summary);
            }
            addWildcardFault(summary, faults);
            return faults;
        });
    }

    /** (3) The favourite is the clear best: one answer. */
    @Test
    void aClearBestFavouriteIsOneAnswer() {
        AskSnapshot clearBest = forecast(4, 5, 3);
        passRate("clear best", () -> {
            AskRun run = typed(QUESTION_BEST, clearBest, WITH_MARYS);
            List<String> faults = answered(run);
            if (!faults.isEmpty()) {
                return faults;
            }
            String summary = run.outcome().answer().summary();
            List<AskPick> picks = run.outcome().answer().picks();
            if (picks.isEmpty() || picks.getFirst().locationId() != MARYS) {
                faults.add("the lead is not Mary's Lighthouse: " + picks);
            }
            if (summary.toLowerCase(Locale.ROOT).contains("scores the same")) {
                faults.add("a tie is claimed where there is none: " + summary);
            }
            addWildcardFault(summary, faults);
            return faults;
        });
    }

    /** (4) A reader with no favourites gets the ordinary answer, with no favourite wording. */
    @Test
    void noFavouritesMeansNoFavouriteWording() {
        AskSnapshot tie = forecast(4, 4, 3);
        passRate("no favourites", () -> {
            AskRun run = typed(QUESTION_BEST, tie, NO_STARS);
            List<String> faults = answered(run);
            if (!faults.isEmpty()) {
                return faults;
            }
            String summary = run.outcome().answer().summary().toLowerCase(Locale.ROOT);
            if (summary.contains("favourite") || summary.contains("your ")) {
                faults.add("favourite wording for a reader with no favourites: " + summary);
            }
            addWildcardFault(summary, faults);
            return faults;
        });
    }

    // -- running ----------------------------------------------------------------------------

    private static List<String> answered(AskRun run) {
        List<String> faults = new ArrayList<>();
        if (run.outcome().status() != AskOutcome.Status.OK || !run.outcome().answer().answerable()) {
            faults.add("not answered: " + run.outcome().status() + " (engine reason: " + run.reason() + ")");
        }
        return faults;
    }

    private static void addWildcardFault(String summary, List<String> faults) {
        if (WILDCARD.matcher(summary).find()) {
            faults.add("the summary uses the word wildcard: " + summary);
        }
    }

    private static AskRun typed(String text, AskSnapshot forecast, AskUserContext asker) {
        AskQuestion question = new AskQuestion(text, text.toLowerCase(Locale.ROOT), null, AskScope.ALL, "plan");
        AskRun run = engine.run(question, forecast, asker, AskRunOptions.none());
        System.out.println("[ask-favourites-regression] " + text + " -> " + run.outcome().status() + ": "
                + (run.outcome().answer() == null ? run.reason() : run.outcome().answer().summary())
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
