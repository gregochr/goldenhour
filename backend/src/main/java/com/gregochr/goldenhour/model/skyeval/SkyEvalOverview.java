package com.gregochr.goldenhour.model.skyeval;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;

import java.time.Instant;
import java.util.List;

/**
 * Everything the redesigned Sky Eval page renders, served by one read ({@code GET /overview}). Every figure
 * is derived on the server; the client only formats. Percentages are 0 to 100 (not 0 to 1); costs are pounds;
 * instants are ISO-8601 UTC.
 *
 * @param head            the running build; every field null when unknown
 * @param schedule        the weekly job
 * @param lanes           the two production lanes, near-term then far-term
 * @param fixtures        the fixture registry, in registry order
 * @param versions        every version seen on a run plus HEAD, newest first
 * @param sessions        runs grouped into sessions, newest first
 * @param runFixtures     per completed run and fixture figures (each model's newest runs)
 * @param models          one row per model with a run or a lane
 * @param modelFixtures   per model and fixture figures over the model's last four completed runs
 * @param verdict         the headline over the production lanes
 * @param comparisons     before and after for the last five versions
 * @param checkDefaults   what the Run a check panel opens with
 */
public record SkyEvalOverview(
        Version head,
        Schedule schedule,
        List<Lane> lanes,
        List<Fixture> fixtures,
        List<Version> versions,
        List<Session> sessions,
        List<RunFixture> runFixtures,
        List<ModelStats> models,
        List<ModelFixture> modelFixtures,
        Verdict verdict,
        List<Comparison> comparisons,
        CheckDefaults checkDefaults) {

    /**
     * A version: one commit.
     *
     * @param sha         full commit sha, or null when unknown
     * @param shortSha    first seven characters of the sha
     * @param message     the commit subject line, or null
     * @param committedAt when it was committed (first run time for an old sha without a date), or null
     */
    public record Version(String sha, String shortSha, String message, Instant committedAt) {
    }

    /**
     * The weekly job.
     *
     * @param label     for example {@code weekly, Mon 04:00} (UK time), the raw cron, or {@code weekly run paused}
     * @param jobStatus the job's scheduler status, or null when it has no row
     * @param models    the models the weekly run scores
     */
    public record Schedule(String label, SchedulerJobStatus jobStatus, List<EvaluationModel> models) {
    }

    /**
     * A production lane and how the other models compare with its model.
     *
     * @param runType  the lane's run type
     * @param label    for example "T and T+1 forecasts"
     * @param purpose  what the lane needs from its model
     * @param model    the model the lane runs on today (from {@code model_selection})
     * @param compared one row per other fresh model with weekly history
     */
    public record Lane(RunType runType, String label, String purpose, EvaluationModel model,
            List<Compared> compared) {
    }

    /**
     * One "Compared with, last 4 weekly" row; differences are relative to the lane's model.
     *
     * @param model            the other model
     * @param lanes            the lanes that model serves (empty = eval only)
     * @param last4WeeklyPct   its pooled in-band share over its last four scheduled runs
     * @param deltaPts         points above (positive) or below the lane's model; null if either is unknown
     * @param costMultiple     its cost per run divided by the lane model's; null if either is unknown
     * @param fellToOnHeadPct  its in-band share on HEAD when it fell three points or more there, else null
     */
    public record Compared(EvaluationModel model, List<RunType> lanes, Double last4WeeklyPct, Double deltaPts,
            Double costMultiple, Double fellToOnHeadPct) {
    }

    /**
     * A fixture.
     *
     * @param name        the registry name
     * @param bandLo      lowest in-band rating
     * @param bandHi      highest in-band rating
     * @param firstSeenAt the first run time that scored it, or null if never run
     */
    public record Fixture(String name, int bandLo, int bandHi, Instant firstSeenAt) {
    }

    /**
     * A session: runs of one trigger and version started close together (or in one batch).
     *
     * @param id        the first run's id
     * @param trigger   what started it
     * @param version   the version sha, or null when unknown
     * @param startedAt when its first run started
     * @param status    RUNNING if any run is running, else FAILED if all failed, else DONE
     * @param costGbp   the runs' costs summed in pounds
     * @param runs      the runs, in start order
     * @param byModel   per model: runs and pooled in-band share (the chart point and the Runs chip)
     */
    public record Session(long id, SkyRatingEvalTrigger trigger, String version, Instant startedAt,
            SessionStatus status, double costGbp, List<Run> runs, List<ModelSession> byModel) {
    }

    /** A session's state. */
    public enum SessionStatus {
        /** At least one run is still running. */
        RUNNING,
        /** Every run failed. */
        FAILED,
        /** Finished. */
        DONE
    }

    /**
     * One run inside a session.
     *
     * @param id             the run id
     * @param model          the model
     * @param fixtureCount   fixtures scored
     * @param runsPerFixture repeats per fixture
     * @param ratings        attempts persisted so far (failed attempts count)
     * @param inBand         attempts inside the band
     * @param below          attempts rated under the band
     * @param above          attempts rated over the band
     * @param inBandPct      in band over attempts, or null with no attempts
     * @param costGbp        cost in pounds at the exchange rate of the run's start date
     * @param durationMs     wall-clock duration, or null while running
     * @param status         the run's status
     * @param errorMessage   the failure or partial-failure text, or null
     * @param progress       0 to 1 while a synchronous run is running, else null
     */
    public record Run(long id, EvaluationModel model, int fixtureCount, int runsPerFixture, long ratings,
            long inBand, long below, long above, Double inBandPct, double costGbp, Long durationMs,
            SkyRatingEvalStatus status, String errorMessage, Double progress) {
    }

    /**
     * A model's pooled result within one session.
     *
     * @param model     the model
     * @param runCount  its runs in the session
     * @param inBandPct pooled in band over attempts across those runs, or null with none
     */
    public record ModelSession(EvaluationModel model, int runCount, Double inBandPct) {
    }

    /**
     * One completed run on one fixture. Attempts include failed calls, which are non-passes; the mean, minimum
     * and maximum cover only the attempts that carry a rating.
     *
     * @param runId   the run
     * @param fixture the fixture
     * @param n       attempts
     * @param inBand  attempts in band
     * @param below   attempts under the band
     * @param above   attempts over the band
     * @param mean    mean rating, or null when none rated
     * @param min     lowest rating, or null
     * @param max     highest rating, or null
     */
    public record RunFixture(long runId, String fixture, long n, long inBand, long below, long above, Double mean,
            Integer min, Integer max) {
    }

    /** A model's standing against the previous version. */
    public enum ModelStatus {
        /** Within three points of the previous version. */
        HELD,
        /** Down three points or more. */
        DROPPED,
        /** No completed run on HEAD. */
        UNCHECKED,
        /** Run on HEAD but nothing earlier to compare with. */
        NO_BASELINE
    }

    /**
     * One row of the All models table and the data behind the lane stat strips.
     *
     * @param model              the model
     * @param label              its display name
     * @param lanes              the lanes it serves (empty = eval only)
     * @param last4WeeklyPct     pooled in band over its last four completed scheduled runs, or null
     * @param last4Below         below-band attempts in those runs
     * @param last4Above         above-band attempts in those runs
     * @param last4Ratings       attempts in those runs
     * @param onHeadPct          pooled in band over its completed runs on HEAD, or null
     * @param onHeadRuns         how many completed runs it has on HEAD
     * @param previousVersionPct pooled over its last two runs on the most recent earlier version, or null
     * @param deltaPts           onHeadPct minus previousVersionPct (unrounded), or null
     * @param status             held, dropped, unchecked or no baseline
     * @param costPerRunGbp      mean cost of its last four scheduled runs, or null
     * @param costPerRatingGbp   cost per attempt over its recent completed manual runs, or null
     * @param secondsPerRating   seconds per attempt over those manual runs, or null
     * @param lastTestedAt       completion of its newest completed run, or null
     * @param fresh              tested within 21 days
     */
    public record ModelStats(EvaluationModel model, String label, List<RunType> lanes, Double last4WeeklyPct,
            long last4Below, long last4Above, long last4Ratings, Double onHeadPct, int onHeadRuns,
            Double previousVersionPct, Double deltaPts, ModelStatus status, Double costPerRunGbp,
            Double costPerRatingGbp, Double secondsPerRating, Instant lastTestedAt, boolean fresh) {
    }

    /**
     * A model's result on one fixture over its last four completed runs.
     *
     * @param model       the model
     * @param fixture     the fixture
     * @param last4Misses below plus above attempts
     * @param last4Pct    in band over attempts
     */
    public record ModelFixture(EvaluationModel model, String fixture, long last4Misses, double last4Pct) {
    }

    /** Which verdict the headline carries. */
    public enum VerdictKind {
        /** A production model dropped three points or more. */
        DROPPED,
        /** A production model has not run on HEAD. */
        UNCHECKED,
        /** Every production model held. */
        HELD,
        /** The running build has no version recorded. */
        NO_HEAD,
        /** No production model has anything earlier to compare with. */
        NO_BASELINE
    }

    /**
     * The verdict card.
     *
     * @param kind                  which case applies
     * @param headline              the finished sentence
     * @param model                 the model the headline names (dropped or unchecked), else null
     * @param lines                 each production model's before and after
     * @param droppedNonProduction  true when a model not used in production dropped (the body adds a note)
     */
    public record Verdict(VerdictKind kind, String headline, EvaluationModel model, List<VerdictLine> lines,
            boolean droppedNonProduction) {
    }

    /**
     * One production model's before and after in the verdict body.
     *
     * @param model     the model
     * @param label     its display name
     * @param beforePct previous-version share, or null
     * @param afterPct  HEAD share, or null
     */
    public record VerdictLine(EvaluationModel model, String label, Double beforePct, Double afterPct) {
    }

    /**
     * Before and after for one version.
     *
     * @param version the version compared
     * @param rows    one row per model, production models first
     */
    public record Comparison(Version version, List<ComparisonRow> rows) {
    }

    /**
     * One model's before and after on a version.
     *
     * @param model     the model
     * @param lanes     the lanes it serves
     * @param beforePct last two runs on the most recent earlier version, over the fixtures both sides ran
     * @param afterPct  all its runs on the version, over the same fixtures
     * @param deltaPts  afterPct minus beforePct (unrounded), or null
     * @param notRun    true when the model has no completed run on this version
     * @param moved     fixtures whose in-band share moved by 20 points or more
     */
    public record ComparisonRow(EvaluationModel model, List<RunType> lanes, Double beforePct, Double afterPct,
            Double deltaPts, boolean notRun, List<Moved> moved) {
    }

    /** Which way a fixture moved. */
    public enum Direction {
        /** In-band share rose. */
        BETTER,
        /** In-band share fell. */
        WORSE
    }

    /** Which side of the band the misses fall on. */
    public enum MissSide {
        /** Rated too low. */
        BELOW,
        /** Rated too high. */
        ABOVE
    }

    /**
     * A fixture whose in-band share moved by 20 points or more.
     *
     * @param fixture   the fixture
     * @param beforePct in band before
     * @param afterPct  in band after
     * @param direction better or worse
     * @param missSide  the dominant miss side after, or null with no misses or a tie
     */
    public record Moved(String fixture, double beforePct, double afterPct, Direction direction,
            MissSide missSide) {
    }

    /**
     * What the Run a check panel opens with.
     *
     * @param models          the production models, distinct
     * @param runsPerFixture  the default repeats
     * @param missedLastTime  fixtures with a miss in the most recent finished session
     */
    public record CheckDefaults(List<EvaluationModel> models, int runsPerFixture, List<String> missedLastTime) {
    }
}
