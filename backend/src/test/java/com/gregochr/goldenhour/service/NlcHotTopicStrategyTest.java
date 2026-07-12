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
    /** Dark-sky total scanned — the denominator in the "clear at X of Y" detail line. */
    private static final int TOTAL_DARK_SKY = 20;

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
                new NlcNightClarity.ClearNight(
                        TODAY, 3, TOTAL_DARK_SKY, List.of("Northumberland"), EVENING, MORNING))));

        List<HotTopic> topics = strategy.detect(TODAY, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("NLC");
        assertThat(topic.priority()).isEqualTo(8);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.regions()).containsExactly("Northumberland");
        assertThat(topic.detail())
                .isEqualTo("Clear northern horizon tonight — clear at 3 of 20 dark-sky locations");
    }

    @Test
    @DisplayName("clear night carries both twilight windows onto the topic")
    void detect_clearNight_attachesWindows() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(
                        TODAY, 3, TOTAL_DARK_SKY, List.of("Northumberland"), EVENING, MORNING))));

        HotTopic topic = strategy.detect(TODAY, TO).get(0);

        assertThat(topic.eveningWindow()).isEqualTo(EVENING);
        assertThat(topic.morningWindow()).isEqualTo(MORNING);
    }

    @Test
    @DisplayName("clear night emits generic facts (NW dusk + NE dawn windows) and the look-note")
    void detect_clearNight_emitsGenericFacts() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(
                        TODAY, 3, TOTAL_DARK_SKY, List.of("Northumberland"), EVENING, MORNING))));

        HotTopic topic = strategy.detect(TODAY, TO).get(0);

        assertThat(topic.facts()).hasSize(2);
        assertThat(topic.facts().get(0).value()).isEqualTo("after dusk · 22:46–23:52");
        assertThat(topic.facts().get(0).dir()).isEqualTo("NW");
        assertThat(topic.facts().get(1).value()).isEqualTo("before dawn · 02:10–03:18");
        assertThat(topic.facts().get(1).dir()).isEqualTo("NE");
        assertThat(topic.note()).isEqualTo("look low on the horizon");
    }

    @Test
    @DisplayName("a partial (evening-only) night emits a single generic fact")
    void detect_partialWindow_emitsSingleFact() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(
                        TODAY, 2, TOTAL_DARK_SKY, List.of("Northumberland"), EVENING, null))));

        HotTopic topic = strategy.detect(TODAY, TO).get(0);

        assertThat(topic.facts()).hasSize(1);
        assertThat(topic.facts().get(0).dir()).isEqualTo("NW");
    }

    @Test
    @DisplayName("clear night with no twilight geometry (both windows null) is suppressed")
    void detect_noGeometry_suppressed() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(
                        TODAY, 3, TOTAL_DARK_SKY, List.of("Northumberland"), null, null))));

        assertThat(strategy.detect(TODAY, TO)).isEmpty();
    }

    @Test
    @DisplayName("a partial window (evening only) still qualifies")
    void detect_partialWindow_fires() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(
                        TODAY, 2, TOTAL_DARK_SKY, List.of("Northumberland"), EVENING, null))));

        List<HotTopic> topics = strategy.detect(TODAY, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).eveningWindow()).isEqualTo(EVENING);
        assertThat(topics.get(0).morningWindow()).isNull();
    }

    @Test
    @DisplayName("a single clear location reads 'clear at 1 of N' in the detail line")
    void detect_singleLocation_readsClearAtOneOfTotal() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TODAY.plusDays(1), 1, TOTAL_DARK_SKY,
                        List.of("The Lake District"), EVENING, MORNING))));

        List<HotTopic> topics = strategy.detect(TODAY, TO);

        assertThat(topics.get(0).detail())
                .isEqualTo("Clear northern horizon tomorrow night — clear at 1 of 20 dark-sky locations");
    }

    @Test
    @DisplayName("earliest clear night in the window is chosen; later ones ignored")
    void detect_multipleClearNights_picksEarliest() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TODAY.plusDays(2), 5, TOTAL_DARK_SKY,
                        List.of("Northumberland"), EVENING, MORNING),
                new NlcNightClarity.ClearNight(TODAY.plusDays(3), 2, TOTAL_DARK_SKY,
                        List.of("The Lake District"), EVENING, MORNING))));

        List<HotTopic> topics = strategy.detect(TODAY, TO);

        assertThat(topics).hasSize(1);
        // 2026-06-19 is a Friday.
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(2));
        assertThat(topics.get(0).detail())
                .isEqualTo("Clear northern horizon Friday night — clear at 5 of 20 dark-sky locations");
    }

    @Test
    @DisplayName("clear night outside the requested window is filtered out")
    void detect_clearNightOutsideWindow_suppressed() {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TO.plusDays(5), 4, TOTAL_DARK_SKY,
                        List.of("Northumberland"), EVENING, MORNING))));

        assertThat(strategy.detect(TODAY, TO)).isEmpty();
    }
}
