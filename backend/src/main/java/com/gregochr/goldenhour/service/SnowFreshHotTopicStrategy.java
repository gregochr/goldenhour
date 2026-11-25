package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.SnowRows.PointDay;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Detects the two snow-underfoot hot topics (SNOW_FRESH and SNOW_MIST) from each place's own modelled
 * snowpack ({@code snow_state}, snow-topics-truth-plan §3.3 rule 1).
 *
 * <p>A place has snow lying when its own SUNRISE row is fresh and its modelled snow water is at or above
 * {@code PointSnowpack.LYING_MM}, at any height: a snowy summit viewpoint is "snow underfoot". A missing, stale
 * or null row means unknown, so the place gets no topic (null never fires). Lying places whose valley mist band is
 * {@code LIKELY} at that sunrise ({@link MistHotTopicStrategy#isLikely}, the same rule as the MIST topic) become
 * SNOW_MIST; a place with no slot row is not misty, so it stays SNOW_FRESH. Humidity no longer decides mist (see
 * {@code docs/engineering/snow-mist-valley-band-plan.md}). Makes no external API calls.
 */
@Component
public class SnowFreshHotTopicStrategy implements HotTopicStrategy {

    private static final String FRESH_DESCRIPTION =
            "Overnight snowfall transforms familiar landscapes. Low temperatures mean the snow"
                    + " holds — fresh white ground is a rare opportunity.";

    private static final String MIST_DESCRIPTION =
            "Fresh snow combined with mist creates ethereal conditions. Trees and landmarks"
                    + " emerge from fog against a white landscape — among the most dramatic"
                    + " photography conditions possible.";

    private static final String FROST_DESCRIPTION =
            "A clear, calm, freezing night over lying snow: hoar frost feathers every twig and blade,"
                    + " and mist pools on the valley floor. Where the mist thickens it freezes on contact"
                    + " as rime. It's densest before dawn and goes as the sun warms the ground, so first"
                    + " light is the window.";

    private static final String FRESH_DETAIL_FELL = "Fresh snow overnight";

    private static final String FRESH_DETAIL_LYING = "Snow lying";

    private static final String MIST_DETAIL_FELL = "Fresh snow overnight with mist — exceptional conditions";

    private static final String MIST_DETAIL_LYING = "Snow lying with mist — exceptional conditions";

    private static final String FROST_DETAIL = "Freezing mist and frost over lying snow";

    private static final String FRESH_LABEL = "Fresh snow";

    private static final String MIST_LABEL = "Fresh snow with mist";

    private static final String FROST_LABEL = "Snow, mist & frost";

    /** Topic priority for plain fresh snow — act-on-it, sorts above the calendar heads-ups. */
    private static final int PRIORITY_FRESH = 2;

    /** Topic priority when mist co-occurs — the standout condition, ranks above plain fresh snow. */
    private static final int PRIORITY_MIST = 1;

    /** Snow that fell in the last 24 h counts as "fresh" from this many centimetres. */
    static final double FRESH_FELL_CM = 1.0;

    /** The italic cue for plain fresh snow. */
    private static final String FRESH_NOTE = "clean at first light, before wind and footprints";

    /** The italic cue when the mist sits over sub-zero snow (frost likely). */
    private static final String FROST_NOTE = "densest before dawn — frost on every branch";

    /** The italic cue when mist sits over snow but the air is not (provably) sub-zero. */
    private static final String MIST_NOTE = "mist over lying snow — soft, flat light";

    private final SlotSignalReader slotSignalReader;
    private final LocationRepository locationRepository;
    private final SnowStateReader snowStateReader;

    /** One place with snow lying on one sunrise; {@code mist} is its LIKELY-band slot row, or null. */
    private record Lying(LocationEntity place, SnowStateEntity row, SlotSignals mist) {
    }

    /**
     * Constructs a {@code SnowFreshHotTopicStrategy}.
     *
     * @param slotSignalReader   the unified slot read model (valley mist band and temperature readings)
     * @param locationRepository the enabled location roster
     * @param snowStateReader    the fresh {@code snow_state} rows
     */
    public SnowFreshHotTopicStrategy(SlotSignalReader slotSignalReader, LocationRepository locationRepository,
            SnowStateReader snowStateReader) {
        this.slotSignalReader = slotSignalReader;
        this.locationRepository = locationRepository;
        this.snowStateReader = snowStateReader;
    }

    /**
     * Returns true when the row is a sunrise whose valley mist band is {@code LIKELY}, through
     * {@link MistHotTopicStrategy#isLikely} so SNOW_MIST and the MIST topic share one rule. SNOW_MIST
     * is a first-light topic, so a sunset row never counts; FOGBOUND, POSSIBLE, NONE and an absent
     * band (an unflagged site, a scored-null row) are not misty. Humidity is not consulted.
     *
     * @param signals the slot's composite
     * @return true if the mist signal is present
     */
    static boolean isMisty(SlotSignals signals) {
        return signals.eventType() == TargetType.SUNRISE && MistHotTopicStrategy.isLikely(signals);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds from each place's own SUNRISE snow row and emits <em>one topic per day</em>, each carrying that
     * day's regions and the exact places with snow lying in {@code locationNames}, so the map rings only them.
     * Snow is an all-day condition, so there is no dawn cut-off: today's topic stays until midnight. Per day, the
     * lying places whose valley mist band is {@code LIKELY} yield a SNOW_MIST topic (higher priority, mist-aware
     * label and description); the remaining lying places that day stay a SNOW_FRESH topic. SNOW_MIST topics come
     * first. Returns empty when no place has snow lying in the window.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        return detect(fromDate, toDate, HotTopicInputs.ofSnow(
                () -> slotSignalReader.read(fromDate, toDate),
                locationRepository::findAllByEnabledTrueOrderByNameAsc,
                () -> snowStateReader.freshRows(fromDate, toDate)));
    }

    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate, HotTopicInputs inputs) {
        Map<String, LocationEntity> placesByKey = new HashMap<>();
        for (LocationEntity place : inputs.enabledLocations()) {
            String key = SnowRows.keyOf(place);
            if (key != null) {
                placesByKey.put(key, place);
            }
        }
        List<Map.Entry<PointDay, SnowStateEntity>> lyingRows = SnowRows.sunriseByPointDay(inputs.snowRows())
                .entrySet().stream()
                .filter(e -> placesByKey.containsKey(e.getKey().pointKey()))
                .filter(e -> !e.getKey().date().isBefore(fromDate) && !e.getKey().date().isAfter(toDate))
                .filter(e -> SnowRows.isLying(e.getValue()))
                .sorted(Comparator.comparing((Map.Entry<PointDay, SnowStateEntity> e) -> e.getKey().date())
                        .thenComparing(e -> Objects.toString(placesByKey.get(e.getKey().pointKey()).getName(), "")))
                .toList();
        if (lyingRows.isEmpty()) {
            return List.of();
        }

        Map<PointDay, SlotSignals> mistySignals = new HashMap<>();
        for (SlotSignals s : inputs.slotSignals()) {
            String key = SnowRows.keyOf(s.location());
            if (key != null && isMisty(s)) {
                mistySignals.putIfAbsent(new PointDay(key, s.date()), s);
            }
        }

        Map<LocalDate, List<Lying>> mistyByDate = new TreeMap<>();
        Map<LocalDate, List<Lying>> plainByDate = new TreeMap<>();
        for (Map.Entry<PointDay, SnowStateEntity> e : lyingRows) {
            LocationEntity place = placesByKey.get(e.getKey().pointKey());
            SlotSignals mist = mistySignals.get(e.getKey());
            // A (place, day) is exactly one of the two: a misty place is never also a plain fresh one.
            Map<LocalDate, List<Lying>> target = mist != null ? mistyByDate : plainByDate;
            target.computeIfAbsent(e.getKey().date(), d -> new ArrayList<>())
                    .add(new Lying(place, e.getValue(), mist));
        }

        List<HotTopic> topics = new ArrayList<>();
        mistyByDate.forEach((date, rows) -> topics.add(buildMistTopic(date, rows)));
        plainByDate.forEach((date, rows) -> topics.add(buildFreshTopic(date, rows)));
        return topics;
    }

    /** A day's plain fresh-snow topic: the exact lying places, with the fresh-snow fact line. */
    private HotTopic buildFreshTopic(LocalDate date, List<Lying> rows) {
        double freshCm = maxFreshCm(rows);
        boolean fell = freshCm >= FRESH_FELL_CM;
        return new HotTopic("SNOW_FRESH", FRESH_LABEL, fell ? FRESH_DETAIL_FELL : FRESH_DETAIL_LYING,
                date, PRIORITY_FRESH, null, regionsOf(rows), FRESH_DESCRIPTION, null)
                .withLocations(namesOf(rows))
                .withScience(List.of(snowFact(freshCm)), FRESH_NOTE);
    }

    /**
     * A day's mist topic from that day's lying places whose valley mist band is LIKELY. When the representative
     * place's slot temperature is provably sub-zero the topic becomes the "Snow, mist &amp; frost" case: a clear,
     * calm, near-saturated night over snow, so hoar frost is near certain and mist likely; the fact line leads with
     * the sub-zero air. Otherwise it stays a plain "fresh snow with mist" pill. The representative is the place
     * with the most modelled snow, so its temperature is a coherent single-location reading.
     */
    private HotTopic buildMistTopic(LocalDate date, List<Lying> rows) {
        Lying rep = rows.stream()
                .max(Comparator.comparingDouble(l -> l.row().getSnowWaterMm()))
                .orElseThrow();
        Double temperature = rep.mist().readings().temperatureCelsius();
        boolean frost = temperature != null && temperature < 0;
        double freshCm = maxFreshCm(rows);

        List<HotTopicFact> facts = new ArrayList<>();
        facts.add(snowFact(freshCm));
        if (frost) {
            // The gate is a strict temperature < 0, so the displayed integer must never read "0 °C"
            // (the freezing point) beside a "frost likely" claim — a reading in [-0.5, 0) rounds
            // to 0, so clamp the shown value to at most -1 °C. It stays genuinely sub-zero and honest.
            long airCelsius = Math.min(-1L, Math.round(temperature));
            facts.add(new HotTopicFact("air", airCelsius + " °C · frost likely", null, false, false));
        }
        facts.add(new HotTopicFact("mist", "mist likely", null, false, true));

        String label = frost ? FROST_LABEL : MIST_LABEL;
        String detail = frost ? FROST_DETAIL : (freshCm >= FRESH_FELL_CM ? MIST_DETAIL_FELL : MIST_DETAIL_LYING);
        // The (i) "science" tooltip explains the frost only when the air is provably sub-zero;
        // otherwise it keeps the general snow-mist copy (no over-claiming what forms).
        String description = frost ? FROST_DESCRIPTION : MIST_DESCRIPTION;
        return new HotTopic("SNOW_MIST", label, detail, date, PRIORITY_MIST, null,
                regionsOf(rows), description, null)
                .withLocations(namesOf(rows))
                .withScience(facts, frost ? FROST_NOTE : MIST_NOTE);
    }

    /** {@code fresh ~N cm} when at least a centimetre fell in the last 24 h, else {@code lying}. */
    private static HotTopicFact snowFact(double freshCm) {
        String value = freshCm >= FRESH_FELL_CM ? "fresh ~" + Math.round(freshCm) + " cm" : "lying";
        return new HotTopicFact(null, value, null, true, false);
    }

    /** The most snow that fell in the last 24 h at any of the day's places (rows without it count as 0). */
    private static double maxFreshCm(List<Lying> rows) {
        return rows.stream()
                .map(l -> l.row().getSnowfall24hCm())
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max().orElse(0.0);
    }

    private static List<String> regionsOf(List<Lying> rows) {
        Set<String> regions = new LinkedHashSet<>();
        for (Lying l : rows) {
            if (l.place().getRegion() != null && l.place().getRegion().getName() != null) {
                regions.add(l.place().getRegion().getName());
            }
        }
        return new ArrayList<>(regions);
    }

    private static List<String> namesOf(List<Lying> rows) {
        Set<String> names = new LinkedHashSet<>();
        rows.stream()
                .map(l -> l.place().getName())
                .filter(Objects::nonNull)
                .forEach(names::add);
        return new ArrayList<>(names);
    }
}
