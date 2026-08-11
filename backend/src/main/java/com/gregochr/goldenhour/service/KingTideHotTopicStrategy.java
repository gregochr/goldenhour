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
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.TideLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.TideMetrics;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects King Tide hot topics from the cached briefing triage data.
 *
 * <p>A king tide occurs when a new or full moon coincides with the moon's
 * closest approach to Earth (perigee), producing the strongest tidal forcing.
 * This happens only 5–10 times per year and warrants a high-priority alert
 * for coastal photography.
 *
 * <p>Reads from the briefing cache ({@link BriefingService#getCachedDays()})
 * so that the pill is consistent with what the heatmap grid and Best Bet show.
 * Falls back to empty when no briefing has been generated yet.
 */
@Component
public class KingTideHotTopicStrategy implements HotTopicStrategy {

    private static final String KING_TIDE_DESCRIPTION =
            "King tides occur when a new or full moon coincides with the moon's closest"
                    + " approach to Earth, producing exceptionally large tidal ranges."
                    + " Only happens 5\u201310 times per year — rare dramatic foreground at"
                    + " coastal locations.";

    /**
     * What a king tide day says for itself when its high water misses the light. Stated rather than
     * left silent because a king tide is worth knowing about either way — the water still covers
     * ground it normally does not.
     */
    static final String KING_UNALIGNED =
            "no tide alignment — but exceptional coastal foreground";

    /**
     * What a king tide day says when its water <em>did</em> line up with the light, but that light
     * has already been and gone. Distinct from {@link #KING_UNALIGNED} because the card still shows
     * the chart, the verdict and the aligned-day editorial line for that alignment — denying it
     * outright would restore the self-contradiction one branch further along.
     */
    static final String KING_ALIGNMENT_PASSED =
            "tide alignment already passed — but exceptional coastal foreground";

    private final BriefingService briefingService;
    private final LocationRepository locationRepository;
    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final SolarEventFreshness freshness;
    private final CoastalTideFactsBuilder coastalTideFactsBuilder;
    private final TideRunBuilder tideRunBuilder;

    /**
     * Constructs a {@code KingTideHotTopicStrategy}.
     *
     * @param briefingService              cached briefing data (injected lazily
     *                                     to break circular dependency)
     * @param locationRepository           repository for location lookups
     * @param forecastEvaluationRepository repository for tide alignment queries
     * @param freshness                    shared filter dropping solar events already past
     * @param coastalTideFactsBuilder      builds the enriched tide + sea-state fact line
     * @param tideRunBuilder               builds each day's row of the multi-day run
     */
    public KingTideHotTopicStrategy(@Lazy BriefingService briefingService,
            LocationRepository locationRepository,
            ForecastEvaluationRepository forecastEvaluationRepository,
            SolarEventFreshness freshness,
            CoastalTideFactsBuilder coastalTideFactsBuilder,
            TideRunBuilder tideRunBuilder) {
        this.briefingService = briefingService;
        this.locationRepository = locationRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.freshness = freshness;
        this.coastalTideFactsBuilder = coastalTideFactsBuilder;
        this.tideRunBuilder = tideRunBuilder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the cached briefing days for king tide candidates and emits one topic per date whose
     * sunrise or sunset is still ahead — each dated to that day, with that day's own tide-alignment
     * counts driving both its detail and its expanded card, so a multi-day king tide reads as an
     * adjacent run of day cards. Returns empty when no briefing has been cached yet.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<BriefingDay> days = briefingService.getCachedDays();
        if (days == null) {
            return List.of();
        }

        List<BriefingDay> kingCandidates = days.stream()
                .filter(d -> !d.date().isBefore(fromDate)
                        && !d.date().isAfter(toDate))
                .sorted(Comparator.comparing(BriefingDay::date))
                .filter(d -> findKingTide(d) != null)
                .toList();

        if (kingCandidates.isEmpty()) {
            return List.of();
        }

        List<LocationEntity> coastalLocations =
                locationRepository.findCoastalLocations();
        LocationEntity representative =
                coastalLocations.isEmpty() ? null : coastalLocations.get(0);
        List<String> coastalRegions = extractRegionNames(coastalLocations);

        // Built before the loop because the headline is now derived from it: the sentence and the
        // chart under it are one computation. See #alignmentInfo for what they used to be.
        //
        // Built from EVERY king-tide day in the window, not from the topics that survive the
        // solar-freshness filter below. A run describes the tide, which does not renumber itself
        // because a sunset has passed — numbering off the survivors made the chip count down
        // through the day (1/4 becoming 1/3 once Monday expired) and could migrate the "peak
        // range" verdict onto a day that is not the run's biggest.
        Map<LocalDate, TideRunDay> run = tideRunBuilder.build(
                kingCandidates.stream().map(BriefingDay::date).toList(), coastalLocations, true);

        List<HotTopic> topics = new ArrayList<>();
        for (BriefingDay candidate : kingCandidates) {
            LocalDate date = candidate.date();
            // Drop days whose sunrise and sunset have both already passed — advance notice only.
            Set<TargetType> nonExpired = nonExpiredEvents(date, representative, freshness);
            if (nonExpired.isEmpty()) {
                continue;
            }
            Map<TargetType, Long> counts = maskExpired(
                    parseTideAlignmentCounts(forecastEvaluationRepository, date), nonExpired);

            String alignmentInfo = alignmentInfo(
                    run.get(date), nonExpired, KING_UNALIGNED, KING_ALIGNMENT_PASSED);
            BriefingSlot.TideInfo kingTide = findKingTide(candidate);
            ExpandedHotTopicDetail expandedDetail = buildExpandedDetail(
                    coastalLocations, "King tide", kingTide.lunarPhase(), counts);

            HotTopic topic = new HotTopic(
                    "KING_TIDE",
                    "King tide",
                    buildKingTideDetail(alignmentInfo, coastalLocations.size()),
                    date,
                    1,
                    null,
                    coastalRegions,
                    KING_TIDE_DESCRIPTION,
                    expandedDetail);
            CoastalTideFactsBuilder.CoastalScience science =
                    coastalTideFactsBuilder.buildKing(candidate, coastalLocations);
            if (science != null) {
                topic = topic.withScience(science.facts(), science.note());
            }
            topics.add(topic);
        }
        return topics.stream().map(t -> t.withTideRun(run.get(t.date()))).toList();
    }

    /**
     * The detail line's alignment segment, taken from the day's own run row — the same geometry the
     * chart directly beneath it draws.
     *
     * <p><b>This used to be a different measurement entirely, and the card contradicted itself.</b>
     * The segment was a SQL count of {@code forecast_evaluation} rows whose {@code tide_aligned}
     * flag was set, and that flag does not mean "high water lands near the light" — it means the
     * tide <em>state</em> matched that location's own {@link com.gregochr.goldenhour.entity.TideType}
     * preference, inside a window sized as half the blue+golden hour span. So a king high water 39
     * minutes before sunrise counted for nothing at every location configured to shoot low water,
     * which on a king tide is most of them, and the pill read "no tide alignments" directly above a
     * chart stating {@code HW 04:58 · 39m before sunrise} and an editorial line that only renders on
     * an aligned day. Worse, the count could not distinguish "no location aligned" from "no rows
     * were written for this date" — and {@code forecast_evaluation} is now largely a triage table —
     * so missing data rendered as a positive claim about the tide.
     *
     * <p>A null run row is therefore answered with silence rather than a denial: no geometry means
     * nothing to say, and {@link #buildKingTideDetail} drops the segment.
     *
     * <p>Alignment with an event that has already passed is not advertised as a reason to go, but
     * neither is it denied — it gets its own {@code passed} wording. Collapsing it onto
     * {@code unaligned} would print "no tide alignment" above a chart still drawing that morning's
     * high water 39 minutes before sunrise, complete with the editorial line that renders only on an
     * aligned day: the same contradiction this method was written to remove, one branch further on.
     * The caller's {@code nonExpired} set is what distinguishes them, replacing the old
     * {@link #maskExpired} pass over the counts — one event at a time instead of one location.
     *
     * @param day        this date's run row, or null when no curve could be derived
     * @param nonExpired the solar event types still ahead on this date
     * @param unaligned  wording for a day whose water misses the light, or null for silence
     * @param passed     wording for an alignment that has already happened, or null for silence
     * @return the alignment segment, or null when nothing may be claimed
     */
    static String alignmentInfo(TideRunDay day, Set<TargetType> nonExpired, String unaligned,
            String passed) {
        if (day == null) {
            return null;
        }
        if (day.alignedEvent() == null) {
            return unaligned;
        }
        TargetType alignedWith = "sunrise".equals(day.alignedEvent())
                ? TargetType.SUNRISE : TargetType.SUNSET;
        return nonExpired.contains(alignedWith)
                ? "tide aligned with " + day.alignedEvent()
                : passed;
    }

    /**
     * Returns the first king tide info found in the given briefing day.
     *
     * @param day the briefing day to scan
     * @return first matching {@link BriefingSlot.TideInfo}, or null if none
     */
    static BriefingSlot.TideInfo findKingTide(BriefingDay day) {
        for (BriefingEventSummary event : day.eventSummaries()) {
            for (BriefingRegion region : event.regions()) {
                for (BriefingSlot slot : region.slots()) {
                    if (isKingTide(slot.tide())) {
                        return slot.tide();
                    }
                }
            }
            for (BriefingSlot slot : event.unregioned()) {
                if (isKingTide(slot.tide())) {
                    return slot.tide();
                }
            }
        }
        return null;
    }

    private static boolean isKingTide(BriefingSlot.TideInfo tide) {
        return tide != null
                && (tide.isKingTide()
                        || tide.lunarTideType() == LunarTideType.KING_TIDE);
    }

    /**
     * Returns the solar event types on this date whose event has not yet passed, judged from a
     * representative coastal location's sunrise/sunset. Shared by the king-tide and spring-tide
     * detectors so both surface a tide only on solar events the photographer can still shoot. A
     * null representative (no coastal locations) cannot be placed, so both events are treated as
     * ahead rather than suppressing the topic.
     *
     * @param date           the date to check
     * @param representative a coastal location whose horizon times the events, or null
     * @param freshness      the shared solar-event freshness filter
     * @return the still-ahead event types (may be empty)
     */
    static Set<TargetType> nonExpiredEvents(LocalDate date, LocationEntity representative,
            SolarEventFreshness freshness) {
        Set<TargetType> result = EnumSet.noneOf(TargetType.class);
        if (representative == null) {
            result.add(TargetType.SUNRISE);
            result.add(TargetType.SUNSET);
            return result;
        }
        if (freshness.isAhead(representative, date, TargetType.SUNRISE)) {
            result.add(TargetType.SUNRISE);
        }
        if (freshness.isAhead(representative, date, TargetType.SUNSET)) {
            result.add(TargetType.SUNSET);
        }
        return result;
    }

    /**
     * Returns a copy of the alignment counts with expired event types removed, so a tide aligned
     * only with a solar event that has already passed is not counted or advertised.
     *
     * @param counts     per-event alignment counts for a day
     * @param nonExpired the event types still ahead on that day
     * @return the counts restricted to non-expired event types
     */
    static Map<TargetType, Long> maskExpired(Map<TargetType, Long> counts,
            Set<TargetType> nonExpired) {
        Map<TargetType, Long> masked = new java.util.EnumMap<>(TargetType.class);
        for (Map.Entry<TargetType, Long> e : counts.entrySet()) {
            if (nonExpired.contains(e.getKey())) {
                masked.put(e.getKey(), e.getValue());
            }
        }
        return masked;
    }

    /**
     * Builds expanded detail with coastal locations grouped by region.
     *
     * @param coastalLocations     all enabled coastal locations
     * @param tidalClassification  "King tide" or "Spring tide"
     * @param lunarPhase           current moon phase name
     * @param alignmentCounts      tide alignment counts by target type
     * @return populated expanded detail
     */
    static ExpandedHotTopicDetail buildExpandedDetail(List<LocationEntity> coastalLocations,
            String tidalClassification, String lunarPhase,
            Map<TargetType, Long> alignmentCounts) {
        Map<String, List<LocationEntity>> byRegion = coastalLocations.stream()
                .filter(loc -> loc.getRegion() != null)
                .collect(Collectors.groupingBy(
                        loc -> loc.getRegion().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<RegionGroup> regionGroups = new ArrayList<>();
        for (Map.Entry<String, List<LocationEntity>> entry
                : byRegion.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
            List<LocationEntry> locations = entry.getValue().stream()
                    .sorted(Comparator.comparing(LocationEntity::getName))
                    .map(loc -> {
                        String tidePreference = loc.getTideType() != null
                                && !loc.getTideType().isEmpty()
                                ? loc.getTideType().iterator().next().name() : null;
                        return new LocationEntry(
                                loc.getName(), "Coastal", null, null,
                                new TideLocationMetrics(tidePreference));
                    })
                    .toList();
            regionGroups.add(new RegionGroup(
                    entry.getKey(), null, null, null, locations));
        }

        int sunriseCount = alignmentCounts
                .getOrDefault(TargetType.SUNRISE, 0L).intValue();
        int sunsetCount = alignmentCounts
                .getOrDefault(TargetType.SUNSET, 0L).intValue();

        return new ExpandedHotTopicDetail(
                regionGroups, null,
                new TideMetrics(tidalClassification, lunarPhase,
                        sunriseCount, sunsetCount));
    }

    /**
     * Parses the raw query result into a map of target type to alignment count.
     *
     * @param repository the forecast evaluation repository
     * @param date       the date to query
     * @return map of target type to count of aligned coastal locations
     */
    static Map<TargetType, Long> parseTideAlignmentCounts(
            ForecastEvaluationRepository repository, LocalDate date) {
        List<Object[]> rows = repository.countTideAlignedByTargetType(date);
        Map<TargetType, Long> counts = new java.util.EnumMap<>(TargetType.class);
        for (Object[] row : rows) {
            counts.put((TargetType) row[0], (Long) row[1]);
        }
        return counts;
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
     * Builds the single-day detail line for a king tide pill. The day is carried by the pill's
     * timing lead, so the detail states only the condition.
     *
     * <p>Format: {@code [alignmentInfo] · N coastal locations}
     *
     * @param alignmentInfo alignment info or {@code null}
     * @param coastalCount  total number of coastal locations
     * @return human-readable detail line
     */
    static String buildKingTideDetail(String alignmentInfo, int coastalCount) {
        StringBuilder sb = new StringBuilder();
        if (alignmentInfo != null) {
            sb.append(alignmentInfo).append(" \u00b7 ");
        }
        sb.append(coastalCount)
                .append(coastalCount == 1
                        ? " coastal location"
                        : " coastal locations");
        return sb.toString();
    }

    static String formatCatch(int count, String event) {
        return count == 1
                ? String.format("1 location catches %s", event)
                : String.format("%d locations catch %s", count, event);
    }

    static String formatCatchShort(int count, String event) {
        return count == 1
                ? String.format("1 catches %s", event)
                : String.format("%d catch %s", count, event);
    }

    static String formatLocationCount(int count) {
        return count == 1 ? "1 location" : count + " locations";
    }
}
