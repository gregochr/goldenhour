package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.SnowStateRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code snow_state} with the freshness rule (snow-topics-truth-plan §3.2): a row computed more than
 * {@value #FRESH_HOURS} hours ago, measured with the {@link Clock} bean, is ignored.
 *
 * <p>A missing or stale row means <em>unknown</em>, never "no snow": this reader returns nothing for it, and a
 * caller must not read the absence as bare ground.
 */
@Service
public class SnowStateReader {

    /** A row older than this many hours is not served. */
    static final int FRESH_HOURS = 36;

    private static final Duration FRESHNESS = Duration.ofHours(FRESH_HOURS);

    private final SnowStateRepository repository;
    private final Clock clock;

    /**
     * Creates the reader.
     *
     * @param repository the {@code snow_state} repository
     * @param clock      the application clock the freshness is measured by
     */
    public SnowStateReader(SnowStateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * The fresh rows in a date range (both ends inclusive), every point and both events.
     *
     * @param from first date
     * @param to   last date
     * @return the rows computed within the freshness window; stale rows are left out
     */
    public List<SnowStateEntity> freshRows(LocalDate from, LocalDate to) {
        LocalDateTime cutoff = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).minus(FRESHNESS);
        return repository.findByEvaluationDateBetween(from, to).stream()
                .filter(row -> !row.getComputedAt().isBefore(cutoff))
                .toList();
    }

    /**
     * The fresh row for one point, date and event.
     *
     * @param pointKey  {@code loc:<id>} or {@code summit:<key>}
     * @param date      the UK civil date
     * @param eventType SUNRISE or SUNSET
     * @return the row, or empty when it is missing or stale (unknown, not "no snow")
     */
    public Optional<SnowStateEntity> freshRow(String pointKey, LocalDate date, TargetType eventType) {
        return freshRows(date, date).stream()
                .filter(row -> pointKey.equals(row.getPointKey()) && row.getEventType() == eventType)
                .findFirst();
    }
}
