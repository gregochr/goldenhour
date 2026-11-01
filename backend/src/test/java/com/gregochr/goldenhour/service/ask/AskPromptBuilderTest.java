package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.ThreadExchange.EventRef;
import com.gregochr.goldenhour.service.ask.ThreadExchange.PickRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AskPromptBuilder}. */
class AskPromptBuilderTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private final AskPromptBuilder builder = new AskPromptBuilder();

    @Test
    @DisplayName("states today's UK date with the day name, and the rules")
    void statesTodayAndTheRules() {
        String prompt = builder.systemPrompt(MONDAY, TestScopes.of(), null, true);

        assertThat(prompt).contains("Today is Monday 5 October 2026 in the UK.")
                .contains("every region")
                .contains("submit_answer")
                .contains("under 22 words")
                .contains("tideAligned is true")
                .contains("bestBet")
                .contains("Never give advice about access, parking, walking")
                .contains("No web addresses");
    }

    @Test
    @DisplayName("names the scope's regions, sorted, when the question has one")
    void namesTheScope() {
        String prompt = builder.systemPrompt(MONDAY, TestScopes.of("Teesdale", "Northumberland"), null,
                true);

        assertThat(prompt).contains("these regions only: Northumberland, Teesdale.")
                .doesNotContain("every region");
    }

    @Test
    @DisplayName("states the window the reader is looking at, with its id")
    void statesTheContextWindow() {
        AskSnapshot.Window window = new AskSnapshot.Window("2026-10-10_sunrise", LocalDate.of(2026, 10, 10),
                TargetType.SUNRISE, LocalDateTime.of(2026, 10, 10, 6, 0), null, null, null, List.of());

        String prompt = builder.systemPrompt(MONDAY, TestScopes.of(), window, true);

        assertThat(prompt).contains("looking at Saturday 10 October sunrise (windowId 2026-10-10_sunrise)");
    }

    @Test
    @DisplayName("a user-less conversation is told never to mention home; a typed one is told about "
            + "drive times, and never both")
    void userLessAndTypedDiffer() {
        String shared = builder.systemPrompt(MONDAY, TestScopes.of(), null, false);
        String typed = builder.systemPrompt(MONDAY, TestScopes.of(), null, true);

        assertThat(shared).contains("never mention home, distance or drive times")
                .doesNotContain("maxDriveMinutes");
        assertThat(typed).contains("maxDriveMinutes").doesNotContain("never mention home");
    }

    @Test
    @DisplayName("the model may never ask the reader anything, and 'close to home' is nearHome, a number of "
            + "minutes is maxDriveMinutes, 'closer' narrows the earlier drive (the 60-minute rule is gone)")
    void neverAsksAndReadsCloseToHomeAsNearHome() {
        String typed = builder.systemPrompt(MONDAY, TestScopes.of(), null, true).replaceAll("\\s+", " ");

        assertThat(typed).contains("never ask the reader a question")
                .contains("'Close to home', 'near', 'nearby' or 'local' with no number: pass nearHome "
                        + "true to rank_spots, or to rank_night_spots for a night question")
                .contains("A number of minutes: pass it as maxDriveMinutes")
                .contains("'Closer' in a follow-up: pass a maxDriveMinutes smaller than the earlier picks' "
                        + "drive, or nearHome true if it was not used")
                .contains("within N miles of home")
                .contains("never say within reach")
                .doesNotContain("pass 60").doesNotContain("an hour's drive");
    }

    @Test
    @DisplayName("a typed prompt tells the model to call look_up_place for one named place and never to give it "
            + "as a pick; the investigation bullet points to it; the Ready prompt is RULES untouched")
    void typedPromptCarriesThePlaceLookupRule() {
        String typed = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true));
        String ready = builder.systemPrompt(MONDAY, TestScopes.of(), null, false);

        assertThat(typed)
                .contains("Questions this does not fit (one place: call look_up_place; one tide, one event, the "
                        + "night) need none of it.")
                .contains("When the question is about one named place (\"why is Alnmouth 4★\", \"how is "
                        + "Bamburgh on Saturday\"), call look_up_place with its name.")
                .contains("call look_up_place with its name. Pass the windowIds the question is about (the window "
                        + "the reader is looking at, or the day it names); leave them out only when it asks about "
                        + "every window.")
                .contains("If its status is not PICK, explain the rating and say in one clause why PhotoCast is "
                        + "not suggesting the place, using the reason given for its status. Never give it as a "
                        + "pick.")
                .contains("If the reader also wants somewhere to go, call rank_spots.")
                .contains("If look_up_place returns no places, no laterTides, no named area, no closestTo and no "
                        + "suggestions, "
                        + "read its note and answer answerable false: when the note says the place is outside "
                        + "the regions this question is about, say exactly that, never that it is not in the forecast; "
                        + "otherwise the place is not in the forecast.")
                .contains(AskTools.PLACE_OUT_OF_SCOPE_NOTE.replace(".", "").replace("That place", "the place"));
        assertThat(typed.indexOf("When the question is about one named place"))
                .isGreaterThan(typed.indexOf("A good investigation usually runs"));
        assertThat(ready).startsWith(AskPromptBuilder.RULES).doesNotContain("look_up_place");
        assertThat(AskPromptBuilder.RULES).contains(AskPromptBuilder.PLACE_FIT_BEFORE)
                .doesNotContain("look_up_place");
    }

    @Test
    @DisplayName("a typed prompt explains a place from the forecast's own reason; the Ready prompt has none of it")
    void typedPromptCarriesThePlaceReasonRule() {
        String typed = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true));
        String ready = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, false));

        assertThat(typed)
                .contains("Answer from what it returns: the rating, verdict, headline and tide fit, and a row's "
                        + "detail when it has one. detail.summary describes the sky (the light under the trees "
                        + "for a wood): give the rating and its reason from it, in plain words, without its "
                        + "distances or figures. When detail.skyRating is above the rating, the sky looks better "
                        + "than the star: say the tide pulled it down, using the tide fit. Mention "
                        + "detail.fierySky and detail.goldenHour only when the reader asks what kind of light to "
                        + "expect: call them Fiery Sky and Golden Hour, out of 100, and never say one is "
                        + "stronger than the other. Never add a reason the result does not give. Order: the "
                        + "rating and its reason, then why PhotoCast is or is not suggesting it, then the rest. "
                        + "The summary is cut at 60 words, so keep within that.");
        assertThat(ready).doesNotContain("detail.summary").doesNotContain("Fiery Sky and Golden Hour");
    }

    @Test
    @DisplayName("a typed prompt, and a follow-up thread's, carries the light-character rule for questions that name "
            + "no place; the Ready prompt carries no Fiery Sky or Golden Hour wording at all")
    void typedPromptCarriesTheLightCharacterRule() {
        String typed = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true));
        String thread = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true, true));
        String ready = builder.systemPrompt(MONDAY, TestScopes.of(), null, false);
        String bullet = "When the reader asks what the light or colour will be like and names no place (\"is "
                + "tomorrow a fiery morning?\", \"fiery or soft tonight?\"), give the spread first as for any "
                + "window, then rank_spots. Describe each place's light in its headline's words and add its "
                + "fierySky and goldenHour as Fiery Sky N and Golden Hour N, out of 100, for at most two places. "
                + "Never answer yes or no from a figure alone, never call a figure strong, high, low, fiery or "
                + "soft, and never say one is stronger than the other: if the headlines do not say which, say "
                + "the forecast does not rank them. The figures are for the places returned only: never call one "
                + "the fieriest, or the whole window fiery or soft. A pick's why may quote Fiery Sky N as its sky "
                + "fact. Leave the figures out of a where-question (\"best place for golden hour?\"). To compare "
                + "earlier picks' light in a follow-up, call rank_spots again.";

        assertThat(typed).contains(bullet);
        assertThat(thread).contains(bullet);
        assertThat(ready).doesNotContain("fierySky").doesNotContain("Fiery Sky").doesNotContain("Golden Hour");
    }

    @Test
    @DisplayName("a typed prompt tells the model what to say for a closest-spelling result; the Ready prompt "
            + "has none of it")
    void typedPromptCarriesTheClosestSpellingRule() {
        String typed = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true));
        String ready = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, false));

        assertThat(typed)
                .contains("If the result has closestTo, the reader's spelling matched no place and the result "
                        + "is for the closest name: start the answer \"Assuming you mean <name>,\". If it has "
                        + "suggestions, call look_up_place again with the one the reader plainly meant and "
                        + "start the answer the same way. If none fits, answer answerable false and name the "
                        + "suggestions as the places they may mean.");
        assertThat(ready).doesNotContain("closestTo").doesNotContain("Assuming you mean");
    }

    @Test
    @DisplayName("a typed prompt knows named areas: 'no places, no named area ...' is the answerable-false trigger, "
            + "and areaName and other names are explained; the Ready prompt has none of it")
    void typedPromptCarriesTheNamedAreaRule() {
        String typed = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true));
        String ready = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, false));

        assertThat(typed)
                .contains("returns no places, no laterTides, no named area, no closestTo and no suggestions, "
                        + "read its note "
                        + "and answer answerable false")
                .doesNotContain("returns no places, read its note")
                .contains("The name may be a named area, a group of places such as a landmark (\"Hadrian's "
                        + "Wall\"): look_up_place then returns how its places rate in each window; answer from "
                        + "those stars, never as picks.")
                .contains("To suggest places in a named area, call rank_spots with areaName.")
                .contains("Each call covers one named area: when the question names several (\"Swaledale or "
                        + "Wensleydale\"), call once per area; a result's \"Also matched areas\" note lists the "
                        + "ones it did not answer.")
                .contains("A place may also be found by another name (\"the Heugh\"); call it by its listed "
                        + "name, adding the name the reader used.");
        assertThat(ready).doesNotContain("named area").doesNotContain("areaName")
                .doesNotContain("Also matched areas");
        assertThat(AskPromptBuilder.RULES).doesNotContain("named area");
    }

    @Test
    @DisplayName("a typed prompt, and a follow-up thread's, carries the tide-question bullet after E1's order "
            + "sentence; the user-less Ready prompt has no trace of it")
    void typedPromptCarriesTheTideQuestionRule() {
        String typed = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true));
        String thread = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true, true));
        String ready = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, false));
        String bullet = "For a tide question about a named place (is the tide right, aligned, correct, good, does "
                + "it work), call look_up_place, then in the same turn get_hot_topics with types KING_TIDE and "
                + "SPRING_TIDE and limit 2. Lead with whether the tide suits: it suits only when tideAligned is "
                + "true. Then give the rating in one clause, for a rated window. In tideFitPhrase and nearestTide, "
                + "HW and LW are high and low water, 40m and 1h43 are minutes and hours, and times are UK clock "
                + "times: say \"high "
                + "water 06:42, 40 minutes after sunrise\". In \"2.6 m of 4.3 m\", 4.3 m is that day's high "
                + "water. nearestTide is the nearest high or low of either kind: never say the tide would suit "
                + "then. Quote times and heights as given, never work out another, and call a tide big only from "
                + "a king or spring topic whose date is that day and whose regions include the place's region or "
                + "are empty. A row with no tide fields: the forecast has no tide for that place then. Call "
                + "list_windows first. For each window of the day asked that is in laterWindows, pass its id to "
                + "look_up_place (the same ids on a spelling re-call) and give its tide from laterTides as above, "
                + "saying PhotoCast is not giving a sky rating for it; never pass a later id to rank_spots. A day "
                + "in neither: PhotoCast has not forecast that day yet; answer answerable false and give no tide. "
                + "Crossing, a causeway, being cut off or when it is safe to walk out: answer answerable "
                + "false, missing tidal crossing times; never present a tide time as access or safety.";

        assertThat(typed).contains(bullet);
        assertThat(thread).contains(bullet);
        assertThat(typed.indexOf(bullet)).isGreaterThan(typed.indexOf("Order: the rating and its reason, then "
                + "why PhotoCast is or is not suggesting it"));
        assertThat(typed.indexOf(bullet)).isGreaterThan(typed.indexOf("To compare earlier picks' light"));
        assertThat(ready).doesNotContain("nearestTide").doesNotContain("KING_TIDE")
                .doesNotContain("causeway").doesNotContain("look_up_place");
        assertThat(AskPromptBuilder.RULES).doesNotContain("nearestTide").doesNotContain("causeway");
    }

    @Test
    @DisplayName("the typed and thread prompts name later windows and later tides, and the old 'not forecast' "
            + "sentence is gone; the user-less prompt mentions neither")
    void typedPromptCarriesTheLaterWindowRule() {
        String typed = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true));
        String thread = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true, true));
        String ready = flat(builder.systemPrompt(MONDAY, TestScopes.of(), null, false));
        String rated = "Then give the rating in one clause, for a rated window.";
        String later = "Call list_windows first. For each window of the day asked that is in laterWindows, pass "
                + "its id to look_up_place (the same ids on a spelling re-call) and give its tide from "
                + "laterTides as above, saying PhotoCast is not giving a sky rating for it; never pass a later "
                + "id to rank_spots. A day in neither: PhotoCast has not forecast that day yet; answer "
                + "answerable false and give no tide.";

        assertThat(typed).contains(rated).contains(later);
        assertThat(thread).contains(rated).contains(later);
        String refusal = "If look_up_place returns no places, no laterTides, no named area, no closestTo and no "
                + "suggestions, read its note and answer answerable false";
        assertThat(typed).contains(refusal).doesNotContain("returns no places, no named area");
        assertThat(thread).contains(refusal).doesNotContain("returns no places, no named area");
        assertThat(typed).doesNotContain("No row for the day asked");
        assertThat(ready).doesNotContain("laterWindows").doesNotContain("laterTides");
        assertThat(AskPromptBuilder.RULES).doesNotContain("laterWindows").doesNotContain("laterTides");
    }

    @Test
    @DisplayName("a user-less prompt says nothing of nearHome")
    void userLessPromptDoesNotMentionNearHome() {
        assertThat(builder.systemPrompt(MONDAY, TestScopes.of(), null, false)).doesNotContain("nearHome");
    }

    @Test
    @DisplayName("an events question must consult BOTH events tools, and 'no events' is allowed only after both "
            + "returned nothing")
    void eventsQuestionsRequireBothTools() {
        String prompt = builder.systemPrompt(MONDAY, TestScopes.of(), null, true)
                .replaceAll("\\s+", " ");

        assertThat(prompt).contains("call BOTH get_hot_topics")
                .contains("and get_coming_up")
                .contains("Say there are no events only after both returned nothing");
    }

    @Test
    @DisplayName("a night question goes to rank_night_spots: clear skies is the astro forecast, tonight is the "
            + "running night, an aurora claim needs auroraStars, the Pro clause, and the facts a night pick quotes")
    void nightRules() {
        String prompt = builder.systemPrompt(MONDAY, TestScopes.of(), null, true).replaceAll("\\s+", " ");

        assertThat(prompt).contains("call rank_night_spots")
                .contains("\"Clear skies tonight\" is the astro cloud forecast, not the sunrise and sunset one")
                .contains("before dawn is the night still running")
                .contains("Never say an aurora is visible without an auroraStars figure")
                .contains("aurora ratings are a Pro feature, say so in one clause")
                .contains("quotes the clearSky and Bortle facts")
                .contains("astroWindowId for the sky, auroraWindowId for the northern lights");
    }

    @Test
    @DisplayName("no shared rule restricts picks, nearby searches or follow-up searches to rank_spots alone: a "
            + "night question's prompt names rank_night_spots in each, for a fresh question and a follow-up")
    void sharedRulesAdmitTheNightTool() {
        for (boolean thread : new boolean[] {false, true}) {
            String prompt = builder.systemPrompt(MONDAY, TestScopes.of(), null, true, thread)
                    .replaceAll("\\s+", " ");

            assertThat(prompt)
                    .contains("that rank_spots returned or, for a night question, that rank_night_spots returned")
                    .contains("pass nearHome true to rank_spots, or to rank_night_spots for a night question")
                    .doesNotContain("that rank_spots returned. ")
                    .doesNotContain("pass rank_spots' nearHome");
        }
        assertThat(builder.systemPrompt(MONDAY, TestScopes.of(), null, true, true).replaceAll("\\s+", " "))
                .contains("Call `rank_spots` (or `rank_night_spots` for a night question) again")
                .doesNotContain("Call `rank_spots` again");
    }

    @Test
    @DisplayName("the night the Map is on is named with its id, tonight or tomorrow night; with none the "
            + "prompt is the prompt of the solar overload, byte for byte")
    void namesTheContextNight() {
        AskSnapshot.Night tonight = AskNightFixtures.astroNight(MONDAY);
        AskSnapshot.Night tomorrow = AskNightFixtures.auroraNight(MONDAY.plusDays(1));

        String tonightPrompt = builder.systemPrompt(MONDAY, TestScopes.of(), null, tonight, MONDAY, true, false);
        String tomorrowPrompt = builder.systemPrompt(MONDAY, TestScopes.of(), null, tomorrow, MONDAY, true, false);

        assertThat(tonightPrompt).contains("looking at tonight's astro night (windowId 2026-10-05_astro)");
        assertThat(tomorrowPrompt).contains("looking at tomorrow night's aurora night (windowId 2026-10-06_aurora)");
        assertThat(builder.systemPrompt(MONDAY, TestScopes.of(), null, null, null, true, false))
                .isEqualTo(builder.systemPrompt(MONDAY, TestScopes.of(), null, true));
    }

    // -- the thread ---------------------------------------------------------------------------

    @Test
    @DisplayName("a fresh question's system prompt is byte for byte what it was before threads, whichever "
            + "overload builds it")
    void freshSystemPromptIsUnchanged() {
        for (boolean hasUser : new boolean[] {true, false}) {
            String legacy = builder.systemPrompt(MONDAY, TestScopes.of(), null, hasUser);

            assertThat(builder.systemPrompt(MONDAY, TestScopes.of(), null, hasUser, false))
                    .isEqualTo(legacy);
            assertThat(legacy).doesNotContain("Earlier picks").doesNotContain("emoji")
                    .doesNotContain("held back").doesNotContain("held-back");
        }
    }

    @Test
    @DisplayName("a follow-up's system prompt gains the plan's rule word for word, the emoji line and the "
            + "gone-places line, after everything else")
    void threadSystemPromptGainsTheRules() {
        String fresh = builder.systemPrompt(MONDAY, TestScopes.of(), null, true);

        String threaded = builder.systemPrompt(MONDAY, TestScopes.of(), null, true, true);

        assertThat(threaded).startsWith(fresh).endsWith(AskPromptBuilder.THREAD_RULES);
        assertThat(threaded.replaceAll("\\s+", " ")).contains("Earlier picks are real candidates: you may "
                + "rank, compare, or exclude them. Resolve 'closer', 'earlier', 'the other one', 'those' "
                + "against them. Call `rank_spots` (or `rank_night_spots` for a night question) again when "
                + "the reader asks for new places; you do not need to for a question only about the earlier picks.")
                .contains("Never use emoji or symbols other than ★ in the summary.")
                .contains("no longer in the forecast, or outside the area, cannot be a pick");
    }

    @Test
    @DisplayName("a fresh question's user message is the question and nothing else")
    void freshUserMessageIsTheQuestion() {
        assertThat(builder.userMessage("best spot this weekend", AskThreadContext.NONE))
                .isEqualTo("best spot this weekend");
        assertThat(builder.userMessage("best spot this weekend", null)).isEqualTo("best spot this weekend");
    }

    @Test
    @DisplayName("golden: a follow-up's user message is the pinned text - earlier exchanges re-joined live, "
            + "a place outside the area, a place that has gone, an event no longer flagged")
    void threadUserMessageIsTheGolden() throws Exception {
        assertThat(builder.userMessage("what about sunrise instead?", goldenThread()))
                .isEqualTo(golden("user-message-thread.txt"));
    }

    private static AskThreadContext turnedDownThread(boolean turnedDown) {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.coastal(1L, "Whitby", 4, "HIGH", true));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(MONDAY, null, coast)), List.of()));
        AskTools tools = new AskTools(snapshot, new AskUserContext(7L, UserRole.PRO_USER, false),
                TestScopes.of(), mock(DriveTimeResolver.class), new ObjectMapper());
        ThreadExchange earlier = new ThreadExchange("best sunset?", "Whitby.",
                List.of(new PickRef(1L, "2026-10-05_sunset")), List.of(), AskFixtures.GENERATED_AT, false);
        return AskThreadContext.of(new AskThread(List.of(earlier)), snapshot, TestScopes.of(), tools,
                AskLocationNames.NONE, turnedDown, false);
    }

    @Test
    @DisplayName("turned down: the earlier pick is marked, and the message says turned-down places must not be "
            + "picked again, before the question")
    void turnedDownUserMessage() {
        String message = builder.userMessage("neither of those, best sunset?", turnedDownThread(true));

        assertThat(message).contains("1. Whitby (Coast) · locationId 1")
                .contains(" · the reader has turned this place down\n")
                .contains("Turned-down places must not be picked again")
                .endsWith("Now the reader asks: \"neither of those, best sunset?\"");
        assertThat(message.indexOf("Turned-down places")).isLessThan(message.indexOf("Now the reader asks"));
    }

    @Test
    @DisplayName("not turned down: neither the mark nor the rule line appears")
    void notTurnedDownUserMessage() {
        String message = builder.userMessage("best sunset?", turnedDownThread(false));

        assertThat(message).doesNotContain("turned this place down").doesNotContain("Turned-down");
    }

    private static AskThreadContext goldenThread() {
        LocalDate wednesday = MONDAY.plusDays(2);
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.coastal(1L, "Whitby", 4, "HIGH", true),
                AskFixtures.coastal(2L, "Saltburn", 4, "LOW", false));
        BriefingRegion hills = AskFixtures.region("Hills", true, AskFixtures.slot(3L, "Kielder", 5));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(MONDAY, null, coast, hills)),
                List.of(AskFixtures.topic("KING_TIDE", "King tide", "Big water", wednesday,
                        List.of("Coast")))));
        DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 55, 2L, 80, 3L, 40));
        AskScope scope = TestScopes.of("Coast");
        AskTools tools = new AskTools(snapshot, new AskUserContext(7L, UserRole.PRO_USER, true), scope,
                driveTimes, new ObjectMapper());
        ThreadExchange first = new ThreadExchange("anything for sunset with the tide right?",
                "Whitby and Saltburn both work, Whitby has the water.",
                List.of(new PickRef(1L, "2026-10-05_sunset"), new PickRef(2L, "2026-10-05_sunset")),
                List.of(new EventRef("KING_TIDE", wednesday)), AskFixtures.GENERATED_AT, false);
        ThreadExchange second = new ThreadExchange("Thanks. Anything closer to home?",
                "Kielder is 40 minutes away and rated 5★.",
                List.of(new PickRef(3L, "2026-10-05_sunset"), new PickRef(41L, "2026-10-05_sunrise")),
                List.of(new EventRef("AURORA", wednesday)), AskFixtures.GENERATED_AT, false);
        return AskThreadContext.of(new AskThread(List.of(first, second)), snapshot, scope, tools,
                () -> Map.of(41L, new AskLocationNames.Place("Dunstanburgh", 55.5, -1.6)), false, false);
    }

    private static String golden(String name) throws Exception {
        try (var in = AskPromptBuilderTest.class.getResourceAsStream("/ask/" + name)) {
            return new String(java.util.Objects.requireNonNull(in, name).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // -- the window criteria (ask-window-context plan §2.3) ----------------------------------------

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("the rules state the owner's scale and how to judge a window from its spread, and the fill "
            + "instruction ('give 2 or 3 picks') is gone")
    void rulesStateTheCriteria() {
        String rules = flat(AskPromptBuilder.RULES);

        assertThat(rules).contains("1★ and 2★ are stay home; 3★ is marginal; 4★ is game on; 5★ is go")
                .contains("Read the spread before the best rating")
                .contains("a stay-home window with a few exceptions, not a good window")
                .contains("quoting the figures as returned")
                .contains("A window whose regions were all stood down is poor everywhere")
                .contains("mostly not scored or thin is mostly unlooked-at: say that, never that it is poor")
                .contains("Never call a window good because its best rating is 4★ or 5★ when its spread says "
                        + "otherwise")
                .contains("give up to 3 picks at different spots, best first. Fewer is fine and none is fine")
                .contains("never fill the list with marginal places")
                .doesNotContain("give 2 or 3 picks");
    }

    @Test
    @DisplayName("the hint is a hint: the stop condition is 'no game-on place anywhere the question is about', "
            + "never 'every window is stay-home', and it excuses questions it does not fit")
    void rulesStateTheHint() {
        String rules = flat(AskPromptBuilder.RULES);

        assertThat(rules).contains("A good investigation usually runs: list_windows; if none of the windows the "
                + "question is about holds a game-on place, say so and stop")
                .contains("even a couple of exceptions on a stay-home window, rank_spots in those regions and "
                        + "windows")
                .contains("check get_hot_topics for a spring or king tide")
                .contains("Questions this does not fit (one place, one tide, one event, the night) need none "
                        + "of it")
                .doesNotContain("every window is stay-home");
    }

    @Test
    @DisplayName("the 3★ clause is split by conversation and the spread's population is named, with no home "
            + "in a user-less prompt")
    void threeStarAndPopulationSentences() {
        String userLess = flat(AskPromptBuilder.USER_LESS);
        String withUser = flat(AskPromptBuilder.WITH_USER);

        assertThat(userLess).contains("never mention home, distance or drive times")
                .contains("say which area it counts, as the result's over says")
                .contains("never \"within reach\"")
                .contains("a 3★ pick is only ever the forecast's own BEST BET or ALSO GOOD, named as the "
                        + "forecast's pick");
        assertThat(withUser).contains("A 3★ place is worth considering only close to the reader's home; say so "
                + "when you offer one")
                .contains("The spread counts the whole area, not the places within the reader's drive");
        assertThat(userLess).doesNotContain("close to the reader's home");
    }

    @Test
    @DisplayName("a follow-up about the windows themselves is told to call list_windows again")
    void threadRulesSendWindowFollowUpsToListWindows() {
        assertThat(flat(AskPromptBuilder.THREAD_RULES)).contains("A follow-up about the windows themselves "
                + "(\"so is it a stay-home weekend?\", \"how does Sunday look?\") calls list_windows again.");
    }

    @Test
    @DisplayName("a Ready conversation's prompt names the windows its question covers; with none the prompt is "
            + "the one it was")
    void readyPromptNamesTheCoveredWindows() {
        String plain = builder.systemPrompt(MONDAY, TestScopes.of(), null, null, null, false, false);

        String ready = builder.systemPrompt(MONDAY, TestScopes.of(), null, null, null, false, false,
                java.util.List.of("2026-10-10_sunrise", "2026-10-10_sunset", "2026-10-11_sunset"));

        assertThat(ready).contains("The question is about these windows: 2026-10-10_sunrise, 2026-10-10_sunset, "
                + "2026-10-11_sunset. Every pick must be on one of these windows.");
        assertThat(builder.systemPrompt(MONDAY, TestScopes.of(), null, null, null, false, false,
                java.util.List.of())).isEqualTo(plain);
        assertThat(plain).doesNotContain("The question is about these windows")
                .doesNotContain("Every pick must be on one of these windows");
    }

    // -- the two system blocks (prompt caching) ---------------------------------------------

    @Test
    @DisplayName("the static block is the rules and the with-user or user-less text only: identical across "
            + "dates, scopes, windows and threads, so it is cacheable across questions")
    void staticBlockIsIdenticalAcrossQuestions() {
        for (boolean hasUser : new boolean[] {true, false}) {
            AskPromptBuilder.SystemPrompt first = builder.systemPromptBlocks(MONDAY, TestScopes.of(), null, null,
                    null, hasUser, false, List.of());
            AskPromptBuilder.SystemPrompt second = builder.systemPromptBlocks(MONDAY.plusDays(3),
                    TestScopes.of("Northumberland"), null, AskNightFixtures.astroNight(MONDAY), MONDAY, hasUser,
                    true, List.of("2026-10-08_sunset"));

            assertThat(second.staticPart()).isEqualTo(first.staticPart());
            assertThat(second.dynamicPart()).isNotEqualTo(first.dynamicPart());
            assertThat(first.staticPart()).doesNotContain("Today is ");
            assertThat(first.dynamicPart()).startsWith("Today is ");
        }
        assertThat(builder.systemPromptBlocks(MONDAY, TestScopes.of(), null, null, null, true, false, List.of())
                .staticPart()).isNotEqualTo(builder.systemPromptBlocks(MONDAY, TestScopes.of(), null, null, null,
                        false, false, List.of()).staticPart());
    }

    @Test
    @DisplayName("the two blocks joined are exactly the single prompt, whichever overload builds it")
    void blocksJoinToTheSinglePrompt() {
        for (boolean hasUser : new boolean[] {true, false}) {
            for (boolean hasThread : new boolean[] {true, false}) {
                String single = builder.systemPrompt(MONDAY, TestScopes.of("Northumberland"), null, null, null,
                        hasUser, hasThread, List.of("2026-10-05_sunset"));
                AskPromptBuilder.SystemPrompt blocks = builder.systemPromptBlocks(MONDAY,
                        TestScopes.of("Northumberland"), null, null, null, hasUser, hasThread,
                        List.of("2026-10-05_sunset"));

                assertThat(blocks.staticPart() + blocks.dynamicPart()).isEqualTo(single);
                assertThat(blocks.text()).isEqualTo(single);
            }
        }
    }
}
