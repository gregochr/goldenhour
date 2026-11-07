package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.repository.RegionDriveTimeRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegionDriveTimeWriter}.
 */
@ExtendWith(MockitoExtension.class)
class RegionDriveTimeWriterTest {

    private static final Long REGION_ID = 7L;

    @Mock
    private RegionDriveTimeRepository repository;

    @Mock
    private RegionRepository regionRepository;

    private RegionDriveTimeWriter writer;

    private RegionDriveMatrixMemo memo;

    @BeforeEach
    void setUp() {
        memo = new RegionDriveMatrixMemo();
        writer = new RegionDriveTimeWriter(repository, regionRepository, memo);
    }

    private static final double BASE_LAT = 54.601;
    private static final double BASE_LON = -3.135;
    private static final Instant MEASURED_AT = Instant.parse("2026-11-05T02:00:00Z");

    @Test
    @DisplayName("a complete answer stamps the region (conditional on the base) and then replaces its rows")
    void storeIfBaseUnchanged_complete_stampsThenReplaces() {
        RegionDriveTimeEntity row = new RegionDriveTimeEntity(REGION_ID, 11L, 1500);
        when(regionRepository.stampDrivesMeasuredAtIfBaseIs(REGION_ID, BASE_LAT, BASE_LON, MEASURED_AT))
                .thenReturn(1);

        boolean stored = writer.storeIfBaseUnchanged(
                REGION_ID, BASE_LAT, BASE_LON, List.of(row), MEASURED_AT, true);

        assertThat(stored).isTrue();
        InOrder order = inOrder(regionRepository, repository);
        order.verify(regionRepository).stampDrivesMeasuredAtIfBaseIs(REGION_ID, BASE_LAT, BASE_LON, MEASURED_AT);
        order.verify(repository).deleteAllByRegionId(REGION_ID);
        order.verify(repository).saveAll(List.of(row));
        // A location that moved after the roster was read was routed to its old point: its row goes.
        order.verify(repository).deleteMovedSince(REGION_ID, LocalDateTime.of(2026, 11, 5, 2, 0));
    }

    @Test
    @DisplayName("an incomplete answer also drops rows for locations moved after the roster read")
    void storeIfBaseUnchanged_incomplete_dropsMovedLocations() {
        when(regionRepository.lockIfBaseIs(REGION_ID, BASE_LAT, BASE_LON)).thenReturn(1);

        writer.storeIfBaseUnchanged(REGION_ID, BASE_LAT, BASE_LON, List.of(), MEASURED_AT, false);

        verify(repository).deleteMovedSince(REGION_ID, LocalDateTime.of(2026, 11, 5, 2, 0));
    }

    @Test
    @DisplayName("a base that moved mid-flight writes neither the stamp nor the rows")
    void storeIfBaseUnchanged_baseMoved_writesNothing() {
        RegionDriveTimeEntity row = new RegionDriveTimeEntity(REGION_ID, 11L, 1500);
        when(regionRepository.stampDrivesMeasuredAtIfBaseIs(REGION_ID, BASE_LAT, BASE_LON, MEASURED_AT))
                .thenReturn(0);

        boolean stored = writer.storeIfBaseUnchanged(
                REGION_ID, BASE_LAT, BASE_LON, List.of(row), MEASURED_AT, true);

        assertThat(stored).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a short answer stores its rows but never stamps, so the missed destinations stay PENDING")
    void storeIfBaseUnchanged_incomplete_storesWithoutStamping() {
        RegionDriveTimeEntity row = new RegionDriveTimeEntity(REGION_ID, 11L, 1500);
        when(regionRepository.lockIfBaseIs(REGION_ID, BASE_LAT, BASE_LON)).thenReturn(1);

        boolean stored = writer.storeIfBaseUnchanged(
                REGION_ID, BASE_LAT, BASE_LON, List.of(row), MEASURED_AT, false);

        assertThat(stored).isTrue();
        verify(repository).deleteAllByRegionId(REGION_ID);
        verify(repository).saveAll(List.of(row));
        verify(regionRepository, never()).stampDrivesMeasuredAtIfBaseIs(REGION_ID, BASE_LAT, BASE_LON, MEASURED_AT);
    }

    @Test
    @DisplayName("a short answer for a base that moved mid-flight writes nothing")
    void storeIfBaseUnchanged_incompleteAndBaseMoved_writesNothing() {
        when(regionRepository.lockIfBaseIs(REGION_ID, BASE_LAT, BASE_LON)).thenReturn(0);

        boolean stored = writer.storeIfBaseUnchanged(
                REGION_ID, BASE_LAT, BASE_LON, List.of(), MEASURED_AT, false);

        assertThat(stored).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("clearForLocation discards one location's rows from every base and clears the memo")
    void clearForLocation_deletesThatLocationAndClearsMemo() {
        AtomicInteger loads = new AtomicInteger();
        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));

        writer.clearForLocation(11L);

        verify(repository).deleteAllByLocationId(11L);
        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("replaceForRegion deletes that region's rows before inserting the new ones")
    void replaceForRegion_deletesThenSaves() {
        RegionDriveTimeEntity row = new RegionDriveTimeEntity(REGION_ID, 11L, 1500);

        writer.replaceForRegion(REGION_ID, List.of(row));

        InOrder order = inOrder(repository);
        order.verify(repository).deleteAllByRegionId(REGION_ID);
        order.verify(repository).saveAll(List.of(row));
    }

    @Test
    @DisplayName("replaceForRegion with an empty list clears the region and saves nothing")
    void replaceForRegion_emptyList_clearsWithoutSaving() {
        writer.replaceForRegion(REGION_ID, List.of());

        verify(repository).deleteAllByRegionId(REGION_ID);
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("clearForRegion discards that region's rows and nothing else")
    void clearForRegion_deletesOnlyThatRegion() {
        writer.clearForRegion(REGION_ID);

        verify(repository).deleteAllByRegionId(REGION_ID);
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("clearForRegion also nulls the drive-measured stamp, so a moved base reads PENDING")
    void clearForRegion_nullsTheStamp() {
        writer.clearForRegion(REGION_ID);

        verify(regionRepository).clearDrivesMeasuredAt(REGION_ID);
    }

    @Test
    @DisplayName("clearForRegion locks the region row before deleting its drive rows (same order as the store)")
    void clearForRegion_locksRegionBeforeDeletingRows() {
        writer.clearForRegion(REGION_ID);

        InOrder order = inOrder(regionRepository, repository);
        order.verify(regionRepository).clearDrivesMeasuredAt(REGION_ID);
        order.verify(repository).deleteAllByRegionId(REGION_ID);
    }

    @Test
    @DisplayName("replaceForRegion clears the memoised matrix (no transaction: at once)")
    void replaceForRegion_clearsMemo() {
        AtomicInteger loads = new AtomicInteger();
        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));

        writer.replaceForRegion(REGION_ID, List.of());

        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("clearForRegion clears the memoised matrix (no transaction: at once)")
    void clearForRegion_clearsMemo() {
        AtomicInteger loads = new AtomicInteger();
        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));

        writer.clearForRegion(REGION_ID);

        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("inside a transaction the memo is cleared after commit, not before, and not on rollback")
    void clearsAfterCommitOnly() {
        AtomicInteger loads = new AtomicInteger();
        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));
        TransactionSynchronizationManager.initSynchronization();
        try {
            writer.clearForRegion(REGION_ID);

            // Still inside the transaction: the memo must keep serving.
            memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));
            assertThat(loads).as("not cleared inside the transaction").hasValue(1);

            // Rollback: afterCommit never runs.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));
            assertThat(loads).as("a rolled-back write does not clear").hasValue(1);

            // Commit.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));
        assertThat(loads).as("cleared once the transaction commits").hasValue(2);
    }

    @Test
    @DisplayName("a load that began before an invalidation never stores its stale result")
    void staleLoadIsNotStored() {
        AtomicInteger loads = new AtomicInteger();
        memo.getOrLoad(() -> {
            writer.clearForRegion(REGION_ID); // a commit lands while this reader is loading
            return Map.of(7L, Map.of(11L, loads.incrementAndGet()));
        });

        memo.getOrLoad(() -> Map.of(7L, Map.of(11L, loads.incrementAndGet())));
        assertThat(loads).as("the in-flight result was not stored").hasValue(2);
    }
}
