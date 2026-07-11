package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SeaState;
import com.gregochr.goldenhour.model.TideDerivation;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Builds the enriched "science showing" fact line for the KING_TIDE and SPRING_TIDE pills.
 *
 * <p>King and spring tides are the same tidal event — a king tide is a stronger (perigean) spring —
 * so both are served here. For a day, the builder scans the cached briefing's coastal slots that
 * qualify (king, or spring-not-king), re-derives each one's full tide facts through
 * {@link TideFactDeriver} (a DB-only read — never an API call, so it is safe from a hot-topic
 * strategy), and picks the single most dramatic slot as the pill's representative: the biggest high
 * water for king, the biggest range for spring. The headline metric is then shown anomaly-first —
 * beside its spring-tide baseline (king) or its average range (spring) — and, when a matching
 * {@code marine_wave} sample exists for that slot, a sea-state band chip is appended.
 */
@Component
public class CoastalTideFactsBuilder {

    private static final String KING_NOTE = "causeways & foreshore submerged — shoot reflections";
    private static final String SPRING_NOTE = "low water bares the foreground";

    /** Minimum anomaly (m) worth showing — below this "+0.0 m over spring" is noise. */
    private static final double MIN_ANOMALY_METRES = 0.05;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final TideFactDeriver tideFactDeriver;
    private final MarineWaveRepository marineWaveRepository;

    /**
     * Constructs a {@code CoastalTideFactsBuilder}.
     *
     * @param tideFactDeriver      the single tide-fact derivation seam (DB-only)
     * @param marineWaveRepository the shared sea-state carrier (V123)
     */
    public CoastalTideFactsBuilder(TideFactDeriver tideFactDeriver,
            MarineWaveRepository marineWaveRepository) {
        this.tideFactDeriver = tideFactDeriver;
        this.marineWaveRepository = marineWaveRepository;
    }

    /**
     * The enriched fact line for a coastal pill.
     *
     * @param facts the enriched fact chips
     * @param note  the "where to look" cue rendered after the chips
     */
    public record CoastalScience(List<HotTopicFact> facts, String note) {
    }

    private record Candidate(TideDerivation derivation, TargetType event, LocationEntity location) {
    }

    /**
     * Builds the KING_TIDE fact line for the day, or null when no coastal slot can be derived.
     *
     * @param day              the briefing day
     * @param coastalLocations all enabled coastal locations
     * @return the king-tide science, or null
     */
    public CoastalScience buildKing(BriefingDay day, List<LocationEntity> coastalLocations) {
        Candidate rep = selectRepresentative(day, coastalLocations,
                t -> t.isKingTide() || t.lunarTideType() == LunarTideType.KING_TIDE,
                CoastalTideFactsBuilder::highWaterMetric);
        return rep == null ? null : buildKingFacts(rep, day.date());
    }

    /**
     * Builds the SPRING_TIDE fact line for the day, or null when no coastal slot can be derived.
     *
     * @param day              the briefing day
     * @param coastalLocations all enabled coastal locations
     * @return the spring-tide science, or null
     */
    public CoastalScience buildSpring(BriefingDay day, List<LocationEntity> coastalLocations) {
        Candidate rep = selectRepresentative(day, coastalLocations,
                t -> !(t.isKingTide() || t.lunarTideType() == LunarTideType.KING_TIDE)
                        && (t.isSpringTide() || t.lunarTideType() == LunarTideType.SPRING_TIDE),
                CoastalTideFactsBuilder::rangeMetric);
        return rep == null ? null : buildSpringFacts(rep, day.date());
    }

    private Candidate selectRepresentative(BriefingDay day, List<LocationEntity> coastalLocations,
            Predicate<BriefingSlot.TideInfo> matcher, ToDoubleFunction<TideDerivation> metric) {
        Map<String, LocationEntity> byName = coastalLocations.stream()
                .collect(Collectors.toMap(LocationEntity::getName, l -> l, (a, b) -> a));
        Candidate best = null;
        double bestMetric = Double.NEGATIVE_INFINITY;
        for (BriefingEventSummary event : day.eventSummaries()) {
            List<BriefingSlot> slots = new ArrayList<>();
            event.regions().forEach(region -> slots.addAll(region.slots()));
            slots.addAll(event.unregioned());
            for (BriefingSlot slot : slots) {
                if (slot.tide() == null || !matcher.test(slot.tide())
                        || slot.solarEventTime() == null) {
                    continue;
                }
                LocationEntity loc = byName.get(slot.locationName());
                if (loc == null) {
                    continue;
                }
                Optional<TideDerivation> derived = tideFactDeriver.derive(loc.getId(),
                        slot.solarEventTime(), loc.getTideType(), loc.getLat(), loc.getLon(),
                        event.targetType());
                if (derived.isEmpty() || derived.get().nextHighTideHeightMetres() == null) {
                    continue;
                }
                double m = metric.applyAsDouble(derived.get());
                if (m > bestMetric) {
                    bestMetric = m;
                    best = new Candidate(derived.get(), event.targetType(), loc);
                }
            }
        }
        return best;
    }

    private CoastalScience buildKingFacts(Candidate rep, LocalDate date) {
        TideDerivation d = rep.derivation();
        List<HotTopicFact> facts = new ArrayList<>();
        facts.add(HotTopicFact.metric("high water", metres(d.nextHighTideHeightMetres().doubleValue())));
        if (d.springTideThresholdMetres() != null) {
            double delta = d.nextHighTideHeightMetres().doubleValue()
                    - d.springTideThresholdMetres().doubleValue();
            if (delta > MIN_ANOMALY_METRES) {
                facts.add(HotTopicFact.context("+" + metres(delta) + " over spring"));
            }
        }
        if (d.nextHighTideTime() != null) {
            facts.add(HotTopicFact.metric("HW", d.nextHighTideTime().format(CLOCK)).asOptional());
        }
        seaStateFact(rep.location().getId(), date, rep.event()).ifPresent(facts::add);
        return new CoastalScience(facts, KING_NOTE);
    }

    private CoastalScience buildSpringFacts(Candidate rep, LocalDate date) {
        TideDerivation d = rep.derivation();
        List<HotTopicFact> facts = new ArrayList<>();
        double range = rangeMetric(d);
        facts.add(HotTopicFact.metric("range", metres(range)));
        facts.add(HotTopicFact.context(rangeAnomaly(range, d.avgRangeMetres())));
        if (d.nextLowTideTime() != null) {
            facts.add(HotTopicFact.metric("LW", d.nextLowTideTime().format(CLOCK)).asOptional());
        }
        if (d.nextHighTideTime() != null) {
            facts.add(HotTopicFact.metric("HW", d.nextHighTideTime().format(CLOCK)).asOptional());
        }
        seaStateFact(rep.location().getId(), date, rep.event()).ifPresent(facts::add);
        return new CoastalScience(facts, SPRING_NOTE);
    }

    private Optional<HotTopicFact> seaStateFact(Long locationId, LocalDate date, TargetType event) {
        return marineWaveRepository
                .findByLocation_IdAndEvaluationDateAndEventType(locationId, date, event)
                .map(MarineWaveEntity::getSignificantWaveHeightMetres)
                .filter(hs -> hs != null)
                .map(hs -> HotTopicFact.metric("seas",
                        metres(hs) + " · " + SeaState.fromHs(hs).label()).asOptional());
    }

    private static String rangeAnomaly(double range, BigDecimal avgRangeMetres) {
        if (avgRangeMetres != null) {
            double anomaly = range - avgRangeMetres.doubleValue();
            if (anomaly > MIN_ANOMALY_METRES) {
                return "+" + metres(anomaly) + " over average";
            }
        }
        return "big swing";
    }

    private static double highWaterMetric(TideDerivation d) {
        return d.nextHighTideHeightMetres().doubleValue();
    }

    private static double rangeMetric(TideDerivation d) {
        BigDecimal low = d.nextLowTideHeightMetres();
        if (low == null) {
            // No low-tide height means no derivable range — never select or render this slot, rather
            // than fabricating a "range" equal to the raw high-water height.
            return Double.NEGATIVE_INFINITY;
        }
        return d.nextHighTideHeightMetres().doubleValue() - low.doubleValue();
    }

    private static String metres(double value) {
        return String.format(Locale.UK, "%.1f m", value);
    }
}
