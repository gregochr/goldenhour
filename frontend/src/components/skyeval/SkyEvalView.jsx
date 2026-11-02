import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getAvailableModels } from '../../api/modelsApi.js';
import { getSkyEvalOverview, startSkyEvalCheck } from '../../api/skyRatingEvalApi.js';
import { useVisiblePoll } from '../../hooks/useVisiblePoll.js';
import { apiErrorMessage } from '../../utils/apiError.js';
import { modelLabel } from '../../utils/modelLabels.js';
import { appNow } from '../../utils/rewind.js';
import {
  DEFAULT_TAB, SKY_EVAL_TABS, TAB_STORAGE_KEY, isKnownTab,
} from '../../utils/skyEval.js';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import ModelsTab from './ModelsTab.jsx';
import FixturesTab from './FixturesTab.jsx';
import RunCheckPanel from './RunCheckPanel.jsx';
import RunsTab from './RunsTab.jsx';

/** How often the overview refetches while any session is running. */
const POLL_INTERVAL_MS = 4000;

const TAB_LABELS = { models: 'Models', fixtures: 'Fixtures', runs: 'Runs' };

/** The remembered tab, validated against the allowlist; storage that is missing or throws reads as the default. */
function readTab() {
  try {
    const stored = window.localStorage.getItem(TAB_STORAGE_KEY);
    return isKnownTab(stored) ? stored : DEFAULT_TAB;
  } catch {
    return DEFAULT_TAB;
  }
}

/** Remembers the tab; a storage failure is ignored (the choice lasts for this visit only). */
function writeTab(tab) {
  try {
    window.localStorage.setItem(TAB_STORAGE_KEY, tab);
  } catch {
    // storage unavailable
  }
}

/**
 * Sky Eval (Operations, Tuning): the test harness for sky-rating accuracy, built around which model to run in
 * production, whether a change made ratings worse, and a quick check. One read of {@code GET /overview} feeds
 * every tab; it refetches after a check is submitted and every few seconds while any session is running.
 */
export default function SkyEvalView() {
  const [overview, setOverview] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [tab, setTabState] = useState(readTab);
  const [panelOpen, setPanelOpen] = useState(false);
  const [panelKey, setPanelKey] = useState(0);
  const [prefill, setPrefill] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState(null);
  const [openSessionId, setOpenSessionId] = useState(null);
  const [availableModels, setAvailableModels] = useState(null);
  const [fixturesModel, setFixturesModel] = useState(null);

  const seqRef = useRef(0);
  const inFlightRef = useRef(false);
  const pendingExpandRef = useRef(null);
  const panelRef = useRef(null);
  const lastLoadStartRef = useRef(0);
  // True from a successful POST until a load has answered for it, so polling survives a failed follow-up load.
  const [expandPending, setExpandPending] = useState(false);

  // One request at a time from the poll (a tick is skipped while one is in flight); a forced load (after a
  // submit) always goes, and only the newest response is applied, so an older one arriving late is dropped.
  const load = useCallback(async (force = false) => {
    if (inFlightRef.current && !force) return;
    seqRef.current += 1;
    const seq = seqRef.current;
    inFlightRef.current = true;
    lastLoadStartRef.current = Date.now();
    try {
      const res = await getSkyEvalOverview();
      if (seq !== seqRef.current) return;
      const data = res.data;
      setOverview(data);
      setLoadError(null);
      if (pendingExpandRef.current != null) {
        const session = data.sessions.find((s) => s.runs.some((r) => r.id === pendingExpandRef.current));
        if (session) {
          setOpenSessionId(session.id);
          pendingExpandRef.current = null;
          setExpandPending(false);
        } else if (!data.sessions.some((s) => s.status === 'RUNNING')) {
          // Nothing is running and the run is not listed: there is nothing left to wait for.
          pendingExpandRef.current = null;
          setExpandPending(false);
        }
      }
    } catch (err) {
      if (seq === seqRef.current) setLoadError(apiErrorMessage(err, err?.message || 'Failed to load Sky Eval'));
    } finally {
      if (seq === seqRef.current) inFlightRef.current = false;
    }
  }, []);

  useEffect(() => {
    (async () => { await load(); })();
  }, [load]);

  // The selectable models come from the backend, so a new model appears without a frontend edit. A failure is
  // not shown: the panel falls back to the models the overview names.
  useEffect(() => {
    (async () => {
      try {
        const data = await getAvailableModels();
        setAvailableModels((data?.available ?? []).map((m) => m.name));
      } catch {
        setAvailableModels(null);
      }
    })();
  }, []);

  const anyRunning = overview?.sessions.some((s) => s.status === 'RUNNING') ?? false;
  // The hook fetches the moment it is enabled, straight after the load that enabled it; a tick is skipped when
  // the last load started less than one interval ago.
  const poll = useCallback(() => {
    if (Date.now() - lastLoadStartRef.current < POLL_INTERVAL_MS) return;
    load();
  }, [load]);
  useVisiblePoll(poll, POLL_INTERVAL_MS, { enabled: anyRunning || expandPending });

  const setTab = (next) => {
    setTabState(next);
    writeTab(next);
  };

  // The "See which fixtures" and "Fixtures →" links: the Fixtures tab with that model preselected.
  const seeFixtures = (model) => {
    setFixturesModel(model);
    setTab('fixtures');
  };

  const openPanel = (models) => {
    setPrefill(models);
    setSubmitError(null);
    setPanelKey((k) => k + 1);
    setPanelOpen(true);
  };

  const submit = async (body) => {
    if (submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const res = await startSkyEvalCheck(body);
      pendingExpandRef.current = res.data?.runIds?.[0] ?? null;
      setExpandPending(pendingExpandRef.current != null);
      setPanelOpen(false);
      setTab('runs');
      await load(true);
    } catch (err) {
      setSubmitError(apiErrorMessage(err, err?.message || 'Failed to start the check'));
    } finally {
      setSubmitting(false);
    }
  };

  const toggleSession = (id) => setOpenSessionId((current) => (current === id ? null : id));

  const now = appNow();

  // A lane's "Run a check" link can sit far down the page; the panel it opens is at the top.
  useEffect(() => {
    if (panelOpen) panelRef.current?.scrollIntoView?.({ block: 'start' });
  }, [panelOpen, panelKey]);

  const selectableModels = useMemo(() => {
    if (availableModels && availableModels.length > 0) return availableModels;
    return overview ? overview.models.map((m) => m.model) : [];
  }, [availableModels, overview]);

  const scheduleLine = overview ? scheduleSentence(overview.schedule) : '';

  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="sky-eval-view">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="font-serif text-[26px] font-semibold leading-none tracking-[-0.01em]">Sky Eval</h2>
        <span className="ml-auto flex items-center gap-2">
          <button
            type="button"
            onClick={() => (panelOpen ? setPanelOpen(false) : openPanel(null))}
            disabled={!overview || submitting}
            className="min-h-8 cursor-pointer whitespace-nowrap rounded-lg bg-plex-text px-3 py-1.5 text-[13px] font-medium text-[#1B1411] focus-visible:outline-2 focus-visible:outline-plex-gold disabled:cursor-default disabled:opacity-40"
            data-testid="sky-eval-open-check"
          >
            Run a check
          </button>
        </span>
      </div>

      {overview && (
        <p className="-mt-2 flex flex-wrap items-baseline gap-1.5 text-[12.5px] text-plex-text-muted" data-testid="sky-eval-subline">
          <span>Sky-rating calibration</span>
          <span aria-hidden="true">{'·'}</span>
          <span data-testid="sky-eval-fixture-count">{overview.fixtures.length} fixtures</span>
          <span aria-hidden="true">{'·'}</span>
          <span>
            {scheduleLine.prefix}
            {scheduleLine.models && <b className="font-medium text-plex-text-secondary">{scheduleLine.models}</b>}
          </span>
          <a
            href="#manage/scheduler/sky_rating_eval"
            className="text-[12.5px] text-tide hover:text-tide-light focus-visible:outline-2 focus-visible:outline-plex-gold"
            data-testid="sky-eval-edit-scheduler"
          >
            Edit in Scheduler
          </a>
        </p>
      )}

      <ErrorBanner message={loadError} data-testid="sky-eval-load-error" />

      {overview && panelOpen && (
        <div ref={panelRef} className="scroll-mt-4">
          <RunCheckPanel
            key={panelKey}
            overview={overview}
            selectableModels={selectableModels}
            initialModels={prefill}
            submitting={submitting}
            error={submitError}
            onSubmit={submit}
            onClose={() => setPanelOpen(false)}
          />
        </div>
      )}

      {!overview && !loadError && (
        <p className="text-sm text-plex-text-muted" data-testid="sky-eval-loading">Loading{'…'}</p>
      )}

      {overview && (
        <>
          <div className="flex gap-6 border-b border-plex-border" role="group" aria-label="Sky Eval views">
            {SKY_EVAL_TABS.map((key) => (
              <button
                key={key}
                type="button"
                aria-pressed={tab === key}
                onClick={() => {
                  if (key === 'fixtures' && tab !== 'fixtures') setFixturesModel(null);
                  setTab(key);
                }}
                className={`-mb-px flex cursor-pointer items-baseline gap-2 border-b-2 pb-[9px] text-sm font-semibold focus-visible:outline-2 focus-visible:outline-plex-gold ${
                  tab === key ? 'border-plex-text text-plex-text' : 'border-transparent text-plex-text-muted hover:text-plex-text'
                }`}
                data-testid={`sky-eval-tab-${key}`}
              >
                {TAB_LABELS[key]}
                {key === 'fixtures' && <small className="font-mono text-[11px] font-normal text-plex-text-faint">{overview.fixtures.length}</small>}
                {key === 'runs' && <small className="font-mono text-[11px] font-normal text-plex-text-faint">{overview.sessions.length} sessions</small>}
              </button>
            ))}
          </div>

          {tab === 'models' && <ModelsTab overview={overview} now={now} onRunCheck={openPanel} onSeeFixtures={seeFixtures} />}
          {tab === 'fixtures' && <FixturesTab overview={overview} preselected={fixturesModel} />}
          {tab === 'runs' && (
            <RunsTab
              sessions={overview.sessions}
              versions={overview.versions}
              openId={openSessionId}
              onToggle={toggleSession}
              onSeeFixtures={seeFixtures}
              now={now}
            />
          )}
        </>
      )}
    </div>
  );
}

/**
 * The header sub-line's schedule part: the served label, then "on {models}" while the weekly run is active and
 * has models.
 *
 * @param {{label: string, jobStatus: string, models: string[]}} schedule the served schedule
 * @returns {{prefix: string, models: string}} the text before the models, and the bold model names
 */
function scheduleSentence(schedule) {
  const names = schedule.models.map(modelLabel).join(', ');
  const active = schedule.jobStatus === 'ACTIVE';
  if (!active || names === '') return { prefix: schedule.label, models: '' };
  return { prefix: `${schedule.label}, on `, models: names };
}
