import { useEffect, useId, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { refreshTideData, backfillTideData, fetchLocations } from '../../api/forecastApi';
import { enrichBortle } from '../../api/auroraApi';
import { getRegions, submitScheduledBatch, submitJfdiBatch } from '../../api/batchApi';
import { runBriefing } from '../../api/briefingApi.js';
import { useAuth } from '../../context/AuthContext';
import { useAuroraStatus } from '../../hooks/useAuroraStatus.js';
import PanelShell from '../data/PanelShell.jsx';
import AuroraForecastModal from '../AuroraForecastModal';
import AuroraSimulateModal from '../AuroraSimulateModal';
import { LocationSummary, RegionChecklist, pluralLocations } from './StartRunScope.jsx';
import {
  BUSY_MESSAGE, START_GROUPS, getLocationSummary, getSeascapeTideSummary, isBatchOption, tideDataDescription,
} from '../../utils/startRunOptions.js';
import { sectionLabelClass, textButtonClass } from '../users/userFormClasses.js';

/**
 * The Start a run panel's in-flight flags and its status line. {@code RunsView} holds them (not the panel) so that
 * closing and reopening the panel mid-request keeps the "something is running" guard and the message; the panel
 * makes its own when it is rendered on its own.
 *
 * @returns {object} the six running flags and their setters, and {@code runStatus} and {@code setRunStatus}
 */
export function useStartRunState() {
  const [runningTide, setRunningTide] = useState(false);
  const [runningBackfill, setRunningBackfill] = useState(false);
  const [runningLightPollution, setRunningLightPollution] = useState(false);
  const [runningBriefing, setRunningBriefing] = useState(false);
  const [runningScheduledBatch, setRunningScheduledBatch] = useState(false);
  const [runningJfdiBatch, setRunningJfdiBatch] = useState(false);
  const [runStatus, setRunStatus] = useState(null);
  return {
    runningTide, setRunningTide, runningBackfill, setRunningBackfill, runningLightPollution, setRunningLightPollution,
    runningBriefing, setRunningBriefing, runningScheduledBatch, setRunningScheduledBatch, runningJfdiBatch,
    setRunningJfdiBatch, runStatus, setRunStatus,
  };
}

/** The next option's index in a radio group, for an arrow key (wrapping), or null for any other key. */
function arrowTarget(key, index, count) {
  if (key === 'ArrowDown' || key === 'ArrowRight') return (index + 1) % count;
  if (key === 'ArrowUp' || key === 'ArrowLeft') return (index - 1 + count) % count;
  return null;
}

/**
 * One option's radio button: its name and one line of description, with the radio dot at the right. The scope the
 * option expands to is drawn by the caller, under it.
 */
function OptionRadio({
  option, description, checked, tabbable, disabled, onSelect, onKeyDown, buttonRef,
}) {
  return (
    <button
      ref={buttonRef}
      type="button"
      role="radio"
      aria-checked={checked}
      tabIndex={tabbable ? 0 : -1}
      disabled={disabled}
      onClick={onSelect}
      onKeyDown={onKeyDown}
      className="flex w-full cursor-pointer items-start gap-3 rounded-[inherit] px-3.5 py-3 text-left focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-plex-gold disabled:cursor-not-allowed disabled:opacity-50 max-[639px]:min-h-11"
      data-testid={`start-option-${option.key}`}
    >
      <span className="min-w-0 flex-1">
        <span className="block text-[13px] font-medium text-plex-text">{option.name}</span>
        <span className="mt-0.5 block text-[12.5px] text-plex-text-secondary">{description}</span>
      </span>
      <span
        aria-hidden="true"
        className={`mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full border ${
          checked ? 'border-plex-gold' : 'border-plex-border-light'
        }`}
      >
        {checked && <span className="size-2 rounded-full bg-plex-gold" />}
      </span>
    </button>
  );
}

OptionRadio.propTypes = {
  option: PropTypes.shape({ key: PropTypes.string.isRequired, name: PropTypes.string.isRequired }).isRequired,
  description: PropTypes.string.isRequired,
  checked: PropTypes.bool.isRequired,
  tabbable: PropTypes.bool.isRequired,
  disabled: PropTypes.bool.isRequired,
  onSelect: PropTypes.func.isRequired,
  onKeyDown: PropTypes.func.isRequired,
  buttonRef: PropTypes.func.isRequired,
};

/**
 * The Start a run panel ({@code #manage/runs/start}, runs-redesign-plan.md §5.3, R3): a choice of runs in two radio
 * groups (Forecast, Data refresh), the selected one expanding its scope inline, and the aurora simulation row under
 * Testing. The footer carries the scope, Cancel and {@code Start {option}}.
 *
 * <p>A start that returns a {@code jobRunId} hands it to {@code onRunStarted} ({@code RunsView} sets the run
 * {@code RunProgressPanel} follows, reloads the list's head and opens that run's panel). A start with no id, and the
 * aurora forecast's modal on completing, call {@code onFinished} with the line to toast ({@code RunsView} reloads the
 * head and closes this panel). A 409 shows the busy sentence here. The in-flight flags live in {@code RunsView}
 * ({@link useStartRunState}), so closing and reopening the panel mid-request keeps the guard.
 *
 * @param {object} props
 * @param {(jobRunId: number) => void} props.onRunStarted called after a start that returned a job run id
 * @param {(message: string) => void} props.onFinished called after a start with no id, with the line to toast
 * @param {() => void} props.onClose closes the panel
 * @param {object} [props.runState] the lifted flags and status line ({@link useStartRunState}), when the caller holds them
 */
export default function StartRunPanel({
  onRunStarted, onFinished, onClose, runState = undefined,
}) {
  const { isAdmin } = useAuth();
  const ownState = useStartRunState();
  const {
    runningTide, setRunningTide, runningBackfill, setRunningBackfill, runningLightPollution, setRunningLightPollution,
    runningBriefing, setRunningBriefing, runningScheduledBatch, setRunningScheduledBatch, runningJfdiBatch,
    setRunningJfdiBatch, runStatus, setRunStatus,
  } = runState ?? ownState;
  const [selected, setSelected] = useState('scheduled-batch');
  const [showAuroraModal, setShowAuroraModal] = useState(false);
  const [showSimulateModal, setShowSimulateModal] = useState(false);
  const { status: auroraStatus } = useAuroraStatus();
  const [allLocations, setAllLocations] = useState(null);
  const [batchRegions, setBatchRegions] = useState(null);
  // `null` MEANS "all regions" and is not the same as an empty set: the default follows the regions whenever they
  // land. (Snapshotting the roster at open time was a race that sent an empty region list.)
  const [selectedIds, setSelectedIds] = useState(null);
  const optionRefs = useRef({});
  const headingBase = useId();

  // Load locations and batch regions once
  useEffect(() => {
    fetchLocations().then(setAllLocations).catch(() => {});
    getRegions().then(setBatchRegions).catch(() => {});
  }, []);

  const anyRunning = runningTide || runningBackfill || runningLightPollution || runningBriefing
    || runningScheduledBatch || runningJfdiBatch;

  const regions = useMemo(() => batchRegions ?? [], [batchRegions]);
  const batchSelection = useMemo(
    () => selectedIds ?? new Set(regions.map((r) => r.id)),
    [selectedIds, regions],
  );
  const tideSummary = useMemo(() => (allLocations ? getLocationSummary(allLocations, 'TIDE') : null), [allLocations]);
  const historySummary = useMemo(() => (allLocations ? getSeascapeTideSummary(allLocations) : null), [allLocations]);

  const handleToggleRegion = (regionId) => {
    setSelectedIds((prev) => {
      const next = new Set(prev ?? regions.map((r) => r.id));
      if (regionId === '__all__') {
        if (next.size === regions.length) {
          next.clear();
        } else {
          for (const r of regions) next.add(r.id);
        }
      } else if (next.has(regionId)) {
        next.delete(regionId);
      } else {
        next.add(regionId);
      }
      return next;
    });
  };

  const selectOption = (key) => {
    setSelected(key);
    setRunStatus(null);
  };

  // An explicitly emptied batch selection is NOT "all", and is never submitted: the API reads an empty list exactly as
  // it reads null ("null or empty means all regions"), so sending it would run the whole roster the screen has just
  // said is excluded. The Start button is disabled, and the handler refuses too.
  const batchEmpty = isBatchOption(selected) && regions.length > 0 && batchSelection.size === 0;

  /**
   * Runs one start: raises the option's in-flight flag, clears the status line, and routes the outcome. A response with
   * a job run id opens that run; one without finishes with a toast; a 409 shows the busy sentence; any other failure
   * shows {@code failure}.
   */
  const execute = async (setRunning, call, failure) => {
    setRunning(true);
    setRunStatus(null);
    try {
      const result = await call();
      if (result?.jobRunId) {
        onRunStarted(result.jobRunId);
      } else {
        onFinished(result?.status || 'Started.');
      }
    } catch (err) {
      const message = err?.response?.status === 409 ? BUSY_MESSAGE : failure(err);
      setRunStatus({ type: 'error', message });
    } finally {
      setRunning(false);
    }
  };

  const submitBatch = (mode) => {
    if (batchEmpty) return;
    // null means "all regions", which the API expects as a null region list — the same thing an explicitly
    // all-selected set means. An empty set is refused above and never reaches the wire.
    const regionIds = selectedIds === null || selectedIds.size === regions.length ? null : [...selectedIds];
    const scheduled = mode === 'scheduled';
    const label = scheduled ? 'Scheduled batch' : 'JFDI batch';
    execute(
      scheduled ? setRunningScheduledBatch : setRunningJfdiBatch,
      () => (scheduled ? submitScheduledBatch(regionIds) : submitJfdiBatch(regionIds)),
      () => `${label} submission failed. Check the logs.`,
    );
  };

  const handleStart = () => {
    switch (selected) {
      case 'scheduled-batch':
        submitBatch('scheduled');
        break;
      case 'jfdi-batch':
        submitBatch('jfdi');
        break;
      case 'briefing':
        execute(setRunningBriefing, runBriefing, () => 'Briefing refresh failed. Check the logs.');
        break;
      case 'aurora-forecast':
        setShowAuroraModal(true);
        break;
      case 'tide-data':
        execute(setRunningTide, refreshTideData, () => 'Tide data refresh failed. Check the logs.');
        break;
      case 'tide-history':
        execute(setRunningBackfill, backfillTideData, () => 'Tide backfill failed. Check the logs.');
        break;
      default:
        execute(
          setRunningLightPollution,
          enrichBortle,
          (err) => (err?.response?.status === 400
            ? 'Light pollution API key not configured.'
            : 'Light pollution refresh failed.'),
        );
    }
  };

  const startLabel = START_GROUPS.flatMap((g) => g.options).find((o) => o.key === selected).startLabel;

  // The footer's scope: regions and locations for a batch, the location count for a tide option, nothing otherwise.
  let scope = null;
  if (isBatchOption(selected) && batchRegions) {
    const locationCount = regions.filter((r) => batchSelection.has(r.id)).reduce((sum, r) => sum + r.locationCount, 0);
    scope = `${batchSelection.size} of ${regions.length} region${regions.length === 1 ? '' : 's'} · ${pluralLocations(locationCount)}`;
  } else if (selected === 'tide-data' && tideSummary) {
    scope = pluralLocations(tideSummary.total);
  } else if (selected === 'tide-history' && historySummary) {
    scope = pluralLocations(historySummary.total);
  }

  /** The inline scope the selected option expands, or null for an option with none. */
  const scopeFor = (key) => {
    if (isBatchOption(key)) {
      if (!batchRegions) return <p className="text-xs text-plex-text-muted">Loading regions…</p>;
      if (regions.length === 0) return <p className="text-xs text-plex-text-muted">Every region will run.</p>;
      return (
        <RegionChecklist
          regions={regions}
          selection={batchSelection}
          onToggle={handleToggleRegion}
          disabled={anyRunning}
        />
      );
    }
    if (key === 'tide-data' || key === 'tide-history') {
      const summary = key === 'tide-data' ? tideSummary : historySummary;
      if (!summary) return <p className="text-xs text-plex-text-muted">Loading locations…</p>;
      return <LocationSummary groups={summary.groups} total={summary.total} />;
    }
    return null;
  };

  const onOptionKeyDown = (group, index) => (event) => {
    const target = arrowTarget(event.key, index, group.options.length);
    if (target == null) return;
    event.preventDefault();
    const next = group.options[target].key;
    selectOption(next);
    optionRefs.current[next]?.focus();
  };

  return (
    <PanelShell
      title="Start a run"
      subtitle="Runs start straight away and appear at the top of the list."
      onClose={onClose}
      testIdPrefix="start-run-panel"
      footer={isAdmin ? (
        <div
          className="flex flex-wrap items-center gap-x-3 gap-y-2 px-[18px] py-3 max-[639px]:px-4"
          data-testid="start-run-footer"
        >
          <p className="min-w-0 flex-1 font-mono text-[11.5px] text-plex-text-muted" data-testid="start-run-scope">
            {scope}
          </p>
          <button type="button" className={textButtonClass} onClick={onClose} data-testid="start-run-cancel">
            Cancel
          </button>
          <button
            type="button"
            className="btn-primary text-sm max-[639px]:min-h-11"
            onClick={handleStart}
            disabled={anyRunning || batchEmpty}
            data-testid="start-run-submit"
          >
            {startLabel}
          </button>
        </div>
      ) : null}
    >
      {isAdmin && (
        <div data-testid="start-run-card">
          {START_GROUPS.map((group) => {
            const headingId = `${headingBase}-${group.id}`;
            const groupHasSelection = group.options.some((o) => o.key === selected);
            return (
              <section
                key={group.id}
                className="border-b border-plex-border px-[18px] py-[14px] max-[639px]:px-4"
                data-testid={`start-group-${group.id}`}
              >
                <h3 id={headingId} className={`${sectionLabelClass} mb-2.5`}>{group.label}</h3>
                <div role="radiogroup" aria-labelledby={headingId} className="space-y-2">
                  {group.options.map((option, index) => {
                    const checked = option.key === selected;
                    const description = option.key === 'tide-data'
                      ? tideDataDescription(tideSummary ? tideSummary.total : null)
                      : option.description;
                    const optionScope = checked ? scopeFor(option.key) : null;
                    return (
                      <div
                        key={option.key}
                        className={`rounded-lg border ${
                          checked ? 'border-plex-gold/60 bg-plex-gold/5' : 'border-plex-border'
                        }`}
                      >
                        <OptionRadio
                          option={option}
                          description={description}
                          checked={checked}
                          tabbable={groupHasSelection ? checked : index === 0}
                          disabled={anyRunning}
                          onSelect={() => selectOption(option.key)}
                          onKeyDown={onOptionKeyDown(group, index)}
                          buttonRef={(node) => { optionRefs.current[option.key] = node; }}
                        />
                        {optionScope && (
                          <div
                            className="border-t border-plex-border px-3.5 py-2.5"
                            data-testid={`start-scope-${option.key}`}
                          >
                            {optionScope}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </section>
            );
          })}
          <section className="px-[18px] py-[14px] max-[639px]:px-4" data-testid="start-group-testing">
            <h3 className={`${sectionLabelClass} mb-2.5`}>Testing</h3>
            <div className="flex items-start gap-3">
              <div className="min-w-0 flex-1">
                <p className="text-[13px] font-medium text-plex-text">Aurora simulation</p>
                <p className="mt-0.5 text-[12.5px] text-plex-text-secondary">
                  Injects fake NOAA data so the aurora UI can be tested.
                </p>
                <p
                  className={`mt-0.5 text-[12.5px] ${auroraStatus?.simulated ? 'text-verdict-marginal' : 'text-plex-text-muted'}`}
                  data-testid="aurora-simulation-state"
                >
                  {auroraStatus?.simulated ? 'On' : 'Off'}
                </p>
              </div>
              <button
                type="button"
                className="btn-secondary shrink-0 text-sm max-[639px]:min-h-11"
                onClick={() => setShowSimulateModal(true)}
                title={auroraStatus?.simulated ? 'Simulation active — click to manage' : 'Inject fake NOAA data for UI testing'}
                data-testid="aurora-simulate-btn"
              >
                Simulate…
              </button>
            </div>
          </section>
          {runStatus && (
            <p
              role="status"
              className={`px-[18px] pb-4 text-xs max-[639px]:px-4 ${runStatus.type === 'success' ? 'text-plex-ok' : 'text-plex-error'}`}
              data-testid="start-run-status"
            >
              {runStatus.message}
            </p>
          )}
        </div>
      )}

      {/* Aurora forecast night selector modal */}
      {showAuroraModal && (
        <AuroraForecastModal
          onClose={() => setShowAuroraModal(false)}
          onComplete={(result) => {
            const nightCount = result.nights?.length ?? 0;
            const scored = result.nights?.reduce((sum, n) => sum + (n.locationsScored ?? 0), 0) ?? 0;
            onFinished(
              `Aurora forecast complete: ${nightCount} night${nightCount !== 1 ? 's' : ''}, ${scored} location${scored !== 1 ? 's' : ''} scored. Cost: ${result.estimatedCost ?? '—'}.`,
            );
          }}
        />
      )}

      {/* Aurora simulation modal */}
      {showSimulateModal && (
        <AuroraSimulateModal
          isActive={auroraStatus?.simulated === true}
          onClose={() => setShowSimulateModal(false)}
          onSuccess={(msg) => {
            setRunStatus({ type: 'success', message: msg });
          }}
        />
      )}
    </PanelShell>
  );
}

StartRunPanel.propTypes = {
  onRunStarted: PropTypes.func.isRequired,
  onFinished: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  runState: PropTypes.object,
};
