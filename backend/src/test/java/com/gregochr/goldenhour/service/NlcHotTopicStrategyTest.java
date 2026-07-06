package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.model.NlcWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NlcHotTopicStrategy}.
 *
 * <p>The strategy no longer fires on the calendar alone — it reads the {@link NlcClarityService}
 * cache and only surfaces the earliest night with a clear dark-sky viewing chance, suppressing
 * the topic entirely when no such night exists.
 */
@ExtendWith(MockitoExtension.class)
class NlcHotTopicStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = TODAY.plusDays(3);
    private static final NlcWindow EVENING = new NlcWindow("22:46", "23:52", "NW");
    private static final NlcWindow MORNING = new NlcWindow("02:10", "03:18", "NE");

    @Mock
    private NlcClarityService clarityService;

    private NlcHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new NlcHotTopicStrategy(clarityService);
    }

    @Test
    @DisplayName("no cached clarity: suppressed")
    void detect_nullClarity_suppressed() {
        when(clarityService.getCached()).thenReturn(null);

        assertThat(strategy.detect(TODAY, TO)).isEmpty();
    }

    @Test
    @DisplayName("no clear nights: suppressed")
    void detect_noClearNights_suppressed() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of()));

        assertThat(strategy.detect(TODAY, TO)).isEmpty();
    }

    @Test
    @DisplayName("clear night tonight: fires with count, regions and priority 8")
    void detect_clearTonight_fires() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TODAY, 3, List.of("Northumberland"), EVENING, MORNING))));

        List<HotTopic> topics = strategy.detect(TODAY, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("NLC");
        assertThat(topic.priority()).isEqualTo(8);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.regions()).containsExactly("Northumberland");
        assertThat(topic.detail())
                .isEqualTo("Clear northern horizon tonight — 3 dark-sky locations");
    }

    @Test
    @DisplayName("clear night carries both twilight windows onto the topic")
    void detect_clearNight_attachesWindows() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TODAY, 3, List.of("Northumberland"), EVENING, MORNING))));

        HotTopic topic = strategy.detect(TODAY, TO).get(0);

        assertThat(topic.eveningWindow()).isEqualTo(EVENING);
        assertThat(topic.morningWindow()).isEqualTo(MORNING);
    }

    @Test
    @DisplayName("clear night with no twilight geometry (both windows null) is suppressed")
    void detect_noGeometry_suppressed() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TODAY, 3, List.of("Northumberland"), null, null))));

        assertThat(strategy.detect(TODAY, TO)).isEmpty();
    }

    @Test
    @DisplayName("a partial window (evening only) still qualifies")
    void detect_partialWindow_fires() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TODAY, 2, List.of("Northumberland"), EVENING, null))));

        List<HotTopic> topics = strategy.detect(TODAY, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).eveningWindow()).isEqualTo(EVENING);
        assertThat(topics.get(0).morningWindow()).isNull();
    }

    @Test
    @DisplayName("single clear location is singular in the detail line")
    void detect_singleLocation_singularWording() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TODAY.plusDays(1), 1, List.of("The Lake District"),
                        EVENING, MORNING))));

        List<HotTopic> topics = strategy.detect(TODAY, TO);

        assertThat(topics.get(0).detail())
                .isEqualTo("Clear northern horizon tomorrow night — 1 dark-sky location");
    }

    @Test
    @DisplayName("earliest clear night in the window is chosen; later ones ignored")
    void detect_multipleClearNights_picksEarliest() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TODAY.plusDays(2), 5, List.of("Northumberland"),
                        EVENING, MORNING),
                new NlcNightClarity.ClearNight(TODAY.plusDays(3), 2, List.of("The Lake District"),
                        EVENING, MORNING))));

        List<HotTopic> topics = strategy.detect(TODAY, TO);

        assertThat(topics).hasSize(1);
        // 2026-06-19 is a Friday.
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(2));
        assertThat(topics.get(0).detail())
                .isEqualTo("Clear northern horizon Friday night — 5 dark-sky locations");
    }

    @Test
    @DisplayName("clear night outside the requested window is filtered out")
    void detect_clearNightOutsideWindow_suppressed() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TO.plusDays(5), 4, List.of("Northumberland"),
                        EVENING, MORNING))));

        assertThat(strategy.detect(TODAY, TO)).isEmpty();
    }
}
