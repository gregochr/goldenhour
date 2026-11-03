package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.util.DayLabels;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Detects fresh-snow hot topics by reading persisted forecast evaluations.
 *
 * <p>Fresh snow lying on the ground transforms familiar landscapes — the photographic call is
 * "is there snow underfoot at the shooting hour," not "did it fall last night," so this detector
 * fires purely on lying depth: {@code snow_depth_m} at or above {@value #SNOW_DEPTH_THRESHOLD_METRES}
 * metres ({@code 2 cm}) at the hour nearest the solar event. Snowy rows whose valley mist band is
 * {@code LIKELY} on a sunrise ({@link MistHotTopicStrategy#isLikely}, the same rule as the MIST topic)
 * become a SNOW_MIST topic; the remaining snowy rows stay a SNOW_FRESH topic, so one window can emit
 * both. Humidity no longer decides mist (see {@code docs/engineering/snow-mist-valley-band-plan.md}).
 * Reads through the {@link SlotSignalReader} (the unified read surface, {@code slot_atmosphere}).
 * ⚠️ Since the "record conditions for every place" change (Phase 1, owner decision 2026-09-30) this
 * fires for every candidate whose weather was fetched, not only the ones that went on to a Claude
 * rating — snow lying is a fact about the ground, independent of whether the sky above it is worth
 * photographing. Makes no external API calls.
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

    /** Topic priority for plain fresh snow — act-on-it, sorts above the calendar heads-ups. */
    private static final int PRIORITY_FRESH = 2;

    /** Topic priority when mist co-occurs — the standout condition, ranks above plain fresh snow. */
    private static final int PRIORITY_MIST = 1;

    /** Snow depth in metres at or above which snow counts as lying (2 cm). */
    static final double SNOW_DEPTH_THRESHOLD_METRES = 0.02;

    /** The italic cue for plain fresh snow. */
    private static final String FRESH_NOTE = "clean at first light, before wind and footprints";

    /** The italic cue when the mist sits over sub-zero snow (frost likely). */
    private static final String FROST_NOTE = "densest before dawn — frost on every branch";

    /** The italic cue when mist sits over snow but the air is not (provably) sub-zero. */
    private static final String MIST_NOTE = "mist over lying snow — soft, flat light";

    private final SlotSignalReader slotSignalReader;
    private final SolarEventFreshness freshness;

    /**
     * Constructs a {@code SnowFreshHotTopicStrategy}.
     *
     * @param slotSignalReader the unified slot read model (snow depth, temperature and valley mist
     *                         band readings)
     * @param freshness        shared filter dropping sunrise/sunset events already past
     */
    public SnowFreshHotTopicStrategy(SlotSignalReader slotSignalReader,
            SolarEventFreshness freshness) {
        this.slotSignalReader = slotSignalReader;
        this.freshness = freshness;
    }

    /**
     * Returns true when snow is lying — depth present and at or above the 2 cm threshold. Exists so
     * the threshold can be unit-tested at its boundary and kept consistent with the SQL query.
     *
     * @param snowDepthMetres lying snow depth in metres, or null
     * @return true if the fresh-snow condition fires
     */
    static boolean isFreshSnow(Double snowDepthMetres) {
        return snowDepthMetres != null && snowDepthMetres >= SNOW_DEPTH_THRESHOLD_METRES;
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
     * <p>Splits the snowy rows by the valley mist band. Rows that are {@code LIKELY} on a sunrise
     * yield a SNOW_MIST topic (higher priority, mist-aware label and description) built only from
     * those rows; the snowy rows at other places or on other days yield a SNOW_FRESH topic built only
     * from them (a place and day already covered by SNOW_MIST, including its sunset, adds no second
     * card). Each is dated
     * to its own earliest day and lists its own regions, and SNOW_MIST comes first. Returns empty
     * when no row in the window has snow lying.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        return detect(fromDate, toDate,
                HotTopicInputs.ofSlotSignals(() -> slotSignalReader.read(fromDate, toDate)));
    }

    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate,
            HotTopicInputs inputs) {
        List<SlotSignals> snowy = inputs.slotSignals().stream()
                .filter(s -> isFreshSnow(s.readings().snowDepthMetres()))
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .sorted(Comparator.comparing(SlotSignals::date))
                .toList();
        if (snowy.isEmpty()) {
            return List.of();
        }

        List<SlotSignals> misty = snowy.stream().filter(SnowFreshHotTopicStrategy::isMisty).toList();
        Set<Object> mistyPlaceDays = new HashSet<>();
        for (SlotSignals m : misty) {
            mistyPlaceDays.add(placeDay(m));
        }
        // The rest = other places or other days: the same place and day is already a SNOW_MIST card.
        List<SlotSignals> plain = snowy.stream()
                .filter(s -> !isMisty(s) && !mistyPlaceDays.contains(placeDay(s)))
                .toList();
        List<HotTopic> topics = new ArrayList<>();
        if (!misty.isEmpty()) {
            topics.add(buildMistTopic(misty, earliestDay(misty), dayLabel(misty, fromDate),
                    regionsOf(misty)));
        }
        if (!plain.isEmpty()) {
            topics.add(buildFreshTopic(plain, earliestDay(plain), dayLabel(plain, fromDate),
                    regionsOf(plain)));
        }
        return topics;
    }

    /** A (place, day) key: the location's id, or the location object itself when it has none. */
    private static Object placeDay(SlotSignals s) {
        Object place = s.location() != null && s.location().getId() != null
                ? s.location().getId() : s.location();
        return List.of(Objects.requireNonNullElse(place, "none"), s.date());
    }

    /** The earliest date among the rows (non-empty). */
    private static LocalDate earliestDay(List<SlotSignals> rows) {
        return rows.stream().map(SlotSignals::date).min(Comparator.naturalOrder()).orElseThrow();
    }

    /** The relative day label over the rows' distinct dates. */
    private static String dayLabel(List<SlotSignals> rows, LocalDate fromDate) {
        List<LocalDate> days = rows.stream()
                .map(SlotSignals::date)
                .distinct()
                .sorted()
                .toList();
        return DayLabels.joinRelative(days, fromDate);
    }

    /** The distinct region names among the rows, in row order. */
    private static List<String> regionsOf(List<SlotSignals> rows) {
        Set<String> regions = new LinkedHashSet<>();
        for (SlotSignals s : rows) {
            String region = s.location() != null && s.location().getRegion() != null
                    ? s.location().getRegion().getName() : null;
            if (region != null) {
                regions.add(region);
            }
        }
        return new ArrayList<>(regions);
    }

    /**
     * Builds the plain fresh-snow topic with a depth + snow-line fact line. The representative is the
     * deepest-snow row (the most striking lie); the snow line is its freezing-level altitude —
     * "depth and snow-line height tell you where it will actually stick".
     */
    private HotTopic buildFreshTopic(List<SlotSignals> snowy, LocalDate date, String dayLabel,
            List<String> regions) {
        SlotSignals rep = deepest(snowy);
        List<HotTopicFact> facts = new ArrayList<>();
        facts.add(HotTopicFact.metric("depth", depthCm(rep) + " cm"));
        Double freezingLevel = rep.readings().freezingLevelMetres();
        if (freezingLevel != null) {
            facts.add(new HotTopicFact("snow line", "~" + Math.round(freezingLevel) + " m",
                    null, false, true));
        }
        HotTopic topic = new HotTopic(
                "SNOW_FRESH",
                "Fresh snow",
                "Snow lying — white landscapes " + dayLabel,
                date,
                PRIORITY_FRESH,
                null,
                regions,
                FRESH_DESCRIPTION,
                null);
        return topic.withScience(facts, FRESH_NOTE);
    }

    /**
     * Builds the mist variant from the sunrise rows whose valley mist band is LIKELY. When the
     * representative row is provably sub-zero the topic becomes the "Snow, mist &amp; frost" case: a
     * clear, calm, near-saturated night over snow, so hoar frost is near certain and mist likely; the
     * fact line leads with the sub-zero air. Otherwise it stays a plain "fresh snow with mist" pill.
     * The representative is the deepest misty row so its depth and temperature are a coherent
     * single-location snapshot.
     */
    private HotTopic buildMistTopic(List<SlotSignals> mistyRows, LocalDate date, String dayLabel,
            List<String> regions) {
        SlotSignals rep = deepest(mistyRows);
        Double temperature = rep.readings().temperatureCelsius();
        boolean frost = temperature != null && temperature < 0;

        List<HotTopicFact> facts = new ArrayList<>();
        facts.add(HotTopicFact.metric("depth", depthCm(rep) + " cm"));
        if (frost) {
            // The gate is a strict temperature < 0, so the displayed integer must never read "0 °C"
            // (the freezing point) beside a "frost likely" claim — a reading in [-0.5, 0) rounds
            // to 0, so clamp the shown value to at most -1 °C. It stays genuinely sub-zero and honest.
            long airCelsius = Math.min(-1L, Math.round(temperature));
            facts.add(new HotTopicFact("air", airCelsius + " °C · frost likely",
                    null, false, false));
        }
        facts.add(new HotTopicFact("mist", "mist likely", null, false, true));

        String label = frost ? "Snow, mist & frost" : "Fresh snow with mist";
        String detail = frost
                ? "Freezing mist and frost over lying snow " + dayLabel
                : "Snow lying with mist — exceptional conditions " + dayLabel;
        // The (i) "science" tooltip explains the frost only when the air is provably sub-zero;
        // otherwise it keeps the general snow-mist copy (no over-claiming what forms).
        String description = frost ? FROST_DESCRIPTION : MIST_DESCRIPTION;
        HotTopic topic = new HotTopic(
                "SNOW_MIST",
                label,
                detail,
                date,
                PRIORITY_MIST,
                null,
                regions,
                description,
                null);
        return topic.withScience(facts, frost ? FROST_NOTE : MIST_NOTE);
    }

    /** The deepest-snow row in a non-empty list (all rows carry a depth by construction). */
    private static SlotSignals deepest(List<SlotSignals> rows) {
        return rows.stream()
                .max(Comparator.comparingDouble(s -> s.readings().snowDepthMetres()))
                .orElseThrow();
    }

    private static long depthCm(SlotSignals s) {
        return Math.round(s.readings().snowDepthMetres() * 100);
    }
}
