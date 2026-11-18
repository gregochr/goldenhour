package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.served;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.twoRegions;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.warning;
import static org.assertj.core.api.Assertions.assertThat;

/** The small records W6 adds or widens: they carry warnings through every copy and read null as "none". */
class AskWarningRecordsTest {

    private static final AskAnswer.WarningsCard CARD = new AskAnswer.WarningsCard(false,
            AskWarningFixtures.ATTRIBUTION, AskWarningFixtures.URL, List.of(new AskAnswer.WarningCard("W1", "2.0",
                    "AMBER", null, "Amber warning of snow", null, null, null, false)));

    @Test
    @DisplayName("a snapshot read with no warnings is the feature-off shape, and a warning is found by id")
    void snapshotWarnings() {
        AskSnapshot snapshot = twoRegions();
        AskSnapshot.Warnings withOne = served(warning("W1", "AMBER", List.of("SNOW"), 1L));

        assertThat(snapshot.warnings()).isEqualTo(AskSnapshot.Warnings.NONE);
        assertThat(snapshot.withWarnings(null).warnings()).isEqualTo(AskSnapshot.Warnings.NONE);
        assertThat(withOne.find("W1")).isPresent();
        assertThat(withOne.find("nope")).isEmpty();
        assertThat(new AskSnapshot.Warnings(true, false, null, null, null).items()).isEmpty();
        AskSnapshot.Warning bare = new AskSnapshot.Warning("W1", null, null, null, null, null, null, null, false,
                null);
        assertThat(bare.types()).isEmpty();
        assertThat(bare.whatToExpect()).isEmpty();
        assertThat(bare.locationIds()).isEmpty();
    }

    @Test
    @DisplayName("renaming a snapshot's areas keeps its warnings")
    void withNamesKeepsTheWarnings() {
        AskSnapshot snapshot = twoRegions().withWarnings(served(warning("W1", "AMBER", List.of("SNOW"), 1L)));

        AskSnapshot renamed = snapshot.withNames(List.of(), Map.of());

        assertThat(renamed.warnings()).isEqualTo(snapshot.warnings());
    }

    @Test
    @DisplayName("evidence carries the shown warning ids through a thread, and reads null as none")
    void evidenceKeepsTheShownIds() {
        AskEvidence evidence = new AskEvidence(Set.of(), Set.of(), 1, AskEvidence.ThreadFact.NONE, Set.of(),
                Set.of("W1"));

        assertThat(evidence.withThread(AskEvidence.ThreadFact.NONE).warningIds()).containsExactly("W1");
        assertThat(new AskEvidence(Set.of(), Set.of(), 1, AskEvidence.ThreadFact.NONE, Set.of()).warningIds())
                .isEmpty();
        assertThat(new AskEvidence(null, null, 0, null, null, null).warningIds()).isEmpty();
    }

    @Test
    @DisplayName("an answer keeps its warnings through a window copy, and a card reads null items as none")
    void answerKeepsItsWarnings() {
        AskAnswer answer = new AskAnswer(true, "Whitby.", List.of(), List.of(), null).withWarnings(CARD);

        assertThat(answer.withWindows(List.of()).warnings()).isEqualTo(CARD);
        assertThat(new AskAnswer.WarningsCard(true, null, null, null).items()).isEmpty();
    }

    @Test
    @DisplayName("a response can be given warnings after it is built, and null clears them")
    void responseWithWarnings() {
        AskResponse plain = new AskResponse(false, AskResponse.KIND_CANT, "No.", List.of(), List.of(), "x",
                List.of(), 5, 10, false, null, null);

        assertThat(plain.withWarnings(CARD).warnings()).isEqualTo(CARD);
        assertThat(plain.withWarnings(CARD).withWarnings(null).warnings()).isNull();
    }

    @Test
    @DisplayName("a response keeps its warnings through the thread-reset and night-version copies")
    void responseKeepsItsWarnings() {
        AskResponse response = new AskResponse(true, AskResponse.KIND_OWN, "Whitby.", List.of(), List.of(), null,
                List.of(), 5, 10, true, null, null, null, null, null, List.of(), CARD);

        assertThat(response.withThreadReset(AskResponse.THREAD_RESET_REASON).warnings()).isEqualTo(CARD);
        assertThat(response.withNightVersion("n1").warnings()).isEqualTo(CARD);
        assertThat(new AskResponse(true, AskResponse.KIND_OWN, "Whitby.", List.of(), List.of(), null, List.of(), 5, 10,
                true, null, null, null, null, null, List.of()).warnings()).isNull();
    }
}
