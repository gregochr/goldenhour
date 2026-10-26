package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpFact;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ComingUpRoleShaper} (tide-light-pro-plan.md §3 B2): what a Lite reader loses, what
 * every reader keeps, and that a missing carrier fails closed.
 */
class ComingUpRoleShaperTest {

    private static final LocalDate BUILT = LocalDate.of(2026, 10, 26);
    private static final String PRO_LABEL = "Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m · HW 15:48";
    private static final String LITE_LABEL = "Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m";

    private final ComingUpRoleShaper shaper = new ComingUpRoleShaper();

    private static ComingUpFact fact(String text, ComingUpFact.Kind kind) {
        return new ComingUpFact(List.of(new ComingUpFact.Segment(text, "base")), kind);
    }

    private static ComingUpEntry base() {
        AlmanacEvent event = new AlmanacEvent(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 30),
                AlmanacKind.ALMANAC, "spring-tide", "Spring tide run", "detail", Map.of(), List.of());
        return ComingUpEntry.from(event, LocalDate.of(2026, 7, 28));
    }

    private static ComingUpEntry withFacts(List<ComingUpFact> facts) {
        return base().withFacts(facts);
    }

    private static ComingUpResponse feed(ComingUpEntry... entries) {
        return new ComingUpResponse(BUILT, null, null, List.of(entries));
    }

    @Test
    @DisplayName("a null feed stays null")
    void nullStaysNull() {
        assertThat(shaper.forLite(null)).isNull();
    }

    @Test
    @DisplayName("event-water rows are dropped, every other row stays in order, and the entry is marked")
    void dropsOnlyEventWaterRows() {
        ComingUpFact range = fact("range 4.9 m at Seaham", null);
        ComingUpFact sunrise = fact("sunrise · HW 07:40, 10m before · Wed 28", ComingUpFact.Kind.EVENT_WATER);
        ComingUpFact sunset = fact("sunset · HW 15:48, 52m before · Wed 28", ComingUpFact.Kind.EVENT_WATER);
        ComingUpFact scope = fact("Northumberland only", null);

        ComingUpEntry shaped = shaper.forLite(feed(withFacts(List.of(range, sunrise, sunset, scope))))
                .entries().getFirst();

        assertThat(shaped.facts()).containsExactly(range, scope);
        assertThat(shaped.lightRowsWithheld()).isTrue();
    }

    @Test
    @DisplayName("an entry with nothing to withhold is returned as it is and is not marked")
    void untouchedEntryIsNotMarked() {
        ComingUpEntry entry = withFacts(List.of(fact("range 4.9 m", null)));

        ComingUpEntry shaped = shaper.forLite(feed(entry)).entries().getFirst();

        assertThat(shaped).isSameAs(entry);
        assertThat(shaped.lightRowsWithheld()).isFalse();
    }

    @Test
    @DisplayName("a coincidence line serves liteFactsLabel as its factsLabel, and the entry is marked")
    void swapsTheCoincidenceLabel() {
        ComingUpCoincidenceLine tide = new ComingUpCoincidenceLine("coastal", "Spring tide run", PRO_LABEL,
                "spring-tide", LITE_LABEL);
        ComingUpCoincidenceLine moon = new ComingUpCoincidenceLine("sun-moon", "Supermoon",
                "Mon 26 Oct · moonrise 17:22", "supermoon", "Mon 26 Oct · moonrise 17:22");

        ComingUpEntry shaped = shaper.forLite(feed(base().withCoincidence(List.of(tide, moon))))
                .entries().getFirst();

        assertThat(shaped.coincidence()).extracting(ComingUpCoincidenceLine::factsLabel)
                .containsExactly(LITE_LABEL, "Mon 26 Oct · moonrise 17:22");
        assertThat(shaped.lightRowsWithheld()).isTrue();
    }

    @Test
    @DisplayName("fail closed: a line with no liteFactsLabel is served as its span alone, never the Pro label")
    void missingLiteLabelFallsBackToTheSpan() {
        ComingUpCoincidenceLine reloaded = new ComingUpCoincidenceLine("coastal", "Spring tide run", PRO_LABEL,
                "spring-tide");

        ComingUpEntry shaped = shaper.forLite(feed(base().withCoincidence(List.of(reloaded))))
                .entries().getFirst();

        assertThat(shaped.coincidence().getFirst().factsLabel()).isEqualTo("Sun 25 – Fri 30 Oct");
        assertThat(shaped.lightRowsWithheld()).isTrue();
    }

    @Test
    @DisplayName("fail closed: a missing fact kind means the row is not a light row, so it is kept "
            + "(nothing is guessed from text)")
    void missingKindKeepsTheRow() {
        ComingUpFact reloaded = fact("sunrise · HW 07:40, 10m before · Wed 28", null);

        ComingUpEntry shaped = shaper.forLite(feed(withFacts(List.of(reloaded)))).entries().getFirst();

        assertThat(shaped.facts()).containsExactly(reloaded);
        assertThat(shaped.lightRowsWithheld()).isFalse();
    }

    @Test
    @DisplayName("the input feed is not mutated and the shaped feed keeps builtFor, bands and counts")
    void inputNotMutated() {
        ComingUpEntry entry = withFacts(List.of(fact("sunrise row", ComingUpFact.Kind.EVENT_WATER)));
        ComingUpResponse full = feed(entry);

        ComingUpResponse shaped = shaper.forLite(full);

        assertThat(full.entries().getFirst().facts()).hasSize(1);
        assertThat(full.entries().getFirst().lightRowsWithheld()).isFalse();
        assertThat(shaped.builtFor()).isEqualTo(BUILT);
    }

    @Test
    @DisplayName("withFacts, withCoincidence and withLightRowsWithheld change their own component and keep "
            + "every other")
    void withersPreserveEveryOtherComponent() throws Exception {
        ComingUpEntry source = base();
        List<ComingUpFact> facts = List.of(fact("x", null));
        List<ComingUpCoincidenceLine> lines = List.of(new ComingUpCoincidenceLine("a", "b", "c", "d"));

        ComingUpEntry[] rebuilt = {source.withFacts(facts), source.withCoincidence(lines),
                source.withLightRowsWithheld(true)};
        String[] changed = {"facts", "coincidence", "lightRowsWithheld"};
        for (int i = 0; i < rebuilt.length; i++) {
            for (RecordComponent c : ComingUpEntry.class.getRecordComponents()) {
                Object expected = c.getName().equals(changed[i])
                        ? c.getAccessor().invoke(rebuilt[i]) : c.getAccessor().invoke(source);
                assertThat(c.getAccessor().invoke(rebuilt[i])).as(changed[i] + " wither / " + c.getName())
                        .isEqualTo(expected);
            }
        }
        assertThat(rebuilt[0].facts()).isEqualTo(facts);
        assertThat(rebuilt[1].coincidence()).isEqualTo(lines);
        assertThat(rebuilt[2].lightRowsWithheld()).isTrue();
    }

    @Test
    @DisplayName("Every ComingUpEntry component is classified kept or rebuilt")
    void everyEntryComponentIsClassified() {
        Set<String> rebuilt = Set.of("facts", "coincidence", "lightRowsWithheld");
        Set<String> kept = Set.of("startDate", "endDate", "kind", "type", "title", "detail", "meta",
                "regions", "enteredWindow", "id", "family", "kindTag", "superlative", "metric", "prose",
                "aside", "threshold", "scoreNote", "action", "bits", "interim", "tide", "joinNote",
                "dayMarks", "dayRanges", "rangeAverageMetres");

        assertThat(names(ComingUpEntry.class))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(union(rebuilt, kept));
    }

    @Test
    @DisplayName("Every ComingUpCoincidenceLine component is classified kept, swapped or carrier")
    void everyCoincidenceComponentIsClassified() {
        Set<String> swapped = Set.of("factsLabel");
        Set<String> carrier = Set.of("liteFactsLabel");
        Set<String> kept = Set.of("family", "name", "type");

        assertThat(names(ComingUpCoincidenceLine.class))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(union(swapped, carrier, kept));
    }

    @Test
    @DisplayName("Every ComingUpFact component is classified kept or carrier")
    void everyFactComponentIsClassified() {
        assertThat(names(ComingUpFact.class)).containsExactlyInAnyOrder("segments", "kind");
    }

    private static List<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        return Arrays.stream(sets).flatMap(Set::stream).collect(Collectors.toSet());
    }
}
