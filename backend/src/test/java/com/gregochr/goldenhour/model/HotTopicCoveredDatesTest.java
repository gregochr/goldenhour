package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HotTopic#coveredDates()} — the backend twin of {@code windowFirstTopics.topicCoveredDates}.
 */
class HotTopicCoveredDatesTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 7);

    private static HotTopic topic(String type, LocalDate date) {
        return new HotTopic(type, "label", "detail", date, 1, null, List.of(), null, null);
    }

    @Test
    @DisplayName("a NIGHT topic covers its own date and the next morning's")
    void night_coversTwoDates() {
        assertThat(topic("AURORA", DATE).withEvent(HotTopic.EVENT_NIGHT, "18:30").coveredDates())
                .containsExactly(DATE, DATE.plusDays(1));
    }

    @Test
    @DisplayName("a SUNRISE or SUNSET topic covers its one date")
    void singleWindow_coversOneDate() {
        assertThat(topic("DUST", DATE).withEvent("SUNSET", "18:00").coveredDates()).containsExactly(DATE);
        assertThat(topic("INVERSION", DATE).withEvent("SUNRISE", "07:00").coveredDates())
                .containsExactly(DATE);
    }

    @Test
    @DisplayName("an unanchored dated topic covers its own date — a day-scoped tide, a storm surge")
    void unanchored_coversOwnDate() {
        assertThat(topic("SPRING_TIDE", DATE).coveredDates()).containsExactly(DATE);
        assertThat(topic("STORM_SURGE", DATE).coveredDates()).containsExactly(DATE);
    }

    @Test
    @DisplayName("an undated topic covers nothing, NIGHT anchor or not")
    void undated_coversNothing() {
        assertThat(topic("STORM_SURGE", null).coveredDates()).isEmpty();
        assertThat(topic("AURORA", null).withEvent(HotTopic.EVENT_NIGHT, null).coveredDates()).isEmpty();
    }
}
