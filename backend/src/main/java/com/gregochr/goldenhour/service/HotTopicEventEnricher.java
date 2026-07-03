package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** Geographic centre of the UK — fallback for topics without a resolvable region location. */
    private static final double UK_CENTRE_LAT = 54.5;
    private static final double UK_CENTRE_LON = -2.5;

    private final SolarService solarService;
    private final LocationRepository locationRepository;

    /**
     * Constructs the enricher.
     *
     * @param solarService       computes sunrise/sunset/civil-dusk for a location and date
     * @param locationRepository resolves a representative location for a topic's region
     */
    public HotTopicEventEnricher(SolarService solarService, LocationRepository locationRepository) {
        this.solarService = solarService;
        this.locationRepository = locationRepository;
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
        if (topics == null || topics.isEmpty()) {
            return topics == null ? List.of() : topics;
        }
        List<LocationEntity> enabled = locationRepository.findAllByEnabledTrueOrderByNameAsc();
        return topics.stream().map(topic -> enrichOne(topic, enabled)).toList();
    }

    private HotTopic enrichOne(HotTopic topic, List<LocationEntity> enabled) {
        if (topic.eventType() != null || topic.date() == null) {
            return topic;
        }
        String eventType = resolveEventType(topic);
        if (eventType == null) {
            return topic;
        }
        double[] latLon = resolveLatLon(topic, enabled);
        String eventTime = computeEventTime(eventType, latLon[0], latLon[1], topic.date());
        return topic.withEvent(eventType, eventTime);
    }

    /**
     * Resolves the photographic event a topic is about. Tide topics follow whichever solar event
     * their tide aligns with (sunrise preferred on a tie); everything else uses the static policy.
     */
    private String resolveEventType(HotTopic topic) {
        if ("KING_TIDE".equals(topic.type()) || "SPRING_TIDE".equals(topic.type())) {
            return resolveTideEvent(topic);
        }
        return EVENT_BY_TYPE.get(topic.type());
    }

    private String resolveTideEvent(HotTopic topic) {
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
