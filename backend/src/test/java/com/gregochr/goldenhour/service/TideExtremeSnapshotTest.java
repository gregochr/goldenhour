package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideExtremeSnapshot}.
 */
@ExtendWith(MockitoExtension.class)
class TideExtremeSnapshotTest {

    private static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2026, 10, 19, 23, 0);
    private static final LocalDateTime WINDOW_TO = LocalDateTime.of(2026, 10, 31, 0, 0);
    private static final TideExtremeSnapshot.Window WINDOW =
            new TideExtremeSnapshot.Window(WINDOW_FROM, WINDOW_TO);

    @Mock
    private TideExtremeRepository repo;

    private TideExtremeEntity high1;
    private TideExtremeEntity low1;
    private TideExtremeEntity high2;

    @BeforeEach
    void setUp() {
        high1 = extreme(7L, LocalDateTime.of(2026, 10, 24, 6, 0), TideExtremeType.HIGH);
        low1 = extreme(7L, LocalDateTime.of(2026, 10, 24, 12, 20), TideExtremeType.LOW);
        high2 = extreme(7L, LocalDateTime.of(2026, 10, 24, 18, 40), TideExtremeType.HIGH);
    }

    private static TideExtremeEntity extreme(long locationId, LocalDateTime utc, TideExtremeType type) {
        return TideExtremeEntity.builder().locationId(locationId).eventTime(utc)
                .heightMetres(BigDecimal.valueOf(3)).type(type).build();
    }

    private TideExtremeSnapshot loaded() {
        // Deliberately ascending, as the repository query promises.
        when(repo.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(WINDOW_FROM), eq(WINDOW_TO)))
                .thenReturn(List.of(high1, low1, high2));
        return TideExtremeSnapshot.load(repo, List.of(7L), WINDOW);
    }

    @Test
    @DisplayName("between is inclusive at both ends: rows exactly on from and on to are returned")
    void between_isInclusiveAtBothEnds() {
        TideExtremeSnapshot snapshot = loaded();

        List<TideExtremeEntity> rows = snapshot.between(repo, 7L, high1.getEventTime(),
                high2.getEventTime());

        assertThat(rows).containsExactly(high1, low1, high2);
        verify(repo, never()).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                anyLong(), any(), any());
    }

    @Test
    @DisplayName("between narrows to the asked range and keeps ascending order")
    void between_narrowsAndKeepsAscendingOrder() {
        TideExtremeSnapshot snapshot = loaded();

        List<TideExtremeEntity> rows = snapshot.between(repo, 7L,
                high1.getEventTime().plusMinutes(1), high2.getEventTime());

        assertThat(rows).containsExactly(low1, high2);
        assertThat(rows).isSortedAccordingTo(
                java.util.Comparator.comparing(TideExtremeEntity::getEventTime));
    }

    @Test
    @DisplayName("a location that was not loaded falls back to the per-location query, never empty")
    void between_uncoveredLocation_fallsBackToRepository() {
        TideExtremeSnapshot snapshot = loaded();
        LocalDateTime from = LocalDateTime.of(2026, 10, 23, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 25, 0, 0);
        TideExtremeEntity other = extreme(9L, LocalDateTime.of(2026, 10, 24, 1, 0),
                TideExtremeType.LOW);
        when(repo.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(9L, from, to))
                .thenReturn(List.of(other));

        assertThat(snapshot.between(repo, 9L, from, to)).containsExactly(other);
    }

    @Test
    @DisplayName("a range outside the loaded window falls back to the per-location query")
    void between_rangeOutsideWindow_fallsBackToRepository() {
        TideExtremeSnapshot snapshot = loaded();
        LocalDateTime from = WINDOW_FROM.minusMinutes(1);
        LocalDateTime to = WINDOW_FROM.plusDays(1);
        when(repo.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(7L, from, to))
                .thenReturn(List.of(high1));

        assertThat(snapshot.between(repo, 7L, from, to)).containsExactly(high1);

        LocalDateTime lateFrom = WINDOW_TO.minusDays(1);
        LocalDateTime lateTo = WINDOW_TO.plusMinutes(1);
        when(repo.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(7L, lateFrom, lateTo))
                .thenReturn(List.of(low1));

        assertThat(snapshot.between(repo, 7L, lateFrom, lateTo)).containsExactly(low1);
    }

    @Test
    @DisplayName("a range sitting exactly on the window edges is served from memory")
    void between_rangeOnWindowEdges_isCovered() {
        TideExtremeSnapshot snapshot = loaded();

        assertThat(snapshot.between(repo, 7L, WINDOW_FROM, WINDOW_TO))
                .containsExactly(high1, low1, high2);
        verify(repo, never()).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                anyLong(), any(), any());
    }

    @Test
    @DisplayName("a covered location with no rows answers empty from memory, without a query")
    void between_coveredLocationWithNoRows_isEmptyWithoutQuery() {
        when(repo.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(WINDOW_FROM), eq(WINDOW_TO))).thenReturn(List.of());
        TideExtremeSnapshot snapshot = TideExtremeSnapshot.load(repo, List.of(7L), WINDOW);

        assertThat(snapshot.between(repo, 7L, WINDOW_FROM, WINDOW_TO)).isEmpty();
        verify(repo, never()).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                anyLong(), any(), any());
    }

    @Test
    @DisplayName("load makes one bulk query for every id and reports the row count")
    void load_makesOneBulkQuery() {
        TideExtremeSnapshot snapshot = loaded();

        assertThat(snapshot.rowCount()).isEqualTo(3);
        verify(repo).findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(WINDOW_FROM), eq(WINDOW_TO));
    }

    @Test
    @DisplayName("load with no ids makes no query at all")
    void load_noIds_makesNoQuery() {
        TideExtremeSnapshot snapshot = TideExtremeSnapshot.load(repo, List.<Long>of(), WINDOW);

        assertThat(snapshot.rowCount()).isZero();
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("windowFor across the autumn change: London midnight 3 days before, 4 days after")
    void windowFor_autumnClockChange_isPinnedExactly() {
        TideExtremeSnapshot.Window window = TideExtremeSnapshot.windowFor(
                List.of(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 27)));

        // 20 Oct is BST (UTC+1): London midnight is 23:00 UTC the evening before.
        assertThat(window.from()).isEqualTo(LocalDateTime.of(2026, 10, 19, 23, 0));
        // 31 Oct is GMT (clocks went back on 25 Oct): London midnight is UTC midnight.
        assertThat(window.to()).isEqualTo(LocalDateTime.of(2026, 10, 31, 0, 0));
    }

    @Test
    @DisplayName("windowFor across the spring change: GMT start, BST end")
    void windowFor_springClockChange_isPinnedExactly() {
        TideExtremeSnapshot.Window window = TideExtremeSnapshot.windowFor(
                List.of(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 30)));

        // 25 Mar is GMT; 3 Apr is BST, so London midnight is 23:00 UTC the evening before.
        assertThat(window.from()).isEqualTo(LocalDateTime.of(2026, 3, 25, 0, 0));
        assertThat(window.to()).isEqualTo(LocalDateTime.of(2026, 4, 2, 23, 0));
    }

    @Test
    @DisplayName("READ_WINDOW_DAYS is the one source for both ± 2 day fetches")
    void readWindowDays_isTwo() {
        assertThat(TideExtremeSnapshot.READ_WINDOW_DAYS).isEqualTo(2);
    }
}
