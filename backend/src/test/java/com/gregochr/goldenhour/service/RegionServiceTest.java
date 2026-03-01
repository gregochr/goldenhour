package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AddRegionRequest;
import com.gregochr.goldenhour.model.UpdateRegionRequest;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegionService}.
 */
@ExtendWith(MockitoExtension.class)
class RegionServiceTest {

    @Mock
    private RegionRepository regionRepository;

    private RegionService regionService;

    @BeforeEach
    void setUp() {
        regionService = new RegionService(regionRepository);
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

    private RegionEntity buildRegion(Long id, String name) {
        return RegionEntity.builder()
                .id(id)
                .name(name)
                .createdAt(LocalDateTime.of(2026, 3, 1, 12, 0))
                .build();
    }
}
