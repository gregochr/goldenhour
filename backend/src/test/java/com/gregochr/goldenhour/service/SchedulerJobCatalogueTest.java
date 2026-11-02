package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.service.SchedulerJobCatalogue.Draft;
import com.gregochr.goldenhour.service.SchedulerJobCatalogue.Entry;
import com.gregochr.goldenhour.service.SchedulerJobCatalogue.Purpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link SchedulerJobCatalogue}: every edge is declared once and its inverse derived, the copy never
 * hard-codes an editable schedule, and a few entries read as the design copy says.
 */
class SchedulerJobCatalogueTest {

    private static final Pattern CLOCK_TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");
    private static final Pattern INTERVAL = Pattern.compile("(?i)\\bevery \\d+ ?(min|hour|second)");

    private static Draft draft(String key) {
        return SchedulerJobCatalogue.draft(key, "alias " + key, Purpose.TESTING, false, List.of(), "what", "why");
    }

    @Test
    @DisplayName("each forward edge derives its inverse: collects/collected, watches/watched, backstop, stagger")
    void build_derivesEveryInverse() {
        Map<String, Draft> map = new LinkedHashMap<>();
        map.put("submitter", draft("submitter").resultsCollectedBy("poller").watches("poller")
                .backstoppedBy("sweeper").staggeredBefore("follower"));
        map.put("poller", draft("poller"));
        map.put("sweeper", draft("sweeper"));
        map.put("follower", draft("follower"));

        Map<String, Entry> entries = SchedulerJobCatalogue.build(map);

        assertThat(entries.get("submitter").resultsCollectedBy()).containsExactly("poller");
        assertThat(entries.get("poller").collectsResultsFrom()).containsExactly("submitter");
        assertThat(entries.get("submitter").watches()).containsExactly("poller");
        assertThat(entries.get("poller").watchedBy()).containsExactly("submitter");
        assertThat(entries.get("submitter").backstoppedBy()).containsExactly("sweeper");
        assertThat(entries.get("sweeper").backstops()).containsExactly("submitter");
        assertThat(entries.get("submitter").staggeredBefore()).containsExactly("follower");
        assertThat(entries.get("follower").staggeredAfter()).containsExactly("submitter");
    }

    @Test
    @DisplayName("a job with no edges has every edge list empty, and one target can have several sources")
    void build_emptyAndMultipleSources() {
        Map<String, Draft> map = new LinkedHashMap<>();
        map.put("a", draft("a").resultsCollectedBy("poller"));
        map.put("b", draft("b").resultsCollectedBy("poller"));
        map.put("poller", draft("poller"));
        map.put("lonely", draft("lonely"));

        Map<String, Entry> entries = SchedulerJobCatalogue.build(map);

        assertThat(entries.get("poller").collectsResultsFrom()).containsExactly("a", "b");
        Entry lonely = entries.get("lonely");
        assertThat(lonely.collectsResultsFrom()).isEmpty();
        assertThat(lonely.resultsCollectedBy()).isEmpty();
        assertThat(lonely.watches()).isEmpty();
        assertThat(lonely.watchedBy()).isEmpty();
        assertThat(lonely.backstops()).isEmpty();
        assertThat(lonely.backstoppedBy()).isEmpty();
        assertThat(lonely.staggeredAfter()).isEmpty();
        assertThat(lonely.staggeredBefore()).isEmpty();
    }

    @Test
    @DisplayName("an edge naming a job with no entry fails the build, naming both jobs")
    void build_unknownEdgeTargetFails() {
        Map<String, Draft> map = new LinkedHashMap<>();
        map.put("a", draft("a").watches("ghost"));

        assertThatThrownBy(() -> SchedulerJobCatalogue.build(map))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Catalogue edge from a names unknown job ghost");
    }

    @Test
    @DisplayName("in the real catalogue every edge is mirrored by its inverse and names a real job")
    void realCatalogue_edgesAreSymmetricAndResolve() {
        for (String key : SchedulerJobCatalogue.keys()) {
            Entry entry = SchedulerJobCatalogue.find(key).orElseThrow();
            for (String target : entry.resultsCollectedBy()) {
                assertThat(SchedulerJobCatalogue.find(target).orElseThrow().collectsResultsFrom()).contains(key);
            }
            for (String target : entry.watches()) {
                assertThat(SchedulerJobCatalogue.find(target).orElseThrow().watchedBy()).contains(key);
            }
            for (String target : entry.backstoppedBy()) {
                assertThat(SchedulerJobCatalogue.find(target).orElseThrow().backstops()).contains(key);
            }
            for (String target : entry.staggeredBefore()) {
                assertThat(SchedulerJobCatalogue.find(target).orElseThrow().staggeredAfter()).contains(key);
            }
            for (String source : entry.collectsResultsFrom()) {
                assertThat(SchedulerJobCatalogue.find(source).orElseThrow().resultsCollectedBy()).contains(key);
            }
        }
    }

    @Test
    @DisplayName("the catalogue describes the 28 jobs, every one with copy and none naming an editable schedule")
    void realCatalogue_everyEntryHasCopyAndNoEditableSchedule() {
        assertThat(SchedulerJobCatalogue.keys()).hasSize(28).contains("scheduler_fire_log_cleanup");
        for (String key : SchedulerJobCatalogue.keys()) {
            Entry entry = SchedulerJobCatalogue.find(key).orElseThrow();
            assertThat(entry.alias()).as(key).isNotBlank();
            assertThat(entry.what()).as(key).isNotBlank();
            assertThat(entry.why()).as(key).isNotBlank();
            assertThat(entry.purpose()).as(key).isNotNull();
            assertThat(CLOCK_TIME.matcher(entry.why()).find()).as(key + " why has a clock time").isFalse();
            assertThat(INTERVAL.matcher(entry.why()).find()).as(key + " why has an interval").isFalse();
        }
    }

    @Test
    @DisplayName("the cycles feed the batch poller, the watchdog watches two of them, the sweep backstops the poller")
    void realCatalogue_cycleAndPlumbingEdges() {
        Entry poller = SchedulerJobCatalogue.find("batch_result_polling").orElseThrow();
        assertThat(poller.collectsResultsFrom()).containsExactly("near_term_batch_evaluation",
                "intraday_forecast_refresh", "evening_sunrise_refresh", "aurora_batch_evaluation");
        assertThat(poller.backstoppedBy()).containsExactly("evaluation_abandonment_sweep");
        assertThat(SchedulerJobCatalogue.find("evaluation_abandonment_sweep").orElseThrow().backstops())
                .containsExactly("batch_result_polling");
        assertThat(SchedulerJobCatalogue.find("sky_rating_eval_batch_poll").orElseThrow().collectsResultsFrom())
                .containsExactly("sky_rating_eval");
        assertThat(SchedulerJobCatalogue.find("forecast_cycle_watchdog").orElseThrow().watches())
                .containsExactly("near_term_batch_evaluation", "intraday_forecast_refresh");
        assertThat(SchedulerJobCatalogue.find("near_term_batch_evaluation").orElseThrow().watchedBy())
                .containsExactly("forecast_cycle_watchdog");
    }

    @Test
    @DisplayName("the two drive-time jobs are staggered, the user refresh first")
    void realCatalogue_staggerEdge() {
        assertThat(SchedulerJobCatalogue.find("drive_time_refresh").orElseThrow().staggeredBefore())
                .containsExactly("region_drive_time_refresh");
        assertThat(SchedulerJobCatalogue.find("region_drive_time_refresh").orElseThrow().staggeredAfter())
                .containsExactly("drive_time_refresh");
    }

    @Test
    @DisplayName("the fire-log cleanup entry, a Claude job, a database-only job and a service-calling job read right")
    void realCatalogue_sampleEntries() {
        Entry cleanup = SchedulerJobCatalogue.find("scheduler_fire_log_cleanup").orElseThrow();
        assertThat(cleanup.alias()).isEqualTo("Clears old run history");
        assertThat(cleanup.purpose()).isEqualTo(Purpose.HOUSEKEEPING);
        assertThat(cleanup.services()).isEmpty();
        assertThat(cleanup.usesClaude()).isFalse();
        assertThat(cleanup.what()).isEqualTo("Deletes Scheduler run-history rows older than 14 days, so this "
                + "screen's history stays a fortnight deep and the table stays small.");
        assertThat(cleanup.why()).isEqualTo("Nightly, in the housekeeping slot.");

        Entry nightly = SchedulerJobCatalogue.find("near_term_batch_evaluation").orElseThrow();
        assertThat(nightly.alias()).isEqualTo("The overnight forecast cycle");
        assertThat(nightly.usesClaude()).isTrue();
        assertThat(nightly.purpose()).isEqualTo(Purpose.FORECAST);
        assertThat(nightly.services()).containsExactly("Open-Meteo", "Anthropic Batch API", "WorldTides (cached)");
        assertThat(nightly.why()).contains("20 minutes before sunrise");

        Entry evening = SchedulerJobCatalogue.find("evening_sunrise_refresh").orElseThrow();
        assertThat(evening.why()).contains("UK time");

        Entry astro = SchedulerJobCatalogue.find("astro_conditions_refresh").orElseThrow();
        assertThat(astro.usesClaude()).isFalse();
        assertThat(astro.services()).containsExactly("Open-Meteo");
    }

    @Test
    @DisplayName("exactly five jobs use Claude: the three cycles, the aurora batch and the sky eval")
    void realCatalogue_claudeJobs() {
        List<String> claude = SchedulerJobCatalogue.keys().stream()
                .filter(k -> SchedulerJobCatalogue.find(k).orElseThrow().usesClaude()).toList();

        assertThat(claude).containsExactlyInAnyOrder("near_term_batch_evaluation", "intraday_forecast_refresh",
                "evening_sunrise_refresh", "aurora_batch_evaluation", "sky_rating_eval");
    }

    @Test
    @DisplayName("an unknown job key has no entry")
    void find_unknownKeyIsEmpty() {
        assertThat(SchedulerJobCatalogue.find("hand_added_job")).isEmpty();
    }

    @Test
    @DisplayName("a purpose is served by its label")
    void purpose_labels() {
        assertThat(Purpose.NIGHT_SKY.label()).isEqualTo("Night sky");
        assertThat(Purpose.BATCH_PLUMBING.label()).isEqualTo("Batch plumbing");
        assertThat(Purpose.values()).extracting(Purpose::label).containsExactly("Forecast", "Night sky",
                "Batch plumbing", "Housekeeping", "Accounts", "Travel", "Places", "Testing");
    }
}
