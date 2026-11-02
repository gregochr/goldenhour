package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.service.ForecastCycleJobs;
import com.gregochr.goldenhour.service.SchedulerJobCatalogue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the catalogue agrees with the code that names the same jobs: {@link ForecastCycleJobs} for the three
 * forecast cycles, {@link ScheduledFiresLister} for the astro and wildlife jobs and {@link SchedulerRunsService} for
 * tide and the aurora batch. Lives in this package because the lister's constants are package-private.
 */
class SchedulerJobCatalogueAgreementTest {

    @Test
    @DisplayName("the three forecast cycle jobs are catalogued as Claude jobs whose results the batch poller collects")
    void cycleJobs_agreeWithForecastCycleJobs() {
        List<String> cycleKeys = ForecastCycleJobs.allCycleJobs().stream().map(ForecastCycleJobs.Job::key).toList();

        assertThat(SchedulerJobCatalogue.keys()).containsAll(cycleKeys);
        for (String key : cycleKeys) {
            SchedulerJobCatalogue.Entry entry = SchedulerJobCatalogue.find(key).orElseThrow();
            assertThat(entry.usesClaude()).as(key).isTrue();
            assertThat(entry.resultsCollectedBy()).as(key).containsExactly("batch_result_polling");
        }
    }

    @Test
    @DisplayName("the watchdog watches exactly the forecast cycle jobs")
    void watchdog_watchesCycleJobs() {
        List<String> cycleKeys = ForecastCycleJobs.allCycleJobs().stream().map(ForecastCycleJobs.Job::key).toList();

        assertThat(SchedulerJobCatalogue.find("forecast_cycle_watchdog").orElseThrow().watches())
                .containsExactlyInAnyOrderElementsOf(cycleKeys);
    }

    @Test
    @DisplayName("the astro, wildlife, tide and aurora batch jobs the Runs scheduler lists are catalogued")
    void listedJobs_areCatalogued() {
        assertThat(SchedulerJobCatalogue.keys()).contains(ScheduledFiresLister.ASTRO_JOB,
                ScheduledFiresLister.WILDLIFE_JOB, SchedulerRunsService.TIDE_JOB,
                SchedulerRunsService.AURORA_BATCH_JOB, SchedulerJobCostService.SKY_EVAL_JOB);
    }

    @Test
    @DisplayName("every job the cost service prices is catalogued as a Claude job, and no other job is")
    void claudeJobs_matchTheCostPaths() {
        List<String> priced = List.of(ForecastCycleJobs.NIGHTLY_JOB, ForecastCycleJobs.INTRADAY_JOB,
                ForecastCycleJobs.EVENING_JOB, SchedulerRunsService.AURORA_BATCH_JOB,
                SchedulerJobCostService.SKY_EVAL_JOB);

        List<String> claudeJobs = SchedulerJobCatalogue.keys().stream()
                .filter(k -> SchedulerJobCatalogue.find(k).orElseThrow().usesClaude()).toList();

        assertThat(claudeJobs).containsExactlyInAnyOrderElementsOf(priced);
    }
}
