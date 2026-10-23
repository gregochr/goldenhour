package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
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
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prompt regression for named areas and location aliases (ask-named-areas plan §4.12): does Ask answer about
 * "Hadrian's Wall" and "the Heugh" from the places behind those names, instead of saying they are "not in the
 * forecast"? The real {@link ClaudeAskEngine}, real prompt, schemas, tools and validator, a fixed forecast, a
 * real API call:
 * <ol>
 *   <li>"Does Hadrian's Wall look good this weekend?": answerable, the summary names a member, and it does not
 *       say "not in the forecast";</li>
 *   <li>"Where on Hadrian's Wall should I go on Saturday morning?": picks, every one a member;</li>
 *   <li>"How's the Heugh looking tomorrow?": answerable and mentions Hartlepool Headland;</li>
 *   <li>"Does the Great Wall of China look good tomorrow?": not answerable.</li>
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
 *       -Dtest=AskNamedAreaRegressionTest
 * </pre>
 */
@Tag("prompt-regression")
class AskNamedAreaRegressionTest {

    /** How many times each script is run. */
    private static final int RUNS = 10;

    /** How many of those runs must pass. */
    private static final int PASS_BAR = 9;

    private static final String QUESTION_LOOK = "Does Hadrian's Wall look good this weekend?";
    private static final String QUESTION_WHERE = "Where on Hadrian's Wall should I go on Saturday morning?";
    private static final String QUESTION_HEUGH = "How's the Heugh looking tomorrow?";
    private static final String QUESTION_CHINA = "Does the Great Wall of China look good tomorrow?";

    /** The ids of the named area's members in the fixture. */
    private static final Set<Long> MEMBERS = Set.of(1L, 2L, 3L);
    private static final List<String> MEMBER_NAMES = List.of("Walltown Crags", "Cawfields", "Steel Rigg");

    /** The frame the owner was shown, which an answer about a real named area must not use. */
    private static final Pattern NOT_IN_FORECAST = Pattern.compile("not in the forecast|doesn'?t have|does not have",
            Pattern.CASE_INSENSITIVE);

    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final long DAILY_RUN_ID = 1L;

    /** A signed-in Pro asker with no stored drive times. */
    private static final AskUserContext ASKER = new AskUserContext(7L, UserRole.PRO_USER, false);

    private static AskSnapshot forecast;
    private static ClaudeAskEngine engine;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY is not set: the Ask named-area regression makes real API calls");

        BriefingRegion region = AskFixtures.region("Northumberland & Tyneside", true,
                AskFixtures.slot(1L, "Walltown Crags", 4), AskFixtures.slot(2L, "Cawfields", 5),
                AskFixtures.slot(3L, "Steel Rigg", 4), AskFixtures.slot(4L, "Alnmouth", 5),
                AskFixtures.slot(5L, "Hartlepool Headland", 4));
        BriefingWindow window = AskFixtures.window(LocalDateTime.of(2026, 10, 10, 6, 0), DisplayVerdict.WORTH_IT,
                5, null);
        forecast = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.day(SATURDAY,
                AskFixtures.summary(TargetType.SUNRISE, window, region))), List.of())).withNames(
                List.of(new AskSnapshot.Area("Hadrian's Wall", List.of("Hadrian Wall", "Roman Wall"), MEMBERS)),
                Map.of(5L, List.of("The Heugh", "Heugh Lighthouse", "Heugh Battery")));

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

    // -- the scripts ------------------------------------------------------------------------

    /** (1) The screenshot question is answered from the Wall's places. */
    @Test
    void hadriansWallIsAnswered() {
        passRate("Hadrian's Wall / look", () -> {
            AskRun run = typed(QUESTION_LOOK);
            List<String> faults = new ArrayList<>();
            if (run.outcome().status() != AskOutcome.Status.OK || !run.outcome().answer().answerable()) {
                faults.add("not answered: " + run.outcome().status() + " (engine reason: " + run.reason() + ")");
                return faults;
            }
            String summary = run.outcome().answer().summary();
            if (NOT_IN_FORECAST.matcher(summary).find()) {
                faults.add("the summary says it is not in the forecast: " + summary);
            }
            if (MEMBER_NAMES.stream().noneMatch(summary::contains)) {
                faults.add("the summary names no member of the named area: " + summary);
            }
            return faults;
        });
    }

    /** (2) A where-question on the Wall picks only members. */
    @Test
    void hadriansWallPicksAreMembers() {
        passRate("Hadrian's Wall / where", () -> {
            AskRun run = typed(QUESTION_WHERE);
            List<String> faults = new ArrayList<>();
            if (run.outcome().status() != AskOutcome.Status.OK) {
                faults.add("status " + run.outcome().status() + " (engine reason: " + run.reason() + ")");
                return faults;
            }
            List<AskPick> picks = run.outcome().answer().picks();
            if (picks.isEmpty()) {
                faults.add("no pick");
            }
            picks.stream().filter(p -> !MEMBERS.contains(p.locationId()))
                    .forEach(p -> faults.add("a pick that is not on the Wall: " + p.locationName()));
            return faults;
        });
    }

    /** (3) A place called by another name is found. */
    @Test
    void theHeughIsHartlepoolHeadland() {
        passRate("the Heugh", () -> {
            AskRun run = typed(QUESTION_HEUGH);
            List<String> faults = new ArrayList<>();
            if (run.outcome().status() != AskOutcome.Status.OK || !run.outcome().answer().answerable()) {
                faults.add("not answered: " + run.outcome().status() + " (engine reason: " + run.reason() + ")");
                return faults;
            }
            if (!run.outcome().answer().summary().contains("Hartlepool Headland")) {
                faults.add("the summary does not mention Hartlepool Headland: " + run.outcome().answer().summary());
            }
            return faults;
        });
    }

    /** (4) A wall that is not in the forecast is still a can't-answer. */
    @Test
    void theGreatWallOfChinaIsNotAnswerable() {
        passRate("Great Wall of China", () -> {
            AskRun run = typed(QUESTION_CHINA);
            List<String> faults = new ArrayList<>();
            if (run.outcome().status() != AskOutcome.Status.CANT) {
                faults.add("status " + run.outcome().status() + " (engine reason: " + run.reason() + ")");
            }
            return faults;
        });
    }

    // -- running ----------------------------------------------------------------------------

    private static AskRun typed(String text) {
        AskQuestion question = new AskQuestion(text, text.toLowerCase(Locale.ROOT), null, AskScope.ALL, "plan");
        AskRun run = engine.run(question, forecast, ASKER, AskRunOptions.none());
        System.out.println("[ask-named-area-regression] " + text + " -> " + run.outcome().status() + ": "
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
