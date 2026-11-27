package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SlotSignals;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects the "Frost &amp; mist" hot topic: a sunrise whose valley mist band is {@code LIKELY} while the 2 m air at
 * the first hourly slot at or after sunrise is below freezing (frost-mist-plan §3.1).
 *
 * <p>Sub-zero air that is near saturation is supersaturated with respect to ice, so hoar frost is the likely
 * outcome on exposed surfaces; the valley mist band is the honest evidence of the moisture. The band is READ
 * ({@link MistHotTopicStrategy#isLikely}), never re-derived. The temperature is a point value and a screen
 * reading, so the rule under-claims ground frost and never over-claims. Sunrise rows only: frost is a first-light
 * photograph. One topic per day names the exact ringed places.
 *
 * <p>Frost &amp; mist replaces Valley mist for those sunrise place-days ({@link MistHotTopicStrategy} drops them
 * through {@link #isFrostMist}); a place with snow lying still fires (no snow exclusion). Makes no external API
 * calls.
 */
@Component
public class FrostMistHotTopicStrategy implements HotTopicStrategy {

    /** The {@link HotTopic#type()} this strategy emits. */
    public static final String TYPE = "FROST_MIST";

    /** The 2 m air temperature below which a misty sunrise is a frost-and-mist sunrise (strictly below). */
    static final double FROST_AIR_MAX_C = 0.0;

    private static final String LABEL = "Frost & mist";

    private static final String DETAIL = "Hoar frost and valley mist at first light";

    private static final String DESCRIPTION =
            "On a cold, still, misty night, hoar frost can feather twigs and grass, and mist pools on the valley"
                    + " floor. Where the mist thickens it may freeze as rime. Both go as the sun warms the"
                    + " ground, so first light is the window.";

    /** Topic priority, the same as the snow-mist variant's: a standout condition. */
    private static final int PRIORITY = 1;

    private final SlotSignalReader slotSignalReader;
    private final SolarEventFreshness freshness;

    /**
     * Constructs a {@code FrostMistHotTopicStrategy}.
     *
     * @param slotSignalReader the unified slot read model (valley-mist band and temperature readings)
     * @param freshness        shared filter dropping sunrises already past
     */
    public FrostMistHotTopicStrategy(SlotSignalReader slotSignalReader, SolarEventFreshness freshness) {
        this.slotSignalReader = slotSignalReader;
        this.freshness = freshness;
    }

    /**
     * Whether a slot is a frost-and-mist sunrise: a SUNRISE row whose valley mist band is {@code LIKELY}
     * and whose 2 m air temperature is strictly below {@link #FROST_AIR_MAX_C}. A null temperature never fires.
     *
     * @param signals the slot's composite
     * @return true when the row is a sunrise with likely valley mist and sub-zero air
     */
    public static boolean isFrostMist(SlotSignals signals) {
        if (signals.eventType() != TargetType.SUNRISE || !MistHotTopicStrategy.isLikely(signals)) {
            return false;
        }
        Double temperature = signals.readings().temperatureCelsius();
        return temperature != null && temperature < FROST_AIR_MAX_C;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per non-expired day with at least one frost-and-mist sunrise place, dated to that day,
     * stamped SUNRISE and naming exactly those places.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        return detect(fromDate, toDate,
                HotTopicInputs.ofSlotSignals(() -> slotSignalReader.read(fromDate, toDate)));
    }

    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate, HotTopicInputs inputs) {
        List<SlotSignals> rows = inputs.slotSignals().stream()
                .filter(FrostMistHotTopicStrategy::isFrostMist)
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        return PerDateHotTopicBuilder.perDate(rows, TYPE, LABEL, DETAIL, PRIORITY, DESCRIPTION,
                FrostMistHotTopicStrategy::enrich);
    }

    /** Stamps SUNRISE and attaches the frost fact (the day's coldest place), the site count and the cue. */
    private static HotTopic enrich(HotTopic topic, List<SlotSignals> dayRows) {
        double coldest = dayRows.stream()
                .map(s -> s.readings().temperatureCelsius())
                .mapToDouble(Double::doubleValue)
                .min().orElseThrow();
        long sites = dayRows.stream()
                .map(s -> s.location() == null ? null : s.location().getId())
                .distinct()
                .count();
        String siteCount = sites + (sites == 1 ? " site" : " sites");
        List<HotTopicFact> facts = new ArrayList<>();
        facts.add(FrostAir.fact(coldest));
        facts.add(HotTopicFact.metric("valley mist", "likely at " + siteCount));
        GlanceLine glance = new GlanceLine("dawn · " + FrostAir.shownCelsius(coldest) + " °C, " + siteCount, null,
                HotTopicGlanceDecorator.WEATHER_TYPES.contains(TYPE));
        // The clock time is left to HotTopicEventEnricher (EVENT_STAMPED_BY_DETECTOR).
        return topic.withEvent(TargetType.SUNRISE.name(), null)
                .withScience(facts, SnowFreshHotTopicStrategy.FROST_NOTE)
                .withGlance(glance);
    }
}
