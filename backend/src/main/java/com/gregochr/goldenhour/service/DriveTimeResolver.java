package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central read path for per-user drive times.
 *
 * <p>All drive-time consumers (map filter, briefing display, location popups) should
 * call this service rather than reading from the entity directly.
 */
@Service
public class DriveTimeResolver {

    private final UserDriveTimeRepository repository;

    /**
     * Constructs a {@code DriveTimeResolver}.
     *
     * @param repository the user drive time repository
     */
    public DriveTimeResolver(UserDriveTimeRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns all drive times for a user, as a map of location name to minutes.
     *
     * @param userId the user's primary key
     * @return map of locationId to drive minutes; empty if no drive times calculated
     */
    public Map<Long, Integer> getAllMinutes(Long userId) {
        return repository.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        UserDriveTimeEntity::getLocationId,
                        UserDriveTimeEntity::getDriveMinutes));
    }

    /**
     * Returns whether the user has any drive times calculated.
     *
     * @param userId the user's primary key
     * @return true if the user has at least one drive time record
     */
    public boolean hasDriveTimes(Long userId) {
        return !repository.findByUserId(userId).isEmpty();
    }

    /**
     * Returns all drive time entities for a user.
     *
     * @param userId the user's primary key
     * @return list of drive time entities
     */
    public List<UserDriveTimeEntity> findByUserId(Long userId) {
        return repository.findByUserId(userId);
    }
}
