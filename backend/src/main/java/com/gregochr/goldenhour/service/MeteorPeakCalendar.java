package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.MeteorShowerPeakEntity;
import com.gregochr.goldenhour.repository.MeteorShowerPeakRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The meteor-shower peak night and rate for any calendar year: the IMO figures stored in
 * {@code meteor_shower_peak} (V222), else the code's {@link MeteorHotTopicStrategy#SHOWERS} fallback.
 *
 * <p>The table is loaded into an immutable snapshot in {@link #load()} ({@code @PostConstruct}, after Flyway
 * has run) and replaced whole by {@link #reload()}, which the Phase 4 refresh job calls. A failed load logs
 * and keeps whatever snapshot is held, so a database problem can never fail start-up or blank the feed. A
 * year with no row falls back to the shower's {@code MonthDay} and catalogue ZHR.
 */
@Component
public class MeteorPeakCalendar {

    private static final Logger LOG = LoggerFactory.getLogger(MeteorPeakCalendar.class);

    /**
     * One shower's peak on one night.
     *
     * @param shower the shower
     * @param night  the evening date of the night of maximum
     * @param zhr    the zenithal hourly rate for that year
     */
    record ShowerNight(MeteorHotTopicStrategy.Shower shower, LocalDate night, int zhr) { }

    private record Peak(LocalDate night, int zhr) { }

    private final MeteorShowerPeakRepository repository;
    private final ApplicationEventPublisher publisher;
    private final AtomicReference<Map<String, Map<Integer, Peak>>> snapshot =
            new AtomicReference<>(Map.of());

    /**
     * Constructs a {@code MeteorPeakCalendar}.
     *
     * @param repository the per-year peak rows
     * @param publisher  publishes {@link MeteorPeakCalendarReloaded}
     */
    public MeteorPeakCalendar(MeteorShowerPeakRepository repository, ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    /** Loads the table once the bean exists; a failure keeps the code fallback and never fails start-up. */
    @PostConstruct
    void load() {
        try {
            snapshot.set(readSnapshot());
        } catch (RuntimeException e) {
            LOG.warn("Meteor peak calendar: could not read meteor_shower_peak, using the code fallback: {}",
                    e.toString());
        }
    }

    /**
     * Re-reads the table, swaps the snapshot and publishes {@link MeteorPeakCalendarReloaded}. A failed read
     * keeps the held snapshot and publishes nothing.
     */
    public void reload() {
        try {
            snapshot.set(readSnapshot());
        } catch (RuntimeException e) {
            LOG.warn("Meteor peak calendar: reload failed, keeping the held snapshot: {}", e.toString());
            return;
        }
        publisher.publishEvent(new MeteorPeakCalendarReloaded());
    }

    private Map<String, Map<Integer, Peak>> readSnapshot() {
        Map<String, Map<Integer, Peak>> byCode = new HashMap<>();
        for (MeteorShowerPeakEntity row : repository.findAll()) {
            byCode.computeIfAbsent(row.getShowerCode(), k -> new HashMap<>())
                    .put(row.getCalendarYear(), new Peak(row.getPeakNight(), row.getZhr()));
        }
        Map<String, Map<Integer, Peak>> frozen = new HashMap<>();
        byCode.forEach((code, years) -> frozen.put(code, Map.copyOf(years)));
        return Map.copyOf(frozen);
    }

    private Peak stored(MeteorHotTopicStrategy.Shower shower, int year) {
        Map<Integer, Peak> years = snapshot.get().get(shower.code());
        return years == null ? null : years.get(year);
    }

    /**
     * The evening date of the shower's peak night in a calendar year.
     *
     * @param shower the shower
     * @param year   the IMO calendar year
     * @return the stored night, else the code's {@code MonthDay} in that year
     */
    LocalDate peakNight(MeteorHotTopicStrategy.Shower shower, int year) {
        Peak peak = stored(shower, year);
        return peak != null ? peak.night() : shower.peak().atYear(year);
    }

    /**
     * The shower's zenithal hourly rate in a calendar year.
     *
     * @param shower the shower
     * @param year   the IMO calendar year
     * @return the stored figure, else the code's catalogue rate
     */
    int zhr(MeteorHotTopicStrategy.Shower shower, int year) {
        Peak peak = stored(shower, year);
        return peak != null ? peak.zhr() : shower.zhrPeak();
    }

    /**
     * Every shower peak whose night lies in {@code [from, to]}, sorted by night. Scans calendar years
     * {@code from.year - 1 ... to.year + 1}, because a night near New Year can belong to a neighbouring
     * year's calendar.
     *
     * @param from      the first night
     * @param to        the last night (inclusive)
     * @param majorOnly true to keep only the major showers
     * @return the peaks in the range
     */
    List<ShowerNight> peakNightsIn(LocalDate from, LocalDate to, boolean majorOnly) {
        List<ShowerNight> found = new ArrayList<>();
        for (MeteorHotTopicStrategy.Shower shower : MeteorHotTopicStrategy.SHOWERS) {
            if (majorOnly && !shower.major()) {
                continue;
            }
            for (int year = from.getYear() - 1; year <= to.getYear() + 1; year++) {
                LocalDate night = peakNight(shower, year);
                if (!night.isBefore(from) && !night.isAfter(to)) {
                    found.add(new ShowerNight(shower, night, zhr(shower, year)));
                }
            }
        }
        found.sort(Comparator.comparing(ShowerNight::night));
        return found;
    }
}
