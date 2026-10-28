package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SlotSignals;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Detects storm surge hot topics by reading persisted forecast evaluations.
 *
 * <p>Low pressure and onshore winds push water levels above predicted heights; combined
 * with high tide this produces crashing waves and dramatic coastal conditions — even at
 * midday. The forecast pipeline already computes and persists {@code surge_risk_level} per
 * evaluation (only for coastal locations). This detector fires when any candidate row in the
 * window is classified {@value #HIGH_RISK} — high only, not moderate. Reads through the
 * {@link SlotSignalReader} (the unified read surface, {@code slot_atmosphere}). ⚠️ Since
 * the "record conditions for every place" change (Phase 1, owner decision 2026-09-30) this fires
 * for every candidate whose weather was fetched, not only the ones that went on to a Claude
 * rating — a storm surge is a fact about the tide and the weather, independent of whether the sky
 * above the coast is worth photographing. Makes no external API calls.
 *
 * <p>⚠️ <b>Topics are per (date, event), each stamped with its own event.</b> A non-day-scoped
 * topic with no event lands on no Plan window ({@code PlanWindowProjector.keysFor}), which is how
 * surge vanished from every card once the Hot topics strip went. The reading is sampled at the next
 * high water AFTER the event ({@code ForecastDataAugmentor}), so a topic's badge means "a surge on
 * the tide following this event" &mdash; hence "at high water" in its facts. The clock time is left
 * to {@link HotTopicEventEnricher}.
 */
@Component
public class StormSurgeHotTopicStrategy implements HotTopicStrategy {

    private static final String STORM_SURGE_DESCRIPTION =
            "Low pressure and onshore winds push water levels above predicted heights."
                    + " Combined with high tide, this creates crashing waves and"
                    + " dramatic coastal conditions — even midday.";

    /** The {@link HotTopic#type()} this strategy emits. */
    public static final String TYPE = "STORM_SURGE";

    /** Topic priority — most act-on-it, sorts at the top alongside aurora and king tide. */
    private static final int PRIORITY = 1;

    /** The only surge risk level that fires the topic. */
    private static final String HIGH_RISK = "HIGH";

    private static final List<TargetType> EVENTS = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    private final SlotSignalReader slotSignalReader;
    private final StormSurgeFactsBuilder stormSurgeFactsBuilder;

    /**
     * Constructs a {@code StormSurgeHotTopicStrategy}.
     *
     * @param slotSignalReader   the unified slot read model (surge risk + numeric readings)
     * @param stormSurgeFactsBuilder builds the enriched surge + wave fact line
     */
    public StormSurgeHotTopicStrategy(SlotSignalReader slotSignalReader,
            StormSurgeFactsBuilder stormSurgeFactsBuilder) {
        this.slotSignalReader = slotSignalReader;
        this.stormSurgeFactsBuilder = stormSurgeFactsBuilder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per high-surge-risk (date, event), sunrise before sunset within a date,
     * each dated to that day, anchored to that event and carrying only the regions, locations and
     * facts of that event's rows, so a multi-day blow surfaces as an adjacent run of cards. No
     * freshness cutoff is applied here (the passed window is hidden downstream). Returns empty when
     * no sunrise or sunset row in the window is classified high risk.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        return detect(fromDate, toDate,
                HotTopicInputs.ofSlotSignals(() -> slotSignalReader.read(fromDate, toDate)));
    }

    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate,
            HotTopicInputs inputs) {
        List<SlotSignals> highRisk = inputs.slotSignals().stream()
                .filter(s -> EVENTS.contains(s.eventType()))
                .filter(s -> HIGH_RISK.equals(s.readings().surgeRiskLevel()))
                .sorted(Comparator.comparing(SlotSignals::date))
                .toList();
        if (highRisk.isEmpty()) {
            return List.of();
        }

        List<HotTopic> topics = new ArrayList<>();
        for (TargetType event : EVENTS) {
            List<SlotSignals> rows = highRisk.stream().filter(s -> s.eventType() == event).toList();
            if (!rows.isEmpty()) {
                topics.addAll(PerDateHotTopicBuilder.perDate(
                        rows,
                        TYPE,
                        "Storm surge",
                        "High surge risk — dramatic coastal conditions",
                        PRIORITY,
                        STORM_SURGE_DESCRIPTION,
                        // Stamp BEFORE attach; withScience/withSurgeRun carry the event through.
                        (topic, dayRows) -> stormSurgeFactsBuilder.attach(
                                topic.withEvent(event.name(), null), dayRows)));
            }
        }
        topics.sort(Comparator.comparing(HotTopic::date));
        return topics;
    }
}
