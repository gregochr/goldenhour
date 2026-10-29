package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.repository.RegionDriveTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link RegionDriveTimeWriter}.
 */
@ExtendWith(MockitoExtension.class)
class RegionDriveTimeWriterTest {

    private static final Long REGION_ID = 7L;

    @Mock
    private RegionDriveTimeRepository repository;

    private RegionDriveTimeWriter writer;

    private RegionDriveMatrixMemo memo;

    @BeforeEach
    void setUp() {
        memo = new RegionDriveMatrixMemo();
        writer = new RegionDriveTimeWriter(repository, memo);
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
