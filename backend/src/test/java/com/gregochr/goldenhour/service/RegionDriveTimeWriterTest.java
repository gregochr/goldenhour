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

import java.util.List;

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

    @BeforeEach
    void setUp() {
        writer = new RegionDriveTimeWriter(repository);
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
}
