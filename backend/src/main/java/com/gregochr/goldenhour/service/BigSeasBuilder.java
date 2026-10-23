package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BigSeasCandidate;
import com.gregochr.goldenhour.model.BigSeasDay;
import com.gregochr.goldenhour.model.BigSeasDay.AlignedEvent;
import com.gregochr.goldenhour.model.BigSeasDay.GapSide;
import com.gregochr.goldenhour.model.BigSeasStructure;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.repository.MarineHighWaterRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the served {@code bigSeas} block: per day, the one lead group of overlapping big-sea
 * high-water windows at the big-seas structures (docs/engineering/big-seas-plan.md §3 B2).
 *
 * <p>Reads {@code marine_high_water} through one projection query on every serve and applies
 * {@link BigSeasRule} to the stored numbers, so retuning a threshold needs no refetch. Modelled on
 * {@link WindowTideRollupBuilder}: a DB read per serve, <b>fail-soft</b>, never persisted into the
 * briefing cache. The location's own solar event types are ignored (waves are waves at any time of
 * day), so Roker, a sunrise-only location, can be aligned with a sunset.
 */
@Component
public class BigSeasBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(BigSeasBuilder.class);

    /** The block covers the briefing's five days: today plus this many. */
    private static final int HORIZON_DAYS_AHEAD = 4;

    private static final double SECONDS_PER_MINUTE = 60.0;

    private final MarineHighWaterRepository repository;
    private final SolarService solarService;
    private final BigSeasProperties properties;
    private final Clock clock;

    /**
     * Constructs a {@code BigSeasBuilder}.
     *
     * @param repository   the high-water samples
     * @param solarService supplies each structure's own sunrise and sunset
     * @param properties   the thresholds and the sample age bound
     * @param clock        injectable clock; "today" is the UK civil date and "now" is compared in UTC
     */
    public BigSeasBuilder(MarineHighWaterRepository repository, SolarService solarService,
            BigSeasProperties properties, Clock clock) {
        this.repository = repository;
        this.solarService = solarService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Builds the big-seas days for a served briefing.
     *
     * <p><b>Fail-soft by construction.</b> This runs on the serve path of the busiest endpoint for a
     * decorative line, so any failure is logged and the block is simply absent.
     *
     * @param days the served briefing's days; may be null or empty
     * @return one entry per qualifying day, ascending, or null when there are none or the build failed
     */
    public List<BigSeasDay> build(List<BriefingDay> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        try {
            List<BigSeasDay> built = buildOrThrow(days);
            return built.isEmpty() ? null : built;
        } catch (RuntimeException e) {
            LOG.warn("Big seas build failed - this serve carries no bigSeas block", e);
            return null;
        }
    }

    private List<BigSeasDay> buildOrThrow(List<BriefingDay> days) {
        LocalDate today = ForecastHorizon.today(clock);
        LocalDate last = today.plusDays(HORIZON_DAYS_AHEAD);
        TreeSet<LocalDate> served = new TreeSet<>();
        for (BriefingDay day : days) {
            LocalDate date = day.date();
            if (!date.isBefore(today) && !date.isAfter(last)) {
                served.add(date);
            }
        }
        if (served.isEmpty()) {
            return List.of();
        }
        LocalDateTime nowUtc = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        List<BigSeasCandidate> candidates = repository.findBigSeasCandidates(
                clock.instant().minus(properties.getMaxSampleAgeHours(), ChronoUnit.HOURS),
                ForecastHorizon.ukDayStartUtc(served.first()),
                ForecastHorizon.ukDayStartUtc(served.last().plusDays(1)));
        Map<LocalDate, List<Member>> byDate = new TreeMap<>();
        for (BigSeasCandidate c : candidates) {
            LocalDate date = ForecastHorizon.civilDate(c.hwTime().toInstant(ZoneOffset.UTC));
            if (!served.contains(date)) {
                continue;
            }
            boolean qualifies = BigSeasRule.qualifies(c.waveHeightM(), c.waveDirectionDeg(), c.wavePeriodS(),
                    c.facesDeg(), c.windowStart(), c.windowEnd(), properties);
            boolean past = c.windowEnd().isBefore(nowUtc);
            LOG.debug("[BIG SEAS] {} hw={} window={}..{} wave={}m dir={} period={}s faces={} off={} past={} -> {}",
                    c.locationName(), c.hwTime(), c.windowStart(), c.windowEnd(), c.waveHeightM(),
                    c.waveDirectionDeg(), c.wavePeriodS(), c.facesDeg(),
                    c.waveDirectionDeg() == null || c.facesDeg() == null ? null
                            : BigSeasRule.angleOffDeg(c.waveDirectionDeg(), c.facesDeg()),
                    past, qualifies && !past ? "QUALIFIES" : "no");
            if (qualifies && !past) {
                byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(member(c, date));
            }
        }
        List<BigSeasDay> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Member>> entry : byDate.entrySet()) {
            BigSeasDay day = leadDay(entry.getKey(), entry.getValue());
            LOG.info("[BIG SEAS] {} qualifies: {}m from {}deg, {} structure(s), aligned={}, {} other window(s)",
                    day.date(), day.waveHeightM(), day.waveDirectionDeg(), day.structures().size(),
                    day.alignedEvent(), day.otherWindows());
            result.add(day);
        }
        return result;
    }

    /** One qualifying (structure, high water), with its alignment resolved. */
    private record Member(BigSeasCandidate c, AlignedEvent event, LocalDateTime eventTime) {

        boolean aligned() {
            return event != null;
        }

        double wave() {
            return c.waveHeightM();
        }

        long gapSeconds() {
            return Math.abs(Duration.between(c.hwTime(), eventTime).getSeconds());
        }
    }

    private Member member(BigSeasCandidate c, LocalDate date) {
        LocalDateTime sunrise = solarService.sunriseUtc(c.lat(), c.lon(), date);
        LocalDateTime sunset = solarService.sunsetUtc(c.lat(), c.lon(), date);
        if (within(c, sunrise)) {
            return new Member(c, AlignedEvent.SUNRISE, sunrise);
        }
        if (within(c, sunset)) {
            return new Member(c, AlignedEvent.SUNSET, sunset);
        }
        return new Member(c, null, null);
    }

    private static boolean within(BigSeasCandidate c, LocalDateTime event) {
        return event != null && !event.isBefore(c.windowStart()) && !event.isAfter(c.windowEnd());
    }

    /** Highest wave first; ties go to the earlier high water, then the lower location id. */
    private static final Comparator<Member> BY_WAVE = Comparator
            .comparingDouble(Member::wave).reversed()
            .thenComparing(m -> m.c().hwTime())
            .thenComparing(m -> m.c().locationId());

    private BigSeasDay leadDay(LocalDate date, List<Member> members) {
        List<List<Member>> groups = group(members);
        List<Member> lead = groups.stream().min(LEAD_ORDER).orElseThrow();
        Member maxWave = lead.stream().min(BY_WAVE).orElseThrow();
        Member anchor = lead.stream().filter(Member::aligned)
                .min(Comparator.comparingLong(Member::gapSeconds).thenComparing(BY_WAVE))
                .orElse(maxWave);
        LocalDateTime start = lead.stream().map(m -> m.c().windowStart()).min(Comparator.naturalOrder()).orElseThrow();
        LocalDateTime end = lead.stream().map(m -> m.c().windowEnd()).max(Comparator.naturalOrder()).orElseThrow();
        Long gapMinutes = null;
        GapSide side = null;
        if (anchor.aligned()) {
            gapMinutes = Math.round(anchor.gapSeconds() / SECONDS_PER_MINUTE);
            side = anchor.c().hwTime().isBefore(anchor.eventTime()) ? GapSide.BEFORE : GapSide.AFTER;
        }
        List<BigSeasStructure> structures = lead.stream().sorted(BY_WAVE).map(BigSeasBuilder::structure).toList();
        return new BigSeasDay(date, start, end, anchor.c().hwTime(), anchor.event(), gapMinutes, side,
                maxWave.wave(), maxWave.c().waveDirectionDeg(), maxWave.c().wavePeriodS(), structures,
                groups.size() - 1);
    }

    /** The lead group: any aligned member, then most structures, then highest wave, then earliest start. */
    private static final Comparator<List<Member>> LEAD_ORDER = Comparator
            .<List<Member>, Boolean>comparing(g -> g.stream().noneMatch(Member::aligned))
            .thenComparing(g -> -g.size())
            .thenComparing(g -> -g.stream().mapToDouble(Member::wave).max().orElse(0))
            .thenComparing(g -> g.stream().map(m -> m.c().windowStart()).min(Comparator.naturalOrder()).orElseThrow());

    /** Transitive overlap sweep by window start: a window starting after the running max end begins a new group. */
    private static List<List<Member>> group(List<Member> members) {
        List<Member> sorted = members.stream()
                .sorted(Comparator.comparing((Member m) -> m.c().windowStart()).thenComparing(m -> m.c().locationId()))
                .toList();
        List<List<Member>> groups = new ArrayList<>();
        List<Member> current = null;
        LocalDateTime runningEnd = null;
        for (Member m : sorted) {
            if (current == null || m.c().windowStart().isAfter(runningEnd)) {
                current = new ArrayList<>();
                groups.add(current);
                runningEnd = m.c().windowEnd();
            } else if (m.c().windowEnd().isAfter(runningEnd)) {
                runningEnd = m.c().windowEnd();
            }
            current.add(m);
        }
        return groups;
    }

    private static BigSeasStructure structure(Member m) {
        BigSeasCandidate c = m.c();
        String label = c.label() == null || c.label().isBlank() ? c.locationName() : c.label();
        return new BigSeasStructure(c.locationId(), label, c.structure(), c.waveHeightM(), c.waveDirectionDeg(),
                c.wavePeriodS(), c.facesDeg(), c.hwTime(), m.aligned());
    }
}
