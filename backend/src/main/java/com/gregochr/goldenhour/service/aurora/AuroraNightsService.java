package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.model.AuroraNightsResponse;
import com.gregochr.goldenhour.model.CurrentNight;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;

/**
 * Answers which aurora nights exist, for a reader who may not see the aurora forecast itself.
 *
 * <p>A separate bean rather than a method on {@link AuroraForecastRunService}: that class carries the
 * run pipeline's dependencies, and this question needs only the night rule it already owns and the
 * state cache. Neither half is re-derived here: stored nights come from
 * {@link AuroraForecastRunService#getAvailableDates()} (non-simulated by construction) and the night
 * in progress from {@link AuroraForecastRunService#currentNight()}.
 */
@Service
public class AuroraNightsService {

    private final AuroraForecastRunService forecastRunService;
    private final AuroraStateCache stateCache;

    /**
     * Constructs the service.
     *
     * @param forecastRunService owns the current-night rule (and its injected clock) and the stored dates
     * @param stateCache         the live alert state
     */
    public AuroraNightsService(AuroraForecastRunService forecastRunService, AuroraStateCache stateCache) {
        this.forecastRunService = forecastRunService;
        this.stateCache = stateCache;
    }

    /**
     * Returns every night a Pro reader would be offered an aurora row for right now.
     *
     * <p>Stored nights from the current night on (last night stays offered until dawn), plus the
     * current night when a real alert is live at MODERATE or STRONG. The simulation is read first: a
     * non-null one means there is no live night, and only then are the level and active flag read —
     * the order {@code AuroraController.getStatus} uses, so a simulation that ends mid-read cannot be
     * served as a real alert. A null level is QUIET.
     *
     * @return the sorted, distinct nights and the instant the current night ends
     */
    public AuroraNightsResponse offeredNights() {
        CurrentNight night = forecastRunService.currentNight();
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (String stored : forecastRunService.getAvailableDates()) {
            LocalDate date = LocalDate.parse(stored);
            if (!date.isBefore(night.date())) {
                dates.add(date);
            }
        }
        if (AuroraLiveAlert.read(stateCache).alertWorthy()) {
            dates.add(night.date());
        }
        List<String> nights = dates.stream().map(LocalDate::toString).toList();
        return new AuroraNightsResponse(nights, night.endsAt());
    }
}
