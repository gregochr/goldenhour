package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.MapColourPreferencesRequest;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Service for user profile and settings — home location and drive time management.
 *
 * <p>Deliberately not class-level transactional: several methods call external HTTP services
 * (postcodes.io geocoding, the ORS drive-time chain), and a class-level transaction would pin a
 * pooled database connection across those calls. Only the write methods open transactions.
 */
@Service
public class UserSettingsService {

    /** Narrowest useful radius; below this almost nothing qualifies outside a city. */
    public static final int MIN_LOCAL_RADIUS_MILES = 10;

    /** Widest before "close to home" stops meaning it — a 50-mile radius is a 60-minute drive. */
    public static final int MAX_LOCAL_RADIUS_MILES = 50;

    /** The only two values {@code mapColourScale} may take. */
    private static final Set<String> VALID_MAP_COLOUR_SCALES = Set.of("temp", "verdict");


    private static final Logger LOG = LoggerFactory.getLogger(UserSettingsService.class);

    /** Minimum interval between drive time refreshes (30 minutes). */
    private static final long REFRESH_COOLDOWN_MINUTES = 30;

    private final AppUserRepository userRepository;
    private final PostcodesIoClient postcodesIoClient;
    private final DriveDurationService driveDurationService;
    private final UserDriveTimeWriter driveTimeWriter;
    private final Clock clock;

    /**
     * Constructs a {@code UserSettingsService}.
     *
     * @param userRepository       the user repository
     * @param postcodesIoClient    the postcodes.io client for geocoding
     * @param driveDurationService the drive duration calculation service
     * @param driveTimeWriter      discards stored drive times when the home moves
     * @param clock                supplies "now" for the Coming-up last-seen write
     */
    public UserSettingsService(AppUserRepository userRepository,
            PostcodesIoClient postcodesIoClient,
            DriveDurationService driveDurationService,
            UserDriveTimeWriter driveTimeWriter,
            Clock clock) {
        this.userRepository = userRepository;
        this.postcodesIoClient = postcodesIoClient;
        this.driveDurationService = driveDurationService;
        this.driveTimeWriter = driveTimeWriter;
        this.clock = clock;
    }

    /**
     * Returns the current user's settings including profile and home location.
     *
     * @param auth the authenticated user
     * @return the user settings response
     */
    public UserSettingsResponse getSettings(Authentication auth) {
        AppUserEntity user = getUser(auth);
        String placeName = resolveHomePlaceName(user);
        return mapToResponse(user, placeName);
    }

    /**
     * The caller's home coordinates and radius, WITHOUT resolving the place name.
     *
     * <p>{@link #getSettings} geocodes the postcode through {@code PostcodesIoClient} purely to
     * produce a human-readable place name for the Settings screen, and that lookup is uncached.
     * Close to home needs only the numbers, and its panel refetches whenever the briefing or the
     * home settings change — so routing it through {@code getSettings} put an uncached
     * third-party HTTP call on a Plan-tab render path and threw the result away.
     *
     * @param auth the authenticated user
     * @return the caller's home location and radius
     */
    public HomeLocation getHomeLocation(Authentication auth) {
        AppUserEntity user = getUser(auth);
        return new HomeLocation(user.getId(), user.getHomeLatitude(), user.getHomeLongitude(),
                user.getLocalRadiusMiles(), user.getHomePostcode());
    }

    /**
     * A caller's home, without the geocode.
     *
     * <p>The postcode here is the <em>stored</em> one, which is a column read — not the resolved
     * place name {@link #getSettings} pays an uncached postcodes.io call for. That is the whole
     * distinction this record carries, and it is why the masthead's light rule labels its row from
     * this field rather than from the prettier one.
     *
     * @param userId        the user's id, for their drive times
     * @param latitude      home latitude, or null when no postcode is saved
     * @param longitude     home longitude, or null when no postcode is saved
     * @param radiusMiles   the chosen radius, or null to use the default
     * @param postcode      the stored home postcode, or null when none is saved
     */
    public record HomeLocation(Long userId, Double latitude, Double longitude,
            Integer radiusMiles, String postcode) {
    }

    /**
     * Validates and geocodes a UK postcode without persisting.
     *
     * @param postcode the postcode to look up
     * @return the lookup result with coordinates and place name
     * @throws PostcodeLookupException if the postcode is invalid
     */
    public PostcodeLookupResult lookupPostcode(String postcode) {
        return postcodesIoClient.lookup(postcode);
    }

    /**
     * Persists the confirmed home location on the user entity.
     *
     * @param auth    the authenticated user
     * @param request the confirmed postcode and coordinates
     * @return the updated user settings response
     */
    @Transactional
    public UserSettingsResponse saveHome(Authentication auth, SaveHomeRequest request) {
        AppUserEntity user = getUser(auth);
        boolean originMoved = originMoved(user, request);
        user.setHomePostcode(request.postcode());
        user.setHomeLatitude(request.latitude());
        user.setHomeLongitude(request.longitude());
        if (request.localRadiusMiles() != null) {
            // Clamped rather than rejected: this arrives from a slider whose bounds the client
            // already enforces, so an out-of-range value means a stale client or a direct API
            // call, and silently honouring 500 miles would make "close to home" meaningless.
            user.setLocalRadiusMiles(Math.clamp(request.localRadiusMiles(),
                    MIN_LOCAL_RADIUS_MILES, MAX_LOCAL_RADIUS_MILES));
        }
        if (originMoved) {
            // Every stored drive time was measured from the OLD home, so each one now describes a
            // journey nobody is going to make. Discarding leaves them unknown, which this product
            // renders honestly — no drive line, and the reach lens passes the spot at every tier.
            // Keeping them would gate a location on a figure quietly tens of minutes wrong.
            driveTimeWriter.clearForUser(user.getId());
            // Clearing the stamp is not bookkeeping. It is what the UI reads to say when times
            // were last calculated — and it is the refresh COOLDOWN's own input, so leaving it set
            // would lock a user who has just moved house out of recalculating for up to
            // REFRESH_COOLDOWN_MINUTES while they are served nothing at all.
            user.setDriveTimesCalculatedAt(null);
        }
        userRepository.save(user);
        LOG.info("User '{}' saved home location: {} ({}, {}){}",
                user.getUsername(), request.postcode(), request.latitude(), request.longitude(),
                originMoved ? " — drive times cleared" : "");
        return mapToResponse(user, null);
    }

    /**
     * Whether this save moves the origin drive times were measured from.
     *
     * <p>Compared rather than assumed, because {@code saveHome} is also the radius slider's save
     * path: the settings modal re-sends the user's existing postcode and coordinates whenever the
     * "close to home" radius changes. Clearing on every call would throw away a full set of routed
     * drive times every time somebody dragged that slider.
     *
     * <p>Coordinates count as well as the postcode. Distance and routing are computed from the
     * coordinates, so a re-geocode that lands somewhere new has moved the origin whatever the
     * postcode text says.
     */
    private static boolean originMoved(AppUserEntity user, SaveHomeRequest request) {
        return !Objects.equals(user.getHomePostcode(), request.postcode())
                || !Objects.equals(user.getHomeLatitude(), request.latitude())
                || !Objects.equals(user.getHomeLongitude(), request.longitude());
    }

    /**
     * Recalculates drive times from the user's home to all locations.
     *
     * @param auth the authenticated user
     * @return the refresh response with count and timestamp
     * @throws ResponseStatusException 400 if no home location set, 429 if recently refreshed
     */
    public DriveTimeRefreshResponse refreshDriveTimes(Authentication auth) {
        AppUserEntity user = getUser(auth);
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Set a home location before refreshing drive times");
        }
        if (user.getDriveTimesCalculatedAt() != null
                && user.getDriveTimesCalculatedAt()
                        .isAfter(Instant.now().minus(REFRESH_COOLDOWN_MINUTES, ChronoUnit.MINUTES))) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Drive times were refreshed recently. Please wait before trying again.");
        }

        int updated = driveDurationService.refreshForUser(
                user.getId(), user.getHomeLatitude(), user.getHomeLongitude());
        user.setDriveTimesCalculatedAt(Instant.now());
        userRepository.save(user);
        LOG.info("Drive times refreshed for user '{}': {} locations updated",
                user.getUsername(), updated);
        return new DriveTimeRefreshResponse(updated, user.getDriveTimesCalculatedAt());
    }

    /**
     * Persists the caller's map colour preferences.
     *
     * <p>Its own endpoint rather than fields on {@code saveHome}: a colour preference is not
     * home-derived, so folding it into that request would deserialise the home fields to null and
     * wipe a saved postcode.
     *
     * @param auth    the authenticated user
     * @param request the chosen scale and whether markers follow it
     * @return the updated user settings response
     * @throws ResponseStatusException 400 if {@code mapColourScale} is not "temp" or "verdict"
     */
    @Transactional
    public UserSettingsResponse saveMapColourPreferences(Authentication auth,
            MapColourPreferencesRequest request) {
        // Null-checked before the Set lookup: VALID_MAP_COLOUR_SCALES is Set.of(...), whose
        // contains() throws NullPointerException on a null argument rather than returning false —
        // an omitted field would otherwise 500 instead of the 400 a bad request deserves.
        if (request.mapColourScale() == null
                || !VALID_MAP_COLOUR_SCALES.contains(request.mapColourScale())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "mapColourScale must be 'temp' or 'verdict'");
        }
        AppUserEntity user = getUser(auth);
        user.setMapColourScale(request.mapColourScale());
        userRepository.save(user);
        LOG.info("User '{}' saved map colour preference: scale={}",
                user.getUsername(), request.mapColourScale());
        // null place name, matching saveHome: this save does not geocode, and the modal already
        // merges the previous placeName forward rather than losing it to a blanked field.
        return mapToResponse(user, null);
    }

    /**
     * Records that the caller has just looked at the "Coming up" tab (plan D3).
     *
     * <p>Its own endpoint's backing write, never folded into {@code saveHome} — the same reasoning
     * {@link #saveMapColourPreferences} records: a request body carrying only this concern would
     * deserialise the home fields to null and wipe a saved postcode. No request body at all here:
     * "now" is the server's clock, so a client with a wrong clock cannot mark the future seen.
     *
     * <p>Serves both writes the client makes — the explicit {@code Mark seen} press and the quiet
     * first-open bootstrap (D3's null→set transition, without which the badge never activates for
     * any account). The service does not distinguish them: both mean "this account has now seen
     * the feed as of this instant".
     *
     * <p>Writes through {@link AppUserRepository#updateComingUpLastSeenAtByUsername}, a
     * column-scoped bulk update, never a load-mutate-{@code save()} on the whole entity — a
     * whole-entity save here would race {@link #saveHome}/{@link #saveMapColourPreferences} in
     * another tab and could silently discard whichever one flushed last, since {@code
     * AppUserEntity} has neither {@code @Version} nor {@code @DynamicUpdate} (a Codex review
     * finding on PR #695). The subsequent {@link #getUser} re-reads the row fresh — the update's
     * {@code clearAutomatically} evicts the persistence context first — so the response reflects
     * the just-written instant rather than the entity as it stood before this call.
     *
     * @param auth the authenticated user
     * @return the updated user settings response
     */
    @Transactional
    public UserSettingsResponse markComingUpSeen(Authentication auth) {
        userRepository.updateComingUpLastSeenAtByUsername(auth.getName(), clock.instant());
        AppUserEntity user = getUser(auth);
        // null place name, matching saveMapColourPreferences: this save does not geocode.
        return mapToResponse(user, null);
    }

    /**
     * Resolves the authenticated user's database ID.
     *
     * @param auth the authentication context
     * @return the user's primary key
     */
    public Long getUserId(Authentication auth) {
        return getUser(auth).getId();
    }

    private AppUserEntity getUser(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new NoSuchElementException(
                        "User not found: " + auth.getName()));
    }

    private String resolveHomePlaceName(AppUserEntity user) {
        if (user.getHomePostcode() == null) {
            return null;
        }
        try {
            return postcodesIoClient.lookup(user.getHomePostcode()).placeName();
        } catch (Exception e) {
            LOG.debug("Could not resolve place name for postcode '{}': {}",
                    user.getHomePostcode(), e.getMessage());
            return null;
        }
    }

    private UserSettingsResponse mapToResponse(AppUserEntity user, String placeName) {
        return new UserSettingsResponse(
                user.getUsername(),
                user.getEmail(),
                user.getRole().name(),
                user.getHomePostcode(),
                user.getHomeLatitude(),
                user.getHomeLongitude(),
                placeName,
                user.getLocalRadiusMiles(),
                user.getDriveTimesCalculatedAt(),
                user.getMapColourScale(),
                // The London civil date, derived here so the client compares two ISO date strings
                // and no timezone rule reaches the browser (plan D3). The instant stays stored.
                ForecastHorizon.civilDate(user.getComingUpLastSeenAt()));
    }
}
