package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the one-line daily briefing headline summarising the best upcoming
 * photography opportunity across all configured regions.
 *
 * <p>Past solar events are excluded so the headline always reflects actionable conditions.
 */
@Component
public class BriefingHeadlineGenerator {

    /**
     * Generates a headline summarising the best upcoming opportunities across all days and events.
     *
     * @param days the briefing days
     * @return one-line headline string
     */
    public String generateHeadline(List<BriefingDay> days) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        record EventOpp(LocalDate date, TargetType event,
                List<BriefingRegion> allRegions, List<BriefingRegion> goRegions) { }

        List<EventOpp> goOpps = new ArrayList<>();
        for (BriefingDay day : days) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                if (day.date().equals(today) && isEventPast(es, now)) {
                    continue;
                }
                List<BriefingRegion> allRegions = es.regions();
                List<BriefingRegion> goRegions = allRegions.stream()
                        .filter(r -> r.verdict() == Verdict.GO)
                        .toList();
                if (!goRegions.isEmpty()) {
                    goOpps.add(new EventOpp(day.date(), es.targetType(), allRegions, goRegions));
                }
            }
        }

        if (goOpps.isEmpty()) {
            return findMarginalHeadline(days, today, now);
        }

        // Today beats tomorrow; within same day, more GO regions wins
        goOpps.sort(Comparator
                .comparingInt((EventOpp o) -> o.date().equals(today) ? 0 : 1)
                .thenComparingInt(o -> -o.goRegions().size()));

        EventOpp best = goOpps.get(0);
        String dayLabel = best.date().equals(today) ? "Today" : "Tomorrow";
        String emoji = best.event() == TargetType.SUNRISE ? "\uD83C\uDF05" : "\uD83C\uDF07";
        String eventLabel = best.event() == TargetType.SUNRISE ? "sunrise" : "sunset";
        int goCount = best.goRegions().size();
        String topRegion = best.goRegions().get(0).regionName();
        String breakdown = buildVerdictBreakdown(best.allRegions(), goCount);

        if (goCount >= 5) {
            return emoji + " " + dayLabel + " " + eventLabel
                    + " looking excellent \u2014 " + breakdown;
        }
        if (goCount >= 3) {
            return emoji + " " + dayLabel + " " + eventLabel + " \u2014 GO in "
                    + topRegion + " and " + (goCount - 1) + " more, " + breakdown;
        }
        if (goCount == 2) {
            return emoji + " " + dayLabel + " " + eventLabel + " GO in "
                    + topRegion + " and " + best.goRegions().get(1).regionName()
                    + buildNonGoSuffix(best.allRegions());
        }
        return emoji + " " + dayLabel + " " + eventLabel + " GO in " + topRegion
                + buildNonGoSuffix(best.allRegions());
    }

    /**
     * Returns true if all slots for this event summary have already passed.
     *
     * @param es  the event summary to check
     * @param now the current UTC time
     * @return true if the event is in the past
     */
    private boolean isEventPast(BriefingEventSummary es, LocalDateTime now) {
        return es.regions().stream()
                .flatMap(r -> r.slots().stream())
                .filter(s -> s.solarEventTime() != null)
                .findFirst()
                .map(BriefingSlot::solarEventTime)
                .map(t -> t.isBefore(now))
                .orElse(false);
    }

    /**
     * Finds the best marginal headline when no GO conditions exist, skipping past events.
     *
     * @param days  the briefing days
     * @param today today's date
     * @param now   the current UTC time
     * @return a marginal or standdown headline
     */
    private String findMarginalHeadline(List<BriefingDay> days, LocalDate today,
            LocalDateTime now) {
        for (BriefingDay day : days) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                if (day.date().equals(today) && isEventPast(es, now)) {
                    continue;
                }
                for (BriefingRegion region : es.regions()) {
                    if (region.verdict() == Verdict.MARGINAL) {
                        String dayLabel = day.date().equals(today) ? "today" : "tomorrow";
                        String eventLabel = es.targetType() == TargetType.SUNRISE
                                ? "sunrise" : "sunset";
                        return "Marginal only \u2014 best: " + dayLabel + " "
                                + eventLabel + " in " + region.regionName();
                    }
                }
            }
        }
        return "No promising conditions in the next two days";
    }

    /**
     * Builds a full verdict breakdown string, e.g. "6 regions GO, 1 region MARGINAL".
     *
     * @param allRegions all regions for the event
     * @param goCount    pre-computed GO count
     * @return breakdown string
     */
    private String buildVerdictBreakdown(List<BriefingRegion> allRegions, int goCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(goCount).append(goCount == 1 ? " region GO" : " regions GO");
        appendVerdictCounts(allRegions, sb);
        return sb.toString();
    }

    /**
     * Builds a suffix listing non-GO verdict counts for 1-2 GO region headline cases.
     * Returns empty string if all regions are GO.
     *
     * @param allRegions all regions for the event
     * @return suffix string, e.g. ", 2 regions STANDDOWN"
     */
    private String buildNonGoSuffix(List<BriefingRegion> allRegions) {
        StringBuilder sb = new StringBuilder();
        appendVerdictCounts(allRegions, sb);
        return sb.toString();
    }

    /**
     * Appends MARGINAL and STANDDOWN region counts to the given builder.
     *
     * @param allRegions all regions for the event
     * @param sb         the builder to append to
     */
    private void appendVerdictCounts(List<BriefingRegion> allRegions, StringBuilder sb) {
        long marginal = allRegions.stream()
                .filter(r -> r.verdict() == Verdict.MARGINAL).count();
        long standdown = allRegions.stream()
                .filter(r -> r.verdict() == Verdict.STANDDOWN).count();
        if (marginal > 0) {
            sb.append(", ").append(marginal)
                    .append(marginal == 1 ? " region MARGINAL" : " regions MARGINAL");
        }
        if (standdown > 0) {
            sb.append(", ").append(standdown)
                    .append(standdown == 1 ? " region STANDDOWN" : " regions STANDDOWN");
        }
    }
}
