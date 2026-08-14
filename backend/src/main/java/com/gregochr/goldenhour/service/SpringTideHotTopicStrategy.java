package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Detects Spring Tide hot topics from the cached briefing triage data.
 *
 * <p>A spring tide occurs around new and full moons when gravitational alignment produces
 * larger-than-normal tidal ranges. A day qualifies on <b>either</b> axis — the moon at syzygy, or
 * the water at this port above its own spring threshold — because a coastline's biggest water lags
 * syzygy by a day or two and a lunar-only test goes silent on exactly the days worth shooting. See
 * {@link #isSpringNotKing}.
 *
 * <p>A day that is itself a king tide emits nothing here, so <b>at most one tide topic of either
 * kind exists for any one date</b> and king wins. That suppression is per day: it used to empty the
 * entire window whenever one day in it was a king tide.
 *
 * <p>Surveys the whole forecast window and reports <em>every</em> spring-tide day that still has
 * a non-expired solar event, mirroring {@link KingTideHotTopicStrategy}: the pill communicates the
 * full day range and highlights the best tide/solar alignment, and drops solar events that have
 * already passed via {@link SolarEventFreshness}. Reads from the briefing cache
 * ({@link BriefingService#getCachedDays()}) so the pill is consistent with the heatmap grid.
 */
@Component
public class SpringTideHotTopicStrategy implements HotTopicStrategy {

    private static final String SPRING_TIDE_DESCRIPTION =
            "Spring tides happen around each new and full moon, producing the biggest"
                    + " tidal ranges. Higher water at coastal locations means more"
                    + " dramatic foreground and wave action.";

    /**
     * What a spring tide day says for itself when <em>neither</em> of its waters reaches the light.
     * Unlike a king tide it claims no consolation — a spring tide's draw is the swing between the
     * two, and with both of them in the dark there is nothing to offer in its place.
     */
    static final String SPRING_UNALIGNED =
            "no sunrise or sunset alignment, but good coastal foreground";

    /**
     * What a spring tide day says when a water <em>did</em> land in the light, but that light
     * has already passed. Kept distinct from {@link #SPRING_UNALIGNED} for the reason given on
     * {@link KingTideHotTopicStrategy#alignmentInfo}: the chart below still draws the alignment, so
     * denying it outright would have the two lines arguing.
     */
    static final String SPRING_ALIGNMENT_PASSED =
            "tide alignment already passed, but good coastal foreground";

    private final BriefingService briefingService;
    private final LocationRepository locationRepository;
    private final SolarEventFreshness freshness;
    private final CoastalTideFactsBuilder coastalTideFactsBuilder;
    private final TideRunBuilder tideRunBuilder;

    /**
     * Constructs a {@code SpringTideHotTopicStrategy}.
     *
     * @param briefingService              cached briefing data (injected lazily
     *                                     to break circular dependency)
     * @param locationRepository           repository for location lookups
     * @param freshness                    shared filter dropping solar events already past
     * @param coastalTideFactsBuilder      builds the enriched tide + sea-state fact line
     * @param tideRunBuilder               builds each day's row of the multi-day run
     */
    public SpringTideHotTopicStrategy(@Lazy BriefingService briefingService,
            LocationRepository locationRepository,
            SolarEventFreshness freshness,
            CoastalTideFactsBuilder coastalTideFactsBuilder,
            TideRunBuilder tideRunBuilder) {
        this.briefingService = briefingService;
        this.locationRepository = locationRepository;
        this.freshness = freshness;
        this.coastalTideFactsBuilder = coastalTideFactsBuilder;
        this.tideRunBuilder = tideRunBuilder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Drops any day that is itself a king tide — at most one tide topic of either kind is
     * emitted for a given date, and king wins, being the stronger claim about the same water. The
     * suppression is <b>per day, not per window</b>: a king tide on one date says nothing about a
     * spring tide three days later, and silencing the whole window for it deleted genuine cards.
     *
     * <p>Otherwise collects every spring-not-king day that still has a solar event ahead, dates the
     * pill to the earliest, communicates the full day range, and highlights the best non-expired
     * sunrise/sunset alignment. Returns empty when no briefing is cached or no spring-tide day has
     * a non-expired event.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<BriefingDay> days = briefingService.getCachedDays();
        if (days == null) {
            return List.of();
        }

        List<BriefingDay> sorted = days.stream()
                .filter(d -> !d.date().isBefore(fromDate) && !d.date().isAfter(toDate))
                .sorted(Comparator.comparing(BriefingDay::date))
                .toList();

        // A king tide trumps a spring tide ON ITS OWN DAY, and only there. This used to suppress
        // the whole window on a single king-tide day anywhere in it, so a king tide on the Tuesday
        // deleted the Friday's spring card — two different tidal events, one of them silenced by
        // the other's date. The invariant the suppression exists to hold is per-day ("at most one
        // tide topic on any one day"), so it is enforced per day; the comment here always claimed
        // as much.
        List<BriefingDay> springCandidates = sorted.stream()
                .filter(d -> KingTideHotTopicStrategy.findKingTide(d) == null)
                .filter(d -> findSpringTide(d) != null)
                .toList();
        if (springCandidates.isEmpty()) {
            return List.of();
        }

        List<LocationEntity> coastalLocations = locationRepository.findCoastalLocations();
        LocationEntity representative =
                coastalLocations.isEmpty() ? null : coastalLocations.get(0);
        List<String> coastalRegions = extractRegionNames(coastalLocations);

        // Built before the loop because the headline is derived from it — see
        // KingTideHotTopicStrategy#alignmentInfo for why it is no longer a count. Built from EVERY
        // spring-tide day, not the freshness-filtered survivors: a run describes the tide, which
        // does not renumber itself because a sunset has passed.
        Map<LocalDate, TideRunDay> run = tideRunBuilder.build(
                springCandidates.stream().map(BriefingDay::date).toList(), coastalLocations, false);

        List<HotTopic> topics = new ArrayList<>();
        for (BriefingDay day : springCandidates) {
            LocalDate date = day.date();
            // Drop spring-tide days whose sunrise and sunset have both already passed.
            Set<TargetType> nonExpired =
                    KingTideHotTopicStrategy.nonExpiredEvents(date, representative, freshness);
            if (nonExpired.isEmpty()) {
                continue;
            }
            KingTideHotTopicStrategy.Alignment alignmentInfo =
                    KingTideHotTopicStrategy.alignmentInfo(run.get(date), nonExpired,
                            SPRING_UNALIGNED, SPRING_ALIGNMENT_PASSED, coastalLocations.size());
            BriefingSlot.TideInfo springTide = findSpringTide(day);
            ExpandedHotTopicDetail expandedDetail =
                    KingTideHotTopicStrategy.buildExpandedDetail(
                            coastalLocations, "Spring tide", springTide.lunarPhase(),
                            KingTideHotTopicStrategy.rosterCounts(run.get(date), nonExpired));

            HotTopic topic = new HotTopic(
                    "SPRING_TIDE",
                    "Spring tide",
                    buildSpringTideDetail(alignmentInfo, coastalLocations.size()),
                    date,
                    2,
                    null,
                    coastalRegions,
                    SPRING_TIDE_DESCRIPTION,
                    expandedDetail);
            CoastalTideFactsBuilder.CoastalScience science =
                    coastalTideFactsBuilder.buildSpring(day, coastalLocations);
            if (science != null) {
                topic = topic.withScience(science.facts(), science.note());
            }
            topics.add(topic);
        }
        return topics.stream().map(t -> t.withTideRun(run.get(t.date()))).toList();
    }

    /**
     * Returns the first spring-but-not-king tide info found in the given day.
     *
     * @param day the briefing day to scan
     * @return first matching {@link BriefingSlot.TideInfo}, or null if none
     */
    static BriefingSlot.TideInfo findSpringTide(BriefingDay day) {
        for (BriefingEventSummary event : day.eventSummaries()) {
            for (BriefingRegion region : event.regions()) {
                for (BriefingSlot slot : region.slots()) {
                    if (isSpringNotKing(slot.tide())) {
                        return slot.tide();
                    }
                }
            }
            for (BriefingSlot slot : event.unregioned()) {
                if (isSpringNotKing(slot.tide())) {
                    return slot.tide();
                }
            }
        }
        return null;
    }

    /**
     * Whether this slot's day carries spring-sized water and is not a king tide.
     *
     * <p><b>Either axis qualifies, and dropping the height one is what emptied this pill.</b> The
     * lunar test asks whether the date is within a day of syzygy; the height test asks whether the
     * water at this port actually cleared its own {@code springTideThreshold}. They are not two
     * measurements of one thing. A port's biggest tide of the cycle lags syzygy by a day or two —
     * the age of the tide, a property of the coastline no epoch arithmetic recovers — so the lunar
     * window is 2–3 dates wide by construction and closes before the water arrives. Measured
     * against the August 2026 new moon (12 Aug 17:38 UTC) it covered 12–13 Aug while the roster's
     * biggest water ran 12–17 Aug and peaked on the 14th, so a lunar-only test showed nothing on
     * any day of that week's forecast window.
     *
     * <p>This is the same defect {@code TideSizeIndex} was written to fix on the "Coming up" feed,
     * arriving on the Plan tab hours later by the opposite route: the feed was moved off the moon
     * and onto the water, and this predicate was moved off the water and onto the moon. The two
     * surfaces then disagreed about the same week, which {@code TideSurfaceAgreementTest} now pins.
     *
     * <p><b>The height arm is not a weather signal, which is what made removing it look safe.</b>
     * It was defended on the grounds that a port can exceed its threshold "for reasons that are
     * weather rather than astronomy" — but {@code tide_extreme} is populated from WorldTides'
     * {@code extremes} endpoint, which is a harmonic prediction (see {@code TideService}: "tide
     * predictions are ... harmonic constants"). No surge, no weather. A height test on stored
     * extremes is therefore still an astronomical test — simply one taken at the coastline instead
     * of at the moon, which is the only place the age of the tide is visible.
     *
     * <p>⚠️ This does <em>not</em> reopen the height arm on the <b>king</b> label, which stays
     * lunar-only in {@code KingTideHotTopicStrategy#isPerigeanSpring}. King tide copy describes the
     * moon's closest approach, so naming a merely-large tide "king" is a false claim about a cause;
     * "spring" claims only that the water is big, which the height test measures directly.
     *
     * <p><b>The height arm is narrower here than on the feed, deliberately and upstream of this
     * method.</b> {@code BriefingSlotBuilder.calculateTideData} zeroes both height flags unless the
     * slot's tide state is HIGH <em>and</em> its nearest high water falls within ±90 minutes of the
     * solar event, whereas {@code TideSizeIndex} takes the day's maximum high water with no solar
     * gate at all. That is the right asymmetry: the feed answers "which dates carry big water" for
     * trip planning, while this pill sits on a sunrise or sunset row and should not fire for a
     * spring high water that lands at midnight. The consequence to know is that the two surfaces
     * can legitimately disagree on a run's <em>edge</em> days, where the big water has drifted out
     * of the light — they agree about the run, not about every day of it.
     */
    private static boolean isSpringNotKing(BriefingSlot.TideInfo tide) {
        if (tide == null || tide.lunarTideType() == LunarTideType.KING_TIDE) {
            return false;
        }
        return tide.lunarTideType() == LunarTideType.SPRING_TIDE
                || tide.heightAboveSpringThreshold();
    }

    private List<String> extractRegionNames(List<LocationEntity> locations) {
        return locations.stream()
                .map(LocationEntity::getRegion)
                .filter(Objects::nonNull)
                .map(RegionEntity::getName)
                .distinct()
                .toList();
    }

    /**
     * Builds the single-day detail line for a spring tide pill, mirroring the king-tide format:
     * {@code [alignmentInfo] · N coastal locations}. The day is carried by the pill's timing lead.
     *
     * @param alignmentInfo the alignment segment, whose text may be null to omit it entirely
     * @param coastalCount  total number of coastal locations
     * @return human-readable detail line
     */
    static String buildSpringTideDetail(KingTideHotTopicStrategy.Alignment alignmentInfo,
            int coastalCount) {
        StringBuilder sb = new StringBuilder();
        // Null text is silence, not a denial. The unaligned wording is supplied by the caller (see
        // KingTideHotTopicStrategy#alignmentInfo), so null reaches here only when no tide curve
        // could be derived at all — and "no sunrise or sunset alignment" would then be a claim
        // about the tide built from the absence of data about it. An aligned segment already names
        // the roster size, so appending it again would say "of 61 ... 61 coastal locations".
        if (alignmentInfo.text() != null) {
            if (alignmentInfo.carriesRosterSize()) {
                return alignmentInfo.text();
            }
            sb.append(alignmentInfo.text()).append(" · ");
        }
        sb.append(coastalCount)
                .append(coastalCount == 1 ? " coastal location" : " coastal locations");
        return sb.toString();
    }
}
