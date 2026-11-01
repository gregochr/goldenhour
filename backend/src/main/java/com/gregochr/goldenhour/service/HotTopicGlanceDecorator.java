package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurgeRunDay;
import com.gregochr.goldenhour.model.TideRunDay;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fills the {@link GlanceLine} of every hot topic that does not already carry one — the second
 * line of the Coming up strip's chips (coming-up-glance plan §2).
 *
 * <p>Takes the whole list because a storm surge's PEAK badge is a judgement across topics. Never
 * overwrites a non-null glance: the inversion and mist strategies own a number the decorator must
 * not re-derive, so they stamp their own.
 *
 * <p>⚠️ <b>Called only at the end of {@code HotTopicAggregator.getHotTopics}, in both branches.</b>
 * {@code BriefingService} caches the aggregator's output and, on every serve, keeps the cached
 * briefing only when {@code Objects.equals(cached.hotTopics(), live)}; decorating anywhere else
 * makes that check always false or leaves the persisted copy without a glance. The glance is a pure
 * function of the list, so caching it is harmless.
 */
@Component
public class HotTopicGlanceDecorator {

    /**
     * The topic types that are weather-driven — only knowable once they are in the forecast.
     * Shared with the strategies that stamp their own glance.
     */
    public static final Set<String> WEATHER_TYPES = Set.of(
            "STORM_SURGE", "MIST", "SEA_MIST", "INVERSION", "DUST", "CLEARANCE",
            "SNOW_TOPS", "SNOW_MIST", "SNOW_FRESH", "AURORA", "NLC");

    private static final String SURGE = "STORM_SURGE";
    private static final String SPRING_TIDE = "SPRING_TIDE";
    private static final String KING_TIDE = "KING_TIDE";
    private static final String SUPERMOON = "SUPERMOON";
    private static final String DUST = "DUST";

    /** Types whose chip prints the first served fact value. */
    private static final Set<String> FIRST_FACT_TYPES = Set.of(
            "CLEARANCE", "SNOW_TOPS", "SNOW_MIST", "SNOW_FRESH", "AURORA", "NLC",
            "METEOR", "EQUINOX", "ECLIPSE", "LUNAR_ECLIPSE", "BLUEBELL");

    private static final String RISES_KEY = "rises";
    private static final String AOD_KEY = "AOD";
    private static final String FACT_SEPARATOR = " · ";
    private static final String RUN_STARTS_SUFFIX = " · run starts";
    private static final String PEAK_SHORT_SUFFIX = " pk";
    private static final String SUPERMOON_SHORT = "full";
    private static final String RISE_PREFIX = "rise ";
    private static final String HIGH_WATER_PREFIX = "HW ";
    private static final String COMMA = ",";
    private static final int FIRST_RUN_DAY = 1;

    /**
     * Returns the topics with a glance filled in on each that lacked one, in the same order.
     *
     * @param topics the aggregated topics; may be empty
     * @return a new list, equal in size and order; never null
     */
    public List<HotTopic> decorate(List<HotTopic> topics) {
        LocalDate surgePeakDate = surgePeakDate(topics);
        return topics.stream()
                .map(t -> t.glance() != null ? t : glanceFor(t, surgePeakDate))
                .toList();
    }

    private HotTopic glanceFor(HotTopic topic, LocalDate surgePeakDate) {
        String type = topic.type();
        if (SURGE.equals(type)) {
            boolean peakDate = topic.date() != null && topic.date().equals(surgePeakDate);
            return topic.withGlance(surgeGlance(topic.surgeRun(), peakDate));
        }
        if (SPRING_TIDE.equals(type) || KING_TIDE.equals(type)) {
            return topic.withGlance(tideGlance(topic.tideRun()));
        }
        if (SUPERMOON.equals(type)) {
            return topic.withGlance(supermoonGlance(topic));
        }
        if (DUST.equals(type)) {
            return topic.withGlance(dustGlance(topic));
        }
        if (FIRST_FACT_TYPES.contains(type)) {
            String first = firstFactValue(topic).orElse(null);
            return topic.withGlance(new GlanceLine(first, first, null, WEATHER_TYPES.contains(type)));
        }
        if (WEATHER_TYPES.contains(type)) {
            // A weather type no strategy stamped (e.g. a simulated one) is still marked WX.
            return topic.withGlance(new GlanceLine(null, null, null, true));
        }
        return topic;
    }

    /** The date carrying the largest surge among the list's surge topics; earliest wins a tie. */
    private static LocalDate surgePeakDate(List<HotTopic> topics) {
        LocalDate best = null;
        double bestMetres = Double.NEGATIVE_INFINITY;
        for (HotTopic t : topics) {
            if (!SURGE.equals(t.type()) || t.date() == null) {
                continue;
            }
            Optional<Double> max = maxSurge(t.surgeRun());
            if (max.isEmpty()) {
                continue;
            }
            double metres = max.get();
            boolean larger = metres > bestMetres;
            boolean tieEarlier = metres == bestMetres && best != null && t.date().isBefore(best);
            if (larger || tieEarlier) {
                best = t.date();
                bestMetres = metres;
            }
        }
        return best;
    }

    private static Optional<Double> maxSurge(SurgeRunDay run) {
        if (run == null || run.surgeMetres() == null) {
            return Optional.empty();
        }
        return run.surgeMetres().stream().filter(Objects::nonNull).max(Double::compare);
    }

    private static GlanceLine surgeGlance(SurgeRunDay run, boolean peakDate) {
        String badge = peakDate ? GlanceLine.BADGE_PEAK : null;
        if (run == null) {
            return new GlanceLine(null, null, badge, true);
        }
        String peak = run.peak();
        String hw = run.highWaterTime() == null ? null : HIGH_WATER_PREFIX + run.highWaterTime();
        String value;
        if (hw != null && peak != null) {
            value = hw + FACT_SEPARATOR + peak;
        } else {
            value = hw != null ? hw : peak;
        }
        String shortValue = peak == null ? null : peak + (peakDate ? PEAK_SHORT_SUFFIX : "");
        return new GlanceLine(value, shortValue, badge, true);
    }

    private static GlanceLine tideGlance(TideRunDay run) {
        if (run == null) {
            return new GlanceLine(null, null, null, false);
        }
        String range = run.range();
        boolean starts = run.dayNumber() == FIRST_RUN_DAY && run.dayCount() > 1;
        String value = range == null ? null : range + (starts ? RUN_STARTS_SUFFIX : "");
        String shortValue = range == null ? null : range + (run.peak() ? PEAK_SHORT_SUFFIX : "");
        return new GlanceLine(value, shortValue, run.peak() ? GlanceLine.BADGE_PEAK : null, false);
    }

    private static GlanceLine supermoonGlance(HotTopic topic) {
        String rise = factValue(topic, RISES_KEY)
                .map(v -> v.contains(COMMA) ? v.substring(0, v.indexOf(COMMA)) : v)
                .map(String::trim)
                .map(v -> RISE_PREFIX + v)
                .orElse(null);
        return new GlanceLine(rise, SUPERMOON_SHORT, GlanceLine.BADGE_FULL, false);
    }

    private static GlanceLine dustGlance(HotTopic topic) {
        String aod = factValue(topic, AOD_KEY).orElse(null);
        String number = aod == null ? null : aod.split(FACT_SEPARATOR.trim(), 2)[0].trim();
        return new GlanceLine(aod, number, null, true);
    }

    private static Optional<String> factValue(HotTopic topic, String key) {
        if (topic.facts() == null) {
            return Optional.empty();
        }
        return topic.facts().stream()
                .filter(f -> key.equals(f.key()) && f.value() != null)
                .map(HotTopicFact::value)
                .findFirst();
    }

    private static Optional<String> firstFactValue(HotTopic topic) {
        if (topic.facts() == null) {
            return Optional.empty();
        }
        return topic.facts().stream()
                .map(HotTopicFact::value)
                .filter(Objects::nonNull)
                .findFirst();
    }
}
