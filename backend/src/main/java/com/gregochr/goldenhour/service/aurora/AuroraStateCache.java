package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Event-driven state machine tracking the current aurora alert lifecycle.
 *
 * <p>Two states: IDLE (no active event) and ACTIVE (alert in progress, locations scored).
 * The machine emits an {@link Action} on each call to {@link #evaluate(AlertLevel)},
 * which the polling job uses to decide whether to score locations, suppress, or clear.
 *
 * <p>A separate simulation path ({@link #activateSimulation}) bypasses the FSM transitions
 * to inject fake NOAA data for admin testing without a real geomagnetic storm.
 *
 * <p>State transitions:
 * <ul>
 *   <li>IDLE + QUIET/MINOR → {@link Action#NONE} (stay IDLE)</li>
 *   <li>IDLE + MODERATE/STRONG → {@link Action#NOTIFY}, transition to ACTIVE</li>
 *   <li>ACTIVE + higher severity → {@link Action#NOTIFY} (escalation), stay ACTIVE</li>
 *   <li>ACTIVE + same or lower alertable level → {@link Action#SUPPRESS}, stay ACTIVE</li>
 *   <li>ACTIVE + QUIET/MINOR → {@link Action#CLEAR}, transition to IDLE</li>
 * </ul>
 *
 * <p>Thread safety: {@code volatile} fields let readers (the REST endpoints, the briefing builders)
 * see state from their own threads. The compound read-check-write in {@link #evaluate(AlertLevel)}
 * is single-writer: only a polling cycle calls it, and {@link AuroraPollingJob} never runs two
 * cycles at once, whichever route started them. A cycle runs on a scheduler thread, or on the
 * request thread for the admin run endpoint.
 *
 * <p>⚠️ Not every write goes through that guard. The admin {@code reset}, {@code simulate} and
 * {@code simulate/clear} endpoints write from request threads, and the aurora batch job's result
 * handler calls {@link #updateScores} from the batch-polling thread whatever the state. A reset that
 * lands while a cycle is scoring leaves the machine IDLE holding that cycle's scores.
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

    private volatile State state = State.IDLE;
    private volatile AlertLevel currentLevel = null;
    private volatile List<AuroraForecastScore> cachedScores = List.of();
    private volatile TriggerType lastTriggerType = null;
    private volatile Double lastTriggerKp = null;
    private volatile int darkSkyLocationCount = 0;
    private volatile Integer clearLocationCount = null;
    private volatile Instant activeSince = null;
    private volatile boolean simulated = false;
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
     * @param clock supplies the instant an alert becomes active or escalates
     */
    AuroraStateCache(Clock clock) {
        this.clock = clock;
    }

    /**
     * Evaluates an incoming alert level and advances the state machine.
     *
     * <p>Called only from inside a polling cycle, and {@link AuroraPollingJob} never runs two at once.
     *
     * @param incoming the latest alert level from AuroraWatch
     * @return the evaluation result containing the action and level context
     */
    public Evaluation evaluate(AlertLevel incoming) {
        if (wouldClear(incoming)) {
            AlertLevel prev = currentLevel;
            state = State.IDLE;
            currentLevel = null;
            activeSince = null;
            cachedScores = List.of();
            darkSkyLocationCount = 0;
            clearLocationCount = null;
            return new Evaluation(Action.CLEAR, null, prev);
        }
        if (!incoming.isAlertWorthy()) {
            return new Evaluation(Action.NONE, null, null);
        }
        if (!wouldNotify(incoming)) {
            // Same level or de-escalation within alertable range
            return new Evaluation(Action.SUPPRESS, currentLevel, null);
        }
        if (state == State.IDLE) {
            state = State.ACTIVE;
            currentLevel = incoming;
            activeSince = clock.instant();
            return new Evaluation(Action.NOTIFY, incoming, null);
        }
        // An escalation. It writes no state, only the level, as it always has, so a reset landing
        // mid-escalation cannot leave the machine ACTIVE without a level.
        AlertLevel prev = currentLevel;
        currentLevel = incoming;
        activeSince = clock.instant();
        return new Evaluation(Action.NOTIFY, incoming, prev);
    }

    /**
     * Whether {@link #evaluate} would answer NOTIFY for {@code incoming} now, without changing any
     * state. {@code evaluate} decides its own NOTIFY with this method, so the two cannot drift apart.
     *
     * <p>A daylight poll asks before evaluating, so it fetches the data a scoring needs only when a
     * scoring is coming.
     *
     * @param incoming the level about to be evaluated
     * @return {@code true} for a new alert from IDLE or an escalation above the current level
     */
    public boolean wouldNotify(AlertLevel incoming) {
        return incoming.isAlertWorthy()
                && (state == State.IDLE || incoming.severity() > currentLevel.severity());
    }

    /**
     * Whether {@link #evaluate} would answer CLEAR for {@code incoming} now, without changing any
     * state: an alert is active and {@code incoming} is below MODERATE. {@code evaluate} decides its
     * own CLEAR with this method.
     *
     * <p>A night poll asks before evaluating, so it can hold an alert while the reading that will
     * decide it is still due.
     *
     * @param incoming the level about to be evaluated
     * @return {@code true} when evaluating {@code incoming} would end the active alert
     */
    public boolean wouldClear(AlertLevel incoming) {
        return !incoming.isAlertWorthy() && state == State.ACTIVE;
    }

    /**
     * Stores the freshly computed scores after a NOTIFY event.
     *
     * @param scores scored aurora locations; must not be null
     */
    public void updateScores(List<AuroraForecastScore> scores) {
        this.cachedScores = List.copyOf(scores);
    }

    /**
     * Records which trigger path fired the last NOTIFY and the Kp value that drove it.
     *
     * <p>For {@link TriggerType#FORECAST_LOOKAHEAD} this is the highest Kp forecast for the rest of
     * tonight. For {@link TriggerType#REALTIME} it is the Kp for now: NOAA's value for the most
     * recently completed 3-hour block ({@code AuroraOrchestrator.currentKp}).
     *
     * @param triggerType the path that produced the NOTIFY
     * @param kp          the Kp value that triggered the alert
     */
    public void updateTrigger(TriggerType triggerType, double kp) {
        this.lastTriggerType = triggerType;
        this.lastTriggerKp = kp;
    }

    /**
     * Returns the trigger type of the last NOTIFY. {@code null} until a NOTIFY or a simulation sets
     * it; a CLEAR does not reset it, only {@link #reset()} does.
     *
     * @return last {@link TriggerType}, or {@code null}
     */
    public TriggerType getLastTriggerType() {
        return lastTriggerType;
    }

    /**
     * Returns the Kp value that drove the last NOTIFY. {@code null} until a NOTIFY or a simulation
     * sets it; a CLEAR does not reset it, only {@link #reset()} does.
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
        this.darkSkyLocationCount = darkSkyCount;
        this.clearLocationCount = clearCount;
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
     * <p>Sets the machine to ACTIVE with the derived alert level and stores the fake NOAA data.
     * No Claude call is made — the admin must trigger a manual Forecast Run to generate scores.
     * The simulation flag is visible to the status endpoint and forecast preview so the UI
     * can display a "(SIMULATED)" indicator.
     *
     * <p>Intended for admin testing only. The real NOAA polling job continues independently
     * and will override this state once a real geomagnetic event is detected.
     *
     * @param level simulated alert level
     * @param data  fake NOAA space weather values to surface via the status endpoint
     */
    public void activateSimulation(AlertLevel level, SimulatedNoaaData data) {
        state = State.ACTIVE;
        currentLevel = level;
        activeSince = clock.instant();
        cachedScores = List.of();
        lastTriggerType = TriggerType.FORECAST_LOOKAHEAD;
        lastTriggerKp = data.kp();
        simulated = true;
        simulatedData = data;
    }

    /**
     * Returns {@code true} when the state machine is in simulation mode.
     *
     * @return {@code true} if a simulated aurora event is active
     */
    public boolean isSimulated() {
        return simulated;
    }

    /**
     * Returns the simulated NOAA data injected via {@link #activateSimulation}, or {@code null}
     * when not in simulation mode.
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
        state = State.IDLE;
        currentLevel = null;
        activeSince = null;
        cachedScores = List.of();
        lastTriggerType = null;
        lastTriggerKp = null;
        darkSkyLocationCount = 0;
        clearLocationCount = null;
        simulated = false;
        simulatedData = null;
    }
}
