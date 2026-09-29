package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.Verdict;
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
        assertThat(service.findFreshFallback(List.of())).isEmpty();
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

        List<BestBet> picks = service.findFreshFallback(List.of());

        assertThat(picks).hasSize(2);
        assertThat(picks).extracting(BestBet::region)
                .containsExactly("North York Moors", "North Yorkshire Coast");
    }

    @Test
    @DisplayName("Maps persisted fields to BestBet; dayName derived, eventTime null")
    void mapsFields() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600))));

        BestBet pick = service.findFreshFallback(List.of()).get(0);

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

        service.findFreshFallback(List.of());

        ArgumentCaptor<LocalDate> today = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Instant> minRecordedAt = ArgumentCaptor.forClass(Instant.class);
        verify(pickRepository).findFreshFallbackCandidates(today.capture(), minRecordedAt.capture());
        // 2026-06-13T09:00Z is 2026-06-13 in London.
        assertThat(today.getValue()).isEqualTo(LocalDate.of(2026, 6, 13));
        assertThat(minRecordedAt.getValue()).isEqualTo(NOW.minusSeconds(MAX_AGE_HOURS * 3600L));
    }

    // ── re-validated against the CURRENT briefing's verdict eligibility (round 10, P1-B) ──

    private static BriefingRegion region(String name, boolean verdictEligible) {
        BriefingSlot slot = new BriefingSlot("Loc", null, Verdict.GO,
                new BriefingSlot.WeatherConditions(20, java.math.BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, java.math.BigDecimal.ONE, 0, 0),
                BriefingSlot.TideInfo.NONE, List.of(), null);
        return new BriefingRegion(name, Verdict.GO, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null)
                .withSampleSufficient(verdictEligible);
    }

    private static List<BriefingDay> daysWithRegion(BriefingRegion region) {
        return daysWithRegions(region);
    }

    private static List<BriefingDay> daysWithRegions(BriefingRegion... regions) {
        return List.of(new BriefingDay(LocalDate.of(2026, 6, 14), List.of(
                new BriefingEventSummary(TargetType.SUNSET, List.of(regions), List.of()))));
    }

    @Test
    @DisplayName("a stored pick whose region is now verdict-INELIGIBLE in the current briefing is "
            + "dropped, even though it was eligible when persisted")
    void dropsPickWhoseRegionIsNowIneligible() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600))));

        List<BestBet> picks = service.findFreshFallback(
                daysWithRegion(region("Northumberland", false)));

        assertThat(picks).isEmpty();
    }

    @Test
    @DisplayName("a stored pick whose region is still verdict-eligible in the current briefing is "
            + "served")
    void servesPickWhoseRegionIsStillEligible() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600))));

        List<BestBet> picks = service.findFreshFallback(
                daysWithRegion(region("Northumberland", true)));

        assertThat(picks).hasSize(1);
        assertThat(picks.get(0).region()).isEqualTo("Northumberland");
    }

    @Test
    @DisplayName("a stored pick whose region no longer appears in the current briefing at all is "
            + "served — unknown is not the same as ineligible")
    void servesPickWhenRegionNoLongerAppearsInCurrentBriefing() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600))));

        // The current briefing carries a different region entirely — Northumberland has rolled
        // off, or this day's shape changed since the pick was recorded.
        List<BestBet> picks = service.findFreshFallback(
                daysWithRegion(region("North York Moors", false)));

        assertThat(picks).hasSize(1);
        assertThat(picks.get(0).region()).isEqualTo("Northumberland");
    }

    // ── removing a stored pick withdraws the whole set when rank 1 is the one removed
    //    (round 11 — a Codex review of round 10's fallback fix) ──

    @Test
    @DisplayName("prior run had two picks; rank 1 now ineligible, rank 2 eligible → the fallback "
            + "returns NO picks (withdrawn), never a lone promoted rank 2")
    void rankOneNowIneligible_rankTwoEligible_withdrawsWholeSet() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600)),
                row(5L, 2, "North Yorkshire Coast", NOW.minusSeconds(600))));

        List<BestBet> picks = service.findFreshFallback(daysWithRegions(
                region("Northumberland", false),
                region("North Yorkshire Coast", true)));

        assertThat(picks).isEmpty();
    }

    @Test
    @DisplayName("prior run had two picks; rank 1 eligible, rank 2 now ineligible → rank 1 alone, "
            + "unchanged, still rank 1")
    void rankOneEligible_rankTwoNowIneligible_keepsRankOneAlone() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600)),
                row(5L, 2, "North Yorkshire Coast", NOW.minusSeconds(600))));

        List<BestBet> picks = service.findFreshFallback(daysWithRegions(
                region("Northumberland", true),
                region("North Yorkshire Coast", false)));

        assertThat(picks).hasSize(1);
        assertThat(picks.get(0).rank()).isEqualTo(1);
        assertThat(picks.get(0).region()).isEqualTo("Northumberland");
        assertThat(picks.get(0).headline()).isEqualTo("headline-1");
    }

    @Test
    @DisplayName("prior run had two picks; both now ineligible → no picks")
    void bothNowIneligible_noPicks() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600)),
                row(5L, 2, "North Yorkshire Coast", NOW.minusSeconds(600))));

        List<BestBet> picks = service.findFreshFallback(daysWithRegions(
                region("Northumberland", false),
                region("North Yorkshire Coast", false)));

        assertThat(picks).isEmpty();
    }

    @Test
    @DisplayName("prior run had two picks; both still eligible → both returned unchanged "
            + "(existing behaviour)")
    void bothEligible_bothReturnedUnchanged() {
        when(pickRepository.findFreshFallbackCandidates(any(), any())).thenReturn(List.of(
                row(5L, 1, "Northumberland", NOW.minusSeconds(600)),
                row(5L, 2, "North Yorkshire Coast", NOW.minusSeconds(600))));

        List<BestBet> picks = service.findFreshFallback(daysWithRegions(
                region("Northumberland", true),
                region("North Yorkshire Coast", true)));

        assertThat(picks).hasSize(2);
        assertThat(picks).extracting(BestBet::rank).containsExactly(1, 2);
        assertThat(picks).extracting(BestBet::region)
                .containsExactly("Northumberland", "North Yorkshire Coast");
    }
}
