package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prompt regression for {@code look_up_place} (ask-place-lookup plan §5): does Ask explain one named place's
 * rating when the place is not one it would recommend, instead of saying it is "not in the forecast"? The real
 * {@link ClaudeAskEngine}, real prompt, schemas, tools and validator, a fixed forecast, a real API call:
 * <ol>
 *   <li>"Why is Alnmouth 4★ on Saturday morning?" with Alnmouth 4★ in a thin-sample region (the owner's
 *       2026-10-22 shape): answerable, no picks, a summary that quotes 4 and does not say "not in the forecast";</li>
 *   <li>the same with Alnmouth a neighbour outlier: answerable, no picks, and wording about the places beside it;</li>
 *   <li>"Why is Atlantis rated 5★?": not answerable, because no such place is in the forecast.</li>
 * </ol>
 *
 * <p><b>The assertions are pending the owner's approval.</b> They are written without an API key and have never
 * been run. They assert structure and the plan's wording rules, never an ordering of tool calls. Each script is
 * run {@link #RUNS} times and must pass {@link #PASS_BAR} of them: a model's answer varies, and one run proves
 * nothing.
 *
 * <p>Excluded from {@code mvn verify} (tagged "prompt-regression") and skipped without an API key. Run on
 * demand, spending real money, with:
 * <pre>
 *   cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression \
 *       -Dtest=AskPlaceLookupRegressionTest
 * </pre>
 */
@Tag("prompt-regression")
class AskPlaceLookupRegressionTest {

    /** How many times each script is run. */
    private static final int RUNS = 10;

    /** How many of those runs must pass. */
    private static final int PASS_BAR = 9;

    private static final String QUESTION_ALNMOUTH = "Why is Alnmouth 4★ on Saturday morning?";
    private static final String QUESTION_ATLANTIS = "Why is Atlantis rated 5★?";

    /** The frame the owner was shown, which an explanation must not use. */
    private static final Pattern NOT_IN_FORECAST = Pattern.compile("not in the forecast|doesn'?t have|does not have",
            Pattern.CASE_INSENSITIVE);

    /** A summary that says the place is rated above its surroundings, in any of the natural words. */
    private static final Pattern BESIDE_IT = Pattern.compile(
            "beside|next to|nearby|neighbou?r|surrounding|around (it|there)|well above", Pattern.CASE_INSENSITIVE);

    /** A summary that quotes the 4 stars the Map showed. */
    private static final Pattern FOUR_STARS = Pattern.compile("\\b4\\b|4★|four");

    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final long DAILY_RUN_ID = 1L;

    /** A signed-in Pro asker with no stored drive times. */
    private static final AskUserContext ASKER = new AskUserContext(7L, UserRole.PRO_USER, false);

    private static AskSnapshot thinSample;
    private static AskSnapshot outlier;
    private static ClaudeAskEngine engine;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY is not set: the Ask place-lookup regression makes real API calls");

        // Fixture 1: Alnmouth 4 stars in a region whose sample is too thin to recommend a place.
        thinSample = saturdaySunrise(AskFixtures.region("Northumberland & Tyneside", false,
                AskFixtures.slot(1L, "Alnmouth", 4), AskFixtures.slot(2L, "Bamburgh", 4),
                AskFixtures.slot(3L, "Craster", 4), AskFixtures.slot(4L, "Seahouses", 4),
                AskFixtures.slot(5L, "Hartlepool Headland", 3)));
        // Fixture 2: Alnmouth 4 stars among places rated 1 and 2, marked by the neighbour guard.
        outlier = saturdaySunrise(AskFixtures.region("Northumberland & Tyneside", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Alnmouth", 4)), AskFixtures.slot(2L, "Bamburgh", 1),
                AskFixtures.slot(3L, "Craster", 2), AskFixtures.slot(4L, "Seahouses", 1),
                AskFixtures.slot(5L, "Hartlepool Headland", 2)));

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

    private static AskSnapshot saturdaySunrise(BriefingRegion region) {
        BriefingWindow window = AskFixtures.window(LocalDateTime.of(2026, 10, 10, 6, 0), DisplayVerdict.WORTH_IT,
                4, null);
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.day(SATURDAY,
                AskFixtures.summary(TargetType.SUNRISE, window, region))), List.of()));
    }

    // -- the scripts ------------------------------------------------------------------------

    /** (1) A 4-star place in a thin-sample region is explained, not denied. */
    @Test
    void thinSamplePlaceIsExplained() {
        passRate("Alnmouth / thin sample", () -> {
            AskRun run = typed(QUESTION_ALNMOUTH, thinSample);
            List<String> faults = explained(run);
            AskAnswer answer = run.outcome().answer();
            if (answer != null && !FOUR_STARS.matcher(answer.summary()).find()) {
                faults.add("the summary does not quote the 4 stars: " + answer.summary());
            }
            return faults;
        });
    }

    /** (2) A neighbour outlier is explained, and the answer says it is rated above the places beside it. */
    @Test
    void neighbourOutlierIsExplainedAsOne() {
        passRate("Alnmouth / neighbour outlier", () -> {
            AskRun run = typed(QUESTION_ALNMOUTH, outlier);
            List<String> faults = explained(run);
            AskAnswer answer = run.outcome().answer();
            if (answer != null) {
                if (!FOUR_STARS.matcher(answer.summary()).find()) {
                    faults.add("the summary does not quote the 4 stars: " + answer.summary());
                }
                if (!BESIDE_IT.matcher(answer.summary()).find()) {
                    faults.add("the summary does not say it is rated above the places beside it: "
                            + answer.summary());
                }
            }
            return faults;
        });
    }

    /** (3) A place that is not in the forecast is a can't-answer, which is now true. */
    @Test
    void unknownPlaceIsNotAnswerable() {
        passRate("Atlantis / unknown place", () -> {
            AskRun run = typed(QUESTION_ATLANTIS, thinSample);
            List<String> faults = new ArrayList<>();
            if (run.outcome().status() != AskOutcome.Status.CANT) {
                faults.add("status " + run.outcome().status() + " (engine reason: " + run.reason() + ")");
            }
            if (!called(run)) {
                faults.add("look_up_place was never called");
            }
            return faults;
        });
    }

    // -- checks -----------------------------------------------------------------------------

    /** An OK, answerable run that looked the place up, made no pick and did not use the can't-answer frame. */
    private static List<String> explained(AskRun run) {
        List<String> faults = new ArrayList<>();
        if (run.outcome().status() != AskOutcome.Status.OK) {
            faults.add("status " + run.outcome().status() + " (engine reason: " + run.reason() + ")");
            return faults;
        }
        AskAnswer answer = run.outcome().answer();
        if (!answer.answerable()) {
            faults.add("not answerable");
        }
        if (!answer.picks().isEmpty()) {
            faults.add("a pick on an explanation: "
                    + answer.picks().stream().map(AskPick::locationName).collect(Collectors.joining(", ")));
        }
        if (NOT_IN_FORECAST.matcher(answer.summary()).find()) {
            faults.add("the summary says the place is not in the forecast: " + answer.summary());
        }
        if (!called(run)) {
            faults.add("look_up_place was never called");
        }
        return faults;
    }

    private static boolean called(AskRun run) {
        return run.trace().stream().anyMatch(call -> AskToolSchemas.LOOK_UP_PLACE.equals(call.tool()));
    }

    // -- running ----------------------------------------------------------------------------

    private static AskRun typed(String text, AskSnapshot snapshot) {
        AskQuestion question = new AskQuestion(text, text.toLowerCase(Locale.ROOT), null, AskScope.ALL, "plan");
        AskRun run = engine.run(question, snapshot, ASKER, AskRunOptions.none());
        System.out.println("[ask-place-regression] " + text + " -> " + run.outcome().status() + ": "
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
