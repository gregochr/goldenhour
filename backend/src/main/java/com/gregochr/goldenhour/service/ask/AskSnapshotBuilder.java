package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.PlanWindowProjector;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the {@link AskSnapshot} (plan §2.2) from {@link BriefingService#getCachedBriefingForApi()}
 * and nothing else about ratings.
 *
 * <p><b>The window set</b> is the events listed in the served briefing's {@code renderedEvents} (the
 * Plan tab's six; every summary carries a {@code window()}, so the cap is {@code renderedEvents}
 * and not window presence) whose {@code window()} is non-null, that
 * {@link PlanWindowProjector#hasPassed} has not retired, that are solar, and that are not travel
 * days. The elapsed test is the shared projector method, so Ask and the Plan tab retire a window
 * at the same minute.
 *
 * <p><b>Travel days are not marked on the served briefing.</b> A travel day still carries a window
 * and slots; the Plan client learns which dates are away from a separate {@code /api/travel-days}
 * fetch. The server-side test is {@link TravelDayService#isTravelDay}, the same one
 * {@code BriefingRollupBuilder} applies before the best-bet advisor sees a date, so it is used
 * here, once per distinct date.
 *
 * <p>{@link #current()} memoises the snapshot for {@value #MEMO_SECONDS} seconds: the assembly it
 * reads is the full Plan-tab one and is not cheap.
 */
@Service
public class AskSnapshotBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(AskSnapshotBuilder.class);

    /** How long {@link #current()} reuses a snapshot. */
    static final int MEMO_SECONDS = 30;

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter RUN_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final Comparator<AskSnapshot.Window> CHRONOLOGICAL =
            Comparator.comparing(AskSnapshot.Window::date)
                    .thenComparing(AskSnapshot.Window::targetType);

    private final BriefingService briefingService;
    private final TravelDayService travelDayService;
    private final AlmanacService almanacService;
    private final SolarEventFreshness freshness;
    private final Clock clock;

    private Instant memoBuiltAt;
    private AskSnapshot memo;

    /**
     * Creates the builder.
     *
     * @param briefingService  the source of the served briefing
     * @param travelDayService the away-date test
     * @param almanacService   the source of the "Coming up" entries
     * @param freshness        the project's single answer to "now" for solar events
     * @param clock            the application clock, for the memo and the UK civil date
     */
    public AskSnapshotBuilder(BriefingService briefingService, TravelDayService travelDayService,
            AlmanacService almanacService, SolarEventFreshness freshness, Clock clock) {
        this.briefingService = briefingService;
        this.travelDayService = travelDayService;
        this.almanacService = almanacService;
        this.freshness = freshness;
        this.clock = clock;
    }

    /**
     * The snapshot, reused for {@value #MEMO_SECONDS} seconds.
     *
     * @return the snapshot, or empty when no briefing has been built yet
     */
    public synchronized Optional<AskSnapshot> current() {
        Instant now = clock.instant();
        if (memo != null && Duration.between(memoBuiltAt, now).compareTo(
                Duration.ofSeconds(MEMO_SECONDS)) < 0) {
            return Optional.of(memo);
        }
        Optional<AskSnapshot> built = build();
        built.ifPresent(snapshot -> {
            memo = snapshot;
            memoBuiltAt = now;
        });
        return built;
    }

    /**
     * Builds a fresh snapshot, bypassing the memo.
     *
     * @return the snapshot, or empty when no briefing has been built yet
     */
    public Optional<AskSnapshot> build() {
        DailyBriefingResponse briefing = briefingService.getCachedBriefingForApi();
        if (briefing == null) {
            return Optional.empty();
        }
        LocalDateTime now = freshness.now();
        Set<AskWindowKey> rendered = renderedKeys(briefing);
        Map<LocalDate, Boolean> travel = new HashMap<>();
        List<AskSnapshot.Window> windows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BriefingDay day : briefing.days()) {
            if (day == null || day.date() == null) {
                continue;
            }
            for (BriefingEventSummary summary : day.eventSummaries()) {
                AskSnapshot.Window window = toWindow(day.date(), summary, now, travel, rendered);
                if (window != null && seen.add(window.id())) {
                    windows.add(window);
                }
            }
        }
        windows.sort(CHRONOLOGICAL);
        LocalDateTime generatedAt = briefing.generatedAt();
        return Optional.of(new AskSnapshot(generatedAt, runLabel(generatedAt),
                ForecastHorizon.today(clock), windows, topics(briefing), comingUp()));
    }

    /**
     * The {@code HH:mm} Europe/London label of a briefing build time.
     *
     * @param generatedAt the build time (UTC), or null
     * @return the label, or null for a null time
     */
    static String runLabel(LocalDateTime generatedAt) {
        if (generatedAt == null) {
            return null;
        }
        return generatedAt.atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).format(RUN_LABEL);
    }

    private AskSnapshot.Window toWindow(LocalDate date, BriefingEventSummary summary,
            LocalDateTime now, Map<LocalDate, Boolean> travel, Set<AskWindowKey> rendered) {
        if (summary == null || summary.window() == null || !isSolar(summary.targetType())
                || !rendered.contains(new AskWindowKey(date, summary.targetType()))) {
            return null;
        }
        BriefingWindow window = summary.window();
        if (PlanWindowProjector.hasPassed(window.eventTime(), now)) {
            return null;
        }
        if (travel.computeIfAbsent(date, travelDayService::isTravelDay)) {
            return null;
        }
        List<AskSnapshot.Region> regions = summary.regions().stream().map(this::toRegion).toList();
        return new AskSnapshot.Window(AskWindowId.format(date, summary.targetType()), date,
                summary.targetType(), window.eventTime(), window.verdict(), window.bestRating(),
                window.pick(), regions);
    }

    private AskSnapshot.Region toRegion(BriefingRegion region) {
        return new AskSnapshot.Region(region.regionName(), region.displayVerdict(),
                region.meanRating(), region.verdictEligible(),
                region.slots().stream().map(AskSnapshotBuilder::toSlot).toList());
    }

    private static AskSnapshot.Slot toSlot(BriefingSlot slot) {
        BriefingSlot.TideInfo tide = slot.tide();
        return new AskSnapshot.Slot(slot.locationId(), slot.locationName(), slot.claudeRating(),
                slot.displayVerdict(), slot.claudeHeadline(), slot.canopy(),
                tide == null ? null : tide.tideState(),
                tide != null && tide.tideAligned(),
                tide == null ? null : tide.tideFitPhrase());
    }

    /** A rendered event's identity: its date and target type. */
    private record AskWindowKey(LocalDate date, TargetType targetType) {
    }

    /**
     * The events the Plan tab draws: {@code DailyBriefingResponse.renderedEvents}, which
     * {@code PlanWindowProjector} publishes on every served briefing (the leading six non-past
     * events). A null or empty list yields no keys, so a payload that was never projected offers
     * no windows rather than every window.
     */
    private static Set<AskWindowKey> renderedKeys(DailyBriefingResponse briefing) {
        List<PlanRenderedEvent> events = briefing.renderedEvents();
        if (events == null) {
            return Set.of();
        }
        return events.stream().map(e -> new AskWindowKey(e.date(), e.targetType()))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean isSolar(TargetType type) {
        return type == TargetType.SUNRISE || type == TargetType.SUNSET;
    }

    private static List<AskSnapshot.Topic> topics(DailyBriefingResponse briefing) {
        List<HotTopic> served = briefing.hotTopics();
        if (served == null) {
            return List.of();
        }
        return served.stream()
                .map(t -> new AskSnapshot.Topic(t.type(), t.label(), t.detail(), t.date(),
                        t.regions()))
                .toList();
    }

    private List<AskSnapshot.ComingUp> comingUp() {
        try {
            List<ComingUpEntry> entries = almanacService.getFeed(AlmanacService.DEFAULT_DAYS)
                    .entries();
            return entries.stream()
                    .map(e -> new AskSnapshot.ComingUp(e.type(), e.title(), e.startDate(),
                            e.endDate(), e.detail()))
                    .toList();
        } catch (RuntimeException e) {
            // The almanac is an extra surface; Ask still answers about windows without it.
            LOG.warn("[ASK] Could not read the Coming up feed for the snapshot: {}",
                    e.toString());
            return List.of();
        }
    }
}
