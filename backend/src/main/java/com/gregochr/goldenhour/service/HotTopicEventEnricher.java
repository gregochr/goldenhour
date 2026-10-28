package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills in the photographic {@code eventType} and local {@code eventTime} on each hot topic so
 * the frontend pill can lead with "day + event + time" ("Sat sunrise · 04:43 — …") instead of a
 * vague relative word buried in the prose.
 *
 * <p>The event a topic is about is a property of its type — a cloud inversion is a sunrise shoot,
 * an aurora is an after-dark shoot — so the mapping lives here rather than being threaded through
 * all thirteen detection strategies. Tide topics are resolved dynamically from the sunrise/sunset
 * alignment counts the strategy already computed. Topics with no clear solar anchor (storm surge,
 * clearances) are left without an event.
 *
 * <p>The time is computed for the topic's own {@code date} at a representative location — the first
 * enabled location in one of the topic's regions, falling back to a UK-centre point so a sky-wide
 * phenomenon (aurora, NLC) still gets a concrete window time. Sunrise/sunset use the solar event
 * itself; {@code NIGHT} topics use civil dusk as the start of the usable window. Times are rendered
 * as Europe/London 24-hour {@code "HH:mm"} to match the plan header grid.
 */
@Service
public class HotTopicEventEnricher {

    /** Event kinds understood by the frontend lead. */
    private static final String SUNRISE = "SUNRISE";
    private static final String SUNSET = "SUNSET";
    private static final String NIGHT = "NIGHT";

    /** Static event policy for topic types with a fixed solar anchor. Tides resolve dynamically. */
    private static final Map<String, String> EVENT_BY_TYPE = Map.ofEntries(
            Map.entry("INVERSION", SUNRISE),
            // MIST names its own event per topic (sunrise AND sunset rows both count), so this
            // entry is only the fallback for a topic that carries none: the admin simulation.
            Map.entry("MIST", SUNRISE),
            Map.entry("DUST", SUNSET),
            Map.entry("SNOW_FRESH", SUNRISE),
            Map.entry("SNOW_MIST", SUNRISE),
            Map.entry("SNOW_TOPS", SUNRISE),
            Map.entry("BLUEBELL", SUNRISE),
            Map.entry("EQUINOX", SUNRISE),
            Map.entry("AURORA", NIGHT),
            Map.entry("NLC", NIGHT),
            Map.entry("METEOR", NIGHT),
            Map.entry("SUPERMOON", NIGHT));

    /**
     * Topic types whose detector stamps the event itself (it varies per topic, so no static policy
     * can name it). MIST and STORM_SURGE leave the clock time to this class; the two eclipses stamp
     * the time as well, so for them the {@code timeOnly} branch never fires. ⚠️ A non-day-scoped
     * type in neither this set nor {@link #EVENT_BY_TYPE} is dropped from every Plan card
     * ({@code PlanWindowProjector.keysFor}); {@code HotTopicWindowAnchorRosterTest} enforces it.
     */
    private static final Set<String> EVENT_STAMPED_BY_DETECTOR =
            Set.of("MIST", "STORM_SURGE", "ECLIPSE", "LUNAR_ECLIPSE");

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** Geographic centre of the UK — fallback for topics without a resolvable region location. */
    private static final double UK_CENTRE_LAT = 54.5;
    private static final double UK_CENTRE_LON = -2.5;

    private final SolarService solarService;
    private final LocationRepository locationRepository;
    private final SolarEventFreshness freshness;

    /**
     * Constructs the enricher.
     *
     * @param solarService       computes sunrise/sunset/civil-dusk for a location and date
     * @param locationRepository resolves a representative location for a topic's region
     * @param freshness          the shared filter dropping solar events already past — the same one
     *                           the tide strategies use to choose their wording, so the lead and
     *                           the headline cannot disagree about whether an event is still ahead
     */
    public HotTopicEventEnricher(SolarService solarService, LocationRepository locationRepository,
            SolarEventFreshness freshness) {
        this.solarService = solarService;
        this.locationRepository = locationRepository;
        this.freshness = freshness;
    }

    /**
     * Returns a copy of the list with {@code eventType} and {@code eventTime} populated where the
     * topic type has a solar anchor. Topics that already carry an event, or have no anchor, pass
     * through unchanged.
     *
     * @param topics the aggregated hot topics
     * @return a new list with the event fields filled in; never null
     */
    public List<HotTopic> enrich(List<HotTopic> topics) {
        return enrich(topics, HotTopicInputs.ofRosters(
                locationRepository::findCoastalLocations,
                locationRepository::findAllByEnabledTrueOrderByNameAsc,
                locationRepository::findByBortleClassIsNotNullAndEnabledTrue,
                locationRepository::findBluebellLocations));
    }

    /**
     * As {@link #enrich(List)}, reading the four location rosters from the pass's shared inputs so
     * rosters a strategy already loaded are not loaded again.
     *
     * @param topics the aggregated hot topics
     * @param inputs the pass's shared inputs
     * @return a new list with the event fields filled in; never null
     */
    public List<HotTopic> enrich(List<HotTopic> topics, HotTopicInputs inputs) {
        if (topics == null || topics.isEmpty()) {
            return topics == null ? List.of() : topics;
        }
        List<LocationEntity> enabled = inputs.enabledLocations();
        List<LocationEntity> coastal = inputs.coastalLocations();
        List<LocationEntity> darkSky = inputs.darkSkyLocations();
        List<LocationEntity> bluebell = inputs.bluebellLocations();
        return topics.stream()
                .map(topic -> enrichOne(topic, enabled, coastal, darkSky, bluebell))
                .toList();
    }

    /**
     * The fixed solar event the static policy gives a topic type.
     *
     * @param type the topic type
     * @return the event name, or null when the type has no fixed anchor
     */
    static String fixedEventFor(String type) {
        return EVENT_BY_TYPE.get(type);
    }

    /**
     * Whether a topic type's detector stamps its own event.
     *
     * @param type the topic type
     * @return true when the detector names the event
     */
    static boolean isStampedByDetector(String type) {
        return EVENT_STAMPED_BY_DETECTOR.contains(type);
    }

    private HotTopic enrichOne(HotTopic topic, List<LocationEntity> enabled,
            List<LocationEntity> coastal, List<LocationEntity> darkSky, List<LocationEntity> bluebell) {
        HotTopic result = topic;

        // Photographic event type + time — skip when already set, or the topic has no date/anchor.
        // A detector-stamped event with no time yet (MIST) keeps its event and gains the time.
        boolean timeOnly = topic.eventType() != null && topic.eventTime() == null
                && EVENT_STAMPED_BY_DETECTOR.contains(topic.type())
                && (SUNRISE.equals(topic.eventType()) || SUNSET.equals(topic.eventType()));
        if ((topic.eventType() == null || timeOnly) && topic.date() != null) {
            double[] latLon = resolveLatLon(topic, enabled);
            String eventType = timeOnly ? topic.eventType()
                    : resolveEventType(topic, latLon, topic.date());
            if (eventType != null) {
                result = result.withEvent(eventType,
                        computeEventTime(eventType, latLon[0], latLon[1], topic.date()));
            }
        }

        // Qualifying locations — the exact spots the map overlay opens to. Backfill only when the
        // detecting strategy didn't already provide them (slot-based topics carry precise sets).
        if (result.locationNames() == null) {
            List<String> names = qualifyingLocations(result, enabled, coastal, darkSky, bluebell);
            if (!names.isEmpty()) {
                result = result.withLocations(names);
            }
        }
        return result;
    }

    /**
     * The specific spots to show for a topic on the map: locations of the right kind — coastal for
     * tides/supermoon, dark-sky for aurora/NLC/meteor, bluebell woodland for bluebell, else any
     * enabled location — that sit in the topic's regions.
     *
     * @param topic    the topic to resolve locations for
     * @param enabled  all enabled locations
     * @param coastal  coastal locations
     * @param darkSky  dark-sky (Bortle-classified) locations
     * @param bluebell bluebell-woodland locations
     * @return the qualifying location names in the topic's regions; empty when none/regionless
     */
    private List<String> qualifyingLocations(HotTopic topic, List<LocationEntity> enabled,
            List<LocationEntity> coastal, List<LocationEntity> darkSky, List<LocationEntity> bluebell) {
        if (topic.regions() == null || topic.regions().isEmpty()) {
            return List.of();
        }
        Set<String> regions = new LinkedHashSet<>(topic.regions());
        List<LocationEntity> kind = switch (topic.type()) {
            case "KING_TIDE", "SPRING_TIDE", "SUPERMOON" -> coastal;
            case "AURORA", "NLC", "METEOR" -> darkSky;
            case "BLUEBELL" -> bluebell;
            default -> enabled;
        };
        return kind.stream()
                .filter(l -> l.getRegion() != null && regions.contains(l.getRegion().getName()))
                .map(LocationEntity::getName)
                .distinct()
                .toList();
    }

    /**
     * Resolves the photographic event a topic is about. Tide topics follow whichever solar event
     * their tide aligns with (sunrise preferred on a tie); everything else uses the static policy.
     */
    private String resolveEventType(HotTopic topic, double[] latLon, LocalDate date) {
        if ("KING_TIDE".equals(topic.type()) || "SPRING_TIDE".equals(topic.type())) {
            return resolveTideEvent(topic, latLon, date);
        }
        return EVENT_BY_TYPE.get(topic.type());
    }

    /**
     * The solar event a tide topic belongs to, taken from the run row's own geometry.
     *
     * <p><b>This is the null that cost three symptoms at once.</b> It used to read
     * {@code TideMetrics}' aligned counts, which came from {@code forecast_evaluation.tide_aligned}
     * — a column the synchronous engine writes and which in production is empty. Zero counts
     * returned null, and a null {@code eventType} means {@code PlanWindowProjector} silently drops
     * the topic: no badge on the window, no timing lead on the card, so a King tide pill asserted
     * an alignment while being unable to say which day it was for.
     *
     * <p>Reading {@link TideRunDay#alignedEvent()} puts the badge, the lead, the headline and the
     * chart on one measurement. The counts remain as a fallback for a topic with no run row.
     *
     * @param topic the tide topic
     * @return {@code "SUNRISE"}, {@code "SUNSET"}, or null when nothing could be measured
     */
    private String resolveTideEvent(HotTopic topic, double[] latLon, LocalDate date) {
        TideRunDay run = topic.tideRun();
        if (run != null) {
            // The run row is AUTHORITATIVE once it exists — a null alignedEvent means the tide
            // misses the light, and must not fall through to the tally. Falling through was safe
            // only while the tally came from an empty column; now that it is computed live, a
            // representative-unaligned day with 40 roster locations aligned resolved an event
            // anyway, printing a sunrise lead and a window badge directly above the headline's
            // own "no tide alignment".
            if (run.alignedEvent() == null) {
                return null;
            }
            String event = "sunrise".equals(run.alignedEvent()) ? SUNRISE : SUNSET;
            // The tally is clock-free, so alignedEvent still reads "sunrise" at 14:00. The headline
            // says "tide alignment already passed" for exactly this state; a lead and a badge
            // pointing at that morning would contradict it and send a reader to an event gone.
            return freshness.isAhead(eventTimeUtc(event, latLon, date)) ? event : null;
        }
        ExpandedHotTopicDetail expanded = topic.expandedDetail();
        if (expanded == null || expanded.tideMetrics() == null) {
            return null;
        }
        int sunrise = expanded.tideMetrics().sunriseAlignedCount();
        int sunset = expanded.tideMetrics().sunsetAlignedCount();
        if (sunrise == 0 && sunset == 0) {
            return null;
        }
        return sunrise >= sunset ? SUNRISE : SUNSET;
    }

    private double[] resolveLatLon(HotTopic topic, List<LocationEntity> enabled) {
        List<String> regions = topic.regions();
        if (regions != null && !regions.isEmpty()) {
            for (LocationEntity loc : enabled) {
                if (loc.getRegion() != null && regions.contains(loc.getRegion().getName())) {
                    return new double[] {loc.getLat(), loc.getLon()};
                }
            }
        }
        return new double[] {UK_CENTRE_LAT, UK_CENTRE_LON};
    }

    /**
     * The event's instant in UTC, or null when the type has no solar anchor.
     *
     * @param eventType SUNRISE, SUNSET or NIGHT
     * @param latLon    the topic's anchor
     * @param date      the topic's date
     * @return the UTC instant, or null
     */
    private LocalDateTime eventTimeUtc(String eventType, double[] latLon, LocalDate date) {
        return switch (eventType) {
            case SUNRISE -> solarService.sunriseUtc(latLon[0], latLon[1], date);
            case SUNSET -> solarService.sunsetUtc(latLon[0], latLon[1], date);
            case NIGHT -> solarService.civilDuskUtc(latLon[0], latLon[1], date);
            default -> null;
        };
    }

    private String computeEventTime(String eventType, double lat, double lon, LocalDate date) {
        LocalDateTime utc = switch (eventType) {
            case SUNRISE -> solarService.sunriseUtc(lat, lon, date);
            case SUNSET -> solarService.sunsetUtc(lat, lon, date);
            case NIGHT -> solarService.civilDuskUtc(lat, lon, date);
            default -> null;
        };
        if (utc == null) {
            return null;
        }
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).toLocalTime().format(HH_MM);
    }
}
