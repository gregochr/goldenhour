package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.service.EclipseHotTopicStrategy;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.BestAnchor;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Raw;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawEvent;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskAnswerValidator}: every rule, a surviving and a dropped case each. */
class AskAnswerValidatorTest {

    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final String SUNSET_TODAY = "2026-10-05_sunset";
    private static final String SUNRISE_TOMORROW = "2026-10-06_sunrise";

    private final AskAnswerValidator validator = new AskAnswerValidator();

    // -- fixtures ---------------------------------------------------------------------------

    private static AskSnapshot snapshot(BriefingWindow.Pick todaysPick, BriefingSlot... slots) {
        BriefingRegion region = AskFixtures.region("Coast", true, slots);
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, todaysPick, region)), List.of()));
    }

    private static AskEvidence evidenceOf(AskEvidence.Pair... pairs) {
        return new AskEvidence(Set.of(pairs), Set.of(), 1);
    }

    private static AskEvidence.Pair pair(long id) {
        return new AskEvidence.Pair(id, SUNSET_TODAY);
    }

    private static Raw answer(String summary, RawPick... picks) {
        return new Raw(true, summary, List.of(picks), List.of(), null);
    }

    private static RawPick rawPick(long id, String why) {
        return new RawPick(id, SUNSET_TODAY, why);
    }

    private Result validate(Raw raw, AskSnapshot snapshot, AskEvidence evidence) {
        return validator.validate(raw, snapshot, evidence, null);
    }

    // -- picks ------------------------------------------------------------------------------

    @Test
    @DisplayName("a returned, still-eligible pick survives with every card fact joined from served data")
    void pick_survivesWithServedFacts() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "Whitby", 4));

        Result result = validate(answer("Whitby tonight.", rawPick(1L, "Clear west")), snapshot,
                evidenceOf(pair(1L)));

        assertThat(result.accepted()).isTrue();
        AskPick pick = result.answer().picks().getFirst();
        assertThat(pick.rank()).isEqualTo(1);
        assertThat(pick.locationId()).isEqualTo(1L);
        assertThat(pick.locationName()).isEqualTo("Whitby");
        assertThat(pick.regionName()).isEqualTo("Coast");
        assertThat(pick.date()).isEqualTo(TODAY);
        assertThat(pick.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(pick.windowId()).isEqualTo(SUNSET_TODAY);
        assertThat(pick.why()).isEqualTo("Clear west");
        assertThat(pick.ratingAtAnswer()).isEqualTo(4);
        assertThat(pick.verdictAtAnswer()).isEqualTo("WORTH_IT");
    }

    @Test
    @DisplayName("a pick no tool returned is dropped, whatever the snapshot says")
    void pick_notReturnedByATool_isDropped() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "Whitby", 4),
                AskFixtures.slot(2L, "Staithes", 5));

        Result result = validate(answer("Staithes.", rawPick(2L, "Lovely")), snapshot,
                evidenceOf(pair(1L)));

        assertThat(result.answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("a pair returned for another window does not license the same location at this one")
    void pick_pairMustMatchTheWindow() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "Whitby", 4));

        Result result = validate(answer("Tomorrow.", new RawPick(1L, SUNRISE_TOMORROW, "x")),
                snapshot, evidenceOf(pair(1L)));

        assertThat(result.answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("a pick that stopped being eligible since the tool returned it is dropped")
    void pick_noLongerEligible_isDropped() {
        AskSnapshot before = snapshot(null, AskFixtures.slot(1L, "Whitby", 4));
        AskSnapshot after = snapshot(null, AskFixtures.slot(1L, "Whitby", 2));
        AskEvidence returnedBefore = evidenceOf(pair(1L));

        assertThat(validate(answer("Whitby.", rawPick(1L, "x")), before, returnedBefore)
                .answer().picks()).hasSize(1);
        assertThat(validate(answer("Whitby.", rawPick(1L, "x")), after, returnedBefore)
                .answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("a pick whose window left the window set is dropped")
    void pick_windowGone_isDropped() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "Whitby", 4));

        Result result = validate(answer("Gone.", new RawPick(1L, "2026-10-04_sunset", "x")),
                snapshot, new AskEvidence(Set.of(new AskEvidence.Pair(1L, "2026-10-04_sunset")),
                        Set.of(), 1));

        assertThat(result.answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("at most three picks, distinct locations, ranks renumbered from 1")
    void picks_capDistinctAndRenumbered() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4), AskFixtures.slot(2L, "B", 4),
                AskFixtures.slot(3L, "C", 4), AskFixtures.slot(4L, "D", 4));
        AskEvidence evidence = evidenceOf(pair(1L), pair(2L), pair(3L), pair(4L));

        Result result = validate(answer("Four.", rawPick(99L, "unknown"), rawPick(1L, "a"),
                rawPick(1L, "again"), rawPick(2L, "b"), rawPick(3L, "c"), rawPick(4L, "d")),
                snapshot, evidence);

        assertThat(result.answer().picks()).extracting(AskPick::locationId)
                .containsExactly(1L, 2L, 3L);
        assertThat(result.answer().picks()).extracting(AskPick::rank).containsExactly(1, 2, 3);
        assertThat(result.answer().picks().getFirst().why()).isEqualTo("a");
    }

    @Test
    @DisplayName("the reason is capped at 24 words, brand-sanitised and stripped of URLs")
    void pick_whyIsCleaned() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        String why = "Claude says see https://example.com/x now " + "word ".repeat(40);

        Result result = validate(answer("A.", rawPick(1L, why)), snapshot, evidenceOf(pair(1L)));

        String cleaned = result.answer().picks().getFirst().why();
        assertThat(cleaned.split(" ")).hasSize(AskAnswerValidator.WHY_WORDS);
        assertThat(cleaned).startsWith("PhotoCast says see now").doesNotContain("Claude")
                .doesNotContain("http").doesNotContain("example.com");
    }

    @Test
    @DisplayName("a null reason becomes an empty one rather than a null card line")
    void pick_nullWhy() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));

        Result result = validate(answer("A.", new RawPick(1L, SUNSET_TODAY, null)), snapshot,
                evidenceOf(pair(1L)));

        assertThat(result.answer().picks().getFirst().why()).isEmpty();
    }

    // -- summary, caps and cleaning ---------------------------------------------------------

    @Test
    @DisplayName("a missing, blank or all-URL summary discards the answer")
    void summary_isRequired() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = evidenceOf(pair(1L));

        assertThat(validate(answer(null, rawPick(1L, "x")), snapshot, evidence).accepted()).isFalse();
        assertThat(validate(answer("   ", rawPick(1L, "x")), snapshot, evidence).accepted()).isFalse();
        Result urlOnly = validate(answer("https://example.com", rawPick(1L, "x")), snapshot, evidence);
        assertThat(urlOnly.accepted()).isFalse();
        assertThat(urlOnly.reason()).isEqualTo("no summary");
    }

    @Test
    @DisplayName("the summary is capped at 60 words and missing at 8")
    void summaryAndMissing_areWordCapped() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        String longSummary = "word ".repeat(100);
        String longMissing = "item ".repeat(20);

        Result result = validate(new Raw(false, longSummary, null, null, longMissing), snapshot,
                evidenceOf());

        assertThat(result.answer().summary().split(" ")).hasSize(AskAnswerValidator.SUMMARY_WORDS);
        assertThat(result.answer().missing().split(" ")).hasSize(AskAnswerValidator.MISSING_WORDS);
    }

    @Test
    @DisplayName("exactly 60 summary words and 24 reason words are kept whole")
    void caps_boundaryIsInclusive() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        String sixty = "w ".repeat(60).strip();
        String twentyFour = "v ".repeat(24).strip();

        Result result = validate(answer(sixty, rawPick(1L, twentyFour)), snapshot, evidenceOf(pair(1L)));

        assertThat(result.answer().summary()).isEqualTo(sixty);
        assertThat(result.answer().picks().getFirst().why()).isEqualTo(twentyFour);
    }

    @Test
    @DisplayName("URL-like strings are stripped: schemes, www, bare domains with paths; place names are left alone")
    void clean_stripsUrlLikeStrings() {
        assertThat(AskAnswerValidator.clean("See https://a.com/x?y=1 for more", 60))
                .isEqualTo("See for more");
        assertThat(AskAnswerValidator.clean("Go to www.example.org today", 60))
                .isEqualTo("Go to today");
        assertThat(AskAnswerValidator.clean("Book at foo.co.uk/tickets or mailto:a@b.com now", 60))
                .isEqualTo("Book at or now");
        assertThat(AskAnswerValidator.clean("St. Mary's Lighthouse at Whitby, Saturday.", 60))
                .isEqualTo("St. Mary's Lighthouse at Whitby, Saturday.");
        assertThat(AskAnswerValidator.clean("Bamburgh. Then Seahouses.", 60))
                .isEqualTo("Bamburgh. Then Seahouses.");
        assertThat(AskAnswerValidator.clean(null, 60)).isNull();
    }

    @Test
    @DisplayName("the engine's own names are rebranded in the summary")
    void summary_isRebranded() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));

        Result result = validate(answer("Anthropic and Claude agree.", rawPick(1L, "x")), snapshot,
                evidenceOf(pair(1L)));

        assertThat(result.answer().summary()).isEqualTo("PhotoCast and PhotoCast agree.");
    }

    // -- answerable -------------------------------------------------------------------------

    @Test
    @DisplayName("answerable with nothing surviving is allowed only when a tool was called")
    void answerable_withNothingNeedsAToolCall() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 2));
        Raw nothingWorthIt = answer("Nothing is worth the drive this week.");

        Result withTool = validate(nothingWorthIt, snapshot, new AskEvidence(Set.of(), Set.of(), 1));
        Result noTool = validate(nothingWorthIt, snapshot, new AskEvidence(Set.of(), Set.of(), 0));

        assertThat(withTool.accepted()).isTrue();
        assertThat(withTool.answer().answerable()).isTrue();
        assertThat(withTool.answer().picks()).isEmpty();
        assertThat(noTool.accepted()).isFalse();
        assertThat(noTool.reason()).contains("no tool call");
    }

    @Test
    @DisplayName("answerable:false keeps the summary and missing and clears picks and events")
    void unanswerable_clearsPicksAndEvents() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = new AskEvidence(Set.of(pair(1L)),
                Set.of(new AskEvidence.EventFact("AURORA", "Aurora", TODAY)), 1);
        Raw raw = new Raw(false, "PhotoCast has no parking data.", List.of(rawPick(1L, "x")),
                List.of(new RawEvent("AURORA", null, "x")), "  parking  ");

        Result result = validate(raw, snapshot, evidence);

        assertThat(result.answer().answerable()).isFalse();
        assertThat(result.answer().picks()).isEmpty();
        assertThat(result.answer().events()).isEmpty();
        assertThat(result.answer().missing()).isEqualTo("parking");
    }

    @Test
    @DisplayName("a blank missing phrase becomes null")
    void missing_blankBecomesNull() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));

        Result result = validate(new Raw(false, "No.", null, null, "https://x.com"), snapshot,
                evidenceOf());

        assertThat(result.answer().missing()).isNull();
    }

    // -- events -----------------------------------------------------------------------------

    @Test
    @DisplayName("an event survives only if its type was returned, and shows the served label and date")
    void event_survivesWithServedFacts() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = new AskEvidence(Set.of(), Set.of(
                new AskEvidence.EventFact("AURORA", "Aurora possible", TOMORROW),
                new AskEvidence.EventFact("SNOW", "Snow on the tops", TODAY)), 2);
        Raw raw = new Raw(true, "Two things.", null, List.of(
                new RawEvent("aurora", null, "Kp 6 forecast"),
                new RawEvent("METEOR", null, "invented"),
                new RawEvent(null, null, "no type")), null);

        Result result = validate(raw, snapshot, evidence);

        assertThat(result.answer().events()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("AURORA");
            assertThat(e.label()).isEqualTo("Aurora possible");
            assertThat(e.date()).isEqualTo(TOMORROW);
            assertThat(e.why()).isEqualTo("Kp 6 forecast");
        });
    }

    private static final String WARNING = "Certified solar filter on the lens — not only over your eye";

    @Test
    @DisplayName("a served safety note is always on the validated event, whatever the model wrote in its reason")
    void event_safetyNoteIsRejoinedFromServedData() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = new AskEvidence(Set.of(), Set.of(
                new AskEvidence.EventFact("ECLIPSE", "Partial solar eclipse", TOMORROW, WARNING)), 1);

        Result silent = validate(new Raw(true, "An eclipse.", null,
                List.of(new RawEvent("ECLIPSE", null, "Great shots")), null), snapshot, evidence);
        Result contradicting = validate(new Raw(true, "An eclipse.", null,
                List.of(new RawEvent("eclipse", TOMORROW, "No filter needed, just look")), null),
                snapshot, evidence);

        assertThat(silent.answer().events().getFirst().safetyNote()).isEqualTo(WARNING);
        assertThat(contradicting.answer().events().getFirst().safetyNote()).isEqualTo(WARNING);
    }

    @Test
    @DisplayName("a solar eclipse validated from get_coming_up evidence alone carries the note; a lunar one none")
    void event_fromTheAlmanacAlone_solarCarriesTheNoteLunarNone() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskSnapshot withAlmanac = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()), List.of(
                AskSnapshotBuilderTest.almanacEntry("eclipse", "Partial solar eclipse",
                        TODAY.plusDays(40), TODAY.plusDays(40), "A partial eclipse"),
                AskSnapshotBuilderTest.almanacEntry("lunar-eclipse", "Total lunar eclipse",
                        TODAY.plusDays(50), TODAY.plusDays(50), "Moon in shadow")));
        AskTools tools = new AskTools(withAlmanac, AskUserContext.userLess(), Set.of(), null,
                new com.fasterxml.jackson.databind.ObjectMapper());
        tools.getComingUp(null);

        Result result = validate(new Raw(true, "Two eclipses.", null, List.of(
                new RawEvent("eclipse", null, "Worth planning"),
                new RawEvent("LUNAR-ECLIPSE", null, "Easy to watch")), null),
                snapshot, tools.evidence());

        assertThat(result.answer().events()).extracting(AskEvent::type)
                .containsExactly("ECLIPSE", "LUNAR-ECLIPSE");
        assertThat(result.answer().events().get(0).safetyNote())
                .isEqualTo(EclipseHotTopicStrategy.SAFETY_NOTE);
        assertThat(result.answer().events().get(1).safetyNote()).isNull();
    }

    @Test
    @DisplayName("of two served facts for one type and date, the one carrying a warning is used")
    void event_aWarningIsNeverLostToAnEqualFact() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = new AskEvidence(Set.of(),
                Set.of(new AskEvidence.EventFact("ECLIPSE", "Eclipse", TOMORROW, null),
                        new AskEvidence.EventFact("ECLIPSE", "Eclipse", TOMORROW, WARNING)), 2);

        Result result = validate(new Raw(true, "An eclipse.", null,
                List.of(new RawEvent("ECLIPSE", null, "x")), null), snapshot, evidence);

        assertThat(result.answer().events().getFirst().safetyNote()).isEqualTo(WARNING);
    }

    @Test
    @DisplayName("an event whose served topic has no safety note carries none, and none is written on the wire")
    void event_noServedNote_isNullAndOmitted() throws Exception {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = new AskEvidence(Set.of(),
                Set.of(new AskEvidence.EventFact("AURORA", "Aurora", null)), 1);

        Result result = validate(new Raw(true, "Aurora.", null,
                List.of(new RawEvent("AURORA", null, "Kp 6")), null), snapshot, evidence);

        AskEvent event = result.answer().events().getFirst();
        assertThat(event.safetyNote()).isNull();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(mapper.writeValueAsString(event)).has("safetyNote")).isFalse();
        AskEvent withNote = new AskEvent("ECLIPSE", "Eclipse", null, "why", WARNING);
        assertThat(mapper.readTree(mapper.writeValueAsString(withNote)).path("safetyNote").asText())
                .isEqualTo(WARNING);
    }

    @Test
    @DisplayName("the model's date selects among served dates and a date no tool returned drops the event")
    void event_dateMustMatchAServedDate() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = new AskEvidence(Set.of(), Set.of(
                new AskEvidence.EventFact("KING_TIDE", "King tide", TOMORROW.plusDays(2)),
                new AskEvidence.EventFact("KING_TIDE", "King tide", TOMORROW)), 1);
        Raw raw = new Raw(true, "Tides.", null, List.of(
                new RawEvent("KING_TIDE", TOMORROW.plusDays(2), "later"),
                new RawEvent("KING_TIDE", TOMORROW.plusDays(9), "invented date"),
                new RawEvent("KING_TIDE", null, "earliest served")), null);

        Result result = validate(raw, snapshot, evidence);

        assertThat(result.answer().events()).extracting(AskEvent::date)
                .containsExactly(TOMORROW.plusDays(2), TOMORROW);
        assertThat(result.answer().events()).extracting(AskEvent::why)
                .containsExactly("later", "earliest served");
    }

    @Test
    @DisplayName("the same event named twice appears once")
    void event_duplicateIsDropped() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = new AskEvidence(Set.of(),
                Set.of(new AskEvidence.EventFact("AURORA", "Aurora", TODAY)), 1);

        Result result = validate(new Raw(true, "Aurora.", null, List.of(
                new RawEvent("AURORA", null, "one"), new RawEvent("AURORA", null, "two")), null),
                snapshot, evidence);

        assertThat(result.answer().events()).hasSize(1);
        assertThat(result.answer().events().getFirst().why()).isEqualTo("one");
    }

    @Test
    @DisplayName("an event-only answer with a tool call is accepted; a null event why becomes empty")
    void event_onlyAnswer() {
        AskSnapshot snapshot = snapshot(null, AskFixtures.slot(1L, "A", 4));
        AskEvidence evidence = new AskEvidence(Set.of(),
                Set.of(new AskEvidence.EventFact("AURORA", "Aurora", null)), 1);

        Result result = validate(new Raw(true, "Aurora.", null,
                List.of(new RawEvent("AURORA", null, null)), null), snapshot, evidence);

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().events().getFirst().why()).isEmpty();
        assertThat(result.answer().events().getFirst().date()).isNull();
    }

    // -- the BEST anchor --------------------------------------------------------------------

    private static AskSnapshot twoWindowSnapshot() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast", "Whitby", 1L);
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Whitby", 4), AskFixtures.slot(2L, "Staithes", 5));
        BriefingWindow tonight = AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 5, best);
        BriefingWindow morning = AskFixtures.window(TOMORROW.atTime(5, 40), DisplayVerdict.WORTH_IT, 5, null);
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(
                AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET, tonight, region)),
                AskFixtures.day(TOMORROW, AskFixtures.summary(TargetType.SUNRISE, morning, region))),
                List.of()));
    }

    private static final BestAnchor ANCHOR =
            new BestAnchor(Set.of(SUNSET_TODAY, SUNRISE_TOMORROW), Set.of());

    private static final AskEvidence BOTH_WINDOWS = new AskEvidence(Set.of(
            new AskEvidence.Pair(1L, SUNSET_TODAY), new AskEvidence.Pair(2L, SUNSET_TODAY),
            new AskEvidence.Pair(2L, SUNRISE_TOMORROW)), Set.of(), 1);

    @Test
    @DisplayName("BEST_*: pick 1 on the BEST BET window is accepted, whichever location leads there")
    void bestAnchor_leadingWithTheBestWindowIsAccepted() {
        Result result = validator.validate(answer("Tonight.", rawPick(2L, "Brightest")),
                twoWindowSnapshot(), BOTH_WINDOWS, ANCHOR);

        assertThat(result.accepted()).isTrue();
    }

    @Test
    @DisplayName("BEST_*: pick 1 on any other window discards the answer")
    void bestAnchor_leadingElsewhereIsDiscarded() {
        Result result = validator.validate(
                answer("Tomorrow.", new RawPick(2L, SUNRISE_TOMORROW, "x"), rawPick(1L, "y")),
                twoWindowSnapshot(), BOTH_WINDOWS, ANCHOR);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains(SUNSET_TODAY);
    }

    @Test
    @DisplayName("BEST_*: an answer with no surviving pick at all is discarded when a BEST BET exists")
    void bestAnchor_noPickIsDiscarded() {
        Result result = validator.validate(answer("Nothing."), twoWindowSnapshot(), BOTH_WINDOWS, ANCHOR);

        assertThat(result.accepted()).isFalse();
    }

    @Test
    @DisplayName("BEST_*: unanswerable is not an escape from the anchor")
    void bestAnchor_unanswerableIsDiscarded() {
        Result result = validator.validate(new Raw(false, "No.", null, null, "data"),
                twoWindowSnapshot(), BOTH_WINDOWS, ANCHOR);

        assertThat(result.accepted()).isFalse();
    }

    @Test
    @DisplayName("BEST_*: a question whose windows carry no BEST BET is not anchored")
    void bestAnchor_notAnchoredWithoutABestWindowCovered() {
        BestAnchor tomorrowOnly = new BestAnchor(Set.of(SUNRISE_TOMORROW), Set.of());

        Result result = validator.validate(
                answer("Tomorrow.", new RawPick(2L, SUNRISE_TOMORROW, "x")),
                twoWindowSnapshot(), BOTH_WINDOWS, tomorrowOnly);

        assertThat(result.accepted()).isTrue();
    }

    @Test
    @DisplayName("BEST_*: a BEST BET outside the question's scope does not anchor it")
    void bestAnchor_bestBetOutOfScope() {
        BestAnchor otherScope = new BestAnchor(Set.of(SUNSET_TODAY), Set.of("Hills"));

        Result result = validator.validate(
                answer("Tomorrow.", new RawPick(2L, SUNRISE_TOMORROW, "x")),
                twoWindowSnapshot(), BOTH_WINDOWS, otherScope);

        assertThat(result.accepted()).isTrue();
    }

    @Test
    @DisplayName("BEST_*: a BEST BET window with nothing pick-eligible to lead with does not anchor")
    void bestAnchor_nothingEligibleOnTheBestWindow() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast", "Whitby", 1L);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(
                AskFixtures.sunsetDay(TODAY, best,
                        AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Whitby", 2)))),
                List.of()));

        Result result = validator.validate(answer("Nothing is worth it tonight."), snapshot,
                new AskEvidence(Set.of(), Set.of(), 1), new BestAnchor(Set.of(SUNSET_TODAY), Set.of()));

        assertThat(result.accepted()).isTrue();
    }

    /** The BEST BET names Coast, whose only slot is 2 stars; Hills, outside a Coast scope, has a 4-star slot. */
    private static AskSnapshot bestRegionHasNothingEligibleSnapshot() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast", "Whitby", 1L);
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(TODAY, best,
                AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Whitby", 2)),
                AskFixtures.region("Hills", true, AskFixtures.slot(5L, "Cat Bells", 4)))), List.of()));
    }

    @Test
    @DisplayName("BEST_*: scoped to a region with nothing eligible, an eligible slot elsewhere does not anchor it")
    void bestAnchor_scopedToARegionWithNothingEligible_doesNotApply() {
        AskSnapshot snapshot = bestRegionHasNothingEligibleSnapshot();
        BestAnchor coastOnly = new BestAnchor(Set.of(SUNSET_TODAY), Set.of("coast"));
        AskEvidence toolsCalled = new AskEvidence(Set.of(), Set.of(), 1);

        Result noPicks = validator.validate(answer("Nothing is worth it in Coast tonight."),
                snapshot, toolsCalled, coastOnly);
        Result unanswerable = validator.validate(new Raw(false, "No.", null, null, "data"),
                snapshot, toolsCalled, coastOnly);

        assertThat(noPicks.accepted()).isTrue();
        assertThat(unanswerable.accepted()).isTrue();
    }

    @Test
    @DisplayName("BEST_*: the same snapshot with the whole country in scope still anchors on the eligible slot")
    void bestAnchor_emptyScopeStillAnchors() {
        AskSnapshot snapshot = bestRegionHasNothingEligibleSnapshot();
        BestAnchor everywhere = new BestAnchor(Set.of(SUNSET_TODAY), Set.of());
        AskEvidence toolsCalled = new AskEvidence(Set.of(), Set.of(), 1);

        Result noPicks = validator.validate(answer("Nothing."), snapshot, toolsCalled, everywhere);

        assertThat(noPicks.accepted()).isFalse();
    }

    @Test
    @DisplayName("BEST_*: a scope that includes an eligible slot, in any letter case, still enforces the anchor")
    void bestAnchor_scopeWithAnEligibleSlotStillAnchors() {
        AskSnapshot snapshot = bestRegionHasNothingEligibleSnapshot();
        BestAnchor hillsOnly = new BestAnchor(Set.of(SUNSET_TODAY), Set.of("Coast", "HILLS"));
        AskEvidence toolsCalled = new AskEvidence(Set.of(), Set.of(), 1);

        Result noPicks = validator.validate(answer("Nothing."), snapshot, toolsCalled, hillsOnly);

        assertThat(noPicks.accepted()).isFalse();
    }

    @Test
    @DisplayName("the anchor and rank_spots agree: where the anchor does not apply, the scoped tool offers nothing")
    void bestAnchor_agreesWithRankSpotsUnderTheSameScope() {
        AskSnapshot snapshot = bestRegionHasNothingEligibleSnapshot();
        AskTools scoped = new AskTools(snapshot, AskUserContext.userLess(), Set.of("Coast"), null,
                new com.fasterxml.jackson.databind.ObjectMapper());

        AskTools.RankSpotsResult result = (AskTools.RankSpotsResult) scoped.rankSpots(null).payload();

        assertThat(result.spots()).isEmpty();
        assertThat(snapshot.candidates(snapshot.windows().getFirst(), Set.of("coast"))).isEmpty();
        assertThat(snapshot.candidates(snapshot.windows().getFirst(), Set.of("HILLS"))).hasSize(1);
        assertThat(snapshot.candidates(snapshot.windows().getFirst(), Set.of())).hasSize(1);
        assertThat(snapshot.candidates(snapshot.windows().getFirst(), null)).hasSize(1);
    }

    @Test
    @DisplayName("BEST_*: an anchor with null sets reads as empty")
    void bestAnchor_nullSets() {
        BestAnchor anchor = new BestAnchor(null, null);

        assertThat(anchor.windowIds()).isEmpty();
        assertThat(anchor.scope()).isEmpty();
    }

    @Test
    @DisplayName("a Raw answer with null lists reads as empty")
    void raw_nullListsAreEmpty() {
        Raw raw = new Raw(true, "x", null, null, null);

        assertThat(raw.picks()).isEmpty();
        assertThat(raw.events()).isEmpty();
        List<RawPick> noPicks = new ArrayList<>();
        assertThat(new Raw(true, "x", noPicks, List.of(), null).picks()).isEmpty();
    }

    @Test
    @DisplayName("agreement: evidence gathered by the real tools lets the tools' own top pick through")
    void agreementWithTheTools() {
        AskSnapshot snapshot = twoWindowSnapshot();
        AskTools tools = new AskTools(snapshot, AskUserContext.userLess(), Set.of(), null,
                new com.fasterxml.jackson.databind.ObjectMapper());
        tools.rankSpots(new AskTools.RankSpotsArgs(null, null, null, null, null, 3));
        AskTools.SpotInfo top = ((AskTools.RankSpotsResult) tools.rankSpots(
                new AskTools.RankSpotsArgs(List.of(SUNSET_TODAY), null, null, null, null, 1))
                .payload()).spots().getFirst();

        Result result = validator.validate(
                answer("Top.", new RawPick(top.locationId(), top.windowId(), "Best")),
                snapshot, tools.evidence(), null);

        assertThat(result.answer().picks()).singleElement()
                .satisfies(p -> assertThat(p.locationId()).isEqualTo(top.locationId()));
    }
}
