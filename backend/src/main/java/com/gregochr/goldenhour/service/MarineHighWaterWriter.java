package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.repository.MarineHighWaterRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Replaces one location's {@code marine_high_water} rows atomically.
 *
 * <p>A separate bean so the {@code @Transactional} proxy applies (a call from inside
 * {@link MarineWaveRefreshService} would be self-invocation and bypass it). The Open-Meteo fetch
 * happens before this is called, never inside the transaction. A concurrent briefing serve sees
 * either the old rows or the new ones, never none. Passing an empty list clears the range, which is
 * how a failed or land-cell fetch stops last cycle's waves being served as this cycle's.
 */
@Component
public class MarineHighWaterWriter {

    private final MarineHighWaterRepository repository;

    /**
     * Constructs a {@code MarineHighWaterWriter}.
     *
     * @param repository the high-water sea-state repository
     */
    public MarineHighWaterWriter(MarineHighWaterRepository repository) {
        this.repository = repository;
    }

    /**
     * Bulk-deletes the location's rows whose high water is in {@code [from, to]}, then inserts the
     * fresh ones, in one transaction.
     *
     * @param locationId the location primary key
     * @param from       range start (inclusive), UTC
     * @param to         range end (inclusive), UTC
     * @param rows       the fresh rows, possibly empty
     */
    @Transactional
    public void replace(Long locationId, LocalDateTime from, LocalDateTime to,
            List<MarineHighWaterEntity> rows) {
        repository.deleteByLocationIdAndHwTimeBetween(locationId, from, to);
        if (!rows.isEmpty()) {
            repository.saveAll(rows);
        }
    }
}
