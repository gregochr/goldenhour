package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.util.DayLabels;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects fresh-snow hot topics by reading persisted forecast evaluations.
 *
 * <p>Fresh snow lying on the ground transforms familiar landscapes — the photographic call is
 * "is there snow underfoot at the shooting hour," not "did it fall last night," so this detector
 * fires purely on lying depth: {@code snow_depth_m} at or above {@value #SNOW_DEPTH_THRESHOLD_METRES}
 * metres ({@code 2 cm}) at the hour nearest the solar event. When the existing briefing mist signal
 * co-occurs (humidity above {@link BriefingVerdictEvaluator#HUMIDITY_MARGINAL}% on a snowy row), the
 * topic is enriched into the SNOW_MIST variant rather than emitted as a separate topic. Reads
 * through the {@link SurvivorSignalReader} (the unified survivor surface), so it fires off the
 * survivor population, not the triaged rejects the legacy {@code forecast_evaluation} read
 * sampled. Makes no external API calls.
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

    private static final String HOAR_FROST_DESCRIPTION =
            "Hoar frost is the feathery white ice that forms when mist freezes straight onto every"
                    + " twig and blade in sub-zero air — a coating of crystals over the lying snow,"
                    + " what forms here as freezing fog. It's densest before dawn and burns back as"
                    + " the ground warms, so first light is the window; the low mist keeps the"
                    + " light soft and flat.";

    /** Topic priority for plain fresh snow — act-on-it, sorts above the calendar heads-ups. */
    private static final int PRIORITY_FRESH = 2;

    /** Topic priority when mist co-occurs — the standout condition, ranks above plain fresh snow. */
    private static final int PRIORITY_MIST = 1;

    /** Snow depth in metres at or above which snow counts as lying (2 cm). */
    static final double SNOW_DEPTH_THRESHOLD_METRES = 0.02;

    /** The italic cue for plain fresh snow. */
    private static final String FRESH_NOTE = "clean at first light, before wind and footprints";

    /** The italic cue when mist deposits hoar frost (sub-zero). */
    private static final String HOAR_FROST_NOTE = "densest before dawn, lifts mid-morning — rime on every branch";

    /** The italic cue when mist sits over snow but the air is not (provably) sub-zero. */
    private static final String MIST_NOTE = "mist over lying snow — soft, flat light";

    private final SurvivorSignalReader survivorSignalReader;
    private final SolarEventFreshness freshness;

    /**
     * Constructs a {@code SnowFreshHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (snow depth + humidity readings)
     * @param freshness            shared filter dropping sunrise/sunset events already past
     */
    public SnowFreshHotTopicStrategy(SurvivorSignalReader survivorSignalReader,
            SolarEventFreshness freshness) {
        this.survivorSignalReader = survivorSignalReader;
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
     * Returns true when humidity indicates co-occurring mist, reusing the briefing's mist
     * threshold exactly ({@link BriefingVerdictEvaluator#HUMIDITY_MARGINAL}).
     *
     * @param humidityPercent relative humidity percentage, or null
     * @return true if the mist signal is present
     */
    static boolean isMisty(Integer humidityPercent) {
        return humidityPercent != null && humidityPercent > BriefingVerdictEvaluator.HUMIDITY_MARGINAL;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic dated to the earliest snow-lying day in the window, with the distinct
     * regions affected. If any snowy row also shows mist, the topic is upgraded to the SNOW_MIST
     * variant (higher priority, mist-aware label and description). Returns empty when no row in the
     * window has snow lying.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SurvivorSignals> snowy = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> isFreshSnow(s.readings().snowDepthMetres()))
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .sorted(Comparator.comparing(SurvivorSignals::date))
                .toList();
        if (snowy.isEmpty()) {
            return List.of();
        }

        List<LocalDate> days = snowy.stream()
                .map(SurvivorSignals::date)
                .distinct()
                .sorted()
                .toList();
        Set<String> regions = new LinkedHashSet<>();
        for (SurvivorSignals s : snowy) {
            String region = s.location() != null && s.location().getRegion() != null
                    ? s.location().getRegion().getName() : null;
            if (region != null) {
                regions.add(region);
            }
        }

        String dayLabel = DayLabels.joinRelative(days, fromDate);
        List<SurvivorSignals> mistyRows = snowy.stream()
                .filter(s -> isMisty(s.readings().humidity()))
                .toList();
        List<String> regionList = new ArrayList<>(regions);
        if (!mistyRows.isEmpty()) {
            return List.of(buildMistTopic(mistyRows, days.get(0), dayLabel, regionList));
        }
        return List.of(buildFreshTopic(snowy, days.get(0), dayLabel, regionList));
    }

    /**
     * Builds the plain fresh-snow topic with a depth + snow-line fact line. The representative is the
     * deepest-snow row (the most striking lie); the snow line is its freezing-level altitude —
     * "depth and snow-line height tell you where it will actually stick".
     */
    private HotTopic buildFreshTopic(List<SurvivorSignals> snowy, LocalDate date, String dayLabel,
            List<String> regions) {
        SurvivorSignals rep = deepest(snowy);
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
     * Builds the mist variant. When the representative misty row is provably sub-zero the topic
     * becomes the SNOW_MIST "hoar frost" case — freezing fog over snow deposits rime — and the fact
     * line leads with the sub-zero air; when the air is not (provably) below freezing it stays a
     * plain "fresh snow with mist" pill, claiming only the humidity we can see. The representative is
     * the deepest misty row so its temperature/humidity are a coherent single-location snapshot.
     */
    private HotTopic buildMistTopic(List<SurvivorSignals> mistyRows, LocalDate date, String dayLabel,
            List<String> regions) {
        SurvivorSignals rep = deepest(mistyRows);
        Double temperature = rep.readings().temperatureCelsius();
        boolean hoarFrost = temperature != null && temperature < 0;

        List<HotTopicFact> facts = new ArrayList<>();
        facts.add(HotTopicFact.metric("depth", depthCm(rep) + " cm"));
        if (hoarFrost) {
            // The gate is a strict temperature < 0, so the displayed integer must never read "0 °C"
            // (the freezing point) beside a "hoar frost likely" claim — a reading in [-0.5, 0) rounds
            // to 0, so clamp the shown value to at most -1 °C. It stays genuinely sub-zero and honest.
            long airCelsius = Math.min(-1L, Math.round(temperature));
            facts.add(new HotTopicFact("air", airCelsius + " °C · hoar frost likely",
                    null, false, false));
        }
        Integer humidity = rep.readings().humidity();
        if (humidity != null) {
            facts.add(new HotTopicFact("mist", "humidity " + humidity + "%", null, false, true));
        }

        String label = hoarFrost ? "Snow mist & hoar frost" : "Fresh snow with mist";
        String detail = hoarFrost
                ? "Freezing fog over lying snow — hoar frost " + dayLabel
                : "Snow lying with mist — exceptional conditions " + dayLabel;
        // The (i) "science" tooltip explains hoar frost only when the air is provably sub-zero;
        // otherwise it keeps the general snow-mist copy (no over-claiming what forms).
        String description = hoarFrost ? HOAR_FROST_DESCRIPTION : MIST_DESCRIPTION;
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
        return topic.withScience(facts, hoarFrost ? HOAR_FROST_NOTE : MIST_NOTE);
    }

    /** The deepest-snow row in a non-empty list (all rows carry a depth by construction). */
    private static SurvivorSignals deepest(List<SurvivorSignals> rows) {
        return rows.stream()
                .max(Comparator.comparingDouble(s -> s.readings().snowDepthMetres()))
                .orElseThrow();
    }

    private static long depthCm(SurvivorSignals s) {
        return Math.round(s.readings().snowDepthMetres() * 100);
    }
}
