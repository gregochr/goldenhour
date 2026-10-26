package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.AreaConflictException;
import com.gregochr.goldenhour.exception.SubRegionsLockedException;
import com.gregochr.goldenhour.model.SubRegionLocationRow;
import com.gregochr.goldenhour.model.SubRegionState;
import com.gregochr.goldenhour.model.UserPickNameRow;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserSubRegionRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationSubRegionRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Lite reader's sub-region picks and the change rule that governs them (sub-regions-plan §4.3).
 *
 * <p>The rule, on UK civil dates only ({@link ForecastHorizon#today}, {@link ForecastHorizon#civilDate}),
 * with the clock injected so every boundary is testable:
 * <ul>
 *   <li>a reader's <strong>first choice</strong> (no picks yet) is always allowed; it starts the grace period
 *       ({@code graceUntil = today + grace-days - 1}, if none has started) and stamps the change;</li>
 *   <li><strong>during grace</strong> any change is allowed and stamps;</li>
 *   <li><strong>after grace</strong> a change that only adds picks (under the limit) is allowed and stamps
 *       nothing; a change that removes a pick needs {@code today >= max(graceUntil + 1, changedOn +
 *       lock-days)} and stamps;</li>
 *   <li>an identical set is a no-op; a reader over a lowered limit may remove picks (only) without a lock;</li>
 *   <li>an admin removing a sub-region clears its pickers' stamps (done by {@code AreaService.delete}).</li>
 * </ul>
 *
 * <p><strong>Concurrency.</strong> The lock check and its stamp are one conditional {@code UPDATE} whose row
 * count is the answer ({@code AppUserRepository#stampSubRegionsChangedIfUnlocked}); it runs in the same
 * transaction as the picks delete and insert, so a lost race (0 rows) rolls the whole change back and answers
 * 409. New stamps are written with {@code LocalDateTime.now(clock)} (UTC wall clock); only civil dates are
 * compared. The change columns are written only by scoped repository updates, never by {@code save()}.
 *
 * <p>Pro and Admin readers do not pick: their {@link #getState} says {@code NOT_APPLICABLE} and
 * {@link #replacePicks} refuses them. Picks they held as Lite are kept, unused (§4.1).
 */
@Service
public class UserSubRegionService {

    private static final Logger LOG = LoggerFactory.getLogger(UserSubRegionService.class);

    /** How many example location names an option carries. */
    private static final int EXAMPLE_COUNT = 2;

    private final AppUserRepository userRepository;
    private final AppUserSubRegionRepository pickRepository;
    private final AreaRepository areaRepository;
    private final LocationSubRegionRepository membership;
    private final SubRegionPolicyService policy;
    private final SubRegionProperties properties;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param userRepository the users, and the scoped writers of the change columns
     * @param pickRepository the picks
     * @param areaRepository the sub-regions
     * @param membership     the sub-region members, for counts, examples and suggestions
     * @param policy         the admin-set pick limit
     * @param properties     enforcement, grace days and lock days
     * @param clock          supplies "now"; UK civil dates are derived from it
     */
    public UserSubRegionService(AppUserRepository userRepository, AppUserSubRegionRepository pickRepository,
            AreaRepository areaRepository, LocationSubRegionRepository membership, SubRegionPolicyService policy,
            SubRegionProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.pickRepository = pickRepository;
        this.areaRepository = areaRepository;
        this.membership = membership;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The caller's picks, what they may do next, and the options.
     *
     * @param username the caller
     * @return the state
     * @throws NoSuchElementException if there is no such user (HTTP 404)
     */
    @Transactional(readOnly = true)
    public SubRegionState getState(String username) {
        return buildState(requireUser(username), true);
    }

    /**
     * Replaces the caller's picks, under the change rule.
     *
     * @param username the caller
     * @param areaIds  the sub-region ids to hold
     * @return the new state
     * @throws AccessDeniedException       if the caller is not a Lite reader (HTTP 403)
     * @throws IllegalArgumentException    if the list is empty, over the limit, has a duplicate, or names an id
     *                                     that is not a sub-region (HTTP 400)
     * @throws SubRegionsLockedException   if the change is locked (HTTP 409)
     */
    @Transactional
    public SubRegionState replacePicks(String username, List<Long> areaIds) {
        AppUserEntity user = lockUser(userRepository.findIdByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username)));
        if (user.getRole() != UserRole.LITE_USER) {
            throw new AccessDeniedException("Only a Lite account picks sub-regions");
        }
        int limit = policy.getLiteLimit();
        List<Long> desired = validate(areaIds, limit, false);
        List<Long> current = pickRepository.findAreaIdsByUserId(user.getId());
        Set<Long> currentSet = new HashSet<>(current);
        if (currentSet.equals(new HashSet<>(desired))) {
            return buildState(user, true);
        }
        Set<Long> removed = new HashSet<>(currentSet);
        removed.removeAll(desired);
        List<Long> added = desired.stream().filter(id -> !currentSet.contains(id)).toList();
        LocalDate today = ForecastHorizon.today(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        authorise(user, current, removed, added, limit, today, now);
        write(user.getId(), removed, added, now);
        LOG.info("Sub-region picks for user id={} are now {}", user.getId(), desired);
        return buildState(reload(user.getId()), true);
    }

    /**
     * An admin's view of one reader's picks and standing (no options).
     *
     * @param userId the reader
     * @return the state, with the reader's own role deciding {@code NOT_APPLICABLE}
     * @throws NoSuchElementException if there is no such user (HTTP 404)
     */
    @Transactional(readOnly = true)
    public SubRegionState getStateForAdmin(Long userId) {
        return buildState(reload(userId), false);
    }

    /**
     * An admin sets a reader's picks directly. The change rule does not apply: the ids are validated as for a
     * reader (a sub-region each, no duplicates, at most the limit; an empty list clears them), the change
     * stamp is cleared so the reader's next change is not locked, and the grace period is left as it is.
     *
     * @param userId  the reader (any role)
     * @param areaIds the sub-region ids to hold
     * @return the new state
     * @throws NoSuchElementException   if there is no such user (HTTP 404)
     * @throws IllegalArgumentException if an id is not a sub-region, repeats, or there are more than the limit
     *                                  (HTTP 400)
     */
    @Transactional
    public SubRegionState adminReplacePicks(Long userId, List<Long> areaIds) {
        AppUserEntity user = lockUser(userId);
        List<Long> desired = validate(areaIds, policy.getLiteLimit(), true);
        Set<Long> currentSet = new HashSet<>(pickRepository.findAreaIdsByUserId(userId));
        Set<Long> removed = new HashSet<>(currentSet);
        removed.removeAll(desired);
        List<Long> added = desired.stream().filter(id -> !currentSet.contains(id)).toList();
        LocalDateTime now = LocalDateTime.now(clock);
        if (currentSet.isEmpty() && !desired.isEmpty()) {
            // A reader's first picks, whoever made them, start their grace period (a no-op if one has started).
            userRepository.startSubRegionGraceIfUnset(userId,
                    ForecastHorizon.today(clock).plusDays(properties.getGraceDays() - 1L));
        }
        write(userId, removed, added, now);
        userRepository.clearSubRegionsChangedAt(userId);
        LOG.info("Admin set sub-region picks for user id={} to {}", userId, desired);
        return buildState(reload(user.getId()), false);
    }

    /**
     * An admin lifts a reader's lock: the next change is allowed.
     *
     * @param userId the reader
     * @throws NoSuchElementException if there is no such user (HTTP 404)
     */
    @Transactional
    public void clearLock(Long userId) {
        if (userRepository.clearSubRegionsChangedAt(userId) == 0) {
            throw new NoSuchElementException("No user with id " + userId);
        }
        LOG.info("Admin cleared the sub-region lock for user id={}", userId);
    }

    /**
     * Every reader's picks as names, for the admin user list.
     *
     * @return user id to the picked sub-region names, by name; users with no picks are absent
     */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> pickNamesByUser() {
        Map<Long, List<String>> byUser = new LinkedHashMap<>();
        for (UserPickNameRow row : pickRepository.findAllPickNames()) {
            byUser.computeIfAbsent(row.userId(), id -> new ArrayList<>()).add(row.areaName());
        }
        return byUser;
    }

    /**
     * The enabled locations a reader's picks cover, for enforcement (sub-regions-plan §5.3, D8): the members
     * of the sub-regions the reader holds. A reader holding more than the limit (the admin lowered it) is
     * served the first <em>n</em> picks by pick time (§4.3). A reader with no user row or no picks gets an
     * empty list, which shapes everything as gated. The caller shapes fail-closed around this: any exception
     * from here is "no picks".
     *
     * @param username the caller
     * @return the covered enabled locations (id and name), in no promised order
     */
    @Transactional(readOnly = true)
    public List<SubRegionLocationRow> pickedLocationRows(String username) {
        Long userId = userRepository.findIdByUsername(username).orElse(null);
        if (userId == null) {
            return List.of();
        }
        Set<Long> picked = new HashSet<>(pickRepository.findAreaIdsByUserId(userId).stream()
                .limit(policy.getLiteLimit()).toList());
        if (picked.isEmpty()) {
            return List.of();
        }
        return membership.findEnabledMemberRows().stream()
                .filter(row -> picked.contains(row.areaId()))
                .toList();
    }

    // ------------------------------------------------------------------ the change rule

    /**
     * The first UK civil date a swap is allowed: {@code max(graceUntil + 1, changedOn + lockDays)}.
     *
     * @param graceUntil the last free day, or null if none started
     * @param changedAt  the last stamp (UTC wall clock), or null
     * @param lockDays   the lock length
     * @return the date, or null when neither input constrains it
     */
    static LocalDate nextChangeOn(LocalDate graceUntil, LocalDateTime changedAt, int lockDays) {
        LocalDate afterGrace = graceUntil == null ? null : graceUntil.plusDays(1);
        LocalDate afterLock = changedAt == null
                ? null : ForecastHorizon.civilDate(changedAt.toInstant(ZoneOffset.UTC)).plusDays(lockDays);
        if (afterGrace == null) {
            return afterLock;
        }
        if (afterLock == null) {
            return afterGrace;
        }
        return afterGrace.isAfter(afterLock) ? afterGrace : afterLock;
    }

    /** Whether a swap is still refused on {@code today}. */
    private static boolean isLocked(LocalDate nextChangeOn, LocalDate today) {
        return nextChangeOn != null && today.isBefore(nextChangeOn);
    }

    /** Applies the rule to a change that is not a no-op: stamps what it must, or throws. */
    private void authorise(AppUserEntity user, List<Long> current, Set<Long> removed, List<Long> added, int limit,
            LocalDate today, LocalDateTime now) {
        Long id = user.getId();
        LocalDate graceUntil = user.getSubRegionsGraceUntil();
        if (current.isEmpty()) {
            userRepository.startSubRegionGraceIfUnset(id, today.plusDays(properties.getGraceDays() - 1L));
            userRepository.stampSubRegionsChanged(id, now);
            return;
        }
        if (graceUntil != null && !today.isAfter(graceUntil)) {
            userRepository.stampSubRegionsChanged(id, now);
            return;
        }
        if (removed.isEmpty()) {
            return;
        }
        if (current.size() > limit && added.isEmpty()) {
            return;
        }
        LocalDate next = nextChangeOn(graceUntil, user.getSubRegionsChangedAt(), properties.getLockDays());
        if (isLocked(next, today)) {
            throw new SubRegionsLockedException(next);
        }
        // The first unlocked day is changedOn + lockDays, so a stamp is unlocked when it is before the start
        // of the UK day after (today - lockDays).
        LocalDateTime cutoff = ForecastHorizon.ukDayStartUtc(today.minusDays(properties.getLockDays()).plusDays(1));
        if (userRepository.stampSubRegionsChangedIfUnlocked(id, now, cutoff) == 0) {
            LocalDateTime fresh = reload(id).getSubRegionsChangedAt();
            LocalDate freshNext = nextChangeOn(graceUntil, fresh, properties.getLockDays());
            throw new SubRegionsLockedException(freshNext == null ? today.plusDays(properties.getLockDays())
                    : freshNext);
        }
    }

    private void write(Long userId, Set<Long> removed, List<Long> added, LocalDateTime now) {
        try {
            if (!removed.isEmpty()) {
                pickRepository.deletePicks(userId, removed);
            }
            for (Long areaId : added) {
                pickRepository.insertPick(userId, areaId, now);
            }
        } catch (DataIntegrityViolationException e) {
            throw new AreaConflictException(
                    "Your sub-regions were changed at the same moment; reload and try again");
        }
    }

    /** Validates a requested set; returns it in request order. */
    private List<Long> validate(List<Long> areaIds, int limit, boolean allowEmpty) {
        if (areaIds == null || (areaIds.isEmpty() && !allowEmpty)) {
            throw new IllegalArgumentException("Choose at least one sub-region");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long areaId : areaIds) {
            if (areaId == null) {
                throw new IllegalArgumentException("A sub-region id must not be null");
            }
            if (!unique.add(areaId)) {
                throw new IllegalArgumentException("Each sub-region may be chosen only once");
            }
        }
        if (unique.size() > limit) {
            throw new IllegalArgumentException("Choose at most " + limit + " sub-region" + (limit == 1 ? "" : "s"));
        }
        Set<Long> known = new HashSet<>();
        for (AreaEntity area : areaRepository.findAllById(unique)) {
            if (area.getKind() == AreaKind.SUB_REGION) {
                known.add(area.getId());
            }
        }
        for (Long areaId : unique) {
            if (!known.contains(areaId)) {
                throw new IllegalArgumentException("Not a sub-region: " + areaId);
            }
        }
        return new ArrayList<>(unique);
    }

    // ------------------------------------------------------------------ the state

    private SubRegionState buildState(AppUserEntity user, boolean includeOptions) {
        int limit = policy.getLiteLimit();
        boolean lite = user.getRole() == UserRole.LITE_USER;
        List<Long> pickIds = pickRepository.findAreaIdsByUserId(user.getId());
        LocalDate today = ForecastHorizon.today(clock);
        LocalDate graceUntil = user.getSubRegionsGraceUntil();
        LocalDate next = nextChangeOn(graceUntil, user.getSubRegionsChangedAt(), properties.getLockDays());

        String status;
        LocalDate freeUntil = null;
        LocalDate lockedUntil = null;
        if (!lite) {
            status = SubRegionState.NOT_APPLICABLE;
        } else if (pickIds.isEmpty()) {
            status = SubRegionState.FIRST_CHOICE;
        } else if (graceUntil != null && !today.isAfter(graceUntil)) {
            status = SubRegionState.FREE_UNTIL;
            freeUntil = graceUntil;
        } else if (isLocked(next, today)) {
            status = SubRegionState.LOCKED;
            lockedUntil = next;
        } else {
            status = SubRegionState.OPEN;
        }
        Boolean overLimit = lite && pickIds.size() > limit ? Boolean.TRUE : null;

        List<AreaEntity> subRegions = areaRepository.findByKind(AreaKind.SUB_REGION);
        Map<Long, AreaEntity> byId = subRegions.stream().collect(Collectors.toMap(AreaEntity::getId, a -> a));
        List<SubRegionState.Pick> picks = new ArrayList<>();
        for (Long areaId : pickIds) {
            AreaEntity area = byId.get(areaId);
            if (area != null) {
                picks.add(new SubRegionState.Pick(areaId, area.getName(), regionName(area)));
            }
        }
        List<SubRegionState.RegionOptions> options = List.of();
        List<Long> suggested = List.of();
        if (lite && includeOptions) {
            Map<Long, List<SubRegionLocationRow>> membersByArea = membership.findEnabledMemberRows().stream()
                    .collect(Collectors.groupingBy(SubRegionLocationRow::areaId));
            options = optionsOf(subRegions, membersByArea);
            if (pickIds.isEmpty() && user.getHomeLatitude() != null && user.getHomeLongitude() != null) {
                suggested = nearestToHome(subRegions, membersByArea, user.getHomeLatitude(),
                        user.getHomeLongitude(), limit);
            }
        }
        return new SubRegionState(properties.isEnforce(), limit, picks, options, suggested, status, freeUntil,
                lockedUntil, overLimit);
    }

    private static List<SubRegionState.RegionOptions> optionsOf(List<AreaEntity> subRegions,
            Map<Long, List<SubRegionLocationRow>> membersByArea) {
        Map<Long, List<AreaEntity>> byRegion = new LinkedHashMap<>();
        List<AreaEntity> sorted = subRegions.stream()
                .filter(a -> a.getRegion() != null)
                .sorted(Comparator.comparing((AreaEntity a) -> a.getRegion().getName(),
                        String.CASE_INSENSITIVE_ORDER).thenComparing(AreaEntity::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (AreaEntity area : sorted) {
            byRegion.computeIfAbsent(area.getRegion().getId(), id -> new ArrayList<>()).add(area);
        }
        List<SubRegionState.RegionOptions> out = new ArrayList<>();
        for (List<AreaEntity> group : byRegion.values()) {
            RegionEntity region = group.get(0).getRegion();
            List<SubRegionState.Option> options = group.stream().map(area -> {
                List<SubRegionLocationRow> members = membersByArea.getOrDefault(area.getId(), List.of());
                List<String> examples = members.stream().map(SubRegionLocationRow::name)
                        .sorted(String.CASE_INSENSITIVE_ORDER).limit(EXAMPLE_COUNT).toList();
                return new SubRegionState.Option(area.getId(), area.getName(), members.size(), examples);
            }).toList();
            out.add(new SubRegionState.RegionOptions(region.getId(), region.getName(), options));
        }
        return out;
    }

    /** The {@code limit} sub-regions whose member centroid is nearest home; sub-regions with no members skip. */
    private static List<Long> nearestToHome(List<AreaEntity> subRegions,
            Map<Long, List<SubRegionLocationRow>> membersByArea, double homeLat, double homeLon, int limit) {
        record Scored(Long areaId, String name, double miles) {
        }
        List<Scored> scored = new ArrayList<>();
        for (AreaEntity area : subRegions) {
            List<SubRegionLocationRow> members = membersByArea.getOrDefault(area.getId(), List.of());
            if (members.isEmpty()) {
                continue;
            }
            double lat = members.stream().mapToDouble(SubRegionLocationRow::lat).average().orElseThrow();
            double lon = members.stream().mapToDouble(SubRegionLocationRow::lon).average().orElseThrow();
            scored.add(new Scored(area.getId(), area.getName(), GeoUtils.distanceMiles(homeLat, homeLon, lat, lon)));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::miles).thenComparing(Scored::name))
                .limit(limit)
                .map(Scored::areaId)
                .toList();
    }

    private static String regionName(AreaEntity area) {
        return area.getRegion() == null ? null : area.getRegion().getName();
    }

    private AppUserEntity requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    /**
     * Takes the user row's lock ({@code SELECT ... FOR UPDATE}) as the first thing a write does, so two requests
     * for one reader run one after the other: the second reads the picks and dates the first committed, and
     * cannot slip past the limit or the grace start. It is the first entity read of the transaction on purpose
     * (a managed copy loaded earlier could be stale). The conditional lock-term update stays as the guard on the
     * lock term itself.
     */
    private AppUserEntity lockUser(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NoSuchElementException("No user with id " + userId));
    }

    private AppUserEntity reload(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("No user with id " + userId));
    }
}
