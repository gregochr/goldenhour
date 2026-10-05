package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskPromptBuilder}. */
class AskPromptBuilderTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private final AskPromptBuilder builder = new AskPromptBuilder();

    @Test
    @DisplayName("states today's UK date with the day name, and the rules")
    void statesTodayAndTheRules() {
        String prompt = builder.systemPrompt(MONDAY, Set.of(), Optional.empty(), true);

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
        String prompt = builder.systemPrompt(MONDAY, List.of("Teesdale", "Northumberland"), Optional.empty(),
                true);

        assertThat(prompt).contains("these regions only: Northumberland, Teesdale.")
                .doesNotContain("every region");
    }

    @Test
    @DisplayName("states the window the reader is looking at, with its id")
    void statesTheContextWindow() {
        AskSnapshot.Window window = new AskSnapshot.Window("2026-10-10_sunrise", LocalDate.of(2026, 10, 10),
                TargetType.SUNRISE, LocalDateTime.of(2026, 10, 10, 6, 0), null, null, null, List.of());

        String prompt = builder.systemPrompt(MONDAY, Set.of(), Optional.of(window), true);

        assertThat(prompt).contains("looking at Saturday 10 October sunrise (windowId 2026-10-10_sunrise)");
    }

    @Test
    @DisplayName("a user-less conversation is told never to mention home; a typed one is told about "
            + "drive times, and never both")
    void userLessAndTypedDiffer() {
        String shared = builder.systemPrompt(MONDAY, Set.of(), Optional.empty(), false);
        String typed = builder.systemPrompt(MONDAY, Set.of(), Optional.empty(), true);

        assertThat(shared).contains("never mention home, distance or drive times")
                .doesNotContain("maxDriveMinutes");
        assertThat(typed).contains("maxDriveMinutes").doesNotContain("never mention home");
    }
}
