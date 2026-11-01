package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Detects the "Sea mist" hot topic from the slot surface's coastal mist reading
 * ({@link SlotSignals.Readings#coastalMistBand()}), coastal-mist-plan Phase 2. The coastal sibling of
 * {@link MistHotTopicStrategy}, and a DISTINCT topic type: valley mist already owns {@code MIST}, one topic per
 * date and event, and the two must not collide.
 *
 * <p>A light haar drifting in off the North Sea at sunrise is a genuine coastal photograph, and Phase 1 records
 * the band for every coastal, non-{@code mist_prone} site. This detector is the reader: "what is happening",
 * free, no Claude call. It reads {@code slot_atmosphere} alone, so a triage stand-down or a Gate 4 decision never
 * silences it (the two-question rule). Triage could not apply to a LIGHT band anyway: LIGHT starts at the same
 * 5 km line triage stands a slot down below.
 *
 * <h2>Why it is gated so tightly</h2>
 *
 * <p>The evidence is one site (Boulmer, 2023-2025) at about 68 % precision with a low cloud gate, so the topic
 * fires only when ALL of these hold for a slot, and the wording stays modest:
 * <ul>
 *   <li>the band is exactly {@link CoastalMistBand#LIGHT} (never THICK, which is fog, and never NONE);</li>
 *   <li>the event is SUNRISE (there is no sunset evidence);</li>
 *   <li>the date is today or tomorrow in UK civil time (the evidence is short-lead forecasts);</li>
 *   <li>the site is on the east (North Sea) coast, longitude above {@value #EAST_COAST_MIN_LONGITUDE}, and
 *       satisfies the Phase 1 predicate ({@link CoastalMistCalculator#appliesTo}): tide types, not
 *       {@code mist_prone};</li>
 *   <li>low cloud at the event slot is under {@value #SEA_MIST_TOPIC_MAX_LOW_CLOUD_PCT} %;</li>
 *   <li>there is no precipitation at the event slot.</li>
 * </ul>
 * A missing low cloud or precipitation reading (a row written before V211, or missing weather) means the gate
 * cannot be confirmed, so nothing is raised.
 *
 * <p>Rows are grouped per date; regions and location names are those of the contributing slots, as MIST's are.
 * {@link SolarEventFreshness} drops a sunrise that has already passed. Makes no external API calls.
 */
@Component
public class SeaMistHotTopicStrategy implements HotTopicStrategy {

    /** The {@link HotTopic#type()} this strategy emits. */
    public static final String TYPE = "SEA_MIST";

    /** Sites at or west of this longitude are off the North Sea coast (the Irish Sea is a different mechanism). */
    public static final double EAST_COAST_MIN_LONGITUDE = -2.5;

    /** Event-slot low cloud (percent) at or above which the topic is not raised. */
    public static final int SEA_MIST_TOPIC_MAX_LOW_CLOUD_PCT = 50;

    /** The latest {@code daysAhead} (UK civil date) the topic is raised for: today and tomorrow. */
    static final int MAX_DAYS_AHEAD = 1;

    private static final String LABEL = "Sea mist";

    private static final String DETAIL = "Light sea mist likely at sunrise";

    private static final String DESCRIPTION =
            "On a still, near-saturated morning a thin veil of sea mist can drift in off the North Sea. Low"
                    + " sun through it softens the coast. It is patchy, and some mornings turn out clear.";

    /** Topic priority — modest, below the valley mist's act-on-it 2. */
    private static final int PRIORITY = 3;

    private static final String NOTE = "patchy — some mornings are clear, so go early and watch the coast";

    private final SlotSignalReader slotSignalReader;
    private final SolarEventFreshness freshness;
    private final Clock clock;

    /**
     * Constructs a {@code SeaMistHotTopicStrategy}.
     *
     * @param slotSignalReader the unified slot read model (coastal mist readings)
     * @param freshness        shared filter dropping events already past
     * @param clock            the injected clock for the UK civil-date horizon
     */
    public SeaMistHotTopicStrategy(SlotSignalReader slotSignalReader, SolarEventFreshness freshness,
            Clock clock) {
        this.slotSignalReader = slotSignalReader;
        this.freshness = freshness;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one SUNRISE-anchored topic per date with at least one qualifying slot. Returns empty when none
     * remains after dropping sunrises already past.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        return detect(fromDate, toDate,
                HotTopicInputs.ofSlotSignals(() -> slotSignalReader.read(fromDate, toDate)));
    }

    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate, HotTopicInputs inputs) {
        List<SlotSignals> qualifying = inputs.slotSignals().stream()
                .filter(this::qualifies)
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .toList();
        if (qualifying.isEmpty()) {
            return List.of();
        }
        return PerDateHotTopicBuilder.perDate(qualifying, TYPE, LABEL, DETAIL, PRIORITY, DESCRIPTION,
                SeaMistHotTopicStrategy::enrich);
    }

    /**
     * Whether a slot passes every gate of the topic.
     *
     * @param signals the slot's composite
     * @return true when the band is LIGHT, the event is SUNRISE, the date is today or tomorrow, the site is on
     *         the east coast and coastal, low cloud is under the limit and the slot is dry
     */
    boolean qualifies(SlotSignals signals) {
        SlotSignals.Readings readings = signals.readings();
        if (readings == null || signals.eventType() != TargetType.SUNRISE
                || !readings.coastalMistScored() || readings.coastalMistBand() != CoastalMistBand.LIGHT) {
            return false;
        }
        if (ForecastHorizon.daysAhead(signals.date(), clock) > MAX_DAYS_AHEAD) {
            return false;
        }
        LocationEntity location = signals.location();
        if (!CoastalMistCalculator.appliesTo(location) || location.getLon() <= EAST_COAST_MIN_LONGITUDE) {
            return false;
        }
        Integer lowCloud = readings.coastalMistLowCloudPct();
        Double precipitation = readings.coastalMistPrecipMm();
        return lowCloud != null && lowCloud < SEA_MIST_TOPIC_MAX_LOW_CLOUD_PCT
                && precipitation != null && precipitation <= 0.0;
    }

    /**
     * Stamps the sunrise event and attaches the fact line (the count of sites) and the cue.
     *
     * @param topic   the day's base topic
     * @param dayRows that day's qualifying rows
     * @return the topic anchored to SUNRISE, with its fact, cue and glance
     */
    private static HotTopic enrich(HotTopic topic, List<SlotSignals> dayRows) {
        long sites = dayRows.stream()
                .map(s -> s.location() == null ? null : s.location().getId())
                .distinct()
                .count();
        String siteCount = sites + (sites == 1 ? " site" : " sites");
        GlanceLine glance = new GlanceLine("dawn · light mist, " + siteCount, "likely", null,
                HotTopicGlanceDecorator.WEATHER_TYPES.contains(TYPE));
        // The clock time is left to HotTopicEventEnricher, which completes a detector-stamped event.
        return topic.withEvent(TargetType.SUNRISE.name(), null)
                .withScience(List.of(HotTopicFact.metric("sea mist", "likely at " + siteCount)), NOTE)
                .withGlance(glance);
    }
}
