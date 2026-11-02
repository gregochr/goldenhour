package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The plain-English catalogue of the scheduled jobs, keyed by {@code scheduler_job_config.job_key}
 * (scheduler-redesign-plan.md D4).
 *
 * <p>For each job: a plain alias, what it does, why it runs when it does, a purpose tag, the external services it
 * calls, whether it spends Claude money, and the edges that connect it to other jobs. The copy is the owner-approved
 * design copy; the one edit made to it is that a {@code why} never hard-codes a clock time or interval an admin can
 * edit, because an edited schedule would make it stale.
 *
 * <p>Each edge is declared <em>once</em>, on the job that is the subject of the forward form
 * ({@code resultsCollectedBy}, {@code watches}, {@code backstoppedBy}, {@code staggeredBefore}), and the inverse
 * ({@code collectsResultsFrom}, {@code watchedBy}, {@code backstops}, {@code staggeredAfter}) is derived when the
 * catalogue is built, so the two directions cannot disagree.
 *
 * <p>The required config key is deliberately not here: the {@code configSource} column already serves it. A job key
 * with no entry (production rows are hand-editable) is {@link #find absent}, and the API serves
 * {@code catalogue: null} for it.
 */
public final class SchedulerJobCatalogue {

    /** What a job is for, as the Scheduler screen's purpose tag. */
    public enum Purpose {

        /** Forecast cycles and the things that feed them. */
        FORECAST("Forecast"),

        /** Night-sky jobs: aurora, noctilucent cloud, astro conditions. */
        NIGHT_SKY("Night sky"),

        /** Batch polling, sweeps and watchdogs. */
        BATCH_PLUMBING("Batch plumbing"),

        /** Deleting what has outlived its retention. */
        HOUSEKEEPING("Housekeeping"),

        /** Sign-up and account lifecycle. */
        ACCOUNTS("Accounts"),

        /** Drive times. */
        TRAVEL("Travel"),

        /** Locations and tides. */
        PLACES("Places"),

        /** Calibration and accuracy testing. */
        TESTING("Testing");

        private final String label;

        Purpose(String label) {
            this.label = label;
        }

        /**
         * The tag as served and shown.
         *
         * @return the label, such as {@code Night sky}
         */
        @JsonValue
        public String label() {
            return label;
        }
    }

    /**
     * One job's catalogue entry, as served under {@code catalogue}.
     *
     * @param alias               the plain name ("The afternoon refresh")
     * @param what                one plain paragraph on what the job does
     * @param why                 why it runs when it does, without any editable clock time or interval
     * @param purpose             the purpose tag
     * @param services            the external services it calls; empty means the database only
     * @param usesClaude          whether the job (or what it submits) spends Claude money
     * @param collectsResultsFrom jobs whose batch results this job collects
     * @param resultsCollectedBy  jobs that collect the results of the batches this job submits
     * @param watches             jobs this job watches
     * @param watchedBy           jobs that watch this job
     * @param backstops           jobs this job is the safety net for
     * @param backstoppedBy       jobs that are this job's safety net
     * @param staggeredAfter      jobs this job runs 30 minutes after (a shared rate limit)
     * @param staggeredBefore     jobs that run 30 minutes after this one
     */
    public record Entry(String alias, String what, String why, Purpose purpose, List<String> services,
            boolean usesClaude, List<String> collectsResultsFrom, List<String> resultsCollectedBy,
            List<String> watches, List<String> watchedBy, List<String> backstops, List<String> backstoppedBy,
            List<String> staggeredAfter, List<String> staggeredBefore) {
    }

    /** A job under construction: its copy and its forward edges, before the inverses are derived. */
    static final class Draft {
        private final String jobKey;
        private final String alias;
        private final String what;
        private final String why;
        private final Purpose purpose;
        private final List<String> services;
        private final boolean usesClaude;
        private final List<String> resultsCollectedBy = new ArrayList<>();
        private final List<String> watches = new ArrayList<>();
        private final List<String> backstoppedBy = new ArrayList<>();
        private final List<String> staggeredBefore = new ArrayList<>();

        private Draft(String jobKey, String alias, Purpose purpose, boolean usesClaude, List<String> services,
                String what, String why) {
            this.jobKey = jobKey;
            this.alias = alias;
            this.purpose = purpose;
            this.usesClaude = usesClaude;
            this.services = services;
            this.what = what;
            this.why = why;
        }

        /**
         * Declares that the given jobs collect the batch results this job submits.
         *
         * @param keys the collecting jobs
         * @return this draft
         */
        Draft resultsCollectedBy(String... keys) {
            resultsCollectedBy.addAll(List.of(keys));
            return this;
        }

        /**
         * Declares that this job watches the given jobs.
         *
         * @param keys the watched jobs
         * @return this draft
         */
        Draft watches(String... keys) {
            watches.addAll(List.of(keys));
            return this;
        }

        /**
         * Declares that the given jobs are this job's safety net.
         *
         * @param keys the backstop jobs
         * @return this draft
         */
        Draft backstoppedBy(String... keys) {
            backstoppedBy.addAll(List.of(keys));
            return this;
        }

        /**
         * Declares that the given jobs run 30 minutes after this one.
         *
         * @param keys the following jobs
         * @return this draft
         */
        Draft staggeredBefore(String... keys) {
            staggeredBefore.addAll(List.of(keys));
            return this;
        }
    }

    private static final Map<String, Draft> DRAFTS = new LinkedHashMap<>();

    private static final Map<String, Entry> ENTRIES;

    static {
        declare();
        ENTRIES = build(DRAFTS);
    }

    private SchedulerJobCatalogue() {
    }

    /**
     * Starts a draft; the edge methods on it add the forward edges.
     *
     * @param jobKey     the job key
     * @param alias      the plain alias
     * @param purpose    the purpose tag
     * @param usesClaude whether the job spends Claude money
     * @param services   the external services it calls
     * @param what       what it does
     * @param why        why it runs when it does
     * @return the draft
     */
    static Draft draft(String jobKey, String alias, Purpose purpose, boolean usesClaude, List<String> services,
            String what, String why) {
        return new Draft(jobKey, alias, purpose, usesClaude, services, what, why);
    }

    private static void add(Draft draft) {
        if (DRAFTS.putIfAbsent(draft.jobKey, draft) != null) {
            throw new IllegalStateException("Duplicate catalogue entry: " + draft.jobKey);
        }
    }

    /**
     * Builds the entries from drafts, deriving each inverse edge from its forward edge.
     *
     * @param drafts the drafts by job key
     * @return the entries by job key, in declaration order
     * @throws IllegalStateException if an edge names a job that has no draft
     */
    static Map<String, Entry> build(Map<String, Draft> drafts) {
        Map<String, List<String>> collectsResultsFrom = new LinkedHashMap<>();
        Map<String, List<String>> watchedBy = new LinkedHashMap<>();
        Map<String, List<String>> backstops = new LinkedHashMap<>();
        Map<String, List<String>> staggeredAfter = new LinkedHashMap<>();
        for (Draft d : drafts.values()) {
            invert(drafts, d.jobKey, d.resultsCollectedBy, collectsResultsFrom);
            invert(drafts, d.jobKey, d.watches, watchedBy);
            invert(drafts, d.jobKey, d.backstoppedBy, backstops);
            invert(drafts, d.jobKey, d.staggeredBefore, staggeredAfter);
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Draft d : drafts.values()) {
            entries.put(d.jobKey, new Entry(d.alias, d.what, d.why, d.purpose, List.copyOf(d.services),
                    d.usesClaude, of(collectsResultsFrom, d.jobKey), List.copyOf(d.resultsCollectedBy),
                    List.copyOf(d.watches), of(watchedBy, d.jobKey), of(backstops, d.jobKey),
                    List.copyOf(d.backstoppedBy), of(staggeredAfter, d.jobKey), List.copyOf(d.staggeredBefore)));
        }
        return Collections.unmodifiableMap(entries);
    }

    private static void invert(Map<String, Draft> drafts, String from, List<String> targets,
            Map<String, List<String>> inverse) {
        for (String target : targets) {
            if (!drafts.containsKey(target)) {
                throw new IllegalStateException("Catalogue edge from " + from + " names unknown job " + target);
            }
            inverse.computeIfAbsent(target, k -> new ArrayList<>()).add(from);
        }
    }

    private static List<String> of(Map<String, List<String>> edges, String jobKey) {
        return List.copyOf(edges.getOrDefault(jobKey, List.of()));
    }

    /**
     * The catalogue entry of a job.
     *
     * @param jobKey the job key
     * @return the entry, or empty for a key the catalogue does not know
     */
    public static Optional<Entry> find(String jobKey) {
        return Optional.ofNullable(ENTRIES.get(jobKey));
    }

    /**
     * Every job key the catalogue describes.
     *
     * @return the keys, in declaration order
     */
    public static Set<String> keys() {
        return ENTRIES.keySet();
    }

    private static void declare() {
        add(draft("near_term_batch_evaluation", "The overnight forecast cycle", Purpose.FORECAST, true,
                List.of("Open-Meteo", "Anthropic Batch API", "WorldTides (cached)"),
                "The main forecast. Sends every short-term location's coming sunrises and sunsets to Claude"
                        + " through the Batch API (half price, results arrive later) and stores the star ratings "
                        + "people see when they wake up.",
                "Fires a set time before sunrise, so it moves with the seasons and the ratings are in well "
                        + "before first light. The watchdog emails you if it hasn't finished 20 minutes before "
                        + "sunrise.")
                .resultsCollectedBy("batch_result_polling"));
        add(draft("intraday_forecast_refresh", "The afternoon refresh", Purpose.FORECAST, true,
                List.of("Open-Meteo", "Anthropic Batch API"),
                "A second look before tonight's sunset. Re-rates tonight's sunset and tomorrow's sunrise "
                        + "and sunset where the weather is still changing, skips places that are settled, then "
                        + "rebuilds the briefing.",
                "Fires a set time before sunset so there's still time to change plans. If tomorrow's "
                        + "sunrise already has a rating, it leaves it for the evening refresh.")
                .resultsCollectedBy("batch_result_polling"));
        add(draft("evening_sunrise_refresh", "Tomorrow's sunrise, at bedtime", Purpose.FORECAST, true,
                List.of("Open-Meteo", "Anthropic Batch API"),
                "Re-rates tomorrow's sunrise only, so the decision to set an early alarm is based on a "
                        + "fresh forecast.",
                "At a set bedtime hour in UK time all year (it follows the clocks, unlike most jobs, which "
                        + "run on GMT).")
                .resultsCollectedBy("batch_result_polling"));
        add(draft("aurora_batch_evaluation", "Overnight aurora ratings", Purpose.NIGHT_SKY, true,
                List.of("Anthropic Batch API"),
                "Sends the aurora viewing locations to Claude through the Batch API overnight and stores "
                        + "how good each one looks for aurora.",
                "In the quiet overnight slot after the main forecast has been submitted.")
                .resultsCollectedBy("batch_result_polling"));
        add(draft("sky_rating_eval", "The weekly accuracy test", Purpose.TESTING, true,
                List.of("Anthropic Batch API"),
                "Runs the frozen test fixtures through the real scorer with each model and records how many"
                        + " ratings land in the expected band. This is what the Sky Eval screen charts.",
                "Weekly, because calibration drifts slowly. Its slot keeps it away from the forecast "
                        + "cycles.")
                .resultsCollectedBy("sky_rating_eval_batch_poll"));
        add(draft("batch_result_polling", "Collects forecast results", Purpose.BATCH_PLUMBING, false,
                List.of("Anthropic Batch API"),
                "Every minute, asks Anthropic whether any submitted forecast batches have finished, and "
                        + "saves their ratings. Does nothing when no batch is in flight.",
                "Costs nothing itself. The Claude cost belongs to the job that submitted the batch.")
                .backstoppedBy("evaluation_abandonment_sweep"));
        add(draft("sky_rating_eval_batch_poll", "Collects Sky Eval results", Purpose.BATCH_PLUMBING, false,
                List.of("Anthropic Batch API"),
                "The same as Batch Result Polling, but for Sky Eval batches: finishes eval runs whose batch"
                        + " has come back. Safe to leave on; with nothing in flight it's a single empty query.",
                "Must be active before the weekly eval runs, or its results are never collected."));
        add(draft("evaluation_abandonment_sweep", "Clears lost batch results", Purpose.BATCH_PLUMBING, false,
                List.of(),
                "The safety net for batch results that never came back. Marks forecast ratings that have "
                        + "been pending for more than 48 hours as abandoned.",
                "Runs regularly; the 48-hour cut-off does the real work."));
        add(draft("forecast_cycle_watchdog", "Emails you if a cycle is late", Purpose.BATCH_PLUMBING, false,
                List.of("Email"),
                "Emails the admins, once per problem, if a forecast cycle didn't start within 30 minutes of"
                        + " its time, or hadn't finished 20 minutes before the sunrise or sunset it's for. Only "
                        + "reads; never starts or retries anything.",
                "Runs frequently, so a late cycle is caught soon. The deadlines it checks are drawn as red "
                        + "ticks on the cycles in the day picture.")
                .watches("near_term_batch_evaluation", "intraday_forecast_refresh"));
        add(draft("run_progress_cleanup", "Clears stuck progress bars", Purpose.HOUSEKEEPING, false,
                List.of(),
                "Removes progress entries for forecast runs that stalled more than 30 minutes ago, so the "
                        + "progress display doesn't show runs that died.",
                "On a short repeating interval."));
        add(draft("aurora_polling", "Aurora alerts", Purpose.NIGHT_SKY, false,
                List.of("NOAA SWPC", "Email / push"),
                "Checks NOAA's space-weather feed for aurora activity and starts, updates or ends aurora "
                        + "alerts for users.",
                "On a repeating interval."));
        add(draft("nlc_sighting_scrape", "Noctilucent cloud sightings", Purpose.NIGHT_SKY, false,
                List.of("NLCNET"),
                "Checks NLCNET for fresh noctilucent-cloud reports from observers, to drive the NLC "
                        + "sighting banner. If the site is down it fails quietly.",
                "On a repeating interval, matching how often the app checks."));
        add(draft("astro_conditions_refresh", "Stargazing scores", Purpose.NIGHT_SKY, false,
                List.of("Open-Meteo"),
                "Scores tonight's and tomorrow night's stargazing conditions (cloud, visibility, moonlight)"
                        + " for every place with a Bortle rating. Open-Meteo only, no Claude.",
                "Mid-afternoon: after the afternoon refresh, and before dusk at every time of year."));
        add(draft("wildlife_comfort_refresh", "Hourly comfort at wildlife hides", Purpose.FORECAST, false,
                List.of("Open-Meteo"),
                "Refreshes the hourly comfort forecast (temperature, feels-like, wind, rain) for wildlife "
                        + "hides, for today and the next five days. One Open-Meteo request, no Claude.",
                "Morning and evening, for people planning in the morning and in the evening."));
        add(draft("tide_refresh", "Weekly tide tables", Purpose.PLACES, false,
                List.of("WorldTides"),
                "Fetches 97 days of high and low tides from WorldTides for seascape locations that have a "
                        + "tide preference.",
                "Weekly. 97 days covers the 90-day Coming up feed plus a week of slack."));
        add(draft("cloud_verification_backfill", "Checks old forecasts against reality", Purpose.FORECAST, false,
                List.of("Open-Meteo archive (ERA5)"),
                "Checks past forecasts against what the sky actually did (ERA5 reanalysis). Works through "
                        + "any backlog in hourly slices to stay inside Open-Meteo's archive limit, and does nothing "
                        + "once caught up.",
                "Regularly, shortly after the top of the hour, once Open-Meteo's hourly allowance has "
                        + "reset."));
        add(draft("pending_registration_sweep", "Unverified sign-ups", Purpose.ACCOUNTS, false,
                List.of("Email"),
                "Emails people who signed up but never verified, 3 days and 1 day before their place "
                        + "lapses, then deletes the account after the final reminder.",
                "On a regular sweep."));
        add(draft("dormant_account_sweep", "Unused Lite accounts", Purpose.ACCOUNTS, false,
                List.of("Email"),
                "Warns Lite accounts that haven't been used for a long time, then deletes them after a "
                        + "grace period if they don't sign in.",
                "Paused. Even when resumed it only logs what it would do, until photocast.dormancy.dry-run "
                        + "is turned off."));
        add(draft("drive_time_refresh", "Drive times from users' homes", Purpose.TRAVEL, false,
                List.of("OpenRouteService"),
                "Works out drive times from each user's home to every location, but only for users whose "
                        + "postcode changed, who've never been measured, or when a location has been added since. Run"
                        + " now re-measures everyone.",
                "In the quiet overnight slot, after the overnight forecast has settled and when the routing"
                        + " service is quiet.")
                .staggeredBefore("region_drive_time_refresh"));
        add(draft("region_drive_time_refresh", "Drive times from region bases", Purpose.TRAVEL, false,
                List.of("OpenRouteService"),
                "Works out drive times from each region's base town to every location, for the Plan tab's "
                        + "'Plan from here'. Skips regions without a base town.",
                "Half an hour after the user drive-time refresh, because both share the routing service's "
                        + "rate limit."));
        add(draft("grid_cell_backfill", "Fixes locations missing a weather cell", Purpose.PLACES, false,
                List.of("Open-Meteo"),
                "Gives any location without an Open-Meteo grid cell one, so it can be grouped with "
                        + "neighbours in the same ~2 km cell. Locations added by database migrations arrive without "
                        + "one. Usually finds nothing to do.",
                "Nightly, so a location added by a migration is fixed the next night."));
        add(draft("topic_daily_log", "Yesterday's notable conditions", Purpose.FORECAST, false,
                List.of(),
                "Records which Coming up topics (Saharan dust, inversions, big tides, storm surge, snow, "
                        + "aurora, noctilucent cloud) happened yesterday in each region. A history for later rarity "
                        + "scoring; nothing reads it yet.",
                "In the overnight slot, clear of the main cluster of housekeeping jobs."));
        add(draft("refresh_token_cleanup", "Expired sign-in tokens", Purpose.HOUSEKEEPING, false,
                List.of(),
                "Deletes expired sign-in refresh tokens.",
                "Nightly, in the housekeeping slot."));
        add(draft("disposition_cleanup", "Old 'why was this skipped' records", Purpose.HOUSEKEEPING, false,
                List.of(),
                "Deletes the per-location records of why each candidate was rated, skipped or cached in a "
                        + "forecast run, once they're 30 days old.",
                "Nightly, in the housekeeping slot."));
        add(draft("slot_atmosphere_cleanup", "Old atmosphere readings", Purpose.HOUSEKEEPING, false,
                List.of(),
                "Deletes stored atmosphere readings (dust, snow, surge, inversion score) for dates more "
                        + "than 180 days ago.",
                "Nightly, in the housekeeping slot."));
        add(draft("forecast_prompt_cleanup", "Old copies of Claude prompts", Purpose.HOUSEKEEPING, false,
                List.of(),
                "Deletes stored copies of the forecast prompts sent to Claude once they're 30 days old. "
                        + "They're kept to investigate ratings that flip on identical inputs.",
                "Nightly, in the housekeeping slot."));
        add(draft("ask_log_cleanup", "Old Ask PhotoCast questions", Purpose.HOUSEKEEPING, false,
                List.of(),
                "Deletes Ask PhotoCast question-log rows older than 90 days.",
                "Nightly, in the housekeeping slot."));
        add(draft("scheduler_fire_log_cleanup", "Clears old run history", Purpose.HOUSEKEEPING, false, List.of(),
                "Deletes Scheduler run-history rows older than 14 days, so this screen's history stays a fortnight "
                        + "deep and the table stays small.",
                "Nightly, in the housekeeping slot."));
    }
}
