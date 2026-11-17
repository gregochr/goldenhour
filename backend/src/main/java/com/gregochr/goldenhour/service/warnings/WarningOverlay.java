package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.BriefingWindow.WarningPlaceCount;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The serve-time weather-warning overlay (docs/engineering/weather-warnings-plan.md section 8): which Met Office
 * warnings touch a window or a place.
 *
 * <p>Follows the {@code bigSeas} precedent. It is computed per serve from the database, {@link WarningTimeline} (the
 * injected {@link Clock}, so a rewound admin sees the warnings as we had them) and {@link WarningCoverage}; it is
 * attached last and never stored, so {@code daily_briefing_cache} never carries it. Nothing here reads or writes a
 * rating, verdict, pick or count: the overlay only adds {@code warningIds} and the place counts. Simulated rows are
 * excluded for everyone. When the feature is off, or anything fails, the input is served unchanged (fail-soft: a
 * warning lookup must never take the briefing or the forecast list down).
 */
@Component
public class WarningOverlay {

    private static final Logger LOG = LoggerFactory.getLogger(WarningOverlay.class);

    private final WeatherWarningVersionRepository repository;
    private final WarningTimeline timeline;
    private final WarningCoverage coverage;
    private final LocationRepository locations;
    private final WarningsProperties properties;
    private final Clock clock;

    /**
     * Creates the overlay.
     *
     * @param repository the warning store
     * @param timeline   the active-at-T rule
     * @param coverage   the point-in-polygon matcher
     * @param locations  the location store
     * @param properties warnings configuration (the master flag)
     * @param clock      the injected clock (rewound for a rewound admin GET)
     */
    @Autowired
    public WarningOverlay(WeatherWarningVersionRepository repository, WarningTimeline timeline,
            WarningCoverage coverage, LocationRepository locations, WarningsProperties properties, Clock clock) {
        this.repository = repository;
        this.timeline = timeline;
        this.coverage = coverage;
        this.locations = locations;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * An overlay that is permanently off, for tests and call sites that predate the overlay: every method returns its
     * input unchanged and touches no collaborator.
     *
     * @return a disabled overlay
     */
    public static WarningOverlay disabled() {
        return new WarningOverlay(null, null, null, null, new WarningsProperties(), Clock.systemUTC());
    }

    /**
     * The warnings in force now that cover at least one enabled place, ready to answer per window and per place.
     *
     * @return the snapshot; empty when the feature is off, nothing is in force, or the lookup failed
     */
    public Snapshot snapshot() {
        if (!properties.isEnabled()) {
            return Snapshot.EMPTY;
        }
        try {
            Instant now = clock.instant();
            List<WarningTimeline.ActiveWarning> active =
                    timeline.active(repository.findAllVersionsOfWarningsValidAfter(now), false);
            if (active.isEmpty()) {
                return Snapshot.EMPTY;
            }
            List<WeatherWarningVersionEntity> versions = new ArrayList<>();
            for (WarningTimeline.ActiveWarning a : active) {
                versions.add(a.version());
            }
            List<LocationEntity> enabled = locations.findAllByEnabledTrueOrderByNameAsc();
            Map<String, Set<Long>> covered = coverage.locationsByWarning(versions, enabled);
            return build(versions, covered, enabled);
        } catch (RuntimeException e) {
            LOG.warn("Weather warning overlay skipped for this serve: {}", e.getMessage());
            return Snapshot.EMPTY;
        }
    }

    /**
     * Attaches {@code warningIds} and the place counts to every window of a served briefing. Serve-path only.
     *
     * @param response the fully assembled briefing (may be null)
     * @return the same response when there is nothing to add, otherwise a copy whose windows carry the overlay
     */
    public DailyBriefingResponse overlay(DailyBriefingResponse response) {
        if (response == null || !properties.isEnabled()) {
            return response;
        }
        Snapshot snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return response;
        }
        List<BriefingDay> days = new ArrayList<>();
        boolean changed = false;
        for (BriefingDay day : response.days()) {
            List<BriefingEventSummary> summaries = new ArrayList<>();
            boolean dayChanged = false;
            for (BriefingEventSummary summary : day.eventSummaries()) {
                BriefingEventSummary next = overlay(summary, snapshot);
                dayChanged |= next != summary;
                summaries.add(next);
            }
            days.add(dayChanged ? day.withEventSummaries(summaries) : day);
            changed |= dayChanged;
        }
        return changed ? response.withDays(days) : response;
    }

    private static BriefingEventSummary overlay(BriefingEventSummary summary, Snapshot snapshot) {
        BriefingWindow window = summary.window();
        if (window == null) {
            return summary;
        }
        LocalDateTime event = window.eventTime() != null ? window.eventTime() : summary.solarEventTime();
        if (event == null) {
            return summary;
        }
        Instant at = event.toInstant(ZoneOffset.UTC);
        List<String> ids = snapshot.idsAt(at);
        if (ids.isEmpty()) {
            return summary;
        }
        List<String> regionNames = new ArrayList<>();
        for (BriefingRegion region : summary.regions()) {
            regionNames.add(region.regionName());
        }
        return summary.withWindow(window.withWarnings(ids, snapshot.countsAt(at, regionNames)));
    }

    private static Snapshot build(List<WeatherWarningVersionEntity> versions, Map<String, Set<Long>> covered,
            List<LocationEntity> enabled) {
        Map<String, Integer> totalByRegion = new HashMap<>();
        Map<Long, String> regionOf = new HashMap<>();
        for (LocationEntity l : enabled) {
            if (l.getRegion() != null && l.getRegion().getName() != null) {
                regionOf.put(l.getId(), l.getRegion().getName());
                totalByRegion.merge(l.getRegion().getName(), 1, Integer::sum);
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (WeatherWarningVersionEntity v : versions) {
            Set<Long> ids = covered.getOrDefault(v.getWarningId(), Set.of());
            if (ids.isEmpty()) {
                continue;
            }
            Map<String, Integer> byRegion = new HashMap<>();
            for (Long id : ids) {
                String region = regionOf.get(id);
                if (region != null) {
                    byRegion.merge(region, 1, Integer::sum);
                }
            }
            entries.add(new Entry(v.getWarningId(), v.getValidFrom(), v.getValidTo(), Set.copyOf(ids), byRegion));
        }
        return new Snapshot(entries, totalByRegion);
    }

    /** One warning in force that covers at least one enabled place. */
    private record Entry(String warningId, Instant validFrom, Instant validTo, Set<Long> locationIds,
            Map<String, Integer> coveredByRegion) {

        /** The timeline only returns versions with a {@code validTo}, so only {@code validFrom} can be open. */
        boolean spans(Instant at) {
            return (validFrom == null || !at.isBefore(validFrom)) && !at.isAfter(validTo);
        }
    }

    /**
     * The warnings in force for one serve, queryable per instant and per place.
     */
    public static final class Snapshot {

        /** The snapshot with nothing in force. */
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());

        private final List<Entry> entries;
        private final Map<String, Integer> totalByRegion;

        private Snapshot(List<Entry> entries, Map<String, Integer> totalByRegion) {
            this.entries = entries;
            this.totalByRegion = totalByRegion;
        }

        /**
         * Whether nothing is in force.
         *
         * @return true when no warning covers an enabled place
         */
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /**
         * The ids of the warnings that span the instant, most severe first (the timeline's order).
         *
         * @param at the instant, normally a window's event time
         * @return the ids; empty when none
         */
        List<String> idsAt(Instant at) {
            Set<String> ids = new LinkedHashSet<>();
            for (Entry e : entries) {
                if (e.spans(at)) {
                    ids.add(e.warningId());
                }
            }
            return List.copyOf(ids);
        }

        /**
         * The ids of the warnings that cover one place and span the instant.
         *
         * @param locationId the place
         * @param at         the instant, normally that place's own event time
         * @return the ids, most severe first; empty when none or when the id is null
         */
        public List<String> idsFor(Long locationId, Instant at) {
            if (locationId == null || at == null) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (Entry e : entries) {
                if (e.locationIds().contains(locationId) && e.spans(at)) {
                    ids.add(e.warningId());
                }
            }
            return ids;
        }

        /**
         * How many enabled places each spanning warning covers, per named region.
         *
         * @param at          the instant
         * @param regionNames the regions the window lists
         * @return one count per warning and region with at least one covered place
         */
        List<WarningPlaceCount> countsAt(Instant at, List<String> regionNames) {
            List<WarningPlaceCount> out = new ArrayList<>();
            for (Entry e : entries) {
                if (!e.spans(at)) {
                    continue;
                }
                for (String region : new LinkedHashSet<>(regionNames)) {
                    int covered = e.coveredByRegion().getOrDefault(region, 0);
                    if (covered > 0) {
                        out.add(new WarningPlaceCount(e.warningId(), region, covered,
                                totalByRegion.getOrDefault(region, covered)));
                    }
                }
            }
            return out;
        }
    }
}
