package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskSnapshot.AuroraState;
import com.gregochr.goldenhour.service.ask.AskSnapshot.Night;
import com.gregochr.goldenhour.service.ask.AskSnapshot.NightSpot;
import com.gregochr.goldenhour.service.ask.AskSnapshot.Nights;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.goldenhour.util.GeoUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Assertions pending the owner's approval (2026-10-11); written by the autonomous build from the plan's
 * per-step expectations — the owner may tighten or loosen them; until approved, a failure here is a
 * signal, not a gate.
 *
 * <p>Night regression for Ask PhotoCast ({@code docs/engineering/ask-night-plan.md}): the owner's own
 * question, "are there clear skies on the Durham coast tonight?", and its neighbours, through the REAL
 * {@link ClaudeAskEngine} (real system prompt, real tool schemas including {@code rank_night_spots}, real
 * tool loop, real validator) over a fixture snapshot of two nights:
 * <ul>
 *   <li><b>Tonight, astro</b> (Friday 9 October): Seaham Harbour 5 stars and Easington Beach 4 stars on the
 *       Durham coast, Hartlepool Headland 3 stars (below the pick bar), Allenheads 4 stars inside the
 *       asker's home radius, Kielder 5 stars far inland, Dunstanburgh 4 stars on the Northumberland coast,
 *       and a <b>canopy-only wood</b> with Bortle 2 and 5 stars that must never be picked.</li>
 *   <li><b>Tonight, aurora</b> (alert MODERATE, Kp 6): Dunstanburgh 4 stars, and Allenheads a
 *       weather-triaged row.</li>
 *   <li><b>Tomorrow night, astro</b>: Allenheads 5, Kielder 4, Seaham 3 — and no aurora rows, so the state
 *       cache must not be spoken of as if it covered tomorrow.</li>
 * </ul>
 *
 * <p>Five scripts: (a) the owner's question; (b) the aurora question as a PRO asker and as a LITE asker;
 * (c) "is tomorrow night any good?"; (d) a follow-up, "anything closer to home?", carrying an earlier night
 * exchange with its {@code nightVersion}; (e) the same follow-up carrying a STALE {@code nightVersion}, where
 * the server drops the thread. (d) and (e) seed their earlier exchange deterministically instead of chaining
 * a paid call, so each script is independent of the others' answers.
 *
 * <p>Like {@link AskPromptRegressionTest} and {@link AskThreadRegressionTest} (which this class neither
 * edits nor shares assertions with) it asserts <b>invariants only</b> — which kinds, which stars, which
 * words must or must not appear — never wording; the validator already guarantees the cheap ones (a pick is
 * a live, night-eligible slot, never a wood, never an aurora pick for a LITE asker). What a failure here
 * catches is the prompt, the tool schema or the thread rules drifting so the model stops resolving "clear
 * skies" to the astro forecast, cites an aurora figure to a reader who may not see one, or relabels tonight's
 * alert as tomorrow's.
 *
 * <p>Script (e) needs no model call for its reset: the reset is {@code AskService}'s rule
 * ({@code AskThread.nightStaleAgainst}, reason {@link AskResponse#NIGHT_THREAD_RESET_REASON}), which needs a
 * database to construct and is pinned there by {@code AskServiceTest}; here the same predicate and sentence
 * are checked against the same fixture, and the fresh question the server then answers is run through the
 * real engine with the empty thread it would be handed.
 *
 * <p>This class is excluded from {@code mvn verify} (tagged "prompt-regression") and from PIT, and skips when
 * no API key is present. Run on demand, spending real money (a few pence a run, about seven calls), with:
 * <pre>
 *   cd backend &amp;&amp; ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression \
 *       -Dtest=AskNightRegressionTest
 * </pre>
 * It was written without a key and has never been run: the first real run is the owner's (plan section 6).
 */
@Tag("prompt-regression")
class AskNightRegressionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 15, 30);
    private static final LocalDate TONIGHT = NOW.toLocalDate();
    private static final LocalDate TOMORROW = TONIGHT.plusDays(1);
    private static final String TONIGHT_ASTRO = AskWindowId.format(TONIGHT, AskWindowKind.ASTRO);
    private static final String TONIGHT_AURORA = AskWindowId.format(TONIGHT, AskWindowKind.AURORA);
    private static final String TOMORROW_ASTRO = AskWindowId.format(TOMORROW, AskWindowKind.ASTRO);

    /** The astro run that wrote both nights' rows, and the later one that makes an old version stale. */
    private static final Instant GENERATION = Instant.parse("2026-10-09T14:30:00Z");
    private static final Instant LATER_GENERATION = Instant.parse("2026-10-09T15:00:00Z");

    private static final String DURHAM = "County Durham";
    private static final String PENNINES = "North Pennines";
    private static final String NORTHUMBERLAND = "Northumberland";

    private static final long SEAHAM = 101L;
    private static final long EASINGTON = 102L;
    private static final long HARTLEPOOL = 103L;
    private static final long ALLENHEADS = 104L;
    private static final long KIELDER = 105L;
    private static final long DUNSTANBURGH = 106L;
    private static final long WOOD = 107L;

    /** A served aurora sentence unlike anything a model would write on its own, to spot a leak to LITE. */
    private static final String AURORA_MARKER = "clear northern horizon after midnight";

    private static final double HOME_LAT = 54.97;
    private static final double HOME_LON = -2.10;
    private static final int RADIUS_MILES = 25;
    private static final long PRO_ID = 7L;
    private static final long LITE_ID = 8L;

    /** A signed-in Pro asker with a saved home, a 25-mile radius and stored drive times. */
    private static final AskUserContext PRO = new AskUserContext(PRO_ID, UserRole.PRO_USER, true,
            new AskUserContext.Home(HOME_LAT, HOME_LON, RADIUS_MILES));

    /** A signed-in Lite asker: the API serves them no aurora figure, so Ask must not either. */
    private static final AskUserContext LITE = new AskUserContext(LITE_ID, UserRole.LITE_USER, false, null);

    /** Location id to its invented coordinates; Allenheads and the wood are inside the radius. */
    private static final Map<Long, AskLocationNames.Place> PLACES = Map.of(
            SEAHAM, new AskLocationNames.Place("Seaham Harbour", 54.84, -1.34),
            EASINGTON, new AskLocationNames.Place("Easington Beach", 54.78, -1.31),
            HARTLEPOOL, new AskLocationNames.Place("Hartlepool Headland", 54.70, -1.17),
            ALLENHEADS, new AskLocationNames.Place("Allenheads Dark Sky Site", 54.81, -2.22),
            KIELDER, new AskLocationNames.Place("Kielder Water", 55.30, -2.58),
            DUNSTANBURGH, new AskLocationNames.Place("Dunstanburgh Castle", 55.49, -1.59),
            WOOD, new AskLocationNames.Place("Hamsterley Bluebell Wood", 54.66, -1.97));

    private static final Map<Long, Integer> DRIVE_MINUTES = Map.of(
            SEAHAM, 55, EASINGTON, 60, HARTLEPOOL, 65, ALLENHEADS, 25, KIELDER, 70, DUNSTANBURGH, 80, WOOD, 35);

    /** The daily job run the mocked accounting hands out; nothing is written anywhere. */
    private static final long DAILY_RUN_ID = 1L;

    private static AskSnapshot snapshot;
    private static ClaudeAskEngine engine;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY is not set: the Ask night regression makes real API calls");

        snapshot = snapshot(GENERATION);

        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        AskJobRunService jobRuns = mock(AskJobRunService.class);
        when(jobRuns.dailyRunId()).thenReturn(DAILY_RUN_ID);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
        when(driveTimes.getAllMinutes(PRO_ID)).thenReturn(DRIVE_MINUTES);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        engine = new ClaudeAskEngine(new AnthropicApiClient(client), new AskProperties(), jobRuns,
                driveTimes, new AskAnswerValidator(), new AskPromptBuilder(), mapper, Clock.systemUTC(),
                () -> PLACES);
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    // -- (a) the owner's question ---------------------------------------------------------------

    /**
     * "Are there clear skies on the Durham coast tonight?" is the astro cloud forecast: astro picks on
     * tonight's astro night at 4 stars or better, never the wood, with the served clear-sky and Bortle facts
     * in the reasons.
     */
    @Test
    void clearSkiesOnTheDurhamCoastTonight() {
        AskRun run = ask("are there clear skies on the Durham coast tonight?", PRO, List.of());
        AskAnswer answer = requireOk(run);

        assertThat(trace(run)).as("the night tool was used").contains(AskToolSchemas.RANK_NIGHT_SPOTS);
        assertThat(answer.picks()).as("picks").isNotEmpty();
        assertThat(answer.picks()).as("every pick is an astro pick on tonight's astro night at 4 stars or better")
                .allSatisfy(pick -> {
                    assertThat(pick.targetType()).isEqualTo(AskWindowKind.ASTRO);
                    assertThat(pick.windowId()).isEqualTo(TONIGHT_ASTRO);
                    assertThat(pick.ratingAtAnswer()).isGreaterThanOrEqualTo(AskSnapshot.MIN_ASTRO_PICK_RATING);
                    assertThat(pick.verdictAtAnswer()).as("a night has no verdict word").isNull();
                });
        assertThat(answer.picks()).extracting(AskPick::locationId)
                .as("a wood is never a night pick, whatever its Bortle class and stars").doesNotContain(WOOD);
        assertThat(answer.picks()).as("at least one pick is on the Durham coast")
                .anyMatch(pick -> DURHAM.equals(pick.regionName()));
        assertThat(whys(answer)).as("the reasons quote the served clear-sky forecast")
                .anyMatch(why -> why.contains("clear") || why.contains("cloud"));
        assertThat(whys(answer)).as("the reasons quote the served Bortle class")
                .anyMatch(why -> why.contains("bortle"));
        assertLiveAndRanked(answer, PRO);
    }

    // -- (b) the aurora question, by role -------------------------------------------------------

    /**
     * A PRO asker gets an aurora pick on tonight's aurora night at the place with a result, never the
     * weather-triaged place, and the alert level in the words.
     */
    @Test
    void auroraTonightForAProAsker() {
        AskRun run = ask("anywhere good for the aurora tonight?", PRO, List.of());
        AskAnswer answer = requireOk(run);

        assertThat(trace(run)).as("the night tool was used").contains(AskToolSchemas.RANK_NIGHT_SPOTS);
        assertThat(answer.picks()).as("an aurora pick on tonight's aurora night at 3 stars or better")
                .anyMatch(pick -> pick.targetType() == AskWindowKind.AURORA
                        && TONIGHT_AURORA.equals(pick.windowId())
                        && pick.ratingAtAnswer() >= AskSnapshot.MIN_AURORA_PICK_RATING);
        assertThat(answer.picks()).as("the weather-triaged place is never an aurora pick")
                .noneMatch(pick -> pick.targetType() == AskWindowKind.AURORA
                        && pick.locationId() == ALLENHEADS);
        assertThat(answer.picks()).extracting(AskPick::locationId).doesNotContain(WOOD);
        assertThat(prose(answer)).as("the summary names the alert level the state cache reports")
                .containsAnyOf("moderate", "kp 6", "kp6");
        assertLiveAndRanked(answer, PRO);
    }

    /**
     * The same question from a LITE asker: the API serves LITE no aurora result, so there is no aurora pick,
     * no aurora figure or served aurora sentence anywhere in the prose, and the "Pro feature" clause is said.
     */
    @Test
    void auroraTonightForALiteAskerIsWithheld() {
        AskRun run = ask("anywhere good for the aurora tonight?", LITE, List.of());
        AskOutcome outcome = run.outcome();

        assertThat(outcome.status()).as("status (engine reason: %s)", run.reason())
                .isIn(AskOutcome.Status.OK, AskOutcome.Status.CANT);
        AskAnswer answer = outcome.answer();
        assertThat(trace(run)).as("the night tool was used, so the model could read its Pro note")
                .contains(AskToolSchemas.RANK_NIGHT_SPOTS);
        assertThat(answer.picks()).as("no aurora pick for a LITE asker")
                .noneMatch(pick -> pick.targetType() == AskWindowKind.AURORA);
        String said = prose(answer);
        assertThat(said).as("no served aurora sentence").doesNotContain(AURORA_MARKER);
        assertThat(said).as("no Kp figure").doesNotContainPattern("\\bkp\\b");
        assertThat(said).as("no alert level the state cache would have reported").doesNotContain("moderate");
        assertThat(said).as("no aurora star figure")
                .doesNotContainPattern("aurora[^.]{0,30}\\d\\s*(★|stars?)")
                .doesNotContainPattern("\\d\\s*(★|stars?)[^.]{0,30}aurora");
        assertThat(said).as("the Pro-feature clause is present").containsPattern("\\bpro\\b");
        assertLiveAndRanked(answer, LITE);
    }

    // -- (c) tomorrow night ----------------------------------------------------------------------

    /**
     * "Is tomorrow night any good?" is tomorrow's astro night: every pick is on it, and nothing says
     * tonight's alert or Kp, because the state cache is tonight-only and tomorrow has no aurora rows.
     */
    @Test
    void tomorrowNightIsTomorrowsAstroNight() {
        AskRun run = ask("is tomorrow night any good?", PRO, List.of());
        AskAnswer answer = requireOk(run);

        assertThat(trace(run)).as("the night tool was used").contains(AskToolSchemas.RANK_NIGHT_SPOTS);
        assertThat(answer.picks()).as("picks").isNotEmpty();
        assertThat(answer.picks()).as("every pick is on tomorrow night's astro night")
                .allSatisfy(pick -> {
                    assertThat(pick.windowId()).isEqualTo(TOMORROW_ASTRO);
                    assertThat(pick.date()).isEqualTo(TOMORROW);
                    assertThat(pick.targetType()).isEqualTo(AskWindowKind.ASTRO);
                });
        assertThat(answer.picks()).extracting(AskPick::locationId).doesNotContain(WOOD);
        String said = prose(answer);
        assertThat(said).as("no state-cache prose: tonight's alert is not tomorrow's")
                .doesNotContainPattern("\\bkp\\b").doesNotContain("alert").doesNotContain("moderate");
        assertLiveAndRanked(answer, PRO);
    }

    // -- (d) a follow-up that carries the earlier night exchange --------------------------------------

    /**
     * "Anything closer to home?" after a Durham-coast astro answer, carrying that exchange with the
     * {@code nightVersion} the server wrote on it: the version is live, the thread is kept, and "closer"
     * resolves to the saved radius — every pick is inside it or one the earlier answer already gave, the
     * answer names the radius, and the wood (inside the radius, 5 stars) is still never offered.
     */
    @Test
    void closerToHomeResolvesAgainstTheEarlierNightAnswer() {
        List<ThreadExchange> thread = List.of(earlierExchange(snapshot));
        assertThat(new AskThread(thread).nightStaleAgainst(snapshot, true))
                .as("the seeded exchange's nightVersion is the live one").isFalse();
        Set<AskPickKey> earlier = Set.of(new AskPickKey(SEAHAM, TONIGHT_ASTRO),
                new AskPickKey(EASINGTON, TONIGHT_ASTRO));

        AskRun run = ask("anything closer to home?", PRO, thread);
        AskOutcome outcome = run.outcome();

        assertThat(outcome.status()).as("status (engine reason: %s)", run.reason())
                .isIn(AskOutcome.Status.OK, AskOutcome.Status.CANT);
        AskAnswer answer = outcome.answer();
        if (outcome.status() == AskOutcome.Status.OK) {
            assertThat(outcome.personal()).as("closer to home is personal (nearHome or a drive limit)").isTrue();
        }
        assertThat(answer.summary() + " " + answer.missing()).as("the answer names the radius")
                .containsIgnoringCase("miles of home");
        assertThat(answer.picks()).as("each pick is inside the radius or one the earlier answer gave")
                .allSatisfy(pick -> assertThat(milesFromHome(pick.locationId()) <= RADIUS_MILES
                        || earlier.contains(new AskPickKey(pick.locationId(), pick.windowId())))
                        .as("%s at %s is %.1f miles from home", pick.locationName(), pick.windowId(),
                                milesFromHome(pick.locationId()))
                        .isTrue());
        assertThat(answer.picks()).extracting(AskPick::locationId).doesNotContain(WOOD);
        assertLiveAndRanked(answer, PRO);
    }

    // -- (e) the same follow-up with a stale nightVersion --------------------------------------------

    /**
     * The same follow-up, but the earlier exchange carries a {@code nightVersion} from before the afternoon
     * astro refresh moved the night's generation. The server's rule drops the whole thread and says why with
     * the fixed sentence; the question is then answered fresh, through the real engine with the empty thread
     * the service would hand it, and with no earlier picks to excuse it every pick must be inside the radius.
     */
    @Test
    void aStaleNightVersionDropsTheThread() {
        ThreadExchange stale = earlierExchange(snapshot(LATER_GENERATION));
        AskThread thread = new AskThread(List.of(stale));

        assertThat(thread.staleAgainst(snapshot.generatedAt()))
                .as("the briefing itself has not moved, so only the night rule fires").isFalse();
        assertThat(thread.nightStaleAgainst(snapshot, true)).as("the night version is no longer the live one").isTrue();
        String reason = thread.staleAgainst(snapshot.generatedAt()) ? AskResponse.THREAD_RESET_REASON
                : thread.nightStaleAgainst(snapshot, true) ? AskResponse.NIGHT_THREAD_RESET_REASON : null;
        assertThat(reason).isEqualTo("night forecast updated");

        AskRun fresh = ask("anything closer to home?", PRO, List.of());
        AskOutcome outcome = fresh.outcome();
        assertThat(outcome.status()).as("status (engine reason: %s)", fresh.reason())
                .isIn(AskOutcome.Status.OK, AskOutcome.Status.CANT);
        assertThat(outcome.answer().picks()).as("a fresh question has no earlier picks to excuse")
                .allSatisfy(pick -> assertThat(milesFromHome(pick.locationId()))
                        .as("%s is %.1f miles from home", pick.locationName(), milesFromHome(pick.locationId()))
                        .isLessThanOrEqualTo(RADIUS_MILES));
        assertThat(outcome.answer().picks()).extracting(AskPick::locationId).doesNotContain(WOOD);
        assertLiveAndRanked(outcome.answer(), PRO);
    }

    // -- helpers ----------------------------------------------------------------------------------

    private static AskRun ask(String text, AskUserContext asker, List<ThreadExchange> thread) {
        AskQuestion question = new AskQuestion(text, text.toLowerCase(Locale.ROOT), null, AskScope.ALL, "plan");
        AskRun run = engine.run(question, snapshot, asker, AskRunOptions.typed(new AskThread(thread)));
        System.out.println("[ask-night-regression] " + asker.role() + " thread=" + thread.size() + " " + text
                + " -> " + run.outcome().status() + " in " + run.outcome().turns() + " turn(s), personal="
                + run.outcome().personal() + ", uncacheable=" + run.outcome().uncacheable() + ": "
                + (run.outcome().answer() == null ? run.reason() : run.outcome().answer().summary()));
        System.out.println("[ask-night-regression] trace: " + trace(run));
        if (run.outcome().answer() != null) {
            run.outcome().answer().picks().forEach(pick -> System.out.println("[ask-night-regression]   pick "
                    + pick.rank() + " " + pick.locationName() + " " + pick.windowId() + " "
                    + pick.ratingAtAnswer() + " stars: " + pick.why()));
        }
        return run;
    }

    private static List<String> trace(AskRun run) {
        return run.trace().stream().map(AskTools.ToolCall::tool).collect(Collectors.toList());
    }

    private static AskAnswer requireOk(AskRun run) {
        assertThat(run.outcome().status()).as("status (engine reason: %s)", run.reason())
                .isEqualTo(AskOutcome.Status.OK);
        AskAnswer answer = run.outcome().answer();
        assertThat(answer.answerable()).isTrue();
        assertThat(answer.summary()).isNotBlank();
        return answer;
    }

    /** The summary, any missing clause and every reason, lower-cased: all the prose a reader is shown. */
    private static String prose(AskAnswer answer) {
        return (answer.summary() + " " + (answer.missing() == null ? "" : answer.missing()) + " "
                + String.join(" ", whys(answer))).toLowerCase(Locale.ROOT);
    }

    private static List<String> whys(AskAnswer answer) {
        return answer.picks().stream().map(pick -> pick.why() == null ? "" : pick.why().toLowerCase(Locale.ROOT))
                .toList();
    }

    /** Every pick resolves live for this asker (night-eligible, never a wood); ranks run 1..n; at most four. */
    private static void assertLiveAndRanked(AskAnswer answer, AskUserContext asker) {
        List<AskPick> picks = answer.picks();
        assertThat(picks).hasSizeLessThanOrEqualTo(AskAnswerValidator.MAX_RANKED_PICKS);
        assertThat(picks).extracting(AskPick::rank)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, picks.size()).boxed().toList());
        assertThat(picks).allSatisfy(pick -> assertThat(
                snapshot.resolvePick(pick.windowId(), pick.locationId(), asker.canSeeAurora()))
                .as("pick %s at %s is live and eligible for %s", pick.locationName(), pick.windowId(),
                        asker.role())
                .isPresent());
    }

    /**
     * The earlier exchange of scripts (d) and (e): a Durham-coast astro answer naming Seaham and Easington
     * tonight, carrying the {@code nightVersion} the server would have written against {@code answeredAgainst}.
     */
    private static ThreadExchange earlierExchange(AskSnapshot answeredAgainst) {
        String version = AskNightVersion.of(answeredAgainst, Set.of(TONIGHT), PRO.canSeeAurora()).orElseThrow();
        return new ThreadExchange("are there clear skies on the durham coast tonight?",
                "Yes: Seaham Harbour and Easington Beach have clear skies tonight.",
                List.of(new ThreadExchange.PickRef(SEAHAM, TONIGHT_ASTRO),
                        new ThreadExchange.PickRef(EASINGTON, TONIGHT_ASTRO)),
                List.of(), answeredAgainst.generatedAt(), false, version);
    }

    private static double milesFromHome(long locationId) {
        AskLocationNames.Place place = PLACES.get(locationId);
        return GeoUtils.distanceMiles(HOME_LAT, HOME_LON, place.lat(), place.lon());
    }

    /** A pick's identity for the thread: the place at a window. */
    private record AskPickKey(long locationId, String windowId) {
    }

    // -- the fixture ------------------------------------------------------------------------------

    /** The two-night snapshot over an empty solar briefing, tonight's astro night written at {@code astroRun}. */
    private static AskSnapshot snapshot(Instant astroRun) {
        AskSnapshot base = AskFixtures.snapshotAt(NOW, AskFixtures.briefing(List.of(), List.of()), List.of());
        LocalDateTime dusk = TONIGHT.atTime(18, 20);
        LocalDateTime dawn = TOMORROW.atTime(5, 10);

        Night astroTonight = new Night(TONIGHT_ASTRO, TONIGHT, AskWindowKind.ASTRO, dusk, dawn, astroRun, List.of(
                astro(SEAHAM, DURHAM, 5, 4, "Clear skies: 4% low cloud, 6% mid, 3% high cloud"),
                astro(EASINGTON, DURHAM, 4, 4, "Mostly clear: 12% low cloud, 9% mid, 5% high cloud"),
                astro(HARTLEPOOL, DURHAM, 3, 5, "Some cloud: 35% low cloud, 20% mid, 10% high cloud"),
                astro(ALLENHEADS, PENNINES, 4, 3, "Mostly clear: 10% low cloud, 8% mid, 4% high cloud"),
                astro(KIELDER, NORTHUMBERLAND, 5, 2, "Clear skies: 2% low cloud, 3% mid, 2% high cloud"),
                astro(DUNSTANBURGH, NORTHUMBERLAND, 4, 4, "Mostly clear: 14% low cloud, 6% mid, 4% high cloud"),
                canopy(astro(WOOD, DURHAM, 5, 2, "Clear skies: 3% low cloud, 4% mid, 2% high cloud"))));
        Night auroraTonight = new Night(TONIGHT_AURORA, TONIGHT, AskWindowKind.AURORA, dusk, dawn, astroRun,
                List.of(aurora(DUNSTANBURGH, NORTHUMBERLAND, 4, 4),
                        triaged(aurora(ALLENHEADS, PENNINES, 4, 3))));
        Night astroTomorrow = new Night(TOMORROW_ASTRO, TOMORROW, AskWindowKind.ASTRO, dusk.plusDays(1),
                dawn.plusDays(1), astroRun, List.of(
                astro(ALLENHEADS, PENNINES, 5, 3, "Clear skies: 3% low cloud, 4% mid, 2% high cloud"),
                astro(KIELDER, NORTHUMBERLAND, 4, 2, "Mostly clear: 11% low cloud, 7% mid, 4% high cloud"),
                astro(SEAHAM, DURHAM, 3, 4, "Some cloud: 38% low cloud, 22% mid, 12% high cloud")));

        return new AskSnapshot(base.generatedAt(), base.runLabel(), base.today(), base.windows(),
                base.hotTopics(), base.comingUp(), base.briefingStale(),
                new Nights(TONIGHT, List.of(astroTonight, auroraTonight, astroTomorrow),
                        new AuroraState("MODERATE", 6.0)));
    }

    private static NightSpot astro(long id, String region, int stars, int bortle, String cloud) {
        AskLocationNames.Place place = PLACES.get(id);
        return new NightSpot(id, place.name(), region, place.lat(), place.lon(), false, bortle, 21.4, stars,
                "Astro conditions " + stars + " of 5", cloud, "Excellent visibility", "Moon below the horizon",
                "WAXING_CRESCENT", 12.0, null, null, false, null, null, null);
    }

    private static NightSpot aurora(long id, String region, int auroraStars, int bortle) {
        AskLocationNames.Place place = PLACES.get(id);
        return new NightSpot(id, place.name(), region, place.lat(), place.lon(), false, bortle, 21.4, auroraStars,
                "Astro conditions " + auroraStars + " of 5", "Mostly clear: 10% low cloud", "Excellent visibility",
                "Moon below the horizon", "WAXING_CRESCENT", 12.0, auroraStars,
                "Strong geomagnetic activity with a " + AURORA_MARKER, false, null, "MODERATE", 6.0);
    }

    private static NightSpot triaged(NightSpot spot) {
        return new NightSpot(spot.locationId(), spot.name(), spot.regionName(), spot.lat(), spot.lon(),
                spot.canopy(), spot.bortleClass(), spot.skyBrightnessSqm(), spot.astroStars(), spot.astroSummary(),
                spot.cloudExplanation(), spot.visibilityExplanation(), spot.moonExplanation(), spot.moonPhase(),
                spot.moonIlluminationPct(), 1, "Overcast: aurora not visible through the cloud", true,
                "Overcast", "MODERATE", 6.0);
    }

    private static NightSpot canopy(NightSpot spot) {
        return new NightSpot(spot.locationId(), spot.name(), spot.regionName(), spot.lat(), spot.lon(), true,
                spot.bortleClass(), spot.skyBrightnessSqm(), spot.astroStars(), spot.astroSummary(),
                spot.cloudExplanation(), spot.visibilityExplanation(), spot.moonExplanation(), spot.moonPhase(),
                spot.moonIlluminationPct(), spot.auroraStars(), spot.auroraSummary(), spot.triaged(),
                spot.triageReason(), spot.alertLevel(), spot.maxKp());
    }
}
