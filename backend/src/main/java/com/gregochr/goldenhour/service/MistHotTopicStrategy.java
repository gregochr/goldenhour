package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.SlotSignals;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects valley mist hot topics from the slot surface's valley-mist reading
 * ({@link SlotSignals.Readings#mistPotential()}) — valley-mist-plan M1b, the sibling of
 * {@link InversionHotTopicStrategy}.
 *
 * <p>Radiation mist on a river, a lake shore or a valley floor under a clear sky is a whole genre
 * of British landscape photography, and until M1a nothing in the pipeline recorded it. Since M1a
 * {@code ValleyMistCalculator} scores the radiative set-up for every {@code mist_prone} candidate
 * inside {@code ForecastService.fetchWeatherAndTriage}, before the triage check, and
 * {@code SlotAtmosphereWriter} stores the score and its band whatever the triage verdict or Gate 4
 * decision that follows. This detector is the reader: "what is happening", free, with no Claude
 * call — the two-question rule (owner decision 2026-09-29) applied to a new subject. A weather
 * stand-down for low visibility, which retracts the RATING, never silences this topic.
 *
 * <h2>LIKELY only</h2>
 *
 * <p>⚠️ <b>The topic fires on {@link MistPotential#LIKELY} and on nothing else.</b> The plan said
 * "LIKELY or above"; that was amended at build, because the band above LIKELY is
 * {@link MistPotential#FOGBOUND} — a white frame, event-hour visibility under 300 m with no
 * clearing in the trend — which the band exists to keep apart from mist with a view through it. A
 * cue of "get there before the sun, it burns off within the hour" is false for fog that does not
 * clear. {@link MistPotential#POSSIBLE} is too weak a claim to put on a Plan card. The band is read
 * through {@link SlotSignals#effectiveMistPotential()} since M2 (the twin of
 * {@link SlotSignals#effectiveInversionScore()}): the calculator's band wherever it ran, including
 * a deliberate null, and Claude's {@code forecast_score} MIST echo only for a row written before
 * V185.
 *
 * <h2>The band, never the score</h2>
 *
 * <p>The band is READ ({@link SlotSignals.Readings#mistPotential()}), never re-derived from the
 * score: FOGBOUND is decided by event visibility, not the score, so two slots can share a score and
 * differ in band, and a threshold copied here would be a second home for a rule the calculator owns.
 *
 * <h2>Both events</h2>
 *
 * <p>Unlike the inversion (a dawn phenomenon, SUNRISE rows only) mist forming at dusk over a river
 * is a real evening shot, so SUNRISE and SUNSET rows both count. Rows are grouped per date AND per
 * event, so a day can carry a sunrise topic and a sunset topic; each is stamped with its event, which
 * {@code PlanWindowProjector} needs to put the badge on the right window and
 * {@code HotTopicEventEnricher} completes with the clock time. {@link SolarEventFreshness} drops an
 * event that has already passed, so a morning's mist retires the instant its sunrise has.
 *
 * <p>Regions are the regions of the contributing slots (a regional topic, like the inversion's);
 * a day with two qualifying slots in one region is one topic naming that region once.
 *
 * <p>Makes no external API calls.
 */
@Component
public class MistHotTopicStrategy implements HotTopicStrategy {

    /** The {@link HotTopic#type()} this strategy emits. */
    public static final String TYPE = "MIST";

    private static final String LABEL = "Valley mist";

    private static final String DETAIL = "Valley mist likely at mist-prone sites";

    private static final String DESCRIPTION =
            "On a clear, calm, near-saturated night, mist forms over rivers, lake shores and valley"
                    + " floors. Low sun through it is among the most atmospheric light the"
                    + " landscape offers.";

    /** Topic priority — act-on-it, the same as the cloud inversion's. */
    private static final int PRIORITY = 2;

    /** The italic cue at sunrise: mist is gone soon after the sun is up. */
    private static final String SUNRISE_NOTE = "get there before the sun — it burns off within the hour";

    /** The italic cue at sunset: mist forms as the light fades. */
    private static final String SUNSET_NOTE = "mist builds as the light fades — stay on through blue hour";

    private static final List<TargetType> EVENTS = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    private final SlotSignalReader slotSignalReader;
    private final SolarEventFreshness freshness;

    /**
     * Constructs a {@code MistHotTopicStrategy}.
     *
     * @param slotSignalReader the unified slot read model (valley-mist readings)
     * @param freshness        shared filter dropping mist events already past
     */
    public MistHotTopicStrategy(SlotSignalReader slotSignalReader, SolarEventFreshness freshness) {
        this.slotSignalReader = slotSignalReader;
        this.freshness = freshness;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per non-expired (date, event) that has at least one {@code LIKELY} slot —
     * dated to that day, anchored to that event and carrying only the regions of the contributing
     * slots. Returns empty when no such slot remains after dropping events already past.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SlotSignals> likely = slotSignalReader.read(fromDate, toDate).stream()
                .filter(s -> EVENTS.contains(s.eventType()))
                .filter(MistHotTopicStrategy::isLikely)
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .toList();
        if (likely.isEmpty()) {
            return List.of();
        }

        List<HotTopic> topics = new ArrayList<>();
        for (TargetType event : EVENTS) {
            List<SlotSignals> rows = likely.stream().filter(s -> s.eventType() == event).toList();
            if (!rows.isEmpty()) {
                topics.addAll(PerDateHotTopicBuilder.perDate(
                        rows, TYPE, LABEL, DETAIL, PRIORITY, DESCRIPTION,
                        (topic, dayRows) -> enrich(topic, dayRows, event)));
            }
        }
        return topics;
    }

    /**
     * Whether a slot's reading says photographable mist is likely: the effective band
     * ({@link SlotSignals#effectiveMistPotential()}) is exactly {@code LIKELY} &mdash; the
     * calculator's own band where it ran, Claude's echo only for a slot it never reached.
     *
     * @param signals the slot's composite
     * @return true when the band is LIKELY (never FOGBOUND, POSSIBLE, NONE or absent)
     */
    public static boolean isLikely(SlotSignals signals) {
        return signals.readings() != null
                && signals.effectiveMistPotential() == MistPotential.LIKELY;
    }

    /**
     * Stamps the day's event and attaches the fact line (the count of sites) and the cue.
     *
     * @param topic   the day's base topic
     * @param dayRows that day's qualifying rows for this event
     * @param event   the event the rows are for
     * @return the topic anchored to {@code event}, with its fact and cue
     */
    private static HotTopic enrich(HotTopic topic, List<SlotSignals> dayRows, TargetType event) {
        long sites = dayRows.stream()
                .map(s -> s.location() == null ? null : s.location().getId())
                .distinct()
                .count();
        String value = "likely at " + sites + (sites == 1 ? " site" : " sites");
        String note = event == TargetType.SUNSET ? SUNSET_NOTE : SUNRISE_NOTE;
        // The time is left to HotTopicEventEnricher, which completes a detector-stamped event; see
        // its EVENT_STAMPED_BY_DETECTOR.
        String when = event == TargetType.SUNSET ? "dusk" : "dawn";
        String siteCount = sites + (sites == 1 ? " site" : " sites");
        GlanceLine glance = new GlanceLine(when + " · likely, " + siteCount, "likely", null,
                HotTopicGlanceDecorator.WEATHER_TYPES.contains(TYPE));
        return topic.withEvent(event.name(), null)
                .withScience(List.of(HotTopicFact.metric("valley mist", value)), note)
                .withGlance(glance);
    }
}
