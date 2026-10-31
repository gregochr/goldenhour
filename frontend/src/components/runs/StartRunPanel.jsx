import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { refreshTideData, backfillTideData, fetchLocations } from '../../api/forecastApi';
import { enrichBortle } from '../../api/auroraApi';
import { getRegions, submitScheduledBatch, submitJfdiBatch } from '../../api/batchApi';
import { runBriefing } from '../../api/briefingApi.js';
import { useAuth } from '../../context/AuthContext';
import { useAuroraStatus } from '../../hooks/useAuroraStatus.js';
import useConfirmDialog from '../../hooks/useConfirmDialog.js';
import ConfirmDialog from '../shared/ConfirmDialog.jsx';
import PanelShell from '../data/PanelShell.jsx';
import AuroraForecastModal from '../AuroraForecastModal';
import AuroraSimulateModal from '../AuroraSimulateModal';
import { isSkyPromptCandidate } from '../../utils/locationTypes.js';

/**
 * Returns true if the location may go to the SKY prompt.
 *
 * <p>This panel previews which locations a colour RUN will touch, so the predicate it mirrors is
 * `LocationEntity.hasColourTypes()` — the gate those engines filter on — and NOT the briefing's
 * own candidacy test. The two deliberately disagree about WOODLAND: a wood is a briefing
 * candidate but has no sky to forecast.
 */
function hasColourTypes(loc) {
  return isSkyPromptCandidate(loc.locationType);
}

/**
 * Returns true if the location is pure-wildlife only (WILDLIFE but no colour types).
 *
 * <p>⚠️ NOT `locationTypes.isWildlifeOnly`, and the two names mean DIFFERENT sets. This is the
 * backend pure-wildlife rule (a WILDLIFE+WOODLAND or WILDLIFE+BLUEBELL
 * place counts, because no colour type is present). `isWildlifeOnly` is the narrower `LocationEntity.isWildlifeOnly()`
 * (WILDLIFE is the ONLY type), which decides who gets a comfort forecast.
 */
function isPureWildlife(loc) {
  const types = loc.locationType || [];
  return types.includes('WILDLIFE') && !hasColourTypes(loc);
}

/** Returns true if the location is coastal (has at least one tide type). */
function isCoastal(loc) {
  const tides = loc.tideType || [];
  return tides.length > 0;
}

/**
 * Filters enabled locations by run type and groups them by region.
 *
 * @param {Array} locations - All locations from the API.
 * @param {string} runType - WEATHER or TIDE (anything else lists the colour-type locations).
 * @returns {{ groups: Array<{region: string, locations: string[]}>, total: number }}
 */
function getLocationSummary(locations, runType) {
  const enabled = locations.filter((loc) => loc.enabled !== false);

  let filtered;
  if (runType === 'TIDE') {
    filtered = enabled.filter(isCoastal);
  } else if (runType === 'WEATHER') {
    filtered = enabled.filter(isPureWildlife);
  } else {
    filtered = enabled.filter(hasColourTypes);
  }

  // Group by region name (or "No region")
  const byRegion = {};
  for (const loc of filtered) {
    const regionName = loc.region?.name || 'No region';
    if (!byRegion[regionName]) byRegion[regionName] = [];
    byRegion[regionName].push(loc.name);
  }

  const groups = Object.entries(byRegion)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([region, names]) => ({ region, locations: names.sort() }));

  return { groups, total: filtered.length };
}

/**
 * Renders the location summary grouped by region inside a confirmation dialog.
 */
function LocationSummary({ groups, total }) {
  if (total === 0) {
    return <p className="text-xs text-plex-text-muted italic">No matching locations found.</p>;
  }

  return (
    <div className="max-h-48 overflow-y-auto text-xs space-y-2">
      {groups.map((g) => (
        <div key={g.region}>
          <p className="text-plex-text font-medium">{g.region} ({g.locations.length})</p>
          <p className="text-plex-text-muted ml-2">{g.locations.join(', ')}</p>
        </div>
      ))}
      <p className="text-plex-text-secondary font-medium pt-1 border-t border-plex-border">
        {total} location{total !== 1 ? 's' : ''} total
      </p>
    </div>
  );
}

LocationSummary.propTypes = {
  groups: PropTypes.arrayOf(PropTypes.shape({
    region: PropTypes.string.isRequired,
    locations: PropTypes.arrayOf(PropTypes.string).isRequired,
  })).isRequired,
  total: PropTypes.number.isRequired,
};

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

/**
 * The Start a run panel ({@code #manage/runs/start}), R2a's legacy body (runs-redesign-plan.md R-D4): today's admin
 * trigger card lifted as it stands, every button, the batch region dialog, the tide confirm dialogs, both aurora
 * modals, {@code anyRunning} and the busy and error lines. R3 rebuilds the body into radio options; until then
 * nothing about what each button does has changed.
 *
 * <p>A start that returns a {@code jobRunId} hands it to {@code onActiveRunChange}, which sets the run
 * {@code RunProgressPanel} follows (above the list, so it outlives this panel). A start that adds a run to the list
 * calls {@code onStarted}, so the list's head is re-fetched.
 *
 * @param {object} props
 * @param {(jobRunId: number) => void} props.onActiveRunChange sets the run whose progress is followed
 * @param {() => void} props.onStarted called after a start that adds or changes a run, to reload the list head
 * @param {() => void} props.onClose closes the panel
 * @param {object} [props.runState] the lifted flags and status line ({@link useStartRunState}), when the caller holds them
 */
export default function StartRunPanel({
  onActiveRunChange, onStarted, onClose, runState = undefined,
}) {
  const { isAdmin } = useAuth();
  const ownState = useStartRunState();
  const {
    runningTide, setRunningTide, runningBackfill, setRunningBackfill, runningLightPollution, setRunningLightPollution,
    runningBriefing, setRunningBriefing, runningScheduledBatch, setRunningScheduledBatch, runningJfdiBatch,
    setRunningJfdiBatch, runStatus, setRunStatus,
  } = runState ?? ownState;
  const [showAuroraModal, setShowAuroraModal] = useState(false);
  const [showSimulateModal, setShowSimulateModal] = useState(false);
  const { status: auroraStatus } = useAuroraStatus();
  const { config: confirmDialog, openDialog, closeDialog } = useConfirmDialog();
  const [allLocations, setAllLocations] = useState([]);
  const [batchRegions, setBatchRegions] = useState([]);
  const [batchDialog, setBatchDialog] = useState(null);

  // Load locations and batch regions once
  useEffect(() => {
    fetchLocations().then(setAllLocations).catch(() => {});
    getRegions().then(setBatchRegions).catch(() => {});
  }, []);

  const anyRunning = runningTide || runningBackfill || runningLightPollution || runningBriefing || runningScheduledBatch || runningJfdiBatch;

  const handleEnrichLightPollution = async () => {
    setRunningLightPollution(true);
    setRunStatus(null);
    try {
      const result = await enrichBortle();
      const msg = result.jobRunId
        ? `Light pollution enrichment started (job #${result.jobRunId}).`
        : 'Light pollution enrichment started.';
      setRunStatus({ type: 'success', message: msg });
      if (result.jobRunId) {
        onActiveRunChange(result.jobRunId);
      }
      onStarted();
    } catch (err) {
      const msg = err?.response?.status === 400
        ? 'Light pollution API key not configured.'
        : 'Light pollution refresh failed.';
      setRunStatus({ type: 'error', message: msg });
    } finally {
      setRunningLightPollution(false);
    }
  };

  const handleRunBriefing = async () => {
    setRunningBriefing(true);
    setRunStatus(null);
    try {
      const result = await runBriefing();
      setRunStatus({ type: 'success', message: result.status || 'Briefing refreshed.' });
      onStarted();
    } catch (err) {
      // 409: the backend refuses a second build while one is running (typically the pipeline's
      // own) — that is not a failure, and there is nothing in the logs to check.
      const msg = err?.response?.status === 409
        ? 'A briefing refresh is already in progress. Wait for it to complete.'
        : 'Briefing refresh failed. Check the logs.';
      setRunStatus({ type: 'error', message: msg });
    } finally {
      setRunningBriefing(false);
    }
  };

  const handleRefreshTide = () => {
    openDialog({
      title: 'Refresh Tide Data',
      message: 'Refresh tide data for all coastal locations? This will call the WorldTides API.',
      confirmLabel: 'Refresh',
      destructive: false,
      locationSummary: getLocationSummary(allLocations, 'TIDE'),
      onConfirm: async () => {
        closeDialog();
        setRunningTide(true);
        setRunStatus(null);
        try {
          const result = await refreshTideData();
          setRunStatus({ type: 'success', message: result.status || 'Run started.' });
          if (result.jobRunId) {
            onActiveRunChange(result.jobRunId);
          }
          onStarted();
        } catch (err) {
          // 409: the refresh is refused because one is already going (from any route: schedule,
          // Run Now, or an earlier press here).
          const msg = err?.response?.status === 409
            ? 'Refresh Tide Data: a run is already in progress. Wait for it to complete.'
            : 'Refresh Tide Data failed. Check the logs.';
          setRunStatus({ type: 'error', message: msg });
        } finally {
          setRunningTide(false);
        }
      },
    });
  };

  const handleBackfillTide = () => {
    const seascapeCoastal = allLocations
      .filter((loc) => loc.enabled !== false)
      .filter((loc) => (loc.locationType || []).includes('SEASCAPE'))
      .filter(isCoastal);
    const summary = getLocationSummary(
      allLocations.filter((loc) => (loc.locationType || []).includes('SEASCAPE')),
      'TIDE',
    );
    openDialog({
      title: 'Backfill Tide History',
      message: `Backfill 12 months of historical tide data for ${seascapeCoastal.length} SEASCAPE location(s)? This fetches in 7-day chunks, skipping dates where data already exists. Multiple WorldTides API calls will be made per location.`,
      confirmLabel: 'Backfill',
      destructive: false,
      locationSummary: summary,
      onConfirm: async () => {
        closeDialog();
        setRunningBackfill(true);
        setRunStatus(null);
        try {
          const result = await backfillTideData();
          setRunStatus({ type: 'success', message: result.status || 'Backfill started.' });
          onStarted();
        } catch {
          setRunStatus({ type: 'error', message: 'Tide backfill failed. Check the logs.' });
        } finally {
          setRunningBackfill(false);
        }
      },
    });
  };

  /**
   * Opens the batch dialog with every region selected.
   *
   * <p>`selectedIds: null` MEANS "all regions", and is not the same as an empty set. Snapshotting
   * `batchRegions` here instead was a race: the button renders immediately while `getRegions()` is
   * still in flight, so an admin who clicked quickly got a dialog built from an empty roster. The
   * regions then arrived, rendered three unchecked boxes reading "0 of 3 regions selected", and
   * unchecking one actually CHECKED it — the selection was empty, so the toggle added rather than
   * removed. Submitting from that state sent an empty region list. Deferring the resolution to
   * render time makes the default follow the data whenever it lands.
   */
  const openBatchDialog = (mode) => {
    setBatchDialog({ mode, selectedIds: null });
  };

  /** The dialog's live selection: an explicit set once touched, otherwise every known region. */
  const resolveBatchSelection = (selectedIds) =>
    selectedIds ?? new Set(batchRegions.map((r) => r.id));

  const handleBatchToggleRegion = (regionId) => {
    setBatchDialog((prev) => {
      if (!prev) return prev;
      const next = new Set(resolveBatchSelection(prev.selectedIds));
      if (regionId === '__all__') {
        if (next.size === batchRegions.length) {
          next.clear();
        } else {
          for (const r of batchRegions) next.add(r.id);
        }
      } else if (next.has(regionId)) {
        next.delete(regionId);
      } else {
        next.add(regionId);
      }
      return { ...prev, selectedIds: next };
    });
  };

  const handleBatchSubmit = async () => {
    if (!batchDialog) return;
    const { mode, selectedIds } = batchDialog;
    // An explicitly emptied selection is NOT "all", and must never be submitted. The API reads an
    // empty list exactly as it reads null — ForecastTaskCollector:729 says so outright, "null or
    // empty means all regions" — so submitting from "0 of 3 regions selected" would run the whole
    // roster the screen has just told the operator is excluded, at full batch cost. Pre-existing;
    // found by review while fixing the selection race above.
    if (selectedIds !== null && selectedIds.size === 0) {
      return;
    }
    // null means "all regions", which the API expects as a null region list — the same thing an
    // explicitly all-selected set means. An empty set is refused above and never reaches the wire.
    const regionIds = selectedIds === null || selectedIds.size === batchRegions.length
      ? null
      : [...selectedIds];
    const setRunning = mode === 'scheduled' ? setRunningScheduledBatch : setRunningJfdiBatch;
    const submitFn = mode === 'scheduled' ? submitScheduledBatch : submitJfdiBatch;
    const label = mode === 'scheduled' ? 'Scheduled batch' : 'JFDI batch';

    setBatchDialog(null);
    setRunning(true);
    setRunStatus(null);
    try {
      const result = await submitFn(regionIds);
      setRunStatus({
        type: 'success',
        message: `${label} submitted: ${result.requestCount} request(s), batch ${result.batchId}.`,
      });
      onStarted();
    } catch (err) {
      const msg = err?.response?.status === 409
        ? 'A batch is already in progress. Wait for it to complete.'
        : `${label} submission failed. Check the logs.`;
      setRunStatus({ type: 'error', message: msg });
    } finally {
      setRunning(false);
    }
  };

  // Resolved once per render: the dialog's null sentinel must never reach the JSX, and four
  // separate call sites are four chances to miss one — which is exactly what happened.
  const batchSelection = batchDialog ? resolveBatchSelection(batchDialog.selectedIds) : null;

  return (
    <PanelShell
      title="Start a run"
      subtitle="Runs start straight away and appear at the top of the list."
      onClose={onClose}
      testIdPrefix="start-run-panel"
    >
      {isAdmin && (
        <div className="space-y-4 px-[18px] py-4 max-[639px]:px-4" data-testid="start-run-card">
          <div>
            <h3
              className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2"
              data-testid="forecast-runs-heading"
            >
              Forecast Runs
            </h3>
            <div className="flex flex-wrap gap-2">
              <button
                className="text-sm px-3 py-1.5 font-medium rounded border border-indigo-500/50 bg-indigo-900/30 text-indigo-300 hover:bg-indigo-800/40 transition-colors disabled:opacity-50 max-[639px]:min-h-11"
                onClick={() => setShowAuroraModal(true)}
                disabled={anyRunning}
                title="Choose which nights to generate Claude aurora forecasts for"
                data-testid="run-aurora-forecast-btn"
              >
                🌌 Aurora Forecast
              </button>
              <button
                className={`text-sm px-3 py-1.5 font-medium rounded border transition-colors disabled:opacity-50 max-[639px]:min-h-11 ${
                  auroraStatus?.simulated
                    ? 'border-amber-500/50 bg-amber-900/30 text-amber-300 hover:bg-amber-800/40'
                    : 'border-plex-border bg-plex-bg text-plex-text-muted hover:bg-plex-border/30'
                }`}
                onClick={() => setShowSimulateModal(true)}
                title={auroraStatus?.simulated ? 'Simulation active — click to manage' : 'Inject fake NOAA data for UI testing'}
                data-testid="aurora-simulate-btn"
              >
                {auroraStatus?.simulated ? '🧪 Simulated' : '🧪 Simulate'}
              </button>
              <button
                className="btn-primary text-sm max-[639px]:min-h-11"
                onClick={handleRunBriefing}
                disabled={anyRunning}
                title="Trigger an immediate briefing refresh"
                data-testid="run-briefing-btn"
              >
                {runningBriefing ? '⟳ Running…' : '⟳ Briefing'}
              </button>
            </div>
          </div>
          <div>
            <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">Data Refresh</p>
            <div className="flex flex-wrap gap-2">
              <button
                className="btn-primary text-sm max-[639px]:min-h-11"
                onClick={handleRefreshTide}
                disabled={anyRunning}
                data-testid="refresh-tide-btn"
              >
                {runningTide ? '⟳ Running…' : '⟳ Refresh Tide Data'}
              </button>
              <button
                className="btn-primary text-sm max-[639px]:min-h-11"
                onClick={handleBackfillTide}
                disabled={anyRunning}
                data-testid="backfill-tide-btn"
              >
                {runningBackfill ? '⟳ Running…' : '⟳ Backfill Tide History (12 mo)'}
              </button>
              <button
                className="btn-primary text-sm max-[639px]:min-h-11"
                onClick={handleEnrichLightPollution}
                disabled={anyRunning}
                title="Enrich all unenriched locations with light pollution rating"
                data-testid="refresh-light-pollution-btn"
              >
                {runningLightPollution ? '⟳ Running…' : '🌌 Refresh Light Pollution'}
              </button>
            </div>
          </div>
          <div>
            <p className="text-xs font-semibold text-plex-text-muted uppercase tracking-wide mb-2">Batch Evaluation</p>
            <div className="flex flex-wrap gap-2">
              <button
                className="btn-primary text-sm max-[639px]:min-h-11"
                onClick={() => openBatchDialog('scheduled')}
                disabled={anyRunning}
                data-testid="run-scheduled-batch-btn"
              >
                {runningScheduledBatch ? '⟳ Running…' : '⟳ Run Scheduled Batch'}
              </button>
              <button
                className="text-sm px-3 py-1.5 font-medium rounded border border-amber-500/50 bg-amber-900/30 text-amber-300 hover:bg-amber-800/40 transition-colors disabled:opacity-50 max-[639px]:min-h-11"
                onClick={() => openBatchDialog('jfdi')}
                disabled={anyRunning}
                data-testid="run-jfdi-batch-btn"
              >
                {runningJfdiBatch ? '⟳ Running…' : '⟳ Run JFDI Batch'}
              </button>
            </div>
            <p className="text-xs text-plex-text-muted mt-1.5">
              Scheduled = same triage gates as the overnight job. JFDI = all locations, all dates (T to T+3), both events, no gates.
            </p>
          </div>
          {runStatus && (
            <p
              className={`text-xs ${runStatus.type === 'success' ? 'text-green-400' : 'text-red-400'}`}
              data-testid="start-run-status"
            >
              {runStatus.message}
            </p>
          )}
        </div>
      )}

      {/* Styled confirmation dialog */}
      {confirmDialog && (
        <ConfirmDialog
          title={confirmDialog.title}
          message={confirmDialog.message}
          confirmLabel={confirmDialog.confirmLabel}
          onConfirm={confirmDialog.onConfirm}
          onCancel={closeDialog}
          destructive={confirmDialog.destructive}
        >
          {confirmDialog.locationSummary && (
            <LocationSummary
              groups={confirmDialog.locationSummary.groups}
              total={confirmDialog.locationSummary.total}
            />
          )}
        </ConfirmDialog>
      )}

      {/* Batch submission dialog with region selector */}
      {batchDialog && (
        <ConfirmDialog
          title={batchDialog.mode === 'scheduled' ? 'Run Scheduled Batch' : 'Run JFDI Batch'}
          message={
            batchDialog.mode === 'scheduled'
              ? 'Submit a batch with the same triage and stability gates as the overnight scheduled job.'
              : 'Submit a JFDI batch — all dates T+0 to T+3, both sunrise and sunset, no triage gates.'
          }
          confirmLabel={`Submit (${batchSelection.size} region${batchSelection.size !== 1 ? 's' : ''})`}
          onConfirm={handleBatchSubmit}
          onCancel={() => setBatchDialog(null)}
        >
          <div className="max-h-48 overflow-y-auto text-xs space-y-2">
            <label className="flex items-center gap-2 text-plex-text font-medium cursor-pointer" data-testid="batch-region-toggle-all">
              <input
                type="checkbox"
                checked={batchSelection.size === batchRegions.length}
                onChange={() => handleBatchToggleRegion('__all__')}
                className="accent-blue-400"
              />
              All regions ({batchRegions.reduce((sum, r) => sum + r.locationCount, 0)} locations)
            </label>
            {batchRegions.map((r) => (
              <label key={r.id} className="flex items-center gap-2 text-plex-text cursor-pointer" data-testid={`batch-region-toggle-${r.id}`}>
                <input
                  type="checkbox"
                  checked={batchSelection.has(r.id)}
                  onChange={() => handleBatchToggleRegion(r.id)}
                  className="accent-blue-400"
                />
                {r.name} ({r.locationCount})
              </label>
            ))}
            <p className="text-plex-text-secondary font-medium pt-1 border-t border-plex-border">
              {batchSelection.size} of {batchRegions.length} region{batchRegions.length !== 1 ? 's' : ''} selected
            </p>
          </div>
        </ConfirmDialog>
      )}

      {/* Aurora forecast night selector modal */}
      {showAuroraModal && (
        <AuroraForecastModal
          onClose={() => setShowAuroraModal(false)}
          onComplete={(result) => {
            const nightCount = result.nights?.length ?? 0;
            const scored = result.nights?.reduce((sum, n) => sum + (n.locationsScored ?? 0), 0) ?? 0;
            setRunStatus({
              type: 'success',
              message: `Aurora forecast complete: ${nightCount} night${nightCount !== 1 ? 's' : ''}, ${scored} location${scored !== 1 ? 's' : ''} scored. Cost: ${result.estimatedCost ?? '—'}.`,
            });
            onStarted();
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
  onActiveRunChange: PropTypes.func.isRequired,
  onStarted: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  runState: PropTypes.object,
};
