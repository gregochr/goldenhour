package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.Rewind;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The recent solar events an admin can rewind the app to, with the instant each one should be
 * rewound to.
 *
 * <p>The rewind feature (admin only — {@code X-Rewind-To}, {@link com.gregochr.goldenhour.config
 * .RewindFilter}) makes the app render as it would have at an earlier moment, so a photographer
 * who went out for this morning's sunrise can screenshot the forecast that sent them there after the
 * event has passed. This service answers "which events, and what moment": for the UK civil dates
 * from {@value ForecastHorizon#SERVE_PAST_DAYS} days ago through today, both solar events, timed across the whole
 * enabled sky roster — the earliest and latest event time, since a sunrise spans ~20 minutes
 * across the British Isles — and a {@code rewindTo} of {@value #LEAD_MINUTES} minutes before the
 * roster's earliest event, which is before the window at every location without being so early
 * that the day reads as the night before.
 *
 * <p>Deliberately not a snapshot: a rewind turns the clock back, never the data. What the app then
 * shows is the current forecast cache rendered as of that moment. For a window that has passed that
 * is normally what was shown at the time, since the pipeline does not re-score a slot once its event
 * has gone, but {@code briefingGeneratedAt} is served beside the events so the admin can see when
 * the briefing was last built relative to the moment they are rewinding to.
 *
 * <p>⚠️ <b>The Plan tab can only show a day the current briefing holds.</b> {@code BriefingService
 * .doRefreshBriefing()} rebuilds the cached payload for the real {@code today..today+3} every cycle,
 * and the serve path only projects those cached days — a rewound clock cannot reconstruct a
 * {@code BriefingDay} the last build dropped. So once today's first cycle has run, yesterday's
 * sunrise is gone from the Plan tab for good, even though the Map tab (which reads
 * {@code forecast_evaluation} two days back) would still draw it. Each event therefore carries
 * {@link RewindEvent#inBriefing()}, read off {@link BriefingService#getCachedDays()}, and the
 * Operations view offers only the events that are both passed and still in the briefing — a
 * Codex review of #998 found the first cut offered three days of events the Plan tab could not
 * honour after the next refresh.
 */
@Service
public class RewindEventService {

    /** How long before the roster's earliest event time the rewind instant is placed. */
    static final int LEAD_MINUTES = 60;

    private final LocationRepository locationRepository;
    private final SolarEventFreshness freshness;
    private final BriefingService briefingService;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param locationRepository the roster
     * @param freshness          per-location solar event times and the current UTC time
     * @param briefingService    for the cached briefing's build time
     * @param clock              the application clock, for the UK civil date
     */
    public RewindEventService(LocationRepository locationRepository, SolarEventFreshness freshness,
            BriefingService briefingService, Clock clock) {
        this.locationRepository = locationRepository;
        this.freshness = freshness;
        this.briefingService = briefingService;
        this.clock = clock;
    }

    /**
     * The recent solar events, newest first (today's sunset, today's sunrise, yesterday's sunset,
     * …), each timed across the enabled sky roster.
     *
     * @return the events plus the current instant and the cached briefing's build time
     */
    public RewindEvents events() {
        LocalDateTime now = freshness.now();
        LocalDate today = ForecastHorizon.today(clock);
        List<LocationEntity> roster = locationRepository.findAllByEnabledTrueOrderByNameAsc().stream()
                .filter(LocationEntity::hasColourTypes)
                .toList();
        Set<LocalDate> briefed = briefedDates();
        List<RewindEvent> events = new ArrayList<>();
        for (int back = 0; back <= ForecastHorizon.SERVE_PAST_DAYS; back++) {
            LocalDate date = today.minusDays(back);
            for (TargetType type : List.of(TargetType.SUNSET, TargetType.SUNRISE)) {
                RewindEvent event = eventFor(roster, date, type, now, briefed.contains(date));
                if (event != null) {
                    events.add(event);
                }
            }
        }
        LocalDateTime generatedAt = briefingService.getCachedGeneratedAt();
        return new RewindEvents(
                now.toInstant(ZoneOffset.UTC),
                generatedAt == null ? null : generatedAt.toInstant(ZoneOffset.UTC),
                (int) Rewind.MAX_AGE.toDays(),
                events);
    }

    /** The dates the cached briefing holds a {@code BriefingDay} for; empty when there is no briefing. */
    private Set<LocalDate> briefedDates() {
        List<BriefingDay> days = briefingService.getCachedDays();
        if (days == null) {
            return Set.of();
        }
        Set<LocalDate> dates = new HashSet<>();
        for (BriefingDay day : days) {
            if (day != null && day.date() != null) {
                dates.add(day.date());
            }
        }
        return dates;
    }

    private RewindEvent eventFor(List<LocationEntity> roster, LocalDate date, TargetType type,
            LocalDateTime now, boolean inBriefing) {
        LocalDateTime earliest = null;
        LocalDateTime latest = null;
        int count = 0;
        for (LocationEntity location : roster) {
            LocalDateTime time = freshness.eventTime(location, date, type);
            if (time == null) {
                continue;
            }
            count++;
            if (earliest == null || time.isBefore(earliest)) {
                earliest = time;
            }
            if (latest == null || time.isAfter(latest)) {
                latest = time;
            }
        }
        if (earliest == null) {
            return null;
        }
        return new RewindEvent(
                date,
                type,
                earliest.toInstant(ZoneOffset.UTC),
                latest.toInstant(ZoneOffset.UTC),
                earliest.minusMinutes(LEAD_MINUTES).toInstant(ZoneOffset.UTC),
                PlanWindowProjector.hasPassed(latest, now),
                inBriefing,
                count);
    }

    /**
     * The answer: the current instant, the cached briefing's build time and the events.
     *
     * @param now                 the current instant (UTC) — the real one, never a rewound one,
     *                            since {@code /api/admin/**} is never rewound
     * @param briefingGeneratedAt when the cached briefing was built, or null if none exists
     * @param maxAgeDays          how many days back a rewind may reach ({@code Rewind.MAX_AGE}), so
     *                            the admin screen bounds a hand-typed moment where the filter would
     *                            refuse it, without carrying a copy of the number
     * @param events              the recent solar events, newest first
     */
    public record RewindEvents(Instant now, Instant briefingGeneratedAt, int maxAgeDays,
            List<RewindEvent> events) { }

    /**
     * One solar event across the roster.
     *
     * @param date          the UK civil date of the event
     * @param eventType     SUNRISE or SUNSET
     * @param earliest      the earliest event time on the roster
     * @param latest        the latest event time on the roster
     * @param rewindTo      the instant to rewind to for this event ({@value #LEAD_MINUTES} minutes
     *                      before {@code earliest})
     * @param passed        whether the window has gone by the Plan tab's own elapsed rule
     *                      ({@link PlanWindowProjector#hasPassed}, afterglow included) at the
     *                      roster's latest event time
     * @param inBriefing    whether the cached briefing still holds this date — only then can the
     *                      Plan tab show the window (see the class javadoc); false when there is
     *                      no briefing at all
     * @param locationCount how many enabled sky locations the times were measured across
     */
    public record RewindEvent(LocalDate date, TargetType eventType, Instant earliest, Instant latest,
            Instant rewindTo, boolean passed, boolean inBriefing, int locationCount) { }
}
