package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.exception.FavouritesLimitException;
import com.gregochr.goldenhour.model.AreaKindRow;
import com.gregochr.goldenhour.model.FavouriteLocations;
import com.gregochr.goldenhour.model.FavouriteRow;
import com.gregochr.goldenhour.model.FavouritesRequest;
import com.gregochr.goldenhour.model.FavouritesResponse;
import com.gregochr.goldenhour.model.SubRegionMemberRow;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.UserFavouriteRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Pro or Admin reader's favourites: starred places and starred areas (find-and-favourites-plan F3).
 *
 * <p><strong>Merged on the server (D2).</strong> {@link FavouritesResponse#locationIds()} is the starred
 * places plus the ENABLED members of the starred areas (a named area's {@code area_location} rows, a
 * sub-region's {@code location_sub_region} rows), de-duplicated and in id order. A disabled location may be
 * starred; it stays in {@code places} and is hidden from {@code locationIds} until it is enabled.
 *
 * <p><strong>Watched dates (one-screen-plan D16).</strong> A third kind of row, keyed
 * {@code <almanac type>:<start date>} ({@link WatchDateKey}). A date is not a place: it adds nothing to
 * {@link FavouritesResponse#locationIds()} and Ask never reads it ({@link #forAsk} asks for place and area rows only).
 * It counts toward the cap. Once its start date is more than {@value #WATCH_GRACE_DAYS} UK civil days gone, every
 * write deletes it under the reader's lock, so a row nobody can see never blocks the cap; a read stops serving it at
 * the same moment.
 *
 * <p><strong>The cap (D5).</strong> {@value #MAX_FAVOURITES} places, areas and dates per reader. Every write takes
 * the reader's {@code app_user} row lock ({@code SELECT ... FOR UPDATE}) first, so two requests for one
 * reader run one after the other and the second counts what the first committed; the partial unique indexes
 * of V226 are the backstop. Exceeding it is {@link FavouritesLimitException} (HTTP 409).
 *
 * <p><strong>Independent of sub-region picks.</strong> {@code app_user_sub_region} (V192) is a Lite reader's
 * own pick list with its own limit and lock; this service never reads or writes it, and it never reads or
 * writes this table. A pick and a favourite on the same area coexist.
 *
 * <p>Role gating (Pro and Admin only) is the controller's {@code @PreAuthorize}; this service trusts it.
 */
@Service
public class UserFavouritesService {

    /** The most favourites (places plus areas) one reader may hold. */
    public static final int MAX_FAVOURITES = 25;

    /** How many UK civil days after its start a watched date is still kept (and served) before it expires. */
    public static final int WATCH_GRACE_DAYS = 14;

    /** What the reader is told at the cap. */
    public static final String LIMIT_MESSAGE = "You can keep up to " + MAX_FAVOURITES + " places and dates.";

    private static final Logger LOG = LoggerFactory.getLogger(UserFavouritesService.class);

    private final AppUserRepository userRepository;
    private final UserFavouriteRepository favouriteRepository;
    private final LocationRepository locationRepository;
    private final AreaRepository areaRepository;
    private final SubRegionMembershipService subRegionMembership;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param userRepository      the users, for the id and the row lock
     * @param favouriteRepository the favourites
     * @param locationRepository  the locations: which exist, and which are enabled
     * @param areaRepository      the areas: which exist, their kind and named members
     * @param subRegionMembership where a sub-region's members are read from
     * @param clock               supplies "now" for the star time
     */
    public UserFavouritesService(AppUserRepository userRepository, UserFavouriteRepository favouriteRepository,
            LocationRepository locationRepository, AreaRepository areaRepository,
            SubRegionMembershipService subRegionMembership, Clock clock) {
        this.userRepository = userRepository;
        this.favouriteRepository = favouriteRepository;
        this.locationRepository = locationRepository;
        this.areaRepository = areaRepository;
        this.subRegionMembership = subRegionMembership;
        this.clock = clock;
    }

    /**
     * The caller's favourites, merged.
     *
     * @param username the caller
     * @return places, areas and the expanded location ids
     * @throws NoSuchElementException if there is no such user (HTTP 404)
     */
    @Transactional(readOnly = true)
    public FavouritesResponse get(String username) {
        return build(userId(username));
    }

    /**
     * The reader's favourites as Ask reads them: the enabled favourite locations, and the name of the starred area
     * each member belongs to. Read-only; the caller must already have established that the reader is Pro or Admin.
     *
     * Places and areas only: a watched date is not a place, so the read never asks for date rows.
     *
     * @param userId the reader's id
     * @return the favourites; {@link FavouriteLocations#NONE} when the reader has none
     */
    @Transactional(readOnly = true)
    public FavouriteLocations forAsk(Long userId) {
        List<FavouriteRow> rows = favouriteRepository.findPlaceAndAreaRowsByUserId(userId);
        if (rows.isEmpty()) {
            return FavouriteLocations.NONE;
        }
        Set<Long> wanted = new TreeSet<>();
        List<Long> areaIds = new ArrayList<>();
        for (FavouriteRow row : rows) {
            if (row.locationId() != null) {
                wanted.add(row.locationId());
            } else {
                areaIds.add(row.areaId());
            }
        }
        Map<Long, String> areaByLocation = new HashMap<>();
        if (!areaIds.isEmpty()) {
            Map<Long, List<Long>> members = membersOf(areaRepository.findKindRows(areaIds));
            List<AreaEntity> areas = new ArrayList<>(areaRepository.findAllById(areaIds));
            areas.sort(Comparator.comparing(AreaEntity::getName, String.CASE_INSENSITIVE_ORDER));
            for (AreaEntity area : areas) {
                for (Long locationId : members.getOrDefault(area.getId(), List.of())) {
                    wanted.add(locationId);
                    areaByLocation.putIfAbsent(locationId, area.getName());
                }
            }
        }
        Set<Long> enabled = new HashSet<>(locationRepository.findEnabledIds());
        wanted.retainAll(enabled);
        areaByLocation.keySet().retainAll(wanted);
        return new FavouriteLocations(wanted, areaByLocation);
    }

    /**
     * Replaces the caller's places and areas. Stars that stay keep their original time. The request names no dates, so
     * the reader's watched dates are left as they are, and they count toward the cap.
     *
     * @param username the caller
     * @param request  the places and areas to hold; null lists read as empty
     * @return the new list, merged
     * @throws IllegalArgumentException if an id is null (HTTP 400)
     * @throws FavouritesLimitException if the lists and the watched dates hold more than the cap (HTTP 409)
     * @throws NoSuchElementException   if an id is not a location or an area (HTTP 404)
     */
    @Transactional
    public FavouritesResponse replace(String username, FavouritesRequest request) {
        Long userId = lock(username);
        Set<Long> places = distinct(request.locationIds());
        Set<Long> areas = distinct(request.areaIds());
        Set<Long> heldPlaces = new HashSet<>();
        Set<Long> heldAreas = new HashSet<>();
        int heldDates = 0;
        for (FavouriteRow row : purgeExpiredDates(userId)) {
            if (row.locationId() != null) {
                heldPlaces.add(row.locationId());
            } else if (row.areaId() != null) {
                heldAreas.add(row.areaId());
            } else {
                heldDates++;
            }
        }
        if (places.size() + areas.size() + heldDates > MAX_FAVOURITES) {
            throw new FavouritesLimitException(LIMIT_MESSAGE);
        }
        requireLocations(places);
        requireAreas(areas);
        Set<Long> droppedPlaces = minus(heldPlaces, places);
        Set<Long> droppedAreas = minus(heldAreas, areas);
        if (!droppedPlaces.isEmpty()) {
            favouriteRepository.deletePlaces(userId, droppedPlaces);
        }
        if (!droppedAreas.isEmpty()) {
            favouriteRepository.deleteAreas(userId, droppedAreas);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        minus(places, heldPlaces).forEach(id -> insertPlace(userId, id, now));
        minus(areas, heldAreas).forEach(id -> insertArea(userId, id, now));
        LOG.info("Favourites for user id={} are now {} place(s) and {} area(s)", userId, places.size(),
                areas.size());
        return build(userId);
    }

    /**
     * Stars one place. Starring a place already starred changes nothing.
     *
     * @param username   the caller
     * @param locationId the location (enabled or not)
     * @return the new list, merged
     * @throws NoSuchElementException   if there is no such location (HTTP 404)
     * @throws FavouritesLimitException if the reader is at the cap and this is a new star (HTTP 409)
     */
    @Transactional
    public FavouritesResponse addPlace(String username, Long locationId) {
        Long userId = lock(username);
        requireLocations(Set.of(locationId));
        List<FavouriteRow> held = purgeExpiredDates(userId);
        if (held.stream().noneMatch(row -> locationId.equals(row.locationId()))) {
            requireRoom(held.size());
            insertPlace(userId, locationId, LocalDateTime.now(clock));
        }
        return build(userId);
    }

    /**
     * Stars one area. Starring an area already starred changes nothing.
     *
     * @param username the caller
     * @param areaId   the area (named or sub-region)
     * @return the new list, merged
     * @throws NoSuchElementException   if there is no such area (HTTP 404)
     * @throws FavouritesLimitException if the reader is at the cap and this is a new star (HTTP 409)
     */
    @Transactional
    public FavouritesResponse addArea(String username, Long areaId) {
        Long userId = lock(username);
        requireAreas(Set.of(areaId));
        List<FavouriteRow> held = purgeExpiredDates(userId);
        if (held.stream().noneMatch(row -> areaId.equals(row.areaId()))) {
            requireRoom(held.size());
            insertArea(userId, areaId, LocalDateTime.now(clock));
        }
        return build(userId);
    }

    /**
     * Watches one almanac date. Watching a date already watched changes nothing.
     *
     * @param username the caller
     * @param key      {@code <almanac type>:<start date>}, for example {@code king-tide:2026-12-03}
     * @return the new list, merged
     * @throws IllegalArgumentException if the key is not a known type and an ISO date, or the date is already past
     *                                  its grace period (HTTP 400)
     * @throws FavouritesLimitException if the reader is at the cap and this is a new watch (HTTP 409)
     */
    @Transactional
    public FavouritesResponse addDate(String username, String key) {
        if (isExpired(WatchDateKey.parse(key, ForecastHorizon.today(clock)))) {
            throw new IllegalArgumentException("That date has already passed.");
        }
        Long userId = lock(username);
        List<FavouriteRow> held = purgeExpiredDates(userId);
        if (held.stream().noneMatch(row -> key.equals(row.almanacKey()))) {
            requireRoom(held.size());
            favouriteRepository.insertDate(userId, key, LocalDateTime.now(clock));
        }
        return build(userId);
    }

    /**
     * Un-watches one almanac date. Un-watching a date that is not watched changes nothing.
     *
     * @param username the caller
     * @param key      {@code <almanac type>:<start date>}
     * @return the new list, merged
     * @throws IllegalArgumentException if the key is not a known type and an ISO date (HTTP 400)
     */
    @Transactional
    public FavouritesResponse removeDate(String username, String key) {
        WatchDateKey.requireStorable(key);
        Long userId = lock(username);
        purgeExpiredDates(userId);
        favouriteRepository.deleteDates(userId, List.of(key));
        return build(userId);
    }

    /**
     * Un-stars one place. Un-starring a place that is not starred changes nothing.
     *
     * @param username   the caller
     * @param locationId the location
     * @return the new list, merged
     * @throws NoSuchElementException if there is no such location (HTTP 404)
     */
    @Transactional
    public FavouritesResponse removePlace(String username, Long locationId) {
        Long userId = lock(username);
        requireLocations(Set.of(locationId));
        purgeExpiredDates(userId);
        favouriteRepository.deletePlaces(userId, List.of(locationId));
        return build(userId);
    }

    /**
     * Un-stars one area. Un-starring an area that is not starred changes nothing.
     *
     * @param username the caller
     * @param areaId   the area
     * @return the new list, merged
     * @throws NoSuchElementException if there is no such area (HTTP 404)
     */
    @Transactional
    public FavouritesResponse removeArea(String username, Long areaId) {
        Long userId = lock(username);
        requireAreas(Set.of(areaId));
        purgeExpiredDates(userId);
        favouriteRepository.deleteAreas(userId, List.of(areaId));
        return build(userId);
    }

    /**
     * Inserts one star. The existence check and the insert are separate statements, so a location deleted in
     * between reaches the foreign key: that is "no such location" (404), not a server error.
     */
    private void insertPlace(Long userId, Long locationId, LocalDateTime now) {
        try {
            favouriteRepository.insertPlace(userId, locationId, now);
        } catch (DataIntegrityViolationException e) {
            throw new NoSuchElementException("No location with id " + locationId);
        }
    }

    /** As {@link #insertPlace}, for an area deleted between the check and the insert. */
    private void insertArea(Long userId, Long areaId, LocalDateTime now) {
        try {
            favouriteRepository.insertArea(userId, areaId, now);
        } catch (DataIntegrityViolationException e) {
            throw new NoSuchElementException("No area with id " + areaId);
        }
    }

    private static void requireRoom(int held) {
        if (held >= MAX_FAVOURITES) {
            throw new FavouritesLimitException(LIMIT_MESSAGE);
        }
    }

    private static Set<Long> distinct(List<Long> ids) {
        Set<Long> out = new LinkedHashSet<>();
        if (ids == null) {
            return out;
        }
        for (Long id : ids) {
            if (id == null) {
                throw new IllegalArgumentException("A favourite id must not be null");
            }
            out.add(id);
        }
        return out;
    }

    private static Set<Long> minus(Set<Long> from, Set<Long> remove) {
        Set<Long> out = new LinkedHashSet<>(from);
        out.removeAll(remove);
        return out;
    }

    private void requireLocations(Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> found = new HashSet<>(locationRepository.findExistingIds(ids));
        for (Long id : ids) {
            if (!found.contains(id)) {
                throw new NoSuchElementException("No location with id " + id);
            }
        }
    }

    private void requireAreas(Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> found = new HashSet<>();
        areaRepository.findKindRows(ids).forEach(row -> found.add(row.id()));
        for (Long id : ids) {
            if (!found.contains(id)) {
                throw new NoSuchElementException("No area with id " + id);
            }
        }
    }

    private Long userId(String username) {
        return userRepository.findIdByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    /** Takes the reader's row lock as the first thing a write does; returns the reader's id. */
    private Long lock(String username) {
        Long id = userId(username);
        userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
        return id;
    }

    /** Whether a watched date's start is past the grace period (UK civil today, from the injected clock). */
    private boolean isExpired(LocalDate start) {
        return start.isBefore(ForecastHorizon.today(clock).minusDays(WATCH_GRACE_DAYS));
    }

    /** Whether a stored key is past its grace period, or no longer parses (a type withdrawn since it was stored). */
    private boolean isGone(String key) {
        LocalDate start = WatchDateKey.tryParse(key);
        return start == null || isExpired(start);
    }

    /**
     * Deletes the reader's expired watched dates, and returns what is left. Called by every write, after the
     * reader's lock is taken, so the rows nobody can see are gone before the cap is counted.
     */
    private List<FavouriteRow> purgeExpiredDates(Long userId) {
        List<FavouriteRow> rows = favouriteRepository.findRowsByUserId(userId);
        List<String> expired = rows.stream().map(FavouriteRow::almanacKey)
                .filter(key -> key != null && isGone(key)).toList();
        if (expired.isEmpty()) {
            return rows;
        }
        favouriteRepository.deleteDates(userId, expired);
        return rows.stream().filter(row -> row.almanacKey() == null || !expired.contains(row.almanacKey())).toList();
    }

    private FavouritesResponse build(Long userId) {
        List<FavouritesResponse.Place> places = new ArrayList<>();
        List<FavouritesResponse.Area> areas = new ArrayList<>();
        List<FavouritesResponse.Date> dates = new ArrayList<>();
        for (FavouriteRow row : favouriteRepository.findRowsByUserId(userId)) {
            if (row.locationId() != null) {
                places.add(new FavouritesResponse.Place(row.locationId(), row.createdAt()));
            } else if (row.areaId() != null) {
                areas.add(new FavouritesResponse.Area(row.areaId(), row.createdAt()));
            } else if (!isGone(row.almanacKey())) {
                dates.add(new FavouritesResponse.Date(row.almanacKey(), row.createdAt()));
            }
        }
        Set<Long> wanted = new TreeSet<>();
        places.forEach(place -> wanted.add(place.locationId()));
        if (!areas.isEmpty()) {
            List<AreaKindRow> kinds = areaRepository.findKindRows(areas.stream()
                    .map(FavouritesResponse.Area::areaId).toList());
            // The database cascades a deleted area's favourites; the local H2 schema has no keys, so an
            // orphan is dropped here rather than served.
            Set<Long> existing = new HashSet<>();
            kinds.forEach(row -> existing.add(row.id()));
            areas.removeIf(area -> !existing.contains(area.areaId()));
            membersOf(kinds).values().forEach(wanted::addAll);
        }
        wanted.retainAll(new HashSet<>(locationRepository.findEnabledIds()));
        return new FavouritesResponse(places, areas, List.copyOf(wanted), dates);
    }

    /** Every starred area's members: named areas in one read, sub-regions in one read, whatever the count. */
    private Map<Long, List<Long>> membersOf(List<AreaKindRow> kinds) {
        Map<Long, List<Long>> members = new HashMap<>();
        List<Long> named = new ArrayList<>();
        List<Long> subRegions = new ArrayList<>();
        for (AreaKindRow row : kinds) {
            if (row.kind() == AreaKind.SUB_REGION) {
                subRegions.add(row.id());
            } else {
                named.add(row.id());
            }
        }
        if (!subRegions.isEmpty()) {
            Map<Long, List<Long>> bySubRegion = subRegionMembership.membersByArea();
            subRegions.forEach(id -> members.put(id, bySubRegion.getOrDefault(id, List.of())));
        }
        if (!named.isEmpty()) {
            for (SubRegionMemberRow row : areaRepository.findNamedMemberRowsFor(named)) {
                members.computeIfAbsent(row.areaId(), id -> new ArrayList<>()).add(row.locationId());
            }
        }
        return members;
    }
}
