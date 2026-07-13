package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Enriches best-bet picks with structured display fields (dayName, eventType, eventTime)
 * derived from the triage data hierarchy, not from Claude's output.
 *
 * <p>Instance-scoped seam extracted from {@code BriefingBestBetAdvisor}: it needs the injected
 * {@link Clock} to resolve "today"/"tomorrow" day labels in the UK civil-date zone.
 */
public final class BestBetEnricher {

    /** UK civil-date zone for "today" derivation. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final Clock clock;

    /**
     * Constructs a {@code BestBetEnricher}.
     *
     * @param clock UTC clock supplying "today" (via London) for day-label derivation
     */
    public BestBetEnricher(Clock clock) {
        this.clock = clock;
    }

    /**
     * Enriches picks with structured display fields (dayName, eventType, eventTime)
     * derived from the triage data hierarchy, not from Claude's output.
     *
     * @param picks the validated, ranked picks to enrich
     * @param days  the triage briefing days supplying event times
     * @return the picks with display fields populated
     */
    public List<BestBet> enrichWithEventData(List<BestBet> picks, List<BriefingDay> days) {
        LocalDate today = LocalDate.now(clock.withZone(LONDON));
        return picks.stream().map(pick -> {
            if (pick.event() == null) {
                return pick;
            }
            if (pick.event().endsWith("_aurora")) {
                String[] parts = pick.event().split("_", 2);
                LocalDate date = LocalDate.parse(parts[0]);
                String dayName;
                if (date.equals(today)) {
                    dayName = "Today";
                } else if (date.equals(today.plusDays(1))) {
                    dayName = "Tomorrow";
                } else {
                    dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                }
                return new BestBet(pick.rank(), pick.headline(), pick.detail(),
                        pick.event(), pick.region(), pick.confidence(), pick.nearestDriveMinutes(),
                        dayName, "aurora", "after dark",
                        pick.relationship(), pick.differsBy());
            }
            String[] parts = pick.event().split("_", 2);
            if (parts.length < 2) {
                return pick;
            }
            LocalDate date = LocalDate.parse(parts[0]);
            String eventType = parts[1];

            String dayName;
            if (date.equals(today)) {
                dayName = "Today";
            } else if (date.equals(today.plusDays(1))) {
                dayName = "Tomorrow";
            } else {
                dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            }

            String eventTime = findEventTime(date, eventType, days);

            return new BestBet(pick.rank(), pick.headline(), pick.detail(),
                    pick.event(), pick.region(), pick.confidence(), pick.nearestDriveMinutes(),
                    dayName, eventType, eventTime,
                    pick.relationship(), pick.differsBy());
        }).toList();
    }

    /**
     * Looks up the UK-local event time from the triage data for the given date and event type.
     */
    private String findEventTime(LocalDate date, String eventType, List<BriefingDay> days) {
        TargetType targetType;
        try {
            targetType = TargetType.valueOf(eventType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        ZoneId ukZone = ZoneId.of("Europe/London");
        return days.stream()
                .filter(d -> d.date().equals(date))
                .flatMap(d -> d.eventSummaries().stream())
                .filter(es -> es.targetType() == targetType)
                .flatMap(es -> es.regions().stream())
                .flatMap(r -> r.slots().stream())
                .filter(s -> s.solarEventTime() != null)
                .findFirst()
                .map(s -> s.solarEventTime().atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(ukZone)
                        .format(DateTimeFormatter.ofPattern("HH:mm")))
                .orElse(null);
    }
}
