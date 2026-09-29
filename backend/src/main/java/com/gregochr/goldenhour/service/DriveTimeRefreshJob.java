package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Nightly refresh of every user's per-location drive times.
 *
 * <p><strong>Why this exists.</strong> Drive times were only ever recalculated when a user pressed
 * the button in Settings, and a refresh replaces a user's whole set each time. So a location added
 * after someone's last refresh had no row for them, indefinitely — it simply rendered without a
 * {@code 🚗 NN min} chip and nothing indicated why. Observed in production: Hardwick Hall Country
 * Park and Houghall Woods had zero {@code user_drive_time} rows while their neighbours had one
 * each, because they were added after the last manual refresh.
 *
 * <p><strong>The manual button stays.</strong> This job is the routine path that stops the data
 * going stale on its own; the Settings button remains as the immediate, user-triggered override for
 * someone who has just moved house and does not want to wait for tonight. The two differ
 * deliberately in one respect: the manual path enforces a per-user cooldown to stop the button
 * being hammered, and this one does not — a nightly tick is not abuse, and applying the cooldown
 * here would make the job silently skip any user who happened to press the button that evening,
 * which is precisely the user whose data is most likely to matter.
 *
 * <p>Users with no saved home location are skipped: there is no origin to route from, and their
 * having no drive times is correct rather than stale.
 *
 * <p><strong>{@code driveTimesCalculatedAt} means "drive times were stored for the roster read at
 * this instant" — nothing less, and nothing else proves roster coverage.</strong> This job has
 * always kept that rule on its own route: the {@code driveTimes.isEmpty()} branch below stores
 * nothing and leaves the stamp exactly as it was, so a run that measures nothing never claims
 * coverage it does not have. {@code UserSettingsService.refreshDriveTimes} (the manual Settings
 * button) did not, until a P1 review of PR #942 found that its no-answer branch advanced the stamp
 * through a since-deleted {@code UserDriveTimeWriter.stampIfHomeUnchanged} purely to keep its own
 * 30-minute cooldown honest for a failed attempt — which let a user with a freshly-cleared postcode
 * and a failed measurement read as "covered" and be skipped by this job forever.
 *
 * <p>⚠️ **This job's own {@code isEmpty()} branch below deliberately does NOT distinguish the two
 * kinds of empty measurement the manual path does** — see {@link UserDriveTimeWriter}'s class
 * javadoc for the full outcome table across both routes. {@code DriveDurationService.measureForUser}
 * separates "no answer at all" from "ORS answered, confirmed no destination has a valid duration",
 * and the manual path clears a user's stored rows outright on the second kind, because a person
 * pressed the button and is looking at the result. This job runs unattended, overnight; a transient
 * ORS wobble that returns zero valid durations for one location's neighbourhood must not silently
 * wipe every enabled user's drive times before anyone notices — so BOTH kinds of empty are treated
 * identically here, as a failure that stores nothing and leaves the stamp exactly as it was. This is
 * a deliberate asymmetry between the two routes, not an oversight the manual-path fix should have
 * carried here too.
 *
 * <p><strong>A scheduled fire measures only the users who need it — a manual one measures
 * everyone.</strong> OpenRouteService is a free-plan call budget shared across every user, and
 * re-measuring an unchanged home against an unchanged roster every night spends it for no reason:
 * most nights every answer is identical to the one before. A user is due a refresh when their
 * {@code driveTimesCalculatedAt} stamp is {@code null} — which is exactly what a saved or moved
 * postcode leaves behind, see {@code UserSettingsService.saveHome} — or when the location roster
 * has grown since that stamp (so a location added after everyone's last refresh does not stay
 * unmeasured forever; see {@link #rosterGrewSince}). Every other user is skipped with no ORS call
 * at all, decided from one query for the roster's newest {@code created_at} rather than one query
 * per user. {@link DynamicSchedulerService#triggerNow} — the admin "Run now" button — measures
 * every enabled user with a home regardless, because it is the deliberate tool for the one thing
 * the stamp cannot detect on its own: a location's coordinates being corrected in place.
 *
 * <p><strong>A home can move under the run.</strong> The roster is read once, and each user is
 * then routed for seconds from the home as it was read, so a user who saves a new postcode tonight
 * can be measured from the old one. What was measured is stored through
 * {@link UserDriveTimeWriter#storeIfHomeUnchanged}, which writes nothing when the home has moved:
 * the save that moved it already discarded the old drive times and released the manual cooldown,
 * so that user is counted as superseded, not failed, and is measured afresh by the next run or by
 * their own press of the button. Until this was guarded, the job stored the old home's drive times
 * over that discard and then saved the whole user row it had read — putting the old home back.
 *
 * <p><strong>The stored stamp names when the roster was READ, not when the rows were written.</strong>
 * {@link DriveDurationService#measureForUser} reads the whole location table, then spends seconds
 * routing before this class stores the answer. A location created in that gap has a
 * {@code created_at} later than the moment the roster was read but earlier than a stamp taken only
 * once storing begins — so {@link #rosterGrewSince} would read that user as already covering it and
 * never measure it again until something else intervened (a postcode change, another location, or
 * an admin "Run now"). The instant captured immediately before {@code measureForUser} is what gets
 * stored, so a location created anywhere in the routing window is correctly newer than the stamp and
 * due again next time. {@code UserSettingsService.refreshDriveTimes} — the manual path, which writes
 * and is read by the identical stamp — takes its own instant the same way, immediately before its own
 * {@code measureForUser} call.
 *
 * <p><strong>A location's own {@code created_at} can still be earlier than the roster read that
 * missed it.</strong> {@code LocationEntity.createdAt} is assigned by the application when the
 * entity is built, before the row is saved — so there is a gap, however small, between that reading
 * and the row becoming visible to another connection's query. A roster read landing inside that gap
 * sees a table one location short, while the row it missed nonetheless carries a {@code created_at}
 * earlier than the stamp this class goes on to store for that read. See
 * {@link #ROSTER_VISIBILITY_MARGIN} for how {@link #rosterGrewSince} covers this without a new
 * schema — a widened comparison, not a guarantee the race is closed.
 */
@Service
public class DriveTimeRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(DriveTimeRefreshJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V133. */
    static final String JOB_KEY = "drive_time_refresh";

    /**
     * How far a location's {@code created_at} may sit BEFORE a user's stamp and still count as
     * "added after" it — covers the gap from the application assigning that field (the builder in
     * {@code LocationService.add}, before {@code save()}) to the row becoming visible to
     * {@link LocationRepository#findMaxCreatedAt} and {@link AppUserRepository#findAll} on another
     * connection.
     *
     * <p><strong>The real window is nowhere near an hour.</strong> {@code LocationService.add} is
     * not {@code @Transactional}; the only transaction wrapping the {@code INSERT} is
     * {@code JpaRepository.save()}'s own per-call one, so the row commits as soon as {@code save()}
     * returns — the gap between the {@code createdAt} assignment and the commit is one JDBC round
     * trip, not the tide fetch that follows {@code save()} (which runs after the row is already
     * committed and cannot lengthen this window). A Flyway migration seeding several locations by
     * raw SQL with {@code NOW()} (V84, V138, V143) runs its whole script in one transaction, so its
     * rows become visible together when that script commits — bounded by how long the script takes
     * to execute, which for these seed files is a small fraction of a second. An hour is chosen to
     * exceed either by a wide, deliberate margin, not because either is measured anywhere near it.
     *
     * <p><strong>The cost is bounded and stated precisely: at most one redundant re-measurement,
     * never a repeat.</strong> Widening the test only newly captures users whose stamp already sits
     * within this margin AFTER the location's {@code created_at} — a user due under the strict test
     * stays due regardless. Such a user is measured once more on the next scheduled run and never
     * again for that location, because the fresh stamp that run stores is then a full day ahead of
     * the location, clear of the margin. The common trigger is exactly the admin's own tool for this
     * class of problem: adding a location and pressing "Run now" within the hour — the manual run
     * measures everyone (correctly, since the row is already visible by then), but every one of
     * those fresh stamps still sits inside this margin of that same {@code created_at}, so the very
     * next scheduled run re-measures the whole roster once before settling.
     *
     * <p><strong>This narrows the race; it does not close it.</strong> An {@code INSERT} whose own
     * transaction stays open longer than this margin — a migration far larger than any seeded here,
     * or some future caller wrapping {@code LocationService.add} in a longer transaction — would
     * still be missed for as long as that transaction runs. The remedy for that residual case is the
     * same as for any location whose coordinates or roster membership changed outside this stamp's
     * reach: an admin's "Run now".
     */
    static final Duration ROSTER_VISIBILITY_MARGIN = Duration.ofHours(1);

    private final AppUserRepository userRepository;
    private final LocationRepository locationRepository;
    private final DriveDurationService driveDurationService;
    private final UserDriveTimeWriter driveTimeWriter;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param userRepository          source of users with a saved home location
     * @param locationRepository      supplies the roster's newest {@code created_at}, to decide
     *                                which users a scheduled fire needs to re-measure
     * @param driveDurationService    measures a user's drive times via ORS
     * @param driveTimeWriter         stores them, only while the home is still the one measured from
     * @param dynamicSchedulerService the DB-backed scheduler this job registers with
     * @param clock                   supplies the calculated-at stamp
     */
    public DriveTimeRefreshJob(AppUserRepository userRepository,
            LocationRepository locationRepository,
            DriveDurationService driveDurationService,
            UserDriveTimeWriter driveTimeWriter,
            DynamicSchedulerService dynamicSchedulerService,
            Clock clock) {
        this.userRepository = userRepository;
        this.locationRepository = locationRepository;
        this.driveDurationService = driveDurationService;
        this.driveTimeWriter = driveTimeWriter;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
    }

    /** Registers the nightly refresh with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerManualAwareJobTarget(JOB_KEY, this::run);
    }

    /**
     * Entry point the dynamic scheduler invokes — {@code true} from a manual "Run now" trigger,
     * {@code false} from the nightly cron fire. See the class javadoc for what each measures.
     *
     * @param manual whether this run was triggered manually rather than by the schedule
     */
    void run(boolean manual) {
        List<AppUserEntity> users = userRepository.findAll().stream()
                .filter(AppUserEntity::isEnabled)
                .filter(u -> u.getHomeLatitude() != null && u.getHomeLongitude() != null)
                .toList();

        if (users.isEmpty()) {
            LOG.info("Drive time refresh ({}): no enabled users with a home location — nothing "
                    + "to do", routeName(manual));
            return;
        }

        List<AppUserEntity> due = manual ? users : usersNeedingRefresh(users);
        int skippedUnchanged = users.size() - due.size();

        if (due.isEmpty()) {
            LOG.info("Drive time refresh ({}) complete — 0 refreshed, 0 superseded, 0 failed, "
                    + "{} skipped as unchanged, {} considered — no OpenRouteService calls made",
                    routeName(manual), skippedUnchanged, users.size());
            return;
        }

        int refreshed = 0;
        int superseded = 0;
        int failed = 0;
        int locationsWritten = 0;

        for (AppUserEntity user : due) {
            try {
                double originLat = user.getHomeLatitude();
                double originLon = user.getHomeLongitude();
                // Captured BEFORE the roster read inside measureForUser, and stored as this user's
                // stamp — never an instant taken after routing. See the class javadoc: a location
                // created while routing runs must be newer than the stamp, or rosterGrewSince can
                // never see it.
                Instant rosterReadAt = clock.instant();
                List<UserDriveTimeEntity> driveTimes = driveDurationService
                        .measureForUser(user.getId(), originLat, originLon)
                        .orElse(List.of());
                if (driveTimes.isEmpty()) {
                    // Zero is not an exception — ORS unconfigured, rate-limited, an empty response,
                    // OR a confirmed no-valid-duration answer are all treated identically here,
                    // deliberately NOT split the way the manual path splits them (see this class's
                    // own javadoc and UserDriveTimeWriter's outcome table): this job runs with
                    // nobody watching, so a transient ORS wobble must not clear a user's drive times
                    // overnight. Count it as a failure so the log distinguishes it from success, and
                    // store nothing, so the UI keeps showing the last real refresh. Their
                    // calculated-at stamp is untouched and stays null if it already was, so a
                    // never-measured user whose measurement keeps failing is retried on every
                    // scheduled run — acceptable, since nothing else would ever pick them up.
                    failed++;
                    LOG.warn("Drive time refresh measured no drive times for user {} — leaving the "
                            + "stored ones and their calculated-at stamp in place", user.getId());
                } else if (driveTimeWriter.storeIfHomeUnchanged(
                        user.getId(), originLat, originLon, driveTimes, rosterReadAt)) {
                    refreshed++;
                    locationsWritten += driveTimes.size();
                } else {
                    superseded++;
                    LOG.info("Drive time refresh for user {} discarded — the home moved while it "
                            + "was measured, and the save that moved it discarded the old drive "
                            + "times", user.getId());
                }
            } catch (Exception e) {
                failed++;
                LOG.warn("Drive time refresh failed for user {}: {}", user.getId(), e.getMessage());
            }
        }

        LOG.info("Drive time refresh ({}) complete — {} user(s) refreshed ({} location rows), "
                + "{} superseded by a home move, {} failed, {} skipped as unchanged, {} considered",
                routeName(manual), refreshed, locationsWritten, superseded, failed,
                skippedUnchanged, users.size());
    }

    private static String routeName(boolean manual) {
        return manual ? "manual" : "scheduled";
    }

    /**
     * Narrows the roster to users a scheduled fire actually needs to re-measure.
     *
     * <p>Reads the roster's newest {@code created_at} once — never per user — so a quiet night,
     * where nobody's postcode moved and nothing was added, costs one query and zero ORS calls.
     *
     * @param users enabled users with a saved home
     * @return the subset {@link #needsRefresh} accepts
     */
    private List<AppUserEntity> usersNeedingRefresh(List<AppUserEntity> users) {
        LocalDateTime newestCreatedAtUtc = locationRepository.findMaxCreatedAt();
        Instant newestLocationCreatedAt = newestCreatedAtUtc != null
                ? newestCreatedAtUtc.toInstant(ZoneOffset.UTC)
                : null;
        return users.stream()
                .filter(u -> needsRefresh(u, newestLocationCreatedAt))
                .toList();
    }

    /**
     * A user is due a refresh when their postcode has changed since it was last measured, or when
     * the location roster has grown since (see {@link #rosterGrewSince}) — both owner-approved
     * rules, 2026-09-29. {@code driveTimesCalculatedAt} is {@code null} exactly when the postcode
     * has changed: it is cleared the moment a home is saved or moved (see
     * {@code UserSettingsService.saveHome}) and was never set if drive times have not been measured
     * yet — so a null stamp covers both the "postcode changed" and the "never measured" case.
     *
     * <p><strong>The consequence accepted for this: a user whose home can never be routed to
     * anywhere gets no rows, ever, so their stamp stays null and every scheduled run measures them
     * again — one ORS call per night, indefinitely.</strong> That is deliberate, not a defect this
     * class fails to close: the stamp only ever advances together with at least one stored row (see
     * {@code UserSettingsService}'s class javadoc and {@link #run}, which stores nothing for an
     * empty measurement whatever its cause), so there is no other event to move a genuinely
     * unroutable user off this path. It matches this job's own behaviour from before the
     * skip-logic change (2026-09-29) — every user was measured every night then, this one still is
     * — so the cost is not new, only now confined to the users it actually applies to.
     *
     * @param user                      a candidate user
     * @param newestLocationCreatedAt   the roster's newest {@code created_at}, or {@code null} if
     *                                  the roster is empty
     * @return {@code true} if this user should be re-measured tonight
     */
    private boolean needsRefresh(AppUserEntity user, Instant newestLocationCreatedAt) {
        return user.getDriveTimesCalculatedAt() == null
                || rosterGrewSince(user.getDriveTimesCalculatedAt(), newestLocationCreatedAt);
    }

    /**
     * A location added to the roster since a user's last refresh must be measured for them too —
     * owner-approved, 2026-09-29. A location with no drive time passes every reach tier, so without
     * this rule a location added after everyone's last refresh would stay unmeasured, and therefore
     * unfiltered, forever. Kept as its own predicate, deliberately separate from
     * {@link #needsRefresh}'s postcode-change rule, so this one can be narrowed or dropped on its
     * own without touching that one.
     *
     * <p>{@code stamp} is compared as an instant — the wall-clock reading is a UTC one either way,
     * since {@code LocationEntity.createdAt} is written via {@code LocalDateTime.now(ZoneOffset.UTC)}
     * and {@code driveTimesCalculatedAt} is a zoned {@link Instant}.
     *
     * <p><strong>The boundary is inclusive, deliberately:</strong> {@code created_at} equal to the
     * stamp also counts as grown, not just strictly later. Two things make "later" alone the wrong
     * test. First, {@code LocationEntity.createdAt} is set by the application when the entity is
     * built, which can read a hair before the {@code INSERT} that makes the row visible to
     * {@link LocationRepository#findMaxCreatedAt} — so a location whose clock reading happens to tie
     * the stamp is at least as plausibly "created after" as "created before". Second, {@code stamp}
     * itself is the roster-READ instant (see the class javadoc), taken from the same kind of clock
     * read as {@code created_at} — so an exact tie is the one case with no way to know which side of
     * the boundary is true, and only the inclusive answer can never miss a location: it costs at
     * most one redundant measurement on the rare tie, where excluding it risks leaving a location
     * unmeasured indefinitely. A coincidental tie is reachable only when two independent clock reads
     * — a location's {@code created_at} and a user's roster-read stamp — land on the exact same
     * instant; even then it self-heals on the user's very next successful measurement, which takes a
     * fresh stamp a full day later and so cannot tie the same location's {@code created_at} again.
     *
     * <p><strong>{@link #ROSTER_VISIBILITY_MARGIN} widens the boundary further, the same direction
     * for the same reason.</strong> The tie above assumes the two clock reads happen to land on the
     * same instant; the margin instead accepts that a location's {@code created_at} can be earlier
     * than a stamp — by up to the margin — because the row that reading names was not yet visible
     * to the roster read that produced that stamp. See the constant's own javadoc for the real size
     * of that gap, why the margin chosen exceeds it by a wide deliberate multiple, and the bounded
     * cost of choosing too wide a margin rather than too narrow one.
     *
     * @param stamp                    the user's own {@code driveTimesCalculatedAt}, never null here
     * @param newestLocationCreatedAt  the roster's newest {@code created_at}, or {@code null} if the
     *                                 roster is empty
     * @return {@code true} if a location was added to (or ties, or falls within the margin before)
     *         the roster's state at the stamp
     */
    private boolean rosterGrewSince(Instant stamp, Instant newestLocationCreatedAt) {
        return newestLocationCreatedAt != null
                && !newestLocationCreatedAt.isBefore(stamp.minus(ROSTER_VISIBILITY_MARGIN));
    }
}
