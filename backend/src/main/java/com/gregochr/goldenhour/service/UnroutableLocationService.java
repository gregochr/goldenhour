package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.DriveStatus;
import com.gregochr.goldenhour.model.UnroutableLocationDto;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.DrivePair;
import com.gregochr.goldenhour.repository.DriveStamp;
import com.gregochr.goldenhour.repository.RegionDriveTimeRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The admin read behind "Needs a drive point" (reach-scoped-card-plan R1b): which enabled locations OpenRouteService
 * has no route to, and from how many of the measured homes and region bases.
 *
 * <p>It is the same judgement {@code ReachService} makes for one home, applied to every origin at once: for each
 * (origin, location) pair {@link DriveStatus#of} is given whether a row exists, the origin's stamp, the location's
 * {@code drive_target_changed_at} and {@link DriveTimeRefreshJob#ROSTER_VISIBILITY_MARGIN}, so the count can never
 * disagree with what a reader's card does. A PENDING pair is counted in neither the numerator nor the denominator
 * (the origin cannot yet judge that location). Only measured origins take part: enabled Pro and Admin accounts with
 * a home and a stamp, and enabled regions with a base and a stamp.
 *
 * <p>A fixed number of queries (stamps, then the stored pairs for those origins) and one pass in memory, never a
 * per-user loop of queries. Read-only, admin data: it is served from its own no-store admin path.
 */
@Service
public class UnroutableLocationService {

    /** The roles whose homes are measured, as {@code DriveTimeRefreshJob} filters them. */
    private static final List<UserRole> MEASURED_ROLES = List.of(UserRole.PRO_USER, UserRole.ADMIN);

    private final LocationService locationService;
    private final AppUserRepository userRepository;
    private final RegionRepository regionRepository;
    private final UserDriveTimeRepository userDriveTimeRepository;
    private final RegionDriveTimeRepository regionDriveTimeRepository;

    /**
     * Constructs the service.
     *
     * @param locationService           supplies the enabled roster
     * @param userRepository            supplies the measured homes' stamps
     * @param regionRepository          supplies the measured bases' stamps
     * @param userDriveTimeRepository   supplies which homes have a row for which location
     * @param regionDriveTimeRepository supplies which bases have a row for which location
     */
    public UnroutableLocationService(LocationService locationService, AppUserRepository userRepository,
            RegionRepository regionRepository, UserDriveTimeRepository userDriveTimeRepository,
            RegionDriveTimeRepository regionDriveTimeRepository) {
        this.locationService = locationService;
        this.userRepository = userRepository;
        this.regionRepository = regionRepository;
        this.userDriveTimeRepository = userDriveTimeRepository;
        this.regionDriveTimeRepository = regionDriveTimeRepository;
    }

    /**
     * Lists the enabled locations at least one measured origin reads as UNROUTABLE, in name order.
     *
     * @return one entry per such location; empty when none
     */
    @Transactional(readOnly = true)
    public List<UnroutableLocationDto> findUnroutable() {
        List<LocationEntity> locations = locationService.findAllEnabled();
        Map<Long, int[]> homes = tally(locations, userRepository.findMeasuredHomeStamps(MEASURED_ROLES),
                userDriveTimeRepository::findPairsByUserIds);
        Map<Long, int[]> bases = tally(locations, regionRepository.findMeasuredBaseStamps(),
                regionDriveTimeRepository::findPairsByRegionIds);
        return locations.stream()
                .filter(location -> location.getId() != null)
                .map(location -> toDto(location, homes.get(location.getId()), bases.get(location.getId())))
                .filter(dto -> dto.unroutableHomes() + dto.unroutableBases() > 0)
                .toList();
    }

    private static UnroutableLocationDto toDto(LocationEntity location, int[] home, int[] base) {
        int[] h = home == null ? new int[2] : home;
        int[] b = base == null ? new int[2] : base;
        return new UnroutableLocationDto(location.getId(), location.getName(), h[0], h[1], b[0], b[1]);
    }

    /**
     * Counts, per location id, {@code [unroutable, judged]} over one kind of origin. An origin whose status for a
     * location is PENDING is in neither count.
     */
    private static Map<Long, int[]> tally(List<LocationEntity> locations, List<DriveStamp> stamps,
            Function<List<Long>, List<DrivePair>> pairReader) {
        Map<Long, int[]> counts = new HashMap<>();
        if (stamps.isEmpty()) {
            return counts;
        }
        Map<Long, Set<Long>> rowsByOrigin = new HashMap<>();
        for (DrivePair pair : pairReader.apply(stamps.stream().map(DriveStamp::originId).toList())) {
            rowsByOrigin.computeIfAbsent(pair.originId(), id -> new HashSet<>()).add(pair.locationId());
        }
        for (DriveStamp stamp : stamps) {
            Set<Long> rows = rowsByOrigin.getOrDefault(stamp.originId(), Set.of());
            for (LocationEntity location : locations) {
                DriveStatus status = DriveStatus.of(rows.contains(location.getId()), stamp.measuredAt(),
                        location.getDriveTargetChangedAt(), DriveTimeRefreshJob.ROSTER_VISIBILITY_MARGIN);
                if (status == DriveStatus.PENDING) {
                    continue;
                }
                int[] count = counts.computeIfAbsent(location.getId(), id -> new int[2]);
                count[1]++;
                if (status == DriveStatus.UNROUTABLE) {
                    count[0]++;
                }
            }
        }
        return counts;
    }
}
