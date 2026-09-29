package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.MapColourPreferencesRequest;
import com.gregochr.goldenhour.model.MapTideModeRequest;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Service for user profile and settings — home location and drive time management.
 *
 * <p>Deliberately not class-level transactional: several methods call external HTTP services
 * (postcodes.io geocoding, the ORS drive-time chain), and a class-level transaction would pin a
 * pooled database connection across those calls. Only the write methods open transactions.
 *
 * <p><strong>No write here saves the whole user row.</strong> Each goes through a column-scoped
 * update on {@link AppUserRepository} that writes only what its own request changes. A whole-entity
 * {@code save()} wrote every column back from the copy it had loaded, so two writes that overlapped
 * lost one of them: a colour save and a home save in two tabs, a home save during the nightly
 * drive-time job, or — the worst of them — a postcode saved while a drive-time refresh was routing
 * from the old one, which put the old home back.
 *
 * <p><strong>{@code driveTimesCalculatedAt} means "drive times were stored for the roster read at
 * this instant" — nothing less.</strong> {@link #refreshDriveTimes} never advances it for an attempt
 * that stored no rows — no answer from ORS at all, or an answer with no valid duration to any
 * location (a present but empty list; see the {@code refreshDriveTimes} javadoc, which is where
 * the two are told apart) — in either case only {@link #pendingDriveTimeAttempts}, an in-memory
 * tracker, throttles a repeated press after a failure. That map resets on an app restart, which is
 * accepted — it is a rate limit on a single-instance app, not a record anything downstream reads,
 * and the worst a restart can do is let one extra attempt through before the persisted stamp (once
 * a refresh actually succeeds) takes back over. Migration V157 reconciles the one database state
 * this rule cannot itself repair: a stamp an earlier build left behind with zero rows, from before
 * this rule covered the present-but-empty case too.
 */
@Service
public class UserSettingsService {

    /** Narrowest useful radius; below this almost nothing qualifies outside a city. */
    public static final int MIN_LOCAL_RADIUS_MILES = 10;

    /** Widest before "close to home" stops meaning it — a 50-mile radius is a 60-minute drive. */
    public static final int MAX_LOCAL_RADIUS_MILES = 50;

    /** The only two values {@code mapColourScale} may take. */
    private static final Set<String> VALID_MAP_COLOUR_SCALES = Set.of("temp", "verdict");

    /** The only three values {@code mapTideMode} may take (map-mobile-sheet-plan.md, M4). */
    private static final Set<String> VALID_MAP_TIDE_MODES = Set.of("auto", "always", "off");


    private static final Logger LOG = LoggerFactory.getLogger(UserSettingsService.class);

    /** Minimum interval between drive time refreshes (30 minutes). */
    private static final long REFRESH_COOLDOWN_MINUTES = 30;

    private final AppUserRepository userRepository;
    private final PostcodesIoClient postcodesIoClient;
    private final DriveDurationService driveDurationService;
    private final UserDriveTimeWriter driveTimeWriter;
    private final Clock clock;

    /**
     * A user's most recent drive-time refresh ATTEMPT (not necessarily a stored one), keyed by user
     * id — package-private so tests can assert on it directly rather than only through behaviour.
     *
     * <p>Exists because the persisted {@code driveTimesCalculatedAt} column can no longer carry a
     * failed attempt (see the class javadoc), but the 30-minute cooldown must still catch a repeated
     * press after ORS gives no answer, or the button could be hammered for free. Written just before
     * the ORS call, so the throttle covers the call in flight too. Bounded three ways: removed the
     * moment a refresh for that user stores successfully (the persisted stamp then covers it),
     * removed when the home moves (mirroring the persisted stamp's own clear in {@link #saveHome} —
     * a stale entry here would re-arm a cooldown the move is documented to release), and expired
     * lazily on read once older than the cooldown ({@link #isThrottled}), which is what keeps an
     * entry from lingering forever for a user who fails once and is never throttled by it again.
     */
    final Map<Long, Instant> pendingDriveTimeAttempts = new ConcurrentHashMap<>();

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
     * <p>Reads the row under a lock ({@link AppUserRepository#findByUsernameForUpdate}) and writes
     * the home fields through one column-scoped update. The lock is what makes the "does this move
     * the origin?" decision sound: it is made against the row as it stands, and nothing else can
     * write the row before this commits. Another save waits. So does a drive-time refresh trying
     * to store what it measured from the home this save replaces: its compare-and-set runs once
     * this commits, and matches nothing.
     *
     * @param auth    the authenticated user
     * @param request the confirmed postcode and coordinates
     * @return the updated user settings response
     */
    @Transactional
    public UserSettingsResponse saveHome(Authentication auth, SaveHomeRequest request) {
        AppUserEntity stored = userRepository.findByUsernameForUpdate(auth.getName())
                .orElseThrow(() -> userNotFound(auth));
        boolean originMoved = originMoved(stored, request);
        // Clamped rather than rejected: this arrives from a slider whose bounds the client already
        // enforces, so an out-of-range value means a stale client or a direct API call, and silently
        // honouring 500 miles would make "close to home" meaningless. Null keeps the stored radius.
        Integer radius = request.localRadiusMiles() == null ? null
                : Math.clamp(request.localRadiusMiles(), MIN_LOCAL_RADIUS_MILES, MAX_LOCAL_RADIUS_MILES);
        userRepository.updateHome(stored.getId(), request.postcode(), request.latitude(),
                request.longitude(), radius);
        if (originMoved) {
            // Every stored drive time was measured from the OLD home, so each one now describes a
            // journey nobody is going to make. Discarding leaves them unknown, which this product
            // renders honestly — no drive line, and the reach lens passes the spot at every tier.
            // Keeping them would gate a location on a figure quietly tens of minutes wrong.
            driveTimeWriter.clearForUser(stored.getId());
            // Clearing the stamp is not bookkeeping. It is what the UI reads to say when times
            // were last calculated — and it is the refresh COOLDOWN's own input, so leaving it set
            // would lock a user who has just moved house out of recalculating for up to
            // REFRESH_COOLDOWN_MINUTES while they are served nothing at all.
            userRepository.clearDriveTimesCalculatedAt(stored.getId());
            // Same reasoning applies to the in-memory attempt tracker: a failed attempt from before
            // the move must not go on throttling someone who has just moved house.
            pendingDriveTimeAttempts.remove(stored.getId());
        }
        LOG.info("User '{}' saved home location: {} ({}, {}){}",
                stored.getUsername(), request.postcode(), request.latitude(), request.longitude(),
                originMoved ? " — drive times cleared" : "");
        // Read back rather than assembled here: the updates evicted the locked instance, and a
        // null radius kept whatever was stored, which only the row itself can say.
        return mapToResponse(getUser(auth), null);
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
     * <p><strong>Stores what it measured only while the home is still the one it measured
     * from.</strong> Routing takes seconds and runs outside any transaction, and the settings
     * dialog lets a reader save a new postcode while it runs. Before this was guarded, the refresh
     * wrote its drive times after that save had discarded the old ones, then saved the whole user
     * row it had loaded before routing — putting the old postcode and coordinates back over the
     * new ones. Now the store is a compare-and-set on the coordinates measured from
     * ({@link UserDriveTimeWriter#storeIfHomeUnchanged}); when the home has moved, nothing is
     * written and this answers 409.
     *
     * <p>409 rather than measuring again from the new home. A retry comes back in through the top
     * of this method, so every precondition is judged afresh against the row as it stands then —
     * the cooldown included, which answers 429 if another refresh has already measured from the
     * new home, where re-measuring in place would pay ORS twice for one answer. Refusing costs the
     * reader nothing they can lose: the save that moved the home discarded the old drive times and
     * released the cooldown, so pressing again is allowed.
     *
     * <p><strong>An attempt that stores no rows never advances {@code driveTimesCalculatedAt}
     * — for either of {@link DriveDurationService#measureForUser}'s two kinds of nothing.</strong>
     * ORS giving no answer at all (unconfigured, rate-limited, or an empty/failed response) and ORS
     * answering with no valid duration to any location (a present but empty list) are both treated
     * identically here: no writer call, rows and stamp both left exactly as they were. The response
     * reports the user's own unmodified stamp — {@code null} on the realistic sequence that reaches
     * this (a postcode change, whose save already cleared it, is the only way this button is
     * enabled) — rather than {@code now}, so the settings dialog cannot read a fresh timestamp and
     * print "Last calculated: Just now" for a refresh that calculated nothing. See
     * {@link #pendingDriveTimeAttempts} for how the cooldown still catches a repeated press. A
     * present-but-empty answer used to still call {@link UserDriveTimeWriter#storeIfHomeUnchanged}
     * with an empty list — which clears the stored rows in the very method that also stamps —
     * recreating a stamp with zero rows behind it; V157 repairs the state that left in the
     * database, and this fix is why it cannot recur.
     *
     * @param auth the authenticated user
     * @return the refresh response with count and timestamp
     * @throws ResponseStatusException 400 if no home location set, 429 if recently refreshed, 409 if
     *                                 the home moved while drive times were being measured
     */
    public DriveTimeRefreshResponse refreshDriveTimes(Authentication auth) {
        AppUserEntity user = getUser(auth);
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Set a home location before refreshing drive times");
        }

        Instant now = clock.instant();
        if (isThrottled(user, now)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Drive times were refreshed recently. Please wait before trying again.");
        }

        double originLat = user.getHomeLatitude();
        double originLon = user.getHomeLongitude();
        // Recorded BEFORE the ORS call — not after, and not only on success — so this attempt
        // throttles a same-cooldown retry even if it fails, and so the stamp a SUCCESSFUL attempt
        // eventually stores below is the roster-READ instant, matching DriveTimeRefreshJob's
        // identical fix (measureForUser reads the whole location table, then spends seconds
        // routing; a location created in that gap must have a created_at later than this instant,
        // or the scheduled job's rosterGrewSince would never see it).
        pendingDriveTimeAttempts.put(user.getId(), now);

        Optional<List<UserDriveTimeEntity>> measured =
                driveDurationService.measureForUser(user.getId(), originLat, originLon);

        if (measured.isEmpty() || measured.get().isEmpty()) {
            // Two different kinds of nothing (see DriveDurationService.measureForUser's own
            // javadoc), and this path now treats them identically: no answer at all (ORS
            // unconfigured, rate-limited, or an empty/failed response), or ORS answered but no
            // destination had a valid duration. Either way nothing is stored, so the persisted
            // stamp must not move — see the class and method javadoc — and no writer method is
            // called here; the pending-attempt entry just recorded above is the only thing
            // throttling a repeated press. This used to differ: a present-but-empty answer still
            // called storeIfHomeUnchanged with an empty list, which cleared the user's stored rows
            // AND stamped in the same compare-and-set — recreating exactly the "stamp without
            // rows" state V157 repairs. DriveTimeRefreshJob.run already treats an empty measurement
            // (of either origin) as nothing to store; this closes the one route that did not.
            LOG.warn("Drive time refresh measured no drive times for user {} ({}) — leaving the "
                    + "stored ones and their calculated-at stamp exactly as they are", user.getId(),
                    measured.isEmpty() ? "ORS gave no answer" : "ORS answered with no valid duration");
            return new DriveTimeRefreshResponse(0, user.getDriveTimesCalculatedAt());
        }

        boolean stored = driveTimeWriter.storeIfHomeUnchanged(
                user.getId(), originLat, originLon, measured.get(), now);
        if (!stored) {
            // Belt and braces: saveHome already removes this entry when it moves the home (the
            // same reasoning it gives for clearing the persisted stamp), so this is normally a
            // no-op — but a lingering entry here would re-arm a cooldown that move is documented
            // to release, so it is cleared on this path too rather than assumed.
            pendingDriveTimeAttempts.remove(user.getId());
            LOG.info("Drive time refresh for user {} discarded — the home moved while it was measured",
                    user.getId());
            throw new ResponseStatusException(CONFLICT, "Your home location changed while drive times "
                    + "were being calculated, so nothing was saved. Refresh again to calculate them "
                    + "from the new home.");
        }
        // Stored successfully: the persisted stamp now covers this attempt, so the in-memory entry
        // is redundant — drop it rather than let it sit until it ages out on its own.
        pendingDriveTimeAttempts.remove(user.getId());
        int updated = measured.get().size();
        LOG.info("Drive times refreshed for user '{}': {} locations updated",
                user.getUsername(), updated);
        return new DriveTimeRefreshResponse(updated, now);
    }

    /**
     * Whether a refresh for this user must wait — the later of two clocks. The persisted
     * {@code driveTimesCalculatedAt} covers an attempt that stored; {@link #pendingDriveTimeAttempts}
     * covers one that did not, which is otherwise invisible to any persisted column now that a
     * failed attempt no longer writes a stamp.
     *
     * <p>Reading the in-memory entry also expires it: once it is older than the cooldown it is
     * removed here rather than left in place, which is what keeps the map bounded for a user who
     * fails once and is never throttled by that failure again.
     *
     * @param user the caller, as read at the top of {@link #refreshDriveTimes}
     * @param now  this call's "now" — the same instant the caller will store if it succeeds
     * @return {@code true} if a refresh right now must be refused with 429
     */
    private boolean isThrottled(AppUserEntity user, Instant now) {
        Instant cooldownFloor = now.minus(REFRESH_COOLDOWN_MINUTES, ChronoUnit.MINUTES);
        if (user.getDriveTimesCalculatedAt() != null
                && user.getDriveTimesCalculatedAt().isAfter(cooldownFloor)) {
            return true;
        }
        Instant pending = pendingDriveTimeAttempts.get(user.getId());
        if (pending == null) {
            return false;
        }
        // Same boundary as the persisted stamp above (not-after, not strictly-before): an
        // attempt recorded exactly at the cooldown floor is not throttled, matching
        // `atOrAfterCooldown_refreshes`'s own exact-1800-seconds case for the persisted path.
        if (!pending.isAfter(cooldownFloor)) {
            pendingDriveTimeAttempts.remove(user.getId(), pending);
            return false;
        }
        return true;
    }

    /**
     * Persists the caller's map colour preferences.
     *
     * <p>Its own endpoint rather than fields on {@code saveHome}: a colour preference is not
     * home-derived, so folding it into that request would deserialise the home fields to null and
     * wipe a saved postcode.
     *
     * <p>Writes the scale alone ({@link AppUserRepository#updateMapColourScaleByUsername}), then
     * reads the row back for the response — the shape {@link #markComingUpSeen} records the reason
     * for. A whole-entity save here would write back the home it had loaded, undoing a postcode
     * saved in another tab a moment before.
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
        userRepository.updateMapColourScaleByUsername(auth.getName(), request.mapColourScale());
        AppUserEntity user = getUser(auth);
        LOG.info("User '{}' saved map colour preference: scale={}",
                user.getUsername(), request.mapColourScale());
        // null place name, matching saveHome: this save does not geocode, and the modal already
        // merges the previous placeName forward rather than losing it to a blanked field.
        return mapToResponse(user, null);
    }

    /**
     * Persists the caller's Map tab tide mode — Auto, Always or Off (map-mobile-sheet-plan.md M4).
     *
     * <p>Copies {@link #saveMapColourPreferences}'s shape exactly: its own endpoint because a tide
     * mode is not home-derived, so folding it into {@code saveHome} would deserialise the home
     * fields to null and wipe a saved postcode; a column-scoped write
     * ({@link AppUserRepository#updateMapTideModeByUsername}) so a whole-entity save elsewhere can
     * never discard it; then a read-back for the response.
     *
     * @param auth    the authenticated user
     * @param request the chosen mode
     * @return the updated user settings response
     * @throws ResponseStatusException 400 if {@code mapTideMode} is not "auto", "always" or "off"
     */
    @Transactional
    public UserSettingsResponse saveMapTideMode(Authentication auth, MapTideModeRequest request) {
        // Null-checked before the Set lookup, matching saveMapColourPreferences:
        // VALID_MAP_TIDE_MODES is Set.of(...), whose contains() throws NullPointerException on a
        // null argument rather than returning false — an omitted field would otherwise 500 instead
        // of the 400 a bad request deserves.
        if (request.mapTideMode() == null || !VALID_MAP_TIDE_MODES.contains(request.mapTideMode())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "mapTideMode must be 'auto', 'always' or 'off'");
        }
        userRepository.updateMapTideModeByUsername(auth.getName(), request.mapTideMode());
        AppUserEntity user = getUser(auth);
        LOG.info("User '{}' saved map tide mode: mode={}", user.getUsername(), request.mapTideMode());
        // null place name, matching saveMapColourPreferences: this save does not geocode.
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
                .orElseThrow(() -> userNotFound(auth));
    }

    private static NoSuchElementException userNotFound(Authentication auth) {
        return new NoSuchElementException("User not found: " + auth.getName());
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
                ForecastHorizon.civilDate(user.getComingUpLastSeenAt()),
                user.getMapTideMode());
    }
}
