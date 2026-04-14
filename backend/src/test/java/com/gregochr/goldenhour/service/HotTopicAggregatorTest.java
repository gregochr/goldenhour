package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HotTopicAggregator}.
 */
class HotTopicAggregatorTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 25);
    private static final LocalDate TO = LocalDate.of(2026, 4, 27);

    @Test
    @DisplayName("empty detector list returns empty topics")
    void getHotTopics_noDetectors_returnsEmpty() {
        HotTopicAggregator aggregator = new HotTopicAggregator(List.of());

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("aggregates topics from multiple detectors")
    void getHotTopics_multipleDetectors_aggregatesAll() {
        HotTopic topic1 = new HotTopic("BLUEBELL", "Bluebell conditions", "detail1",
                FROM, 3, "BLUEBELL", List.of());
        HotTopic topic2 = new HotTopic("SPRING_TIDE", "Spring tide", "detail2",
                FROM.plusDays(1), 2, null, List.of());

        HotTopicDetector detector1 = (from, to) -> List.of(topic1);
        HotTopicDetector detector2 = (from, to) -> List.of(topic2);

        HotTopicAggregator aggregator = new HotTopicAggregator(List.of(detector1, detector2));

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).hasSize(2);
        assertThat(topics).contains(topic1, topic2);
    }

    @Test
    @DisplayName("topics are sorted by priority ascending then date")
    void getHotTopics_multiplePriorities_sortedCorrectly() {
        HotTopic lowPriority = new HotTopic("A", "A", "a", FROM, 3, null, List.of());
        HotTopic highPriority = new HotTopic("B", "B", "b", FROM, 1, null, List.of());
        HotTopic medPriorityLaterDate = new HotTopic("C", "C", "c", FROM.plusDays(1), 2, null, List.of());

        HotTopicDetector detector = (from, to) -> List.of(lowPriority, medPriorityLaterDate, highPriority);
        HotTopicAggregator aggregator = new HotTopicAggregator(List.of(detector));

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics.get(0).type()).isEqualTo("B"); // priority 1
        assertThat(topics.get(1).type()).isEqualTo("C"); // priority 2
        assertThat(topics.get(2).type()).isEqualTo("A"); // priority 3
    }

    @Test
    @DisplayName("detector returning empty list is handled gracefully")
    void getHotTopics_detectorReturnsEmpty_noError() {
        HotTopicDetector emptyDetector = (from, to) -> List.of();
        HotTopicAggregator aggregator = new HotTopicAggregator(List.of(emptyDetector));

        List<HotTopic> topics = aggregator.getHotTopics(FROM, TO);

        assertThat(topics).isEmpty();
    }
}
