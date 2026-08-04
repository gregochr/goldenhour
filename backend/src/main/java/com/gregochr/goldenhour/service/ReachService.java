package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ReachEntry;
import com.gregochr.goldenhour.util.GeoUtils;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Serves the caller's reach over the <em>whole</em> location roster — the per-user half of the
 * window-first Plan tab's spot strip.
 *
 * <h2>Why this is its own contract</h2>
 *
 * <p>Shared window content (rating, region, summary, scores) is already on {@code /api/briefing}.
 * Drive minutes and distances are not, and must never be: that path is ETag-revalidated, which
 * requires {@code Cache-Control: private, no-cache} and therefore persists the body to a browser
 * HTTP cache JavaScript cannot evict on logout. So the client fetches shared content from the
 * briefing, reach from here, and joins them on {@code locationId}. That join is a licensed
 * exception, not a licence for more.
 *
 * <h2>Why not one of the endpoints that already exists</h2>
 *
 * <p><b>Not {@code /drive-times}</b>, which {@code DailyBriefing} still consumes as a flat
 * id&rarr;minutes map and which carries no distance at all.
 *
 * <p><b>Not {@code close-to-home}</b>, which is wrong three ways for a lens. Its gate is a
 * <em>distance</em> radius while the design's control is drive-time tiers; a four-way instant
 * control over it would need one uncacheable request per tier; and its {@code qualifies()} gate
 * drops exactly the below-floor spots a drill-down's rating filter has to be able to show.
 *
 * <h2>The whole roster, deliberately</h2>
 *
 * <p>Not radius-gated. Reach is a lens over everything, whereas {@code localRadiusMiles} gates only
 * "Close to home". Gating here would make the payload silently decide what the lens is allowed to
 * reveal, and the widest tier ("Any") could then never show what it promises.
 *
 * <h2>Absence is "unknown", never "out of reach"</h2>
 *
 * <p>A caller with no home postcode is the <b>normal first-run state</b>, not a failure, and gets
 * the full roster with null figures rather than an empty list, a 204 or a {@code homeSet} flag. The
 * roster is what makes the client's join uniform: every location is present, and a null simply
 * means the drive line is not drawn. A second source of truth for "is a home set" could disagree
 * with {@code GET /api/user/settings}, which already answers that with {@code homePostcode} and
 * {@code driveTimesCalculatedAt}.
 */
@Service
public class ReachService {

    private final UserSettingsService userSettingsService;
    private final LocationService locationService;
    private final DriveTimeResolver driveTimeResolver;

    /**
     * Constructs a {@code ReachService}.
     *
     * @param userSettingsService supplies the caller's id and home coordinates
     * @param locationService     supplies the enabled roster
     * @param driveTimeResolver   supplies the caller's stored drive times
     */
    public ReachService(UserSettingsService userSettingsService,
            LocationService locationService,
            DriveTimeResolver driveTimeResolver) {
        this.userSettingsService = userSettingsService;
        this.locationService = locationService;
        this.driveTimeResolver = driveTimeResolver;
    }

    /**
     * Returns the caller's reach for every enabled location.
     *
     * <p>Ordered by {@code locationId} so the payload is stable across requests: the roster query
     * orders by name, and a rename would otherwise reshuffle a response whose content had not
     * changed.
     *
     * @param auth the authenticated caller
     * @return one entry per enabled location, in id order; never null, and never partial
     */
    public List<ReachEntry> getReach(Authentication auth) {
        UserSettingsService.HomeLocation home = userSettingsService.getHomeLocation(auth);
        Map<Long, Integer> minutesByLocation = driveTimeResolver.getAllMinutes(home.userId());
        boolean homeKnown = home.latitude() != null && home.longitude() != null;

        return locationService.findAllEnabled().stream()
                .filter(location -> location.getId() != null)
                .sorted(Comparator.comparing(LocationEntity::getId))
                .map(location -> new ReachEntry(
                        location.getId(),
                        minutesByLocation.get(location.getId()),
                        // Rounded to whole miles because that is the precision the spot card
                        // renders; publishing more would invite a second, more precise-looking
                        // figure onto a screen that already states this one.
                        homeKnown ? (int) Math.round(GeoUtils.distanceMiles(
                                home.latitude(), home.longitude(),
                                location.getLat(), location.getLon())) : null))
                .toList();
    }
}
