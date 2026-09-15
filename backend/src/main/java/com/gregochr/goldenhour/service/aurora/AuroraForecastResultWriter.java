package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Transactional writer for per-night aurora forecast results.
 *
 * <p>Replaces one night's stored results atomically — the delete and the inserts share a single
 * short transaction, so a re-run swaps old data for new without a window where the night is empty
 * and a failed write leaves the previous results intact.
 *
 * <p>Exists as a separate component so {@link AuroraForecastRunService} can run its slow external
 * work (NOAA fetch, one Claude call per night) outside any transaction: the database connection is
 * only held for the duration of this write, and a failure on a later night cannot roll back
 * earlier nights' committed results. Mirrors the write-isolation pattern of
 * {@code ForecastScoreWriter}.
 */
@Component
public class AuroraForecastResultWriter {

    private final AuroraForecastResultRepository resultRepository;

    /**
     * Constructs the writer.
     *
     * @param resultRepository aurora result persistence
     */
    public AuroraForecastResultWriter(AuroraForecastResultRepository resultRepository) {
        this.resultRepository = resultRepository;
    }

    /**
     * Atomically replaces the stored results for one night.
     *
     * <p>Existing rows for the date are deleted and the new rows inserted in the same
     * transaction. An empty list clears the night (used when a re-run finds no activity or no
     * eligible locations, so stale results never survive a re-run).
     *
     * <p><b>{@code simulated} decides what gets deleted, not what {@code results} contains</b> —
     * an empty {@code results} list carries no {@code simulated} value of its own, so the caller's
     * own state (was <em>this run</em> simulated) is the only signal available, and it must be
     * passed explicitly rather than inferred from the list. A <b>real</b> run ({@code false})
     * clears real and simulated rows alike for that night — an admin's earlier test run must not
     * survive a genuine one. A <b>simulated</b> run ({@code true}) clears only rows an earlier
     * simulated run left behind, leaving any real, user-facing results for that night untouched:
     * a repeated admin test must never be able to delete real data.
     *
     * @param date      the night whose results to replace
     * @param results   the new results for that night; may be empty
     * @param simulated whether this write is for a simulated run — decides which existing rows
     *                  for the night are cleared before the new ones are inserted
     */
    @Transactional
    public void replaceNightResults(LocalDate date, List<AuroraForecastResultEntity> results,
            boolean simulated) {
        if (simulated) {
            resultRepository.deleteByForecastDateAndSimulatedTrue(date);
        } else {
            resultRepository.deleteByForecastDateIn(List.of(date));
        }
        if (!results.isEmpty()) {
            resultRepository.saveAll(results);
        }
    }
}
