package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * The transactional "replace" step of {@link WildlifeComfortRefreshJob}: swaps one place's
 * {@code HOURLY} rows for one date for a freshly built set, atomically.
 *
 * <p>A bean of its own, rather than a method on the job, because the guarantee is a transaction
 * boundary and Spring applies {@code @Transactional} only to calls that cross a bean boundary —
 * a self-invoked method on the job would silently run each statement in its own transaction, and
 * a failed insert would then leave the date with no rows at all.
 *
 * <p><b>The delete is never reached without replacements in hand.</b> An empty list is refused
 * before any statement runs, so a failed fetch or an empty extraction leaves the previous rows
 * exactly as they were. A failing insert rolls the delete back with it.
 */
@Service
public class WildlifeComfortWriter {

    private final ForecastEvaluationRepository repository;

    /**
     * Creates the writer.
     *
     * @param repository the {@code forecast_evaluation} repository
     */
    public WildlifeComfortWriter(ForecastEvaluationRepository repository) {
        this.repository = repository;
    }

    /**
     * Deletes the place's existing {@code HOURLY} rows for {@code date}, then inserts {@code rows},
     * in one transaction.
     *
     * @param locationId the location primary key
     * @param date       the target date being replaced
     * @param rows       the replacement rows; every one must be an {@code HOURLY} row for
     *                   {@code date} on the location {@code locationId}
     * @return the number of rows inserted; {@code 0} (and nothing deleted) when {@code rows} is
     *         null or empty
     * @throws IllegalArgumentException if a row is not an {@code HOURLY} row for this place and date
     */
    @Transactional
    public int replaceHourlyRows(Long locationId, LocalDate date, List<ForecastEvaluationEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        for (ForecastEvaluationEntity row : rows) {
            if (row.getTargetType() != TargetType.HOURLY
                    || !date.equals(row.getTargetDate())
                    || row.getLocation() == null
                    || !locationId.equals(row.getLocation().getId())) {
                throw new IllegalArgumentException("Refusing to replace HOURLY rows for location "
                        + locationId + " on " + date + " with a row for "
                        + row.getTargetType() + " " + row.getTargetDate());
            }
        }
        repository.deleteHourlyByLocationIdAndTargetDate(locationId, date);
        repository.saveAll(rows);
        return rows.size();
    }
}
