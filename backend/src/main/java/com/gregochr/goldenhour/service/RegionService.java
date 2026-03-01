package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AddRegionRequest;
import com.gregochr.goldenhour.model.UpdateRegionRequest;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Manages the persisted set of geographic regions.
 *
 * <p>Regions are used to group forecast locations by geographic area. They are
 * managed exclusively via the REST API. Disabled regions are hidden from
 * location add/edit dropdowns but do not affect existing location associations.
 */
@Service
public class RegionService {

    private static final Logger LOG = LoggerFactory.getLogger(RegionService.class);

    private final RegionRepository regionRepository;

    /**
     * Constructs a {@code RegionService}.
     *
     * @param regionRepository repository for {@link RegionEntity}
     */
    public RegionService(RegionRepository regionRepository) {
        this.regionRepository = regionRepository;
    }

    /**
     * Returns all persisted regions ordered alphabetically by name.
     *
     * @return list of region entities
     */
    public List<RegionEntity> findAll() {
        return regionRepository.findAllByOrderByNameAsc();
    }

    /**
     * Returns all enabled regions ordered alphabetically by name.
     *
     * @return list of enabled region entities
     */
    public List<RegionEntity> findAllEnabled() {
        return regionRepository.findAllByEnabledTrueOrderByNameAsc();
    }

    /**
     * Returns a region by its database ID.
     *
     * @param id the region primary key
     * @return the matching {@link RegionEntity}
     * @throws NoSuchElementException if no region with that ID exists
     */
    public RegionEntity findById(Long id) {
        return regionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No region with id " + id));
    }

    /**
     * Adds a new region and persists it to the database.
     *
     * @param request the region details
     * @return the saved {@link RegionEntity}
     * @throws IllegalArgumentException if name is blank or a region with the same name exists
     */
    public RegionEntity add(AddRegionRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Region name must not be blank");
        }
        String trimmed = request.name().trim();
        if (regionRepository.existsByName(trimmed)) {
            throw new IllegalArgumentException("A region named '" + trimmed + "' already exists");
        }

        RegionEntity entity = RegionEntity.builder()
                .name(trimmed)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        RegionEntity saved = regionRepository.save(entity);

        LOG.info("Added region '{}'", saved.getName());
        return saved;
    }

    /**
     * Updates the name of an existing region.
     *
     * @param id      the region primary key
     * @param request the updated region name
     * @return the updated {@link RegionEntity}
     * @throws NoSuchElementException   if no region with that ID exists
     * @throws IllegalArgumentException if name is blank or a region with the new name exists
     */
    public RegionEntity update(Long id, UpdateRegionRequest request) {
        RegionEntity region = findById(id);

        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Region name must not be blank");
        }
        String trimmed = request.name().trim();
        if (!trimmed.equals(region.getName()) && regionRepository.existsByName(trimmed)) {
            throw new IllegalArgumentException("A region named '" + trimmed + "' already exists");
        }

        region.setName(trimmed);
        RegionEntity saved = regionRepository.save(region);

        LOG.info("Updated region id={} — name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * Toggles the enabled state of a region.
     *
     * @param id      the region primary key
     * @param enabled the new enabled state
     * @return the updated {@link RegionEntity}
     * @throws NoSuchElementException if no region with that ID exists
     */
    public RegionEntity setEnabled(Long id, boolean enabled) {
        RegionEntity region = findById(id);
        region.setEnabled(enabled);
        RegionEntity saved = regionRepository.save(region);

        LOG.info("Region '{}' {}", saved.getName(), enabled ? "enabled" : "disabled");
        return saved;
    }
}
