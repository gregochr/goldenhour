package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.AskLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The metrics: the window in UK civil days, the rates and their denominators, the missing phrases, the
 * spend by run type, and that no question can come out of it.
 */
class AskMetricsServiceTest {

    private static final Instant NOON = Instant.parse("2026-10-06T12:00:00Z");

    private final AskLogRepository logs = mock(AskLogRepository.class);
    private final ApiCallLogRepository calls = mock(ApiCallLogRepository.class);
    private final AskMetricsService service = new AskMetricsService(logs, calls, new MutableClock(NOON));

    private static AskLogRepository.OutcomeCount count(String outcome, long total) {
        AskLogRepository.OutcomeCount row = mock(AskLogRepository.OutcomeCount.class);
        when(row.getOutcome()).thenReturn(outcome);
        when(row.getTotal()).thenReturn(total);
        return row;
    }

    private static AskLogRepository.MissingCount missing(String phrase, long total) {
        AskLogRepository.MissingCount row = mock(AskLogRepository.MissingCount.class);
        when(row.getMissing()).thenReturn(phrase);
        when(row.getTotal()).thenReturn(total);
        return row;
    }

    @Test
    @DisplayName("outcome counts, every outcome present (zero when none), and the three rates with their "
            + "documented denominators")
    void countsAndRates() {
        List<AskLogRepository.OutcomeCount> rows = List.of(count("READY_MATCH", 10),
                count("PREFILTER_CANT", 5), count("CACHE_HIT", 15), count("CLAUDE_OK", 30),
                count("CLAUDE_CANT", 5), count("CLAUDE_FAILED", 5));
        when(logs.countByOutcomeSince(any())).thenReturn(rows);

        AskMetricsService.Metrics metrics = service.metrics(7);

        assertThat(metrics.total()).isEqualTo(70);
        assertThat(metrics.outcomes()).containsOnlyKeys("READY_MATCH", "PREFILTER_CANT", "CACHE_HIT",
                "CLAUDE_OK", "CLAUDE_CANT", "CLAUDE_FAILED").containsEntry("CLAUDE_OK", 30L);
        // Cache hits / (hits + engine runs) = 15 / (15 + 40).
        assertThat(metrics.cacheHitRate()).isCloseTo(15.0 / 55.0, offset(1e-9));
        // Ready matches / every answered typed request = 10 / 70.
        assertThat(metrics.readyMatchRate()).isCloseTo(10.0 / 70.0, offset(1e-9));
        // Can't-answers / answered (everything but failures) = 10 / 65.
        assertThat(metrics.cantRate()).isCloseTo(10.0 / 65.0, offset(1e-9));
    }

    @Test
    @DisplayName("with nothing logged every outcome is zero and the rates are null, never a made-up zero")
    void emptyWindow() {
        when(logs.countByOutcomeSince(any())).thenReturn(List.of());

        AskMetricsService.Metrics metrics = service.metrics(7);

        assertThat(metrics.total()).isZero();
        assertThat(metrics.outcomes().values()).containsOnly(0L);
        assertThat(metrics.cacheHitRate()).isNull();
        assertThat(metrics.readyMatchRate()).isNull();
        assertThat(metrics.cantRate()).isNull();
        assertThat(metrics.topMissing()).isEmpty();
    }

    @Test
    @DisplayName("an outcome this build does not know (a row from a newer one) is not counted and is not an error")
    void unknownOutcomeIsIgnored() {
        List<AskLogRepository.OutcomeCount> rows = List.of(count("CLAUDE_OK", 4), count("SOMETHING_NEW", 9));
        when(logs.countByOutcomeSince(any())).thenReturn(rows);

        AskMetricsService.Metrics metrics = service.metrics(7);

        assertThat(metrics.total()).isEqualTo(4);
        assertThat(metrics.outcomes()).doesNotContainKey("SOMETHING_NEW");
    }

    @Test
    @DisplayName("the top ten missing phrases are asked for, most common first, with their counts")
    void topMissing() {
        when(logs.countByOutcomeSince(any())).thenReturn(List.of());
        List<AskLogRepository.MissingCount> rows = List.of(missing("car park information", 7),
                missing("opening times", 3));
        when(logs.topMissingSince(any(), any(Pageable.class))).thenReturn(rows);

        AskMetricsService.Metrics metrics = service.metrics(7);

        assertThat(metrics.topMissing()).containsExactly(new AskMetricsService.MissingPhrase(
                "car park information", 7), new AskMetricsService.MissingPhrase("opening times", 3));
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(logs).topMissingSince(any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("typed and Ready spend are the recorded cost of their own run types since the window's start, "
            + "in dollars")
    void spend() {
        when(logs.countByOutcomeSince(any())).thenReturn(List.of());
        when(calls.sumCostMicroDollarsByRunTypeStartedSince(eq(RunType.ASK), any())).thenReturn(250_000L);
        when(calls.sumCostMicroDollarsByRunTypeStartedSince(eq(RunType.ASK_READY), any())).thenReturn(1_500_000L);

        AskMetricsService.Metrics metrics = service.metrics(1);

        assertThat(metrics.typedSpendUsd()).isEqualTo(0.25);
        assertThat(metrics.readySpendUsd()).isEqualTo(1.5);
    }

    @ParameterizedTest(name = "days={0} is {1}")
    @CsvSource({"-5, 1", "0, 1", "1, 1", "2, 2", "7, 7", "89, 89", "90, 90", "91, 90", "1000, 90"})
    @DisplayName("days is clamped to 1..90: 0/1 and 90/91 at their boundaries")
    void clamp(int requested, int expected) {
        when(logs.countByOutcomeSince(any())).thenReturn(List.of());

        assertThat(AskMetricsService.clampDays(requested)).isEqualTo(expected);
        assertThat(service.metrics(requested).days()).isEqualTo(expected);
    }

    @Test
    @DisplayName("a window given as text clamps however large, and refuses what is not a whole number")
    void clampText() {
        assertThat(AskMetricsService.clampDays("7")).isEqualTo(7);
        assertThat(AskMetricsService.clampDays(" 7".strip())).isEqualTo(7);
        assertThat(AskMetricsService.clampDays("0")).isEqualTo(1);
        assertThat(AskMetricsService.clampDays("91")).isEqualTo(90);
        assertThat(AskMetricsService.clampDays("2147483648")).isEqualTo(90);
        assertThat(AskMetricsService.clampDays("-2147483649")).isEqualTo(1);
        assertThatThrownBy(() -> AskMetricsService.clampDays("seven"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("the window is the last N UK civil days including today: 1 is since UK midnight, 7 since "
            + "UK midnight six days earlier (BST, so 23:00 UTC the evening before)")
    void windowStart() {
        when(logs.countByOutcomeSince(any())).thenReturn(List.of());

        AskMetricsService.Metrics one = service.metrics(1);
        AskMetricsService.Metrics seven = service.metrics(7);

        assertThat(one.from()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(seven.from()).isEqualTo(LocalDate.of(2026, 9, 30));
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(logs, times(2)).countByOutcomeSince(since.capture());
        assertThat(since.getAllValues()).containsExactly(Instant.parse("2026-10-05T23:00:00Z"),
                Instant.parse("2026-09-29T23:00:00Z"));
        ArgumentCaptor<LocalDateTime> cost = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(calls, atLeastOnce())
                .sumCostMicroDollarsByRunTypeStartedSince(eq(RunType.ASK), cost.capture());
        assertThat(cost.getAllValues().getFirst()).isEqualTo(LocalDateTime.of(2026, 10, 5, 23, 0));
    }

    @Test
    @DisplayName("the metrics type has no field a question could travel in")
    void noQuestionField() {
        List<String> components = Arrays.stream(AskMetricsService.Metrics.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(components).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("question"));
        assertThat(AskMetricsService.MissingPhrase.class.getRecordComponents()).extracting(RecordComponent::getName)
                .containsExactly("phrase", "count");
    }
}
