package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.SnowRows.PointDay;
import com.gregochr.goldenhour.util.GeoUtils;
import com.gregochr.goldenhour.util.PointSnowpack;
import com.gregochr.goldenhour.util.SnowSummits;
import com.gregochr.goldenhour.util.SnowSummits.Summit;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Detects the snow-on-the-fells hot topic: a white summit in view (snow-topics-truth-plan §3.3 rule 2).
 *
 * <p>A place is ringed on a day when its nearest <em>white</em> summit (a fresh SUNRISE summit row with modelled
 * snow water at or above {@code PointSnowpack.SUMMIT_WHITE_MM}) lies within {@value SnowSummits#VIEW_RADIUS_KM}
 * km great-circle, <em>and</em> the place's own sunrise low cloud is at most {@value #LOW_CLOUD_MAX_PCT} per cent
 * (tops in cloud cannot be seen; a missing value means the gate is not met). It fires whatever the place's own
 * ground, so it is independent of SNOW_FRESH/SNOW_MIST: an all-white place carries both. The place's own row
 * picks a variant (green below, white throughout, or unknown below when its snow water is null), and a day's one
 * topic takes the variant most of its ringed places hold. Makes no external API calls and no longer reads a
 * location's elevation or a freezing level.
 */
@Component
public class SnowTopsHotTopicStrategy implements HotTopicStrategy {

    /** Sunrise low cloud (per cent) above which the tops are taken to be hidden. */
    static final int LOW_CLOUD_MAX_PCT = 60;

    private static final String LABEL = "Snow on the fells";

    private static final String GREEN_DETAIL = "Tops white above green valleys";

    private static final String WHITE_DETAIL = "White from valley floor to summit";

    private static final String UNKNOWN_DETAIL = "Tops white";

    private static final String GREEN_DESCRIPTION =
            "Snow has fallen on the high ground: fell tops are capped with snow while valleys stay green."
                    + " A classic Lake District and Pennine composition.";

    private static final String WHITE_DESCRIPTION =
            "Snow from the valley floor to the summits: a white landscape with the fells standing out"
                    + " against the sky.";

    private static final String UNKNOWN_DESCRIPTION = "Fell tops are capped with snow.";

    /** Topic priority — act-on-it, sorts above the calendar heads-up topics. */
    private static final int PRIORITY = 3;

    /** The italic "where to shoot" cue on the enriched fact line. */
    private static final String TOPS_NOTE = "shoot from low ground looking up, before cloud builds";

    /** What the ground under a ringed place looks like, from the place's own row. */
    private enum Variant { GREEN_BELOW, WHITE_THROUGHOUT, UNKNOWN_BELOW }

    /** A place ringed on a day, with its nearest white summit and its variant. */
    private record Ringed(LocationEntity place, Summit summit, Variant variant) {
    }

    private final LocationRepository locationRepository;
    private final SnowStateReader snowStateReader;

    /**
     * Constructs a {@code SnowTopsHotTopicStrategy}.
     *
     * @param locationRepository the enabled location roster
     * @param snowStateReader    the fresh {@code snow_state} rows
     */
    public SnowTopsHotTopicStrategy(LocationRepository locationRepository, SnowStateReader snowStateReader) {
        this.locationRepository = locationRepository;
        this.snowStateReader = snowStateReader;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per day on which any place has a white summit in view and a clear enough sunrise (an
     * all-day condition, so no dawn cut-off), each dated to that day and carrying the ringed places' regions and
     * exact names. Returns empty when no summit is white or no place qualifies.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        return detect(fromDate, toDate, HotTopicInputs.ofSnow(null,
                locationRepository::findAllByEnabledTrueOrderByNameAsc,
                () -> snowStateReader.freshRows(fromDate, toDate)));
    }

    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate, HotTopicInputs inputs) {
        Map<PointDay, SnowStateEntity> rows = SnowRows.sunriseByPointDay(inputs.snowRows());
        Map<LocalDate, List<Summit>> whiteByDate = whiteSummits(rows, fromDate, toDate);
        if (whiteByDate.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, List<Ringed>> ringedByDate = new TreeMap<>();
        for (LocationEntity place : inputs.enabledLocations()) {
            String key = SnowRows.keyOf(place);
            if (key == null) {
                continue;
            }
            for (Map.Entry<LocalDate, List<Summit>> day : whiteByDate.entrySet()) {
                Summit nearest = nearestInView(place, day.getValue());
                SnowStateEntity own = rows.get(new PointDay(key, day.getKey()));
                if (nearest != null && ownSkyIsClear(own)) {
                    ringedByDate.computeIfAbsent(day.getKey(), d -> new ArrayList<>())
                            .add(new Ringed(place, nearest, variantOf(own)));
                }
            }
        }
        List<HotTopic> topics = new ArrayList<>();
        ringedByDate.forEach((date, ringed) -> topics.add(buildTopic(date, ringed)));
        return topics;
    }

    /** The white summits on each date in the window: a fresh SUNRISE summit row at or above 5 mm snow water. */
    private static Map<LocalDate, List<Summit>> whiteSummits(Map<PointDay, SnowStateEntity> rows,
            LocalDate fromDate, LocalDate toDate) {
        Map<LocalDate, List<Summit>> white = new TreeMap<>();
        for (Summit summit : SnowSummits.ALL) {
            for (Map.Entry<PointDay, SnowStateEntity> e : rows.entrySet()) {
                LocalDate date = e.getKey().date();
                if (summit.pointKey().equals(e.getKey().pointKey())
                        && !date.isBefore(fromDate) && !date.isAfter(toDate)
                        && e.getValue().getSnowWaterMm() != null
                        && e.getValue().getSnowWaterMm() >= PointSnowpack.SUMMIT_WHITE_MM) {
                    white.computeIfAbsent(date, d -> new ArrayList<>()).add(summit);
                }
            }
        }
        return white;
    }

    /** The place's nearest white summit when it lies within the view radius, else null. */
    private static Summit nearestInView(LocationEntity place, List<Summit> whiteSummits) {
        Summit nearest = null;
        double best = Double.MAX_VALUE;
        for (Summit summit : whiteSummits) {
            double km = GeoUtils.distanceKm(place.getLat(), place.getLon(), summit.lat(), summit.lon());
            if (km < best) {
                best = km;
                nearest = summit;
            }
        }
        return best <= SnowSummits.VIEW_RADIUS_KM ? nearest : null;
    }

    /** The visibility gate: the place's own sunrise low cloud is known and at most 60 per cent. */
    private static boolean ownSkyIsClear(SnowStateEntity own) {
        return own != null && own.getLowCloudPct() != null && own.getLowCloudPct() <= LOW_CLOUD_MAX_PCT;
    }

    private static Variant variantOf(SnowStateEntity own) {
        if (own.getSnowWaterMm() == null) {
            return Variant.UNKNOWN_BELOW;
        }
        return SnowRows.isLying(own) ? Variant.WHITE_THROUGHOUT : Variant.GREEN_BELOW;
    }

    /** A day's one topic: the majority variant (a tie goes green below, then white throughout). */
    private HotTopic buildTopic(LocalDate date, List<Ringed> ringed) {
        Variant variant = majorityVariant(ringed);
        String detail = switch (variant) {
            case GREEN_BELOW -> GREEN_DETAIL;
            case WHITE_THROUGHOUT -> WHITE_DETAIL;
            case UNKNOWN_BELOW -> UNKNOWN_DETAIL;
        };
        String description = switch (variant) {
            case GREEN_BELOW -> GREEN_DESCRIPTION;
            case WHITE_THROUGHOUT -> WHITE_DESCRIPTION;
            case UNKNOWN_BELOW -> UNKNOWN_DESCRIPTION;
        };
        Set<String> regions = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        // The roster arrives ordered by name, so the names and regions come out in a stable order.
        for (Ringed r : ringed) {
            LocationEntity place = r.place();
            if (place.getRegion() != null && place.getRegion().getName() != null) {
                regions.add(place.getRegion().getName());
            }
            if (place.getName() != null) {
                names.add(place.getName());
            }
        }
        return new HotTopic("SNOW_TOPS", LABEL, detail, date, PRIORITY, null,
                new ArrayList<>(regions), description, null)
                .withLocations(new ArrayList<>(names))
                .withScience(List.of(HotTopicFact.metric(null, commonestSummit(ringed).name() + " white")),
                        TOPS_NOTE);
    }

    private static Variant majorityVariant(List<Ringed> ringed) {
        Map<Variant, Integer> counts = new EnumMap<>(Variant.class);
        for (Ringed r : ringed) {
            counts.merge(r.variant(), 1, Integer::sum);
        }
        Variant winner = Variant.GREEN_BELOW;
        int best = -1;
        // Declaration order is the tie-break: green below, then white throughout, then unknown below.
        for (Variant v : Variant.values()) {
            int n = counts.getOrDefault(v, 0);
            if (n > best) {
                best = n;
                winner = v;
            }
        }
        return winner;
    }

    /** The summit most ringed places are nearest to (a tie goes to the earlier one in the reviewed list). */
    private static Summit commonestSummit(List<Ringed> ringed) {
        Map<Summit, Integer> counts = new HashMap<>();
        for (Ringed r : ringed) {
            counts.merge(r.summit(), 1, Integer::sum);
        }
        Summit winner = null;
        int best = 0;
        for (Summit s : SnowSummits.ALL) {
            int n = counts.getOrDefault(s, 0);
            if (n > best) {
                best = n;
                winner = s;
            }
        }
        return winner;
    }
}
