package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.AstroConditionsDto;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingDigestResponse;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ForecastEvaluationDto;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.util.Authorities;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Enforces a Lite reader's sub-region picks at the HTTP edge (docs/engineering/sub-regions-plan.md D6-D8,
 * §4.2, §5.3): every rating of a location outside the picks is removed from the response and the place is
 * marked {@code gated}, so the client never decides what is outside the picks and never counts a gated place
 * as unrated.
 *
 * <p><b>Who is shaped (D8, fail closed).</b> {@link #scopeFor} answers "unrestricted" only when enforcement
 * ({@code photocast.sub-regions.enforce}) is off, or the caller is <em>positively</em> PRO_USER or ADMIN.
 * Every other caller (LITE, an unknown role, no authentication) is shaped, with the locations of the
 * sub-regions the caller picked as the allowed set; a failed picks read, zero picks, an unregioned location
 * and a location with no sub-region all mean "not allowed". The scope is computed once per request, by the
 * controller, and passed to every method here.
 *
 * <p><b>Where it sits.</b> At the same controller edges as {@link BriefingRoleShaper}, after it and after the
 * live overlays ({@code BriefingService.getCachedBriefingForApi} applies {@code withLiveOverlays} before the
 * controller sees the briefing, so the hot topics are shaped here after they are recomputed). The internal
 * callers (Ask, the digest service, the pipeline) never pass through here and keep the full data. Inputs are
 * never mutated.
 *
 * <p><b>What stays free (§9 Q5, Q6).</b> Region- and window-level verdicts, confidences, labels and prose;
 * hot-topic labels, location names, facts and counts. Only the per-location stars, the region and window best
 * and mean stars, and the ratings embedded in a hot topic go.
 *
 * <p><b>Caching (§5.3, Q8).</b> A shaped body reveals the reader's picks, so it is served
 * {@code Cache-Control: no-store}: {@link #respond} for a {@code ResponseEntity}, {@link #markNoStore} for a
 * body returned bare. {@code ShallowEtagHeaderFilter} then adds no ETag. An unshaped body keeps today's
 * {@code private, no-cache} and its ETag.
 */
@Component
public class LitePickShaper {

    private static final Logger LOG = LoggerFactory.getLogger(LitePickShaper.class);

    /** The hot-topic type whose summary line, facts and glance are derived from the best-rated location. */
    static final String BLUEBELL = "BLUEBELL";

    /** Best first: the verdict tiers in the order the Plan tab ranks them. */
    private static final List<DisplayVerdict> TIER_ORDER = List.of(DisplayVerdict.WORTH_IT, DisplayVerdict.MAYBE,
            DisplayVerdict.UNRATED, DisplayVerdict.STAND_DOWN, DisplayVerdict.AWAITING);

    /**
     * Verdict-eligible first, then a region that HAS a mean before one that has none (its presence only,
     * never its value: the client skips a mean-less region, so the unshaped pick never lands on one), then
     * the tier, a region with slots before a blanked one, and the name.
     */
    private static final Comparator<BriefingRegion> LEAD_ORDER =
            Comparator.comparing((BriefingRegion r) -> !r.verdictEligible())
                    .thenComparing(r -> r.meanRating() == null)
                    .thenComparingInt(r -> tierRank(r.displayVerdict()))
                    .thenComparing(r -> r.slots().isEmpty())
                    .thenComparing(BriefingRegion::regionName, Comparator.nullsLast(Comparator.naturalOrder()));

    private final UserSubRegionService picks;
    private final SubRegionProperties properties;

    /**
     * Constructs the shaper.
     *
     * @param picks      reads a reader's picked locations
     * @param properties supplies the enforcement flag
     */
    public LitePickShaper(UserSubRegionService picks, SubRegionProperties properties) {
        this.picks = picks;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ who is shaped

    /**
     * Decides, once per request, which locations the caller's ratings may be served for.
     *
     * @param auth the caller's authentication (may be null)
     * @return {@link PickScope#unrestricted()} when enforcement is off or the caller is positively PRO_USER or
     *         ADMIN; otherwise a shaped scope holding the caller's picked locations (empty on any failure)
     */
    public PickScope scopeFor(Authentication auth) {
        if (!properties.isEnforce()) {
            return PickScope.unrestricted();
        }
        if (Authorities.hasRole(auth, UserRole.PRO_USER) || Authorities.hasRole(auth, UserRole.ADMIN)) {
            return PickScope.unrestricted();
        }
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            return PickScope.nothing();
        }
        try {
            return PickScope.of(picks.pickedLocationRows(auth.getName()));
        } catch (RuntimeException e) {
            // Fail closed: a reader whose picks cannot be read sees no ratings, never everyone's.
            LOG.warn("Could not read the sub-region picks; serving no ratings: {}", e.toString());
            return PickScope.nothing();
        }
    }

    // ------------------------------------------------------------------ caching

    /**
     * Wraps a body for a controller that returns a {@code ResponseEntity}: a shaped body is sent
     * {@code Cache-Control: no-store} (so the ETag filter adds no validator), an unshaped one is sent as it
     * always was.
     *
     * @param body  the (already shaped) body
     * @param scope the request's scope
     * @param <T>   the body type
     * @return a 200 response
     */
    public static <T> ResponseEntity<T> respond(T body, PickScope scope) {
        if (scope.shaped()) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Marks the current response {@code Cache-Control: no-store} when the scope is shaped — for the
     * controllers whose handlers return the body bare. Replaces the {@code private, no-cache} the ETag filter
     * preset (so the filter then adds no ETag). A no-op for an unshaped scope, and outside a web request.
     *
     * @param scope the request's scope
     */
    public static void markNoStore(PickScope scope) {
        if (!scope.shaped()) {
            return;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletResponse response = attrs.getResponse();
            if (response != null) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
            }
        }
    }

    // ------------------------------------------------------------------ the briefing

    /**
     * Shapes the briefing (already shaped for the role): every slot outside the picks is gated, every
     * unregioned slot is gated, the region and window best and mean stars go, the bluebell hot topic loses
     * its ratings, and the served {@code allowedLocationIds} is set.
     *
     * @param response the briefing, or null
     * @param scope    the request's scope
     * @return the response unchanged when the scope is unrestricted or the input null; else a shaped copy
     */
    public DailyBriefingResponse shapeBriefing(DailyBriefingResponse response, PickScope scope) {
        if (response == null || !scope.shaped()) {
            return response;
        }
        List<BriefingDay> days = response.days().stream().map(day -> shapeDay(day, scope)).toList();
        List<HotTopic> topics = response.hotTopics().stream().map(topic -> shapeTopic(topic, scope)).toList();
        return response.withDays(days).withPickScope(topics, scope.sortedIds());
    }

    private static BriefingDay shapeDay(BriefingDay day, PickScope scope) {
        return day.withEventSummaries(day.eventSummaries().stream()
                .map(es -> shapeEvent(es, scope)).toList());
    }

    private static BriefingEventSummary shapeEvent(BriefingEventSummary es, PickScope scope) {
        // The lead is chosen from the UNSHAPED regions (their verdict tiers are free, Q5) and by tier alone.
        String lead = shapedLeadRegion(es.regions());
        List<BriefingRegion> regions = es.regions().stream().map(region -> shapeRegion(region, scope)).toList();
        // An unregioned location can never be in a sub-region (D8), so none of these is ever allowed.
        List<BriefingSlot> unregioned = es.unregioned().stream().map(BriefingSlot::forGated).toList();
        BriefingEventSummary shaped = es.withRegions(regions).withUnregioned(unregioned)
                .withShapedLeadRegion(lead);
        // The window's verdict and confidence stay free (Q5); its best star is taken from every place.
        return shaped.window() == null ? shaped : shaped.withWindow(shapeWindow(shaped.window()));
    }

    private static BriefingWindow shapeWindow(BriefingWindow window) {
        BriefingWindow shaped = window.withBestRating(null);
        // PlanWindowProjector copies a topic's detail and facts into the window's badge, so the bluebell
        // badge carries the same best-site summary and "N/5" chips the topic itself loses below.
        if (window.badges().stream().anyMatch(b -> BLUEBELL.equals(b.type()))) {
            shaped = shaped.withBadges(window.badges().stream()
                    .map(b -> BLUEBELL.equals(b.type()) ? b.withoutFacts(dayLabelOnly(b.detail())) : b)
                    .toList());
        }
        return shaped;
    }

    private static BriefingRegion shapeRegion(BriefingRegion region, PickScope scope) {
        List<BriefingSlot> slots = region.slots().stream()
                .map(slot -> scope.allowsId(slot.locationId()) ? slot : slot.forGated()).toList();
        // The best, the mean and the delta are rebuildable from (or are) an unpicked place's star, so they go
        // from the whole region whatever the picks (§4.2); the verdict, label and prose stay (Q5).
        // picksBestRating is last: the one star the reader may see, over the region's own voting slots (the
        // canopy rule is applied to the FULL slot list, before any place is gated) restricted to allowed ids.
        return region.withSlots(slots).withBestRating(null).withMeanRating(null).withMeanRatingDelta(null)
                .withPicksBestRating(picksBestRating(region, scope));
    }

    private static Integer picksBestRating(BriefingRegion region, PickScope scope) {
        List<BriefingRatingStats.Entry> entries = BriefingSlot.votingSlots(region.slots()).stream()
                .filter(slot -> scope.allowsId(slot.locationId()))
                .map(slot -> new BriefingRatingStats.Entry(slot.locationName(), slot.claudeRating()))
                .toList();
        BriefingRatingStats.Stats stats = BriefingRatingStats.compute(entries, region.regionName(), null, null);
        return stats.isEmpty() ? null : stats.maxRating();
    }

    /**
     * The region a shaped window leads with, by verdict tier alone. It mirrors the eligibility of the client's
     * {@code pickTopEligibleRegion} (the canopy rule of {@code eligibleRegions}: a region with no sky slot is
     * dropped unless the whole window is canopy) and its order (verdict-eligible regions first), but never
     * orders by a mean or a star: it asks only whether a mean EXISTS (the client skips a mean-less region),
     * then ranks by tier, a region with slots before a blanked one, and the name.
     *
     * @param regions the event's unshaped regions
     * @return the lead region's name, or null when the event has no eligible region
     */
    static String shapedLeadRegion(List<BriefingRegion> regions) {
        List<BriefingSlot> all = regions.stream().flatMap(r -> r.slots().stream()).toList();
        boolean allCanopy = !all.isEmpty() && all.stream().allMatch(BriefingSlot::canopy);
        return regions.stream()
                .filter(r -> allCanopy || r.slots().isEmpty() || r.slots().stream().anyMatch(s -> !s.canopy()))
                .min(LEAD_ORDER)
                .map(BriefingRegion::regionName)
                .orElse(null);
    }

    private static int tierRank(DisplayVerdict verdict) {
        if (verdict == null) {
            return TIER_ORDER.size();
        }
        int index = TIER_ORDER.indexOf(verdict);
        return index < 0 ? TIER_ORDER.size() : index;
    }

    // ------------------------------------------------------------------ hot topics

    private static HotTopic shapeTopic(HotTopic topic, PickScope scope) {
        boolean bluebell = BLUEBELL.equals(topic.type());
        HotTopic shaped = topic;
        ExpandedHotTopicDetail expanded = topic.expandedDetail();
        if (expanded != null) {
            shaped = shaped.withExpandedDetail(shapeExpanded(expanded, scope, bluebell));
        }
        if (bluebell) {
            shaped = withoutBluebellAggregates(shaped);
        }
        return shaped;
    }

    private static ExpandedHotTopicDetail shapeExpanded(ExpandedHotTopicDetail expanded, PickScope scope,
            boolean bluebellTopic) {
        List<ExpandedHotTopicDetail.RegionGroup> groups = expanded.regionGroups() == null ? null
                : expanded.regionGroups().stream().map(group -> new ExpandedHotTopicDetail.RegionGroup(
                        group.regionName(), group.glossHeadline(), group.glossDetail(), group.verdict(),
                        shapeEntries(group.locations(), scope, bluebellTopic))).toList();
        ExpandedHotTopicDetail.BluebellMetrics metrics = expanded.bluebellMetrics() == null ? null
                : expanded.bluebellMetrics().withoutScore();
        return new ExpandedHotTopicDetail(groups, metrics, expanded.tideMetrics());
    }

    /**
     * A region's entries, shaped. The list is ranked best first, so the order of the gated entries would give
     * their ranking away: the allowed entries keep their order and come first, the gated ones follow by name,
     * and the "Best" badge (the region's top rating) is kept only when every entry in the region is allowed.
     */
    private static List<ExpandedHotTopicDetail.LocationEntry> shapeEntries(
            List<ExpandedHotTopicDetail.LocationEntry> entries, PickScope scope, boolean bluebellTopic) {
        if (entries == null) {
            return null;
        }
        List<ExpandedHotTopicDetail.LocationEntry> shaped = new ArrayList<>();
        List<ExpandedHotTopicDetail.LocationEntry> gated = new ArrayList<>();
        for (ExpandedHotTopicDetail.LocationEntry entry : entries) {
            boolean carriesRating = bluebellTopic || entry.bluebellLocationMetrics() != null;
            if (!carriesRating || scope.allowsName(entry.locationName())) {
                shaped.add(entry);
            } else {
                gated.add(entry.withoutBluebellRating());
            }
        }
        gated.sort(Comparator.comparing(ExpandedHotTopicDetail.LocationEntry::locationName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        shaped.addAll(gated);
        if (!gated.isEmpty()) {
            shaped = shaped.stream().map(e -> e.badge() == null ? e : e.withoutBadge()).toList();
        }
        return shaped;
    }

    /**
     * The bluebell topic's own line is derived from the best-rated location of the day, wherever it is: the
     * summary that opens {@code detail}, the "N/5 · label" and "N sites scoring 4+/5" facts, and the glance
     * chip that prints the first fact. They go from every shaped response (the picked places keep their own
     * per-location metrics in the expanded detail). {@code detail} is the best summary, a space, then a
     * one-word day label (see {@link #dayLabelOnly}).
     */
    private static HotTopic withoutBluebellAggregates(HotTopic topic) {
        HotTopic shaped = topic.withScience(null, topic.note());
        shaped = shaped.withDetail(dayLabelOnly(topic.detail()));
        GlanceLine glance = topic.glance();
        if (glance != null) {
            shaped = shaped.withGlance(new GlanceLine(null, null, glance.badge(), glance.weather()));
        }
        return shaped;
    }

    /**
     * The day label alone. A bluebell {@code detail} is the best site's summary, a space, then a one-word day
     * label ({@code BluebellHotTopicStrategy#isDayLabel}). Only a last word that really is a day label is
     * kept; a detail of any other shape yields an empty string, never the original text.
     *
     * @param detail the topic's or badge's detail (may be null)
     * @return the day label, or an empty string
     */
    static String dayLabelOnly(String detail) {
        if (detail == null) {
            return "";
        }
        String last = detail.substring(detail.lastIndexOf(' ') + 1);
        return BluebellHotTopicStrategy.isDayLabel(last) ? last : "";
    }

    // ------------------------------------------------------------------ the other endpoints

    /**
     * Shapes the digest: each window's best star goes (the pick, headline and place already go for Lite).
     *
     * @param digest the digest (already shaped for the role), or null
     * @param scope  the request's scope
     * @return the digest unchanged when unrestricted or null; else a copy without the best stars
     */
    public BriefingDigestResponse shapeDigest(BriefingDigestResponse digest, PickScope scope) {
        if (digest == null || !scope.shaped()) {
            return digest;
        }
        return new BriefingDigestResponse(digest.generatedAt(), digest.windows().stream()
                .map(BriefingDigestResponse.Window::withoutBestRating).toList());
    }

    /**
     * Shapes the merged evaluation views: a view outside the picks loses its rating fields and is gated.
     *
     * @param views the views (already shaped for the role)
     * @param scope the request's scope
     * @return the list unchanged when unrestricted; else a shaped copy
     */
    public List<LocationEvaluationView> shapeScores(List<LocationEvaluationView> views, PickScope scope) {
        if (!scope.shaped()) {
            return views;
        }
        return views.stream().map(view -> scope.allowsId(view.locationId()) ? view : view.forGated()).toList();
    }

    /**
     * Shapes one forecast list row, rich or sparse, past or future. The row names its location, not its id.
     *
     * @param row   the row
     * @param scope the request's scope
     * @return the row unchanged when allowed; else its gated copy
     */
    public ForecastListDto shapeListRow(ForecastListDto row, PickScope scope) {
        return scope.allowsName(row.locationName()) ? row : row.forGated();
    }

    /**
     * Shapes one by-id forecast evaluation. Ids are enumerable, so an id outside the picks is never served
     * the full evaluation.
     *
     * @param dto        the full evaluation for the role
     * @param locationId the id of the location the evaluation belongs to
     * @param scope      the request's scope
     * @return the evaluation unchanged when allowed; else its gated copy
     */
    public ForecastEvaluationDto shapeDetail(ForecastEvaluationDto dto, Long locationId, PickScope scope) {
        return scope.allowsId(locationId) ? dto : dto.forGated();
    }

    /**
     * Shapes one night's astro conditions at a location (sub-regions-plan §9 Q7).
     *
     * @param dto   the conditions
     * @param scope the request's scope
     * @return the conditions unchanged when allowed; else their gated copy
     */
    public AstroConditionsDto shapeAstro(AstroConditionsDto dto, PickScope scope) {
        return scope.allowsId(dto.locationId()) ? dto : dto.forGated();
    }
}
