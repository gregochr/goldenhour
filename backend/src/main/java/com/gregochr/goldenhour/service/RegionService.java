package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AddRegionRequest;
import com.gregochr.goldenhour.model.SetRegionBaseRequest;
import com.gregochr.goldenhour.model.UpdateRegionRequest;
import com.gregochr.goldenhour.repository.BriefingRegionSnapshotRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

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

    /** Latitude bounds — a base outside them is a transposed pair or a typo, never a UK town. */
    private static final double MIN_LATITUDE = -90.0;
    private static final double MAX_LATITUDE = 90.0;

    /** Longitude bounds, same reasoning. */
    private static final double MIN_LONGITUDE = -180.0;
    private static final double MAX_LONGITUDE = 180.0;

    private final RegionRepository regionRepository;
    private final RegionDriveTimeWriter regionDriveTimeWriter;
    private final BriefingRegionSnapshotRepository snapshotRepository;

    /**
     * Constructs a {@code RegionService}.
     *
     * @param regionRepository      repository for {@link RegionEntity}
     * @param regionDriveTimeWriter clears a region's shared drive-time matrix when its base moves
     * @param snapshotRepository    the name-keyed movement store a rename orphans
     */
    public RegionService(RegionRepository regionRepository,
            RegionDriveTimeWriter regionDriveTimeWriter,
            BriefingRegionSnapshotRepository snapshotRepository) {
        this.regionRepository = regionRepository;
        this.regionDriveTimeWriter = regionDriveTimeWriter;
        this.snapshotRepository = snapshotRepository;
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
    @Transactional
    public RegionEntity update(Long id, UpdateRegionRequest request) {
        RegionEntity region = findById(id);
        String previousName = region.getName();

        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Region name must not be blank");
        }
        String trimmed = request.name().trim();
        if (!trimmed.equals(region.getName()) && regionRepository.existsByName(trimmed)) {
            throw new IllegalArgumentException("A region named '" + trimmed + "' already exists");
        }

        boolean renamed = !trimmed.equals(region.getName());
        region.setName(trimmed);
        RegionEntity saved = regionRepository.save(region);

        if (renamed) {
            // ⚠️ `briefing_region_snapshot` is keyed on the region NAME (V144), so a rename orphans
            // every row this region has and the Plan strip's movement chip compares the new name
            // against nothing. V137 §7 had to `DELETE FROM` two other name-keyed briefing stores
            // for exactly this reason, by hand, in a migration — and this is an ordinary admin
            // action, not a migration.
            //
            // Discarding rather than renaming the rows is deliberate and is the same trade
            // `region_drive_time` takes below: the degrade is one build cycle (~11-13h) with no
            // chip for the renamed region, where an UPDATE would have to reason about colliding
            // with a region that already holds the new name. Re-keying the table on `region_id` is
            // the real fix and needs `BriefingRegion` to carry an id, which it does not (plan's P7
            // row).
            snapshotRepository.deleteByRegionName(previousName);
        }

        LOG.info("Updated region id={} — name='{}'{}", saved.getId(), saved.getName(),
                renamed ? " (movement snapshots discarded)" : "");
        return saved;
    }

    /**
     * Sets or clears a region's base town — the origin the Plan tab plans from.
     *
     * <p>All three fields null clears the base, after which the region is still searchable and can
     * no longer be an origin. A partial base is rejected outright rather than half-stored: a name
     * with no coordinates cannot be routed from, and coordinates with no name put an unlabelled
     * origin on the chip.
     *
     * <p><b>Moving the base discards that region's stored drive times.</b> Every one of them
     * measures a journey from the old town, and the Plan tab renders an absent drive time as
     * unknown — no drive line, passes every reach tier — where a wrong one silently gates spots in
     * and out. The nightly {@code region_drive_time_refresh} job refills the matrix; an admin who
     * wants it sooner triggers that job. Deliberately not a re-route here: that is an external,
     * rate-limited call per location, which does not belong inside saving a form.
     *
     * <p>A no-op save — the same base sent twice — does <b>not</b> clear anything, which is what
     * makes the admin form's Save button safe to press twice.
     *
     * @param id      the region primary key
     * @param request the new base, or all-null to clear it
     * @return the updated {@link RegionEntity}
     * @throws NoSuchElementException   if no region with that ID exists
     * <p><b>Transactional</b>, because the save and the clear are one decision. Without it the
     * base commits first and a failure in the delete leaves a moved base with the OLD base's drive
     * times still stored — the single state the "unknown is safe, wrong is not" rule exists to
     * prevent, and the one nobody would notice, since every figure would still render.
     *
     * @throws IllegalArgumentException if the base is partial or out of coordinate bounds
     */
    @Transactional
    public RegionEntity setBase(Long id, SetRegionBaseRequest request) {
        RegionEntity region = findById(id);
        String name = request.baseName() == null || request.baseName().isBlank()
                ? null : request.baseName().trim();
        Double lat = request.baseLat();
        Double lon = request.baseLon();

        boolean anyPresent = name != null || lat != null || lon != null;
        boolean allPresent = name != null && lat != null && lon != null;
        if (anyPresent && !allPresent) {
            throw new IllegalArgumentException(
                    "A region base needs a town name, a latitude and a longitude — or none of them");
        }
        if (allPresent && (lat < MIN_LATITUDE || lat > MAX_LATITUDE
                || lon < MIN_LONGITUDE || lon > MAX_LONGITUDE)) {
            throw new IllegalArgumentException("Base coordinates are out of range");
        }

        boolean moved = !Objects.equals(region.getBaseLat(), lat)
                || !Objects.equals(region.getBaseLon(), lon);

        region.setBaseName(name);
        region.setBaseLat(lat);
        region.setBaseLon(lon);
        RegionEntity saved = regionRepository.save(region);

        if (moved) {
            regionDriveTimeWriter.clearForRegion(saved.getId());
        }

        LOG.info("Region '{}' base {} (drive-time matrix {})", saved.getName(),
                allPresent ? "set to '" + name + "'" : "cleared",
                moved ? "discarded" : "unchanged");
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
