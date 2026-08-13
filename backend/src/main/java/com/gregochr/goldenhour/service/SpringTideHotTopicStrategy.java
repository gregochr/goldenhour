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
 * <p>A spring tide occurs around new and full moons when gravitational
 * alignment produces larger-than-normal tidal ranges. Suppressed entirely
 * when any (non-expired) king tide exists in the detection window — they are
 * redundant (a king tide is a stronger spring tide).
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
     * <p>Returns empty if a non-expired king tide exists anywhere in the window (king tide trumps
     * spring). Otherwise collects every spring-not-king day that still has a solar event ahead,
     * dates the pill to the earliest, communicates the full day range, and highlights the best
     * non-expired sunrise/sunset alignment. Returns empty when no briefing is cached or no
     * spring-tide day has a non-expired event.
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

        // King tide trumps spring tide anywhere in the window (a king tide is a stronger spring).
        boolean kingTideInWindow = sorted.stream()
                .anyMatch(d -> KingTideHotTopicStrategy.findKingTide(d) != null);
        if (kingTideInWindow) {
            return List.of();
        }

        List<BriefingDay> springCandidates = sorted.stream()
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
     * Whether this slot's date is a spring tide but not a king one — the lunar axis alone.
     *
     * <p>The height tests are deliberately gone. They asked whether the water at one port cleared a
     * percentile, which is a different question from whether the moon is at syzygy, and mixing them
     * meant a merely-big tide could be named "king" and an ordinary-sized spring tide could fail to
     * be named "spring" at all. See {@code KingTideHotTopicStrategy#isPerigeanSpring}.
     */
    private static boolean isSpringNotKing(BriefingSlot.TideInfo tide) {
        return tide != null && tide.lunarTideType() == LunarTideType.SPRING_TIDE;
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
