package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AddRegionRequest;
import com.gregochr.goldenhour.model.SetRegionBaseRequest;
import com.gregochr.goldenhour.model.UpdateRegionRequest;
import com.gregochr.goldenhour.repository.BriefingRegionSnapshotRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegionService}.
 */
@ExtendWith(MockitoExtension.class)
class RegionServiceTest {

    @Mock
    private RegionRepository regionRepository;

    @Mock
    private RegionDriveTimeWriter regionDriveTimeWriter;

    @Mock
    private BriefingRegionSnapshotRepository snapshotRepository;

    private RegionService regionService;

    @BeforeEach
    void setUp() {
        regionService = new RegionService(
                regionRepository, regionDriveTimeWriter, snapshotRepository);
    }

    // --- findAll ---

    @Test
    @DisplayName("findAll() delegates to findAllByOrderByNameAsc")
    void findAll_returnsRepositoryResults() {
        List<RegionEntity> expected = List.of(
                buildRegion(1L, "Northumberland"),
                buildRegion(2L, "Tyne and Wear"));
        when(regionRepository.findAllByOrderByNameAsc()).thenReturn(expected);

        List<RegionEntity> result = regionService.findAll();

        assertThat(result).isSameAs(expected);
    }

    // --- findAllEnabled ---

    @Test
    @DisplayName("findAllEnabled() delegates to findAllByEnabledTrueOrderByNameAsc")
    void findAllEnabled_returnsEnabledRegions() {
        List<RegionEntity> expected = List.of(buildRegion(1L, "Northumberland"));
        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(expected);

        List<RegionEntity> result = regionService.findAllEnabled();

        assertThat(result).isSameAs(expected);
    }

    // --- findById ---

    @Test
    @DisplayName("findById() returns the region when found")
    void findById_found_returnsRegion() {
        RegionEntity entity = buildRegion(1L, "Northumberland");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));

        RegionEntity result = regionService.findById(1L);

        assertThat(result).isSameAs(entity);
    }

    @Test
    @DisplayName("findById() throws NoSuchElementException when not found")
    void findById_notFound_throwsNoSuchElementException() {
        when(regionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> regionService.findById(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
    }

    // --- add ---

    @Test
    @DisplayName("add() saves and returns entity for valid input")
    void add_validInput_savesAndReturnsEntity() {
        when(regionRepository.existsByName("Northumberland")).thenReturn(false);
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.add(new AddRegionRequest("Northumberland"));

        ArgumentCaptor<RegionEntity> captor = ArgumentCaptor.forClass(RegionEntity.class);
        verify(regionRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Northumberland");
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("add() trims whitespace from name")
    void add_trimsWhitespace() {
        when(regionRepository.existsByName("Northumberland")).thenReturn(false);
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        regionService.add(new AddRegionRequest("  Northumberland  "));

        ArgumentCaptor<RegionEntity> captor = ArgumentCaptor.forClass(RegionEntity.class);
        verify(regionRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Northumberland");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is blank")
    void add_blankName_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> regionService.add(new AddRegionRequest("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when name is null")
    void add_nullName_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> regionService.add(new AddRegionRequest(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("add() throws IllegalArgumentException when duplicate name exists")
    void add_duplicateName_throwsIllegalArgumentException() {
        when(regionRepository.existsByName("Northumberland")).thenReturn(true);

        assertThatThrownBy(() -> regionService.add(new AddRegionRequest("Northumberland")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Northumberland");
    }

    // --- update ---

    @Test
    @DisplayName("update() changes region name")
    void update_changesName() {
        RegionEntity existing = buildRegion(1L, "Old Name");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(regionRepository.existsByName("New Name")).thenReturn(false);
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.update(1L, new UpdateRegionRequest("New Name"));

        assertThat(result.getName()).isEqualTo("New Name");
    }

    @Test
    @DisplayName("update() allows saving with same name (no-op rename)")
    void update_sameName_doesNotThrow() {
        RegionEntity existing = buildRegion(1L, "Northumberland");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.update(1L, new UpdateRegionRequest("Northumberland"));

        assertThat(result.getName()).isEqualTo("Northumberland");
    }

    @Test
    @DisplayName("update() throws IllegalArgumentException when name is blank")
    void update_blankName_throwsIllegalArgumentException() {
        RegionEntity existing = buildRegion(1L, "Old Name");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> regionService.update(1L, new UpdateRegionRequest("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("update() throws IllegalArgumentException when duplicate name exists")
    void update_duplicateName_throwsIllegalArgumentException() {
        RegionEntity existing = buildRegion(1L, "Old Name");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(regionRepository.existsByName("Northumberland")).thenReturn(true);

        assertThatThrownBy(() -> regionService.update(1L, new UpdateRegionRequest("Northumberland")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Northumberland");
    }

    @Test
    @DisplayName("update() throws NoSuchElementException when region not found")
    void update_notFound_throwsNoSuchElementException() {
        when(regionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> regionService.update(99L, new UpdateRegionRequest("Name")))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- setEnabled ---

    @Test
    @DisplayName("setEnabled(true) enables region")
    void setEnabled_enable_setsEnabledTrue() {
        RegionEntity entity = buildRegion(1L, "Northumberland");
        entity.setEnabled(false);
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.setEnabled(1L, true);

        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("setEnabled(false) disables region")
    void setEnabled_disable_setsEnabledFalse() {
        RegionEntity entity = buildRegion(1L, "Northumberland");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.setEnabled(1L, false);

        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("setEnabled() throws NoSuchElementException when region not found")
    void setEnabled_notFound_throwsNoSuchElementException() {
        when(regionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> regionService.setEnabled(99L, true))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ------------------------------------------------------------------------------------------
    // setBase — the origin the Plan tab plans from (heat-field plan P7)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("setBase() stores a complete base and discards the region's stale drive times")
    void setBase_complete_storesBaseAndClearsMatrix() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.setBase(1L,
                new SetRegionBaseRequest("Keswick", 54.601, -3.135));

        assertThat(result.getBaseName()).isEqualTo("Keswick");
        assertThat(result.getBaseLat()).isEqualTo(54.601);
        assertThat(result.getBaseLon()).isEqualTo(-3.135);
        // Every stored row measured a journey from the previous base. Unknown is safe, wrong is not.
        verify(regionDriveTimeWriter).clearForRegion(1L);
    }

    @Test
    @DisplayName("setBase() trims the town name")
    void setBase_trimsName() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.setBase(1L,
                new SetRegionBaseRequest("  Keswick  ", 54.601, -3.135));

        assertThat(result.getBaseName()).isEqualTo("Keswick");
    }

    @Test
    @DisplayName("setBase() with all three null clears the base, and the matrix with it")
    void setBase_allNull_clearsBaseAndMatrix() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        entity.setBaseName("Keswick");
        entity.setBaseLat(54.601);
        entity.setBaseLon(-3.135);
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.setBase(1L,
                new SetRegionBaseRequest(null, null, null));

        assertThat(result.getBaseName()).isNull();
        assertThat(result.getBaseLat()).isNull();
        assertThat(result.getBaseLon()).isNull();
        verify(regionDriveTimeWriter).clearForRegion(1L);
    }

    @Test
    @DisplayName("setBase() re-sent unchanged keeps the matrix — the Save button is safe to press twice")
    void setBase_unchanged_keepsMatrix() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        entity.setBaseName("Keswick");
        entity.setBaseLat(54.601);
        entity.setBaseLon(-3.135);
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        regionService.setBase(1L, new SetRegionBaseRequest("Keswick", 54.601, -3.135));

        verifyNoInteractions(regionDriveTimeWriter);
    }

    @Test
    @DisplayName("setBase() renaming the town only — same coordinates — keeps the matrix")
    void setBase_renameOnly_keepsMatrix() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        entity.setBaseName("Keswick");
        entity.setBaseLat(54.601);
        entity.setBaseLon(-3.135);
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.setBase(1L,
                new SetRegionBaseRequest("Keswick, Cumbria", 54.601, -3.135));

        // The label moved; the point did not. Re-routing on a spelling correction would throw away
        // a whole ORS sweep for a change no journey can see.
        assertThat(result.getBaseName()).isEqualTo("Keswick, Cumbria");
        verifyNoInteractions(regionDriveTimeWriter);
    }

    @Test
    @DisplayName("setBase() rejects a name with no coordinates — there is nothing to route from")
    void setBase_nameWithoutCoordinates_throws() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> regionService.setBase(1L,
                new SetRegionBaseRequest("Keswick", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(regionDriveTimeWriter);
    }

    @Test
    @DisplayName("setBase() rejects coordinates with no name — the chip would be unlabelled")
    void setBase_coordinatesWithoutName_throws() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> regionService.setBase(1L,
                new SetRegionBaseRequest("  ", 54.601, -3.135)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("setBase() rejects a missing longitude alone")
    void setBase_missingLongitude_throws() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> regionService.setBase(1L,
                new SetRegionBaseRequest("Keswick", 54.601, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("setBase() accepts the coordinate bounds and rejects one step outside each")
    void setBase_coordinateBounds() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(regionService.setBase(1L, new SetRegionBaseRequest("Pole", 90.0, 180.0))
                .getBaseLat()).isEqualTo(90.0);
        assertThat(regionService.setBase(1L, new SetRegionBaseRequest("Pole", -90.0, -180.0))
                .getBaseLat()).isEqualTo(-90.0);

        assertThatThrownBy(() -> regionService.setBase(1L,
                new SetRegionBaseRequest("Nowhere", 90.1, 0.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regionService.setBase(1L,
                new SetRegionBaseRequest("Nowhere", -90.1, 0.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regionService.setBase(1L,
                new SetRegionBaseRequest("Nowhere", 0.0, 180.1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regionService.setBase(1L,
                new SetRegionBaseRequest("Nowhere", 0.0, -180.1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("setBase() throws NoSuchElementException when the region does not exist")
    void setBase_notFound_throws() {
        when(regionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> regionService.setBase(99L,
                new SetRegionBaseRequest("Keswick", 54.601, -3.135)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("⚠️ update() renaming a region DISCARDS its movement snapshots, which are name-keyed")
    void update_rename_clearsNameKeyedSnapshots() {
        // `briefing_region_snapshot` (V144) is keyed on the region NAME, so a rename orphans every
        // row: the new name matches nothing and the movement chip compares against a store that no
        // longer knows the region. V137 §7 had to do this by hand in a migration for two other
        // name-keyed briefing stores — and this is an ordinary admin action, not a migration.
        RegionEntity entity = buildRegion(1L, "Lake District");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        regionService.update(1L, new UpdateRegionRequest("The Lakes"));

        // The PREVIOUS name, not the new one: the orphaned rows carry the old one.
        verify(snapshotRepository).deleteByRegionName("Lake District");
    }

    @Test
    @DisplayName("update() re-saving the SAME name keeps the snapshots — history is not free to discard")
    void update_sameName_keepsSnapshots() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        regionService.update(1L, new UpdateRegionRequest("  Lake District  "));

        verifyNoInteractions(snapshotRepository);
    }

    @Test
    @DisplayName("update() renames without touching the base or the matrix")
    void update_rename_leavesBaseIntact() {
        RegionEntity entity = buildRegion(1L, "Lake District");
        entity.setBaseName("Keswick");
        entity.setBaseLat(54.601);
        entity.setBaseLon(-3.135);
        when(regionRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(regionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegionEntity result = regionService.update(1L, new UpdateRegionRequest("The Lakes"));

        // The rename endpoint carries no base fields, so it can never clear one by omission — the
        // reason the base lives on its own endpoint at all.
        assertThat(result.getName()).isEqualTo("The Lakes");
        assertThat(result.getBaseName()).isEqualTo("Keswick");
        verifyNoInteractions(regionDriveTimeWriter);
    }

    private RegionEntity buildRegion(Long id, String name) {
        return RegionEntity.builder()
                .id(id)
                .name(name)
                .createdAt(LocalDateTime.of(2026, 3, 1, 12, 0))
                .build();
    }
}
