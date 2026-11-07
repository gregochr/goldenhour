package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.model.NlcWindow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Date-range and morning-only branches of {@link NlcHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class NlcHotTopicStrategyWindowTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = LocalDate.of(2026, 6, 19);
    private static final NlcWindow EVENING = new NlcWindow("22:46", "23:52", "NW");
    private static final NlcWindow MORNING = new NlcWindow("02:10", "03:18", "NE");

    @Mock
    private NlcClarityService clarityService;

    private NlcHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new NlcHotTopicStrategy(clarityService);
    }

    private static NlcNightClarity.ClearNight night(LocalDate date, NlcWindow evening, NlcWindow morning) {
        return new NlcNightClarity.ClearNight(date, 3, 20, List.of("Northumberland"), evening, morning);
    }

    @Test
    @DisplayName("clear nights before the window start or after the window end are ignored")
    void detect_nightsOutsideRange_suppressed() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                night(FROM.minusDays(1), EVENING, MORNING),
                night(TO.plusDays(1), EVENING, MORNING))));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("the window's boundary nights are inclusive; the first in-range night is chosen")
    void detect_boundaryNights_inclusive() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                night(FROM.minusDays(1), EVENING, MORNING),
                night(TO, EVENING, MORNING))));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TO);
        assertThat(topics.get(0).detail()).startsWith("Clear northern horizon Friday night");
    }

    @Test
    @DisplayName("a morning-only night emits a single before-dawn fact and no evening window")
    void detect_morningOnly_singleFact() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                night(FROM.plusDays(1), null, MORNING))));

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(topic.detail()).startsWith("Clear northern horizon tomorrow night");
        assertThat(topic.eveningWindow()).isNull();
        assertThat(topic.morningWindow()).isEqualTo(MORNING);
        assertThat(topic.facts()).hasSize(1);
        assertThat(topic.facts().get(0).value()).isEqualTo("before dawn · 02:10–03:18");
        assertThat(topic.facts().get(0).dir()).isEqualTo("NE");
    }
}
