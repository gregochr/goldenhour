package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.model.comingup.ComingUpAction;
import com.gregochr.goldenhour.model.comingup.ComingUpCounts;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.PlanHorizon;
import com.gregochr.goldenhour.service.warnings.WarningService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merges the Met Office weather warnings in force into the served "Coming up" feed, per request, after the day
 * cache (weather-warnings-plan.md W3b).
 *
 * <p><strong>Never in the cached feed.</strong> {@link AlmanacService}'s feed is built once a day and is keyed by the
 * UK date, not by the serve epoch, so a warning that arrived or was cancelled a minute ago could not reach it. This
 * merge runs at the controller edge on every request (the {@code bigSeas} serve-time overlay precedent) from
 * {@link WarningService#served()}, which reads only the stored versions through the injected {@link Clock}
 * (rewound for a rewound admin GET), never includes a simulated row, and answers an empty, not-enabled response while
 * the feature is off. With the feature off, or nothing in force, the input is returned as the same instance, so the
 * body and its body-hashed ETag are byte-identical to a build without this class.
 *
 * <p><strong>Beyond the scored days.</strong> Plan and At a Glance own today and the next three days (the W3 lanes
 * and popup row). A warning gets a Coming up entry when its validity reaches past that window, on the UK civil
 * calendar, and starts within the feed's horizon. It is reported with its true span, never clipped. A warning wholly
 * inside the scored days has no entry here.
 *
 * <p><strong>Role-free.</strong> There is no role or user parameter: Lite and Pro read the same warning entries
 * (the two-question rule: "what is happening" is never gated), and nothing personal rides this shared payload. A
 * warning never touches a rating.
 *
 * <p><strong>Shape.</strong> An entry is {@code kind FORECAST} (it could still change, and it never earns a badge),
 * {@code family "warning"}, {@code type "weather-warning"}, with a stable id {@code weather-warning:<warningId>} (the
 * id does not carry the version, so a card the reader has opened stays open across an update). The facts the client
 * needs ride in {@code meta}; the client still checks them against its own 2-minute poll of {@code /api/warnings},
 * so a withdrawn warning leaves inside the licence's ten minutes whatever the feed was last fetched with.
 */
@Component
public class ComingUpWarningMerger {

    private static final Logger LOG = LoggerFactory.getLogger(ComingUpWarningMerger.class);

    /** The {@link ComingUpEntry#type()} of a warning entry. */
    public static final String TYPE = "weather-warning";

    /** The {@link ComingUpEntry#family()} of a warning entry (no filter chip of its own; found under All). */
    public static final String FAMILY = "warning";

    /** The printed tag on the card. */
    static final String KIND_TAG = "Met Office warning";

    /** The action kind of a door to the Plan tab. */
    static final String ACTION_PLAN = "plan";

    /** The action kind of the plain link to the Met Office page (the client draws the link itself). */
    static final String ACTION_MET_OFFICE = "met-office";

    /** Ordering rank on one day: an upgraded warning first, then the existing entries, then other warnings. */
    private static final int RANK_UPGRADED = 0;
    private static final int RANK_EXISTING = 1;
    private static final int RANK_OTHER_WARNING = 2;

    private final WarningService warningService;
    private final Clock clock;

    /**
     * Creates the merger.
     *
     * @param warningService the shared warnings view (stored versions, Clock-evaluated, simulated rows excluded)
     * @param clock          the injected clock; the rewound instant for a rewound admin GET
     */
    public ComingUpWarningMerger(WarningService warningService, Clock clock) {
        this.warningService = warningService;
        this.clock = clock;
    }

    /**
     * Adds the warning entries to a feed.
     *
     * @param feed the (possibly role-shaped) feed; may be null
     * @param days the requested horizon in days, clamped as {@link AlmanacService#getFeed(int)} clamps it
     * @return the same instance when the feature is off, nothing in force reaches past the scored days, or the
     *         lookup failed (fail-soft, logged at WARN); otherwise a copy with the entries and counts updated
     */
    public ComingUpResponse merge(ComingUpResponse feed, int days) {
        if (feed == null) {
            return null;
        }
        WarningsResponse served;
        try {
            served = warningService.served();
        } catch (RuntimeException e) {
            LOG.warn("Coming up warning entries skipped for this serve: {}", e.getMessage());
            return feed;
        }
        if (!served.enabled() || served.warnings() == null || served.warnings().isEmpty()) {
            return feed;
        }
        LocalDate today = ForecastHorizon.today(clock);
        LocalDate lastPlan = PlanHorizon.lastPlanDate(today);
        LocalDate horizonEnd = today.plusDays(Math.clamp(days, AlmanacService.MIN_DAYS, AlmanacService.MAX_DAYS) - 1L);
        List<ComingUpEntry> warningEntries = new ArrayList<>();
        for (WarningDto warning : served.warnings()) {
            ComingUpEntry entry = entryFor(warning, today, lastPlan, horizonEnd);
            if (entry != null) {
                warningEntries.add(entry);
            }
        }
        if (warningEntries.isEmpty()) {
            return feed;
        }
        return new ComingUpResponse(feed.builtFor(), feed.bands(), countsWith(feed.counts(), warningEntries.size()),
                ordered(feed.entries(), warningEntries));
    }

    private static ComingUpEntry entryFor(WarningDto warning, LocalDate today, LocalDate lastPlan,
            LocalDate horizonEnd) {
        // validTo is exclusive in effect: a warning ending exactly at UK midnight covers no minute of the new day, so
        // its last day is the day before (one nanosecond earlier), as in the BST and GMT cases alike.
        LocalDate end = warning.validTo() == null ? null
                : ForecastHorizon.civilDate(warning.validTo().minusNanos(1));
        if (warning.warningId() == null || end == null || !end.isAfter(lastPlan)) {
            return null;
        }
        LocalDate fromDate = ForecastHorizon.civilDate(warning.validFrom());
        LocalDate start = fromDate == null || fromDate.isAfter(end) ? today : fromDate;
        if (start.isAfter(horizonEnd)) {
            return null;
        }
        String title = title(warning);
        // The assembler's convention (start minus 89 days), never today: a warning is not an arrival.
        LocalDate entered = start.minusDays(AlmanacService.DEFAULT_DAYS - 1L);
        return new ComingUpEntry(start, end, AlmanacKind.FORECAST, TYPE, title, warning.headline(),
                meta(warning), List.of(), entered, TYPE + ":" + warning.warningId(), FAMILY, KIND_TAG,
                null, null, null, List.of(), null, null, null, action(start, lastPlan, today),
                null, true, null, List.of(), null, List.of(), List.of(), null);
    }

    /** "Amber warning of snow and wind", the phrase the client prints and the style guide asks for. */
    static String title(WarningDto warning) {
        String word = levelWord(warning.level());
        List<String> types = typeWords(warning.weatherTypes());
        if (types.isEmpty()) {
            return word + " warning";
        }
        String joined = types.size() == 1 ? types.get(0)
                : String.join(", ", types.subList(0, types.size() - 1)) + " and " + types.get(types.size() - 1);
        return word + " warning of " + joined;
    }

    private static String levelWord(String level) {
        if (level == null || level.isBlank()) {
            return "Weather";
        }
        String text = level.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static List<String> typeWords(List<String> types) {
        List<String> words = new ArrayList<>();
        if (types != null) {
            for (String type : types) {
                if (type != null && !type.isBlank()) {
                    words.add(type.trim().toLowerCase(Locale.ROOT).replaceAll("[_-]+", " "));
                }
            }
        }
        return words;
    }

    private static Map<String, String> meta(WarningDto warning) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("warningId", warning.warningId());
        putIfPresent(meta, "warningVersion", warning.warningVersion());
        putIfPresent(meta, "level", warning.level());
        putIfPresent(meta, "weatherTypes", warning.weatherTypes() == null ? null
                : String.join(",", warning.weatherTypes()));
        putIfPresent(meta, "validFrom", warning.validFrom() == null ? null : warning.validFrom().toString());
        putIfPresent(meta, "validTo", warning.validTo() == null ? null : warning.validTo().toString());
        meta.put("updated", Boolean.toString(warning.updated()));
        putIfPresent(meta, "upgradedFrom", warning.upgradedFrom());
        return meta;
    }

    private static void putIfPresent(Map<String, String> meta, String key, String value) {
        if (value != null && !value.isBlank()) {
            meta.put(key, value);
        }
    }

    /** A warning that reaches the scored days gets a door to the plan; one wholly beyond them the Met Office link. */
    private static ComingUpAction action(LocalDate start, LocalDate lastPlan, LocalDate today) {
        if (start.isAfter(lastPlan)) {
            return new ComingUpAction("Read it on the Met Office site →", ACTION_MET_OFFICE, null);
        }
        LocalDate date = start.isBefore(today) ? today : start;
        return new ComingUpAction("See the plan for " + date + " →", ACTION_PLAN, date);
    }

    private static ComingUpCounts countsWith(ComingUpCounts counts, int added) {
        if (counts == null) {
            return null;
        }
        Map<String, Integer> byFamily = new LinkedHashMap<>(counts.byFamily());
        byFamily.merge(FAMILY, added, Integer::sum);
        return new ComingUpCounts(counts.fixed(), counts.forecast() + added, byFamily);
    }

    /**
     * Chronological by start date, as the assembler sorts; on one day an upgraded warning leads, then the existing
     * entries in their own order, then the other warnings in the style guide's order. The sort is stable, so
     * neither the existing entries nor the warnings (already in the served order) are shuffled otherwise.
     */
    private static List<ComingUpEntry> ordered(List<ComingUpEntry> existing, List<ComingUpEntry> warnings) {
        List<ComingUpEntry> all = new ArrayList<>(existing.size() + warnings.size());
        all.addAll(existing);
        all.addAll(warnings);
        all.sort(Comparator.comparing(ComingUpEntry::startDate)
                .thenComparingInt(ComingUpWarningMerger::rank));
        return all;
    }

    private static int rank(ComingUpEntry entry) {
        if (!TYPE.equals(entry.type())) {
            return RANK_EXISTING;
        }
        return entry.meta().containsKey("upgradedFrom") ? RANK_UPGRADED : RANK_OTHER_WARNING;
    }
}
