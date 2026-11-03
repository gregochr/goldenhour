package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The dry run behind the Scheduler editors: what a proposed schedule would do, without saving it
 * (scheduler-redesign-plan.md D8).
 *
 * <p>The proposal is validated by {@link DynamicSchedulerService#validateSchedule}, the same rules
 * {@code updateSchedule} and {@code switchScheduleType} apply, and its fires are computed by the scheduler's own
 * arithmetic ({@link SchedulerJobOverviewService#nextFires}, {@link DynamicSchedulerService#firesBetween}, the solar
 * trigger), on a transient copy of the job, so the preview can never disagree with what saving would do. The
 * client never converts UK times to a cron: the {@code CRON} proposal is a list of {@code HH:MM} times, and the
 * cron comes back in the response.
 *
 * <p>A proposal that fails validation is a normal answer ({@code valid: false} with the message), not an error; an
 * unknown job (404) or a schedule type the job may not use (400) is an error. Nothing is written.
 */
@Service
public class SchedulePreviewService {

    /** How far apart two jobs' fires may be to be neighbours, in minutes. */
    static final int NEIGHBOUR_MINUTES = 30;

    /** The message for times that cannot share one cron expression. */
    static final String MIXED_MINUTES = "All times must share the same minutes past the hour (e.g. 06:15 and 18:15)";

    /** How far ahead the preview looks for its three fires when the overview's nearer windows hold fewer. */
    static final Duration FAR_WINDOW = Duration.ofDays(60);

    private static final Duration NEIGHBOUR_WINDOW = Duration.ofHours(24);
    private static final ZoneId LONDON = ZoneId.of(DynamicSchedulerService.UK_ZONE_ID);
    private static final Duration NEIGHBOUR_DISTANCE = Duration.ofMinutes(NEIGHBOUR_MINUTES);
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final Set<String> DAYS = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    /**
     * A proposed schedule. Which fields are read depends on {@code scheduleType}.
     *
     * @param scheduleType       {@code SOLAR_OFFSET}, {@code CRON} or {@code FIXED_DELAY}
     * @param solarEvent         the solar event (SOLAR_OFFSET)
     * @param solarOffsetMinutes minutes before the event (SOLAR_OFFSET)
     * @param times              the {@code HH:MM} times of day (CRON), all sharing one minute value
     * @param daysOfWeek         {@code MON}..{@code SUN} (CRON); null or empty for every day
     * @param timeZone           {@code Europe/London} or null/{@code UTC} (CRON)
     * @param fixedDelayMs       the delay in ms (FIXED_DELAY)
     */
    public record Proposal(ScheduleType scheduleType, SolarEvent solarEvent, Integer solarOffsetMinutes,
            List<String> times, List<String> daysOfWeek, String timeZone, Long fixedDelayMs) {
    }

    /**
     * A solar proposal's earliest and latest fire time of day over the next year.
     *
     * @param earliest UK local {@code HH:mm}
     * @param latest   UK local {@code HH:mm}
     */
    public record YearRange(String earliest, String latest) {
    }

    /**
     * Another job that fires close to the proposal.
     *
     * @param jobKey       the other job
     * @param displayName  its display name
     * @param at           its fire closest to one of the proposal's
     * @param minutesApart whole minutes between the two fires
     */
    public record Neighbour(String jobKey, String displayName, Instant at, long minutesApart) {
    }

    /**
     * The dry-run answer.
     *
     * @param valid          whether the proposal would be accepted
     * @param error          why not, when invalid
     * @param cronExpression the cron a CRON proposal becomes, else null
     * @param timeZone       the zone a CRON proposal is read in ({@code UTC} or {@code Europe/London}), else null
     * @param nextFires      up to three upcoming fires
     * @param yearRange      a SOLAR_OFFSET proposal's year range, else null
     * @param neighbours     other active, non-repeating jobs firing within {@value #NEIGHBOUR_MINUTES} minutes of
     *                       the proposal's fires in the next 24 hours; empty when the proposal itself repeats
     */
    public record Preview(boolean valid, String error, String cronExpression, String timeZone,
            List<Instant> nextFires, YearRange yearRange, List<Neighbour> neighbours) {

        private static Preview invalid(String error) {
            return new Preview(false, error, null, null, List.of(), null, List.of());
        }
    }

    private final SchedulerJobConfigRepository configs;
    private final DynamicSchedulerService scheduler;
    private final SchedulerJobOverviewService overview;
    private final SolarSchedulePreviewService solarPreview;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param configs      the scheduler job rows
     * @param scheduler    the scheduler: validation and fire arithmetic
     * @param overview     lists a job's next fires
     * @param solarPreview the solar year scan
     * @param clock        the application clock
     */
    public SchedulePreviewService(SchedulerJobConfigRepository configs, DynamicSchedulerService scheduler,
            SchedulerJobOverviewService overview, SolarSchedulePreviewService solarPreview, Clock clock) {
        this.configs = configs;
        this.scheduler = scheduler;
        this.overview = overview;
        this.solarPreview = solarPreview;
        this.clock = clock;
    }

    /**
     * Previews a proposed schedule for a job. Saves nothing.
     *
     * @param jobKey   the job key
     * @param proposal the proposed schedule
     * @return the preview
     * @throws java.util.NoSuchElementException if the job is unknown
     * @throws IllegalArgumentException         if the proposal names no type, or SOLAR_OFFSET for a job that may not
     *                                          use it
     */
    public Preview preview(String jobKey, Proposal proposal) {
        SchedulerJobConfigEntity config = configs.findByJobKey(jobKey).orElseThrow();
        ScheduleType type = proposal.scheduleType();
        if (type == null) {
            throw new IllegalArgumentException("scheduleType is required");
        }
        if (type == ScheduleType.SOLAR_OFFSET) {
            DynamicSchedulerService.requireSolarAllowed(jobKey);
        }
        try {
            String cron = type == ScheduleType.CRON ? cronOf(proposal) : config.getCronExpression();
            String zone = type == ScheduleType.CRON
                    ? DynamicSchedulerService.normaliseScheduleZone(proposal.timeZone()) : null;
            scheduler.validateSchedule(jobKey, type, cron, proposal.fixedDelayMs(), proposal.solarEvent(),
                    proposal.solarOffsetMinutes(), zone);
            SchedulerJobConfigEntity transientConfig = SchedulerJobConfigEntity.builder()
                    .jobKey(jobKey).displayName(config.getDisplayName()).scheduleType(type).cronExpression(cron)
                    .fixedDelayMs(proposal.fixedDelayMs()).initialDelayMs(config.getInitialDelayMs())
                    .lastCompletionTime(config.getLastCompletionTime()).solarEvent(proposal.solarEvent())
                    .solarOffsetMinutes(proposal.solarOffsetMinutes()).timeZone(zone)
                    .status(SchedulerJobStatus.ACTIVE).build();
            return new Preview(true, null, type == ScheduleType.CRON ? cron : null,
                    type == ScheduleType.CRON ? (zone == null ? "UTC" : zone) : null,
                    nextFires(transientConfig), yearRange(jobKey, type, proposal, cron),
                    neighbours(jobKey, transientConfig));
        } catch (IllegalArgumentException e) {
            return Preview.invalid(e.getMessage());
        }
    }

    /**
     * The next three fires: the overview's own answer, widened to {@link #FAR_WINDOW} when a weekly schedule has
     * fewer than three in the overview's windows.
     */
    private List<Instant> nextFires(SchedulerJobConfigEntity proposed) {
        List<Instant> fires = overview.nextFires(proposed);
        if (fires.size() >= SchedulerJobOverviewService.NEXT_FIRES
                || proposed.getScheduleType() == ScheduleType.FIXED_DELAY) {
            return fires;
        }
        Instant now = clock.instant();
        return scheduler.firesBetween(proposed, now, now.plus(FAR_WINDOW)).stream()
                .limit(SchedulerJobOverviewService.NEXT_FIRES).toList();
    }

    private YearRange yearRange(String jobKey, ScheduleType type, Proposal proposal, String cron) {
        if (type != ScheduleType.SOLAR_OFFSET) {
            return null;
        }
        SolarSchedulePreviewService.YearRange range = solarPreview.yearRange(jobKey, proposal.solarEvent(),
                proposal.solarOffsetMinutes(), cron, ForecastHorizon.today(clock));
        return range.earliest() == null ? null
                : new YearRange(range.earliest().fireLocal(), range.latest().fireLocal());
    }

    private List<Neighbour> neighbours(String jobKey, SchedulerJobConfigEntity proposed) {
        Instant now = clock.instant();
        Instant end = now.plus(NEIGHBOUR_WINDOW);
        LocalDate today = ForecastHorizon.today(clock);
        Instant dayAfter = today.atStartOfDay(LONDON).toInstant().minusSeconds(1);
        Instant dayEnd = today.plusDays(1).atStartOfDay(LONDON).toInstant();
        if (scheduler.isRepeating(proposed, dayAfter, dayEnd)) {
            return List.of();
        }
        List<Instant> mine = scheduler.firesBetween(proposed, now, end);
        List<Neighbour> found = new ArrayList<>();
        for (SchedulerJobConfigEntity other : scheduler.getAll()) {
            if (other.getJobKey().equals(jobKey) || other.getStatus() != SchedulerJobStatus.ACTIVE
                    || scheduler.isRepeating(other, dayAfter, dayEnd)) {
                continue;
            }
            closest(other, mine, scheduler.firesBetween(other, now, end)).ifPresent(found::add);
        }
        found.sort(Comparator.comparingLong(Neighbour::minutesApart).thenComparing(Neighbour::jobKey));
        return found;
    }

    private static Optional<Neighbour> closest(SchedulerJobConfigEntity other, List<Instant> mine,
            List<Instant> theirs) {
        Neighbour best = null;
        Duration bestGap = null;
        for (Instant theirFire : theirs) {
            for (Instant myFire : mine) {
                Duration gap = Duration.between(myFire, theirFire).abs();
                if (gap.compareTo(NEIGHBOUR_DISTANCE) <= 0 && (bestGap == null || gap.compareTo(bestGap) < 0)) {
                    bestGap = gap;
                    best = new Neighbour(other.getJobKey(), other.getDisplayName(), theirFire, gap.toMinutes());
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Builds {@code 0 M H1,H2 * * DOW} from the proposal's times. Spring cron cannot express 06:15 and 18:45 in one
     * expression, so every time must have the same minute.
     */
    private static String cronOf(Proposal proposal) {
        List<String> times = proposal.times();
        if (times == null || times.isEmpty()) {
            throw new IllegalArgumentException("At least one time is required");
        }
        Set<Integer> hours = new TreeSet<>();
        Set<Integer> minutes = new LinkedHashSet<>();
        for (String time : times) {
            Matcher matcher = TIME.matcher(time == null ? "" : time.trim());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("times must be HH:MM, got '"
                        + (time == null ? "" : time.replaceAll("[^0-9A-Za-z:]", "?")) + "'");
            }
            hours.add(Integer.parseInt(matcher.group(1)));
            minutes.add(Integer.parseInt(matcher.group(2)));
        }
        if (minutes.size() > 1) {
            throw new IllegalArgumentException(MIXED_MINUTES);
        }
        return "0 " + minutes.iterator().next() + " "
                + hours.stream().map(String::valueOf).collect(Collectors.joining(",")) + " * * "
                + daysOf(proposal.daysOfWeek());
    }

    private static String daysOf(List<String> daysOfWeek) {
        if (daysOfWeek == null || daysOfWeek.isEmpty()) {
            return "*";
        }
        Set<String> days = new LinkedHashSet<>();
        for (String day : daysOfWeek) {
            String upper = day == null ? "" : day.trim().toUpperCase(Locale.ROOT);
            if (!DAYS.contains(upper)) {
                throw new IllegalArgumentException("daysOfWeek must be MON, TUE, WED, THU, FRI, SAT or SUN");
            }
            days.add(upper);
        }
        return String.join(",", days);
    }
}
