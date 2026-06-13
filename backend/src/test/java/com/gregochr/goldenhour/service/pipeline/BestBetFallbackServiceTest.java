package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.repository.PipelineRunPickRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BestBetFallbackService}: latest-run grouping, entity→{@link BestBet}
 * mapping, and that the freshness bounds (today, now-minus-ceiling) are passed to the query.
 * The query's actual filtering is proven in {@code PipelineRunPickRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class BestBetFallbackServiceTest {

    private static final int MAX_AGE_HOURS = 30;
    private static final Instant NOW = Instant.parse("2026-06-13T09:00:00Z");

    @Mock
    private PipelineRunPickRepository pickRepository;

    private BestBetFallbackService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new BestBetFallbackService(pickRepository, clock, MAX_AGE_HOURS);
    }

    private PipelineRunPickEntity row(long runId, int rank, String region, Instant recordedAt) {
        PipelineRunPickEntity e = new PipelineRunPickEntity();
        e.setPipelineRunId(runId);
        e.setPickRank(rank);
        e.setHeadline("headline-" + rank);
        e.setDetail("detail-" + rank);
        e.setEventId("2026-06-14_sunset");
        e.setEventDate(LocalDate.of(2026, 6, 14));
        e.setEventType("sunset");
        e.setRegion(region);
        e.setConfidence("HIGH");
        e.setRecordedAt(recordedAt);
        return e;
    }

    @Test
    @DisplayName("No candidates → empty fallback (caller shows honest empty state)")
    void noCandidatesReturnsEmpty() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of());
        assertThat(service.findFreshFallback()).isEmpty();
    }

    @Test
    @DisplayName("Returns only the most recent run's picks (latest-recorded group)")
    void returnsOnlyLatestRunPicks() {
        Instant newer = NOW.minusSeconds(1800);
        Instant older = NOW.minusSeconds(7200);
        // Repository returns newest-recorded first (run 2), then the older run 1.
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(2L, 1, "North York Moors", newer),
                row(2L, 2, "North Yorkshire Coast", newer),
                row(1L, 1, "Northumberland", older)));

        List<BestBet> picks = service.findFreshFallback();

        assertThat(picks).hasSize(2);
        assertThat(picks).extracting(BestBet::region)
                .containsExactly("North York Moors", "North Yorkshire Coast");
    }

    @Test
    @DisplayName("Maps persisted fields to BestBet; dayName derived, eventTime null")
    void mapsFields() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600))));

        BestBet pick = service.findFreshFallback().get(0);

        assertThat(pick.rank()).isEqualTo(1);
        assertThat(pick.headline()).isEqualTo("headline-1");
        assertThat(pick.region()).isEqualTo("Northumberland");
        assertThat(pick.event()).isEqualTo("2026-06-14_sunset");
        assertThat(pick.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(pick.eventType()).isEqualTo("sunset");
        // 2026-06-14 is the day after today (2026-06-13) → "Tomorrow".
        assertThat(pick.dayName()).isEqualTo("Tomorrow");
        // Event time of day is not persisted on the pick row.
        assertThat(pick.eventTime()).isNull();
        assertThat(pick.nearestDriveMinutes()).isNull();
    }

    @Test
    @DisplayName("Queries with today (London) and now-minus-ceiling as the freshness bounds")
    void passesFreshnessBounds() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of());

        service.findFreshFallback();

        ArgumentCaptor<LocalDate> today = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Instant> minRecordedAt = ArgumentCaptor.forClass(Instant.class);
        verify(pickRepository).findFreshFallbackCandidates(today.capture(), minRecordedAt.capture());
        // 2026-06-13T09:00Z is 2026-06-13 in London.
        assertThat(today.getValue()).isEqualTo(LocalDate.of(2026, 6, 13));
        assertThat(minRecordedAt.getValue()).isEqualTo(NOW.minusSeconds(MAX_AGE_HOURS * 3600L));
    }
}
