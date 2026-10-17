package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Event-driven state machine tracking the current aurora alert lifecycle.
 *
 * <p>Two states: IDLE (no active event) and ACTIVE (alert in progress, locations scored).
 * The machine emits an {@link Action} on each call to {@link #evaluate(AlertLevel)},
 * which the polling job uses to decide whether to score locations, suppress, or clear.
 *
 * <p>A separate simulation path ({@link #activateSimulation}) bypasses the FSM transitions
 * to inject fake NOAA data for admin testing without a real geomagnetic storm. A simulation is an
 * ACTIVE state that lasts only until the next real reading: see {@link #evaluate(AlertLevel)}.
 *
 * <p>State transitions:
 * <ul>
 *   <li>IDLE + QUIET/MINOR → {@link Action#NONE} (stay IDLE)</li>
 *   <li>IDLE + MODERATE/STRONG → {@link Action#NOTIFY}, transition to ACTIVE</li>
 *   <li>ACTIVE + higher severity → {@link Action#NOTIFY} (escalation), stay ACTIVE</li>
 *   <li>ACTIVE + same or lower alertable level → {@link Action#SUPPRESS}, stay ACTIVE</li>
 *   <li>ACTIVE + QUIET/MINOR → {@link Action#CLEAR}, transition to IDLE</li>
 *   <li>Simulated + MODERATE/STRONG → {@link Action#NOTIFY} at the real level, as from IDLE, and
 *       the simulation ends</li>
 *   <li>Simulated + QUIET/MINOR → {@link Action#CLEAR}, transition to IDLE, and the simulation
 *       ends</li>
 * </ul>
 *
 * <p>IDLE is one state however it is reached, and so is the start of a new alert or a simulation:
 * each of those transitions writes every field through one {@code become(...)}, so none can leave a
 * field of the state before it behind. Before that rule each route reset only the fields its author
 * remembered: CLEAR left the trigger and the simulation behind, so a simulation nobody cleared
 * outlived the alert it faked, and the next real alert was served to every Pro user as
 * "(SIMULATED)", with the simulation's G-scale.
 *
 * <p>Thread safety: the machine is written from several threads — the polling job's cycle (a
 * scheduled poll, the Scheduler screen's Run Now, and the admin {@code POST /run} all reach
 * {@link AuroraPollingJob#runCycleIfIdle()}, whose own {@code AtomicBoolean} keeps two cycles from
 * running at once, but does not stop a cycle from racing an admin write below), request threads (the
 * admin {@code simulate}, {@code simulate/clear} and {@code reset} endpoints) and the batch result
 * path ({@code AuroraResultHandler}). Every write here holds one lock for the whole of its
 * transition, so no two transitions interleave: without it, a simulation starting while a CLEAR ran
 * could keep the simulated level and lose the simulation, which is a fake alert served as a real one.
 * A {@link ReentrantLock} rather than {@code synchronized}, because the request threads are virtual
 * and a held monitor pins a virtual thread on Java 21. This does not serialise a cycle's evaluation
 * with an admin write end to end — a reset or simulate landing between a poll's
 * {@link #wouldNotify}/{@link #wouldClear} check and its {@link #evaluate} call still moves the state
 * the check answered for, which is why both callers re-fetch on that mismatch rather than trust the
 * check (see {@code AuroraOrchestrator}). Two things the lock does not do:
 * <ul>
 *   <li><b>Readers take no lock.</b> Each getter is one {@code volatile} read, so a reader that reads
 *       several fields can straddle a transition and describe two states at once. The simulation is
 *       held in one reference, so its flag and its data can never disagree — read
 *       {@link #getSimulatedData()} once and test it for {@code null}. {@code become} writes it on
 *       the side that serves a reader who reads it first: a new simulation is set before the level,
 *       and an ending one is cleared after it. So a reader that finds no simulation cannot find a
 *       simulated level that is only now ending, and a switch from one simulation to another never
 *       shows none. Still open, for one read — which a client can go on showing until its next
 *       status fetch: a reader whose two reads straddle both writes of a starting simulation can find
 *       its level without its data, and one that found a simulation just before a real alert ended
 *       it can find that alert's level beside the simulation's data. Closing it needs one immutable
 *       snapshot published per transition.</li>
 *   <li><b>A NOTIFY and its scoring are separate writes.</b> The orchestrator records the trigger,
 *       the counts and the scores after {@link #evaluate} returns — on the forecast lookahead, only
 *       after a further NOAA fetch — and the batch result path writes scores whatever the state. So
 *       until the orchestrator records its trigger a new alert has none and an escalation shows the
 *       previous NOTIFY's; and a scoring's writes can land after a later transition — a CLEAR, a
 *       reset, a simulation, or a later NOTIFY whose writes they then overwrite — has moved past the
 *       alert they were computed for.</li>
 * </ul>
 */
@Component
public class AuroraStateCache {

    /**
     * Actions emitted by the state machine.
     */
    public enum Action {
        /** New alert, or an escalation — score all eligible locations. Sends no email or push. */
        NOTIFY,
        /** Duplicate or de-escalating alert — do nothing. */
        SUPPRESS,
        /** Alert has ended — clear all cached scores. */
        CLEAR,
        /** No active alert and no change — do nothing. */
        NONE
    }

    /**
     * State machine evaluation result.
     *
     * @param action        what the polling job should do
     * @param currentLevel  the effective current level after this transition
     * @param previousLevel the level before this transition (null for the first NOTIFY)
     */
    public record Evaluation(Action action, AlertLevel currentLevel, AlertLevel previousLevel) {
    }

    /**
     * Simulated NOAA space weather data injected by the admin simulate endpoint.
     *
     * @param kp                 simulated Kp index
     * @param ovationProbability simulated OVATION aurora probability at 55°N
     * @param bzNanoTesla        simulated solar wind Bz component in nanoTesla
     * @param gScale             simulated NOAA G-scale label (e.g. "G3"), or null
     */
    public record SimulatedNoaaData(
            double kp,
            double ovationProbability,
            double bzNanoTesla,
            String gScale) {
    }

    private enum State { IDLE, ACTIVE }

    /** Held by every write for the whole of its transition — see the class comment. */
    private final ReentrantLock transitionLock = new ReentrantLock();

    private volatile State state = State.IDLE;
    private volatile AlertLevel currentLevel = null;
    private volatile List<AuroraForecastScore> cachedScores = List.of();
    private volatile TriggerType lastTriggerType = null;
    private volatile Double lastTriggerKp = null;
    private volatile int darkSkyLocationCount = 0;
    private volatile Integer clearLocationCount = null;
    private volatile Instant activeSince = null;

    /**
     * The running simulation's fake NOAA data, or {@code null} when there is none. Its presence is
     * the simulation flag: a separate boolean, written and cleared as a second field, let a reader
     * see the flag set and the data already gone.
     */
    private volatile SimulatedNoaaData simulatedData = null;

    private final Clock clock;

    /**
     * Constructs the state machine, IDLE, on the system clock.
     */
    public AuroraStateCache() {
        this(Clock.systemUTC());
    }

    /**
     * Constructs the state machine, IDLE, stamping {@link #getActiveSince()} from {@code clock}.
     *
     * @param clock supplies the instant an alert becomes active, escalates, or a simulation starts
     */
    AuroraStateCache(Clock clock) {
        this.clock = clock;
    }

    /**
     * Evaluates an incoming alert level and advances the state machine.
     *
     * <p>Called only from inside a polling cycle ({@link AuroraPollingJob} never runs two at once)
     * or the admin {@code POST /api/aurora/admin/run}, which reaches the same cycle; the transition
     * lock makes the read-check-write atomic against every other write, so two evaluations cannot
     * both NOTIFY from one IDLE.
     *
     * <p>Every branch is decided from one read of the state, the level and whether a simulation is
     * running, taken together under the lock: nothing else can move any of the three between the
     * read and the write this call makes.
     *
     * <p>A real reading ends a running simulation. A simulated alert was never a real one, so a real
     * alert is not measured against it: the reading answers as it would from IDLE — a new alert at
     * the real level, starting clean, with no previous level — where it used to be SUPPRESSed when
     * at or below the simulated level, and so never scored. A reading below alert level CLEARs the
     * simulation as it would any alert.
     *
     * @param incoming the latest alert level from AuroraWatch
     * @return the evaluation result containing the action and level context
     */
    public Evaluation evaluate(AlertLevel incoming) {
        transitionLock.lock();
        try {
            State from = state;
            AlertLevel current = currentLevel;
            boolean simulating = simulatedData != null;
            if (clears(from, incoming)) {
                become(State.IDLE, null, null, null, null, null);
                return new Evaluation(Action.CLEAR, null, current);
            }
            if (!incoming.isAlertWorthy()) {
                return new Evaluation(Action.NONE, null, null);
            }
            if (!notifies(from, current, simulating, incoming)) {
                // Same level or de-escalation within alertable range
                return new Evaluation(Action.SUPPRESS, current, null);
            }
            if (from == State.IDLE || simulating) {
                become(State.ACTIVE, incoming, clock.instant(), null, null, null);
                return new Evaluation(Action.NOTIFY, incoming, null);
            }
            // An escalation writes currentLevel and activeSince only, never the rest of the state.
            currentLevel = incoming;
            activeSince = clock.instant();
            return new Evaluation(Action.NOTIFY, incoming, current);
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * Whether {@link #evaluate} would answer NOTIFY for {@code incoming} now, without changing any
     * state. It asks the same question {@code evaluate} decides its NOTIFY by — simulation included —
     * so the two cannot drift apart.
     *
     * <p>A daylight poll asks before evaluating, so it fetches the data a scoring needs only when a
     * scoring is coming.
     *
     * @param incoming the level about to be evaluated
     * @return {@code true} for a new alert from IDLE, a takeover of a running simulation, or an
     *         escalation above the current real level
     */
    public boolean wouldNotify(AlertLevel incoming) {
        transitionLock.lock();
        try {
            return notifies(state, currentLevel, simulatedData != null, incoming);
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * Whether {@link #evaluate} would answer CLEAR for {@code incoming} now, without changing any
     * state: an alert (real or simulated) is active and {@code incoming} is below MODERATE. It asks
     * the same question {@code evaluate} decides its CLEAR by.
     *
     * <p>A night poll asks before evaluating, so it can hold an alert while the reading that will
     * decide it is still due.
     *
     * @param incoming the level about to be evaluated
     * @return {@code true} when evaluating {@code incoming} would end the active alert
     */
    public boolean wouldClear(AlertLevel incoming) {
        transitionLock.lock();
        try {
            return clears(state, incoming);
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * Whether {@code incoming} would NOTIFY: a new alert from IDLE, any alert-worthy reading during a
     * simulation (a takeover — never measured against the simulated level, so a real reading is a
     * NOTIFY whether it is above, at, or below it), or a real escalation above the current level.
     */
    private static boolean notifies(State from, AlertLevel current, boolean simulating,
            AlertLevel incoming) {
        return incoming.isAlertWorthy()
                && (from == State.IDLE || simulating || incoming.severity() > current.severity());
    }

    /** Whether {@code incoming} would CLEAR: an alert (real or simulated) is active, and it is not. */
    private static boolean clears(State from, AlertLevel incoming) {
        return !incoming.isAlertWorthy() && from == State.ACTIVE;
    }

    /**
     * Stores the freshly computed scores after a NOTIFY event.
     *
     * @param scores scored aurora locations; must not be null
     */
    public void updateScores(List<AuroraForecastScore> scores) {
        transitionLock.lock();
        try {
            this.cachedScores = List.copyOf(scores);
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * Records which trigger path fired the last NOTIFY and the Kp value that drove it.
     *
     * <p>For {@link TriggerType#FORECAST_LOOKAHEAD} this is the highest Kp forecast for the rest of
     * tonight; for {@link TriggerType#REALTIME} it is the Kp for now.
     *
     * @param triggerType the path that produced the NOTIFY
     * @param kp          the Kp value that triggered the alert
     */
    public void updateTrigger(TriggerType triggerType, double kp) {
        transitionLock.lock();
        try {
            this.lastTriggerType = triggerType;
            this.lastTriggerKp = kp;
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * Returns the trigger type of the last NOTIFY, or {@code null} when IDLE — and for a new alert
     * until the orchestrator records its trigger.
     *
     * @return last {@link TriggerType}, or {@code null}
     */
    public TriggerType getLastTriggerType() {
        return lastTriggerType;
    }

    /**
     * Returns the Kp value that drove the last NOTIFY, or {@code null} when IDLE — and for a new
     * alert until the orchestrator records its trigger.
     *
     * @return last trigger Kp, or {@code null}
     */
    public Double getLastTriggerKp() {
        return lastTriggerKp;
    }

    /**
     * Updates the aurora-relevant location counts after Bortle filtering and cloud triage.
     *
     * @param darkSkyCount number of Bortle-eligible (dark sky) locations
     * @param clearCount   number of locations that passed cloud triage (clear skies)
     */
    public void updateLocationCounts(int darkSkyCount, int clearCount) {
        transitionLock.lock();
        try {
            this.darkSkyLocationCount = darkSkyCount;
            this.clearLocationCount = clearCount;
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * Returns the number of dark sky locations meeting the Bortle threshold, or 0 if scoring
     * has not run.
     *
     * @return dark sky location count
     */
    public int getDarkSkyLocationCount() {
        return darkSkyLocationCount;
    }

    /**
     * Returns the number of locations that passed cloud triage (clear skies), or {@code null}
     * if the triage has not yet run (e.g. during simulation or before the scoring pipeline).
     *
     * @return clear location count, or {@code null}
     */
    public Integer getClearLocationCount() {
        return clearLocationCount;
    }

    /**
     * Returns the cached aurora forecast scores from the last NOTIFY event.
     *
     * <p>Returns an empty list when the state machine is IDLE.
     *
     * @return immutable list of scored locations
     */
    public List<AuroraForecastScore> getCachedScores() {
        return cachedScores;
    }

    /**
     * Returns the current effective alert level, or {@code null} when IDLE.
     *
     * @return current {@link AlertLevel}, or {@code null}
     */
    public AlertLevel getCurrentLevel() {
        return currentLevel;
    }

    /**
     * Returns {@code true} when the state machine is in the ACTIVE state.
     *
     * @return {@code true} if an aurora event is in progress
     */
    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /**
     * Returns the instant when the state machine first entered the current active state,
     * or when it last escalated to a higher alert level. Returns {@code null} when IDLE.
     *
     * @return detection timestamp, or {@code null}
     */
    public Instant getActiveSince() {
        return activeSince;
    }

    /**
     * Activates a simulation by directly injecting alert state without going through the FSM.
     *
     * <p>Replaces the whole state — nothing of any alert or simulation it replaces survives, its
     * scores and counts included — and sets the machine ACTIVE at the given level, carrying the fake
     * NOAA data. No Claude call is made; the admin must trigger a manual Forecast Run to generate
     * scores. The data is visible to the status endpoint and the forecast preview, so the UI can
     * display a "(SIMULATED)" indicator.
     *
     * <p>Intended for admin testing only. While the {@code aurora_polling} job runs, the next real
     * reading it evaluates ends the simulation — see {@link #evaluate(AlertLevel)}. After dark every
     * poll evaluates one, whether or not NOAA answers (the client fails open, to its cache or to an
     * empty reading, which derives QUIET), so a simulation started at night lasts until the next
     * poll: five minutes at most by default. By day only the forecast lookahead evaluates, and only
     * when tonight's forecast reaches the alert threshold, so a simulation can last until dusk. With
     * {@code aurora.enabled=false} or the job paused nothing evaluates, and it lasts until cleared.
     *
     * @param level simulated alert level
     * @param data  fake NOAA space weather values to surface via the status endpoint
     */
    public void activateSimulation(AlertLevel level, SimulatedNoaaData data) {
        transitionLock.lock();
        try {
            become(State.ACTIVE, level, clock.instant(), TriggerType.FORECAST_LOOKAHEAD, data.kp(),
                    data);
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * Ends a running simulation, and nothing else: returns the machine to IDLE only while a
     * simulation still stands. A real reading may already have ended it — with a real alert, if the
     * reading was one — and a Clear aimed at the simulation, sent from a screen that has not yet
     * seen that, must not wipe the real alert.
     *
     * @return {@code true} if a simulation was running and has been ended; {@code false} if none was
     */
    public boolean endSimulation() {
        transitionLock.lock();
        try {
            if (simulatedData == null) {
                return false;
            }
            enterIdle();
            return true;
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * Returns the running simulation's fake NOAA data, or {@code null} when no simulation is
     * running — which is also the only way to ask whether one is.
     *
     * <p>Read it once and test the value you read. Asking twice — once for "is it simulated" and
     * once for the data — reopens the gap this single reference exists to close: a simulation ended
     * between the two reads answers "simulated" with no data behind it.
     *
     * @return simulated space weather data, or {@code null}
     */
    public SimulatedNoaaData getSimulatedData() {
        return simulatedData;
    }

    /**
     * Resets the state machine to IDLE with no cached scores.
     *
     * <p>Also clears any active simulation. Intended for testing and admin use only.
     */
    public void reset() {
        transitionLock.lock();
        try {
            enterIdle();
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * The lock every write (and {@link #wouldNotify}/{@link #wouldClear}) holds. Package-private for
     * {@code AuroraStateCacheTest}: no write calls out to anything a test could park inside, so the
     * test holds the lock itself on its own thread and runs each write on a second one, to prove the
     * write waits for a transition in progress.
     *
     * @return the transition lock
     */
    ReentrantLock transitionLock() {
        return transitionLock;
    }

    /** Returns every field to the value a fresh machine starts with. The caller holds the lock. */
    private void enterIdle() {
        become(State.IDLE, null, null, null, null, null);
    }

    /**
     * Writes a whole state: every field, so that no transition can leave one of the previous
     * state's fields behind — the defect that let a simulation outlive the alert it faked. Scores and
     * counts always start empty. The caller holds the lock.
     *
     * <p>The simulation is written on the side that serves a reader who reads it first (as
     * {@code AuroraController.getStatus} does): a new simulation is set before the level, and an
     * ending one cleared after it. Finding no simulation, such a reader cannot then find a simulated
     * level that is only now ending and serve it as a real alert; and a switch from one simulation to
     * another never shows none. No test pins this order — it matters only to a reader racing the
     * writes themselves, which no deterministic test can place.
     */
    private void become(State newState, AlertLevel level, Instant since, TriggerType trigger,
            Double triggerKp, SimulatedNoaaData simulation) {
        if (simulation != null) {
            simulatedData = simulation;
        }
        state = newState;
        currentLevel = level;
        activeSince = since;
        cachedScores = List.of();
        lastTriggerType = trigger;
        lastTriggerKp = triggerKp;
        darkSkyLocationCount = 0;
        clearLocationCount = null;
        if (simulation == null) {
            simulatedData = null;
        }
    }
}
