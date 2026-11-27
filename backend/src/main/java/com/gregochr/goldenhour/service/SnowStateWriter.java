package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.SnowStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The transactional write step of {@link SnowStateRefreshJob}: merges one run's slots into {@code snow_state} and
 * prunes the rows that have aged out, atomically.
 *
 * <p>A bean of its own so the {@code @Transactional} boundary is real (a self-invoked method would run each
 * statement on its own). <b>Partial runs never erase</b>: only slots the run computed are passed in, so a point
 * that was not fetched keeps its previous row, and within a slot a null value never overwrites a stored one.
 */
@Service
public class SnowStateWriter {

    /** Rows dated more than this many days before today are deleted (Rewind reads at most two days back). */
    static final int RETAIN_DAYS = 7;

    private final SnowStateRepository repository;

    /**
     * One computed slot. Null values mean "not computed this run" and never overwrite a stored value.
     *
     * @param pointKey      the point key: "loc:" plus a location id, or "summit:" plus a summit key
     * @param date          the UK civil date of the event
     * @param eventType     SUNRISE or SUNSET
     * @param snowWaterMm   modelled snow water equivalent, mm
     * @param snowfall24hCm snow added in the 24 hours before the slot, cm
     * @param lowCloudPct   low cloud cover at the slot hour, percent
     */
    public record Slot(String pointKey, LocalDate date, TargetType eventType, Double snowWaterMm,
            Double snowfall24hCm, Integer lowCloudPct) {

        boolean isEmpty() {
            return snowWaterMm == null && snowfall24hCm == null && lowCloudPct == null;
        }
    }

    /**
     * Creates the writer.
     *
     * @param repository the {@code snow_state} repository
     */
    public SnowStateWriter(SnowStateRepository repository) {
        this.repository = repository;
    }

    /**
     * Upserts the slots (a slot with every value null is ignored), stamping {@code computedAt} on each row it
     * writes, then deletes rows dated more than {@value #RETAIN_DAYS} days before {@code today}.
     *
     * @param slots      the slots computed this run
     * @param today      the UK civil date the run treats as today
     * @param computedAt the UTC wall-clock time to stamp
     * @return the number of rows inserted or updated
     */
    @Transactional
    public int write(List<Slot> slots, LocalDate today, LocalDateTime computedAt) {
        List<Slot> usable = slots.stream().filter(slot -> !slot.isEmpty()).toList();
        int written = 0;
        if (!usable.isEmpty()) {
            LocalDate first = usable.stream().map(Slot::date).min(LocalDate::compareTo).orElseThrow();
            LocalDate last = usable.stream().map(Slot::date).max(LocalDate::compareTo).orElseThrow();
            Map<String, SnowStateEntity> existing = new HashMap<>();
            for (SnowStateEntity row : repository.findByEvaluationDateBetween(first, last)) {
                existing.put(key(row.getPointKey(), row.getEvaluationDate(), row.getEventType()), row);
            }
            List<SnowStateEntity> toSave = new ArrayList<>();
            for (Slot slot : usable) {
                SnowStateEntity row = existing.get(key(slot.pointKey(), slot.date(), slot.eventType()));
                if (row == null) {
                    row = SnowStateEntity.builder()
                            .pointKey(slot.pointKey()).evaluationDate(slot.date()).eventType(slot.eventType())
                            .build();
                }
                if (slot.snowWaterMm() != null) {
                    row.setSnowWaterMm(slot.snowWaterMm());
                }
                if (slot.snowfall24hCm() != null) {
                    row.setSnowfall24hCm(slot.snowfall24hCm());
                }
                if (slot.lowCloudPct() != null) {
                    row.setLowCloudPct(slot.lowCloudPct());
                }
                row.setComputedAt(computedAt);
                toSave.add(row);
            }
            repository.saveAll(toSave);
            written = toSave.size();
        }
        repository.deleteByEvaluationDateBefore(today.minusDays(RETAIN_DAYS));
        return written;
    }

    private static String key(String pointKey, LocalDate date, TargetType eventType) {
        return pointKey + "|" + date + "|" + eventType;
    }
}
