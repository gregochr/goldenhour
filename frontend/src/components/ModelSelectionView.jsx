import { useEffect, useState } from 'react';
import { modelLabel } from '../utils/modelLabels.js';
import { getAvailableModels, setActiveModel, setExtendedThinking } from '../api/modelsApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import InfoTip from './InfoTip.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';

const CONFIG_TABS = [
  { key: 'BRIEFING_BEST_BET', label: 'Briefing', tip: 'Claude model for the best-bet photography advisor in the daily briefing.' },
  { key: 'AURORA_EVALUATION', label: 'Aurora', tip: 'Claude model for scoring aurora photography conditions per location.' },
  { key: 'BATCH_NEAR_TERM', label: 'Batch Near-Term', tip: 'Claude model for overnight near-term batch runs (T+0, T+1). High-confidence forecasts evaluated every night.', adminOnly: true },
  { key: 'BATCH_FAR_TERM', label: 'Batch Far-Term', tip: 'Claude model for overnight far-term batch runs (T+2, T+3). Only evaluated when weather is settled.', adminOnly: true },
];

/** How long a transient success banner stays on screen before it clears itself. */
const SUCCESS_BANNER_MS = 3000;

/** Returns true if the given tab key is a batch tab. */
const isBatchTab = (key) => key === 'BATCH_NEAR_TERM' || key === 'BATCH_FAR_TERM';

/** Approximate USD to GBP rate for display purposes. */
const USD_TO_GBP = 0.79;

const MODEL_INFO = {
  HAIKU: {
    name: modelLabel('HAIKU'),
    description: 'Fast, cost-efficient model. Returns 1-5 star rating.',
    typicalCostUsd: 0.002,
    tokenRates: '£0.80/MTok in, £4/MTok out',
    speed: 'Fast',
    recommended: true,
  },
  HAIKU_55: {
    name: modelLabel('HAIKU_55'),
    description: 'Newer Haiku. Run here with thinking off at low effort. Returns 1-5 star rating.',
    typicalCostUsd: 0.0003,
    tokenRates: '£0.08/MTok in, £0.40/MTok out',
    speed: 'Fast',
    recommended: false,
  },
  SONNET: {
    name: modelLabel('SONNET'),
    description: 'Advanced model. Returns detailed 0-100 scores for fiery sky and golden hour potential.',
    typicalCostUsd: 0.005,
    tokenRates: '£2.40/MTok in, £12/MTok out',
    speed: 'Moderate',
    recommended: false,
  },
  SONNET_55: {
    name: modelLabel('SONNET_55'),
    description: 'Newer Sonnet. Always thinks adaptively, run here at low effort. Returns detailed 0-100 scores.',
    typicalCostUsd: 0.005,
    tokenRates: '£1.58/MTok in, £7.90/MTok out',
    speed: 'Moderate',
    recommended: false,
  },
  OPUS: {
    name: modelLabel('OPUS'),
    description: 'Highest accuracy model. Returns detailed 0-100 scores with the deepest reasoning.',
    typicalCostUsd: 0.008,
    tokenRates: '£4/MTok in, £20/MTok out',
    speed: 'Slower',
    recommended: false,
  },
};

const UNKNOWN_MODEL_DESCRIPTION = 'Newer model; no description yet.';
const UNKNOWN = 'Unknown';

/**
 * The card details for a model. A model without a hand-written entry (a newly added platform model)
 * gets a generic card, never Recommended, with its cost, speed and token rates marked unknown, so
 * the backend's model list shows up here without a frontend edit.
 *
 * @param {string} model - EvaluationModel enum name
 * @returns {{name: string, description: string, typicalCostUsd: (number|null), tokenRates: string,
 *   speed: string, recommended: boolean}} the card details
 */
export function modelInfoFor(model) {
  if (Object.prototype.hasOwnProperty.call(MODEL_INFO, model)) return MODEL_INFO[model];
  return {
    name: modelLabel(model),
    description: UNKNOWN_MODEL_DESCRIPTION,
    typicalCostUsd: null,
    tokenRates: UNKNOWN,
    speed: UNKNOWN,
    recommended: false,
  };
}

/**
 * The Scheduled Batch tab's description for a model, or the generic one for a model without an entry.
 *
 * @param {string} model - EvaluationModel enum name
 * @returns {string} the description
 */
export function batchDescriptionFor(model) {
  if (Object.prototype.hasOwnProperty.call(BATCH_MODEL_DESCRIPTIONS, model)) {
    return BATCH_MODEL_DESCRIPTIONS[model];
  }
  return UNKNOWN_MODEL_DESCRIPTION;
}

/** Format a USD amount as approximate GBP. */
function toGbp(usd) {
  const gbp = usd * USD_TO_GBP;
  if (gbp < 0.01) return `~£${gbp.toFixed(4)}`;
  return `£${gbp.toFixed(2)}`;
}

/** Typical cost per call in USD, used for run cost estimates. */
const COST_PER_CALL = { HAIKU: 0.002, HAIKU_55: 0.0003, SONNET: 0.005, SONNET_55: 0.005, OPUS: 0.008 };

/** Tab-specific model card descriptions (override generic MODEL_INFO.description). */
const TAB_DESCRIPTIONS = {
  BRIEFING_BEST_BET: 'Analyses region-level triage data to recommend the best photography opportunity',
  AURORA_EVALUATION: 'Evaluates aurora visibility conditions per location based on Kp forecast, cloud cover, and light pollution rating',
};

/** Per-model descriptions for the Scheduled Batch tab. */
const BATCH_MODEL_DESCRIPTIONS = {
  HAIKU: 'Fast, cost-efficient model for overnight batch runs. Lower quality reasoning on marginal conditions.',
  SONNET: 'Recommended for scheduled batch runs. Strong reasoning at low cost with batch pricing and prompt caching applied.',
  HAIKU_55: 'Selectable newer Haiku for scheduled batch runs, about a tenth of Haiku 4.5\'s price per token (its tokeniser counts about 30% more tokens). Untested against the prompt-regression suite; not the recommended default.',
  SONNET_55: 'Selectable newer Sonnet for scheduled batch runs. Costs about the same as Sonnet 4.6 because its tokeniser counts more input tokens; not the recommended default.',
  OPUS: 'Highest accuracy for batch runs. Best reserved for key seasonal events only.',
};

/** Static cost rows for the Scheduled Batch cost table (batch API 50% discount + caching). */
const BATCH_COST_ROWS = [
  { model: 'HAIKU',  perRequest: '~£0.0001', typicalRun: '~£0.01 – £0.04' },
  { model: 'HAIKU_55', perRequest: '~£0.00002', typicalRun: '~£0.001 – £0.005' },
  { model: 'SONNET', perRequest: '~£0.0002', typicalRun: '~£0.02 – £0.08' },
  { model: 'SONNET_55', perRequest: '~£0.0002', typicalRun: '~£0.02 – £0.08' },
  { model: 'OPUS',   perRequest: '~£0.0004', typicalRun: '~£0.04 – £0.16' },
];

/**
 * Cost estimate config for the tabs that have one (the batch tabs use the static batch table).
 * callsPerRun: typical calls per run. label: human description.
 */
const CUSTOM_COST = {
  BRIEFING_BEST_BET: { callsPerRun: 12, label: '~12 calls/day (every 2 hours)' },
  AURORA_EVALUATION: { callsPerRun: 35, label: '~35 locations per aurora evaluation night' },
};

/**
 * Admin-only view for selecting the active evaluation model per run type.
 */
export default function ModelSelectionView() {
  const { isAdmin } = useAuth();
  const [availableModels, setAvailableModels] = useState([]);
  const [configs, setConfigs] = useState({});
  const [extendedThinkingConfig, setExtendedThinkingConfig] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [switching, setSwitching] = useState(false);
  const [activeTab, setActiveTab] = useState('BRIEFING_BEST_BET');

  /**
   * Clears the success banner {@link SUCCESS_BANNER_MS} after it appears.
   *
   * <p>⚠️ The dismiss is an effect keyed on {@code success}, the idiom {@code RegisterPage}'s
   * cooldown already uses, and not a {@code setTimeout} armed by the
   * handlers. Those were bare calls placed *after* each handler's network await, so the callback
   * outlived the component: it ran against a torn-down tree and React's {@code dispatchSetState}
   * read a {@code window} that no longer existed. Vitest fails a run that records an unhandled
   * error while still reporting every test as passing — observed once in six full-suite runs.
   *
   * <p>⚠️ Do not move the timer back into the handlers, even with a cleanup. An unmount cleanup
   * cannot cancel a timer that does not exist yet: `ManageView` renders this behind
   * {@code activeTab === 'models'}, so switching tab mid-request unmounts it before the await
   * resolves, and the handler then arms a timer nothing owns. An effect has no such window — it
   * never runs after unmount, and a {@code setSuccess} on an unmounted component is a no-op — so
   * no {@code isMounted} guard is needed either.
   *
   * <p>The two handlers that show a message (model switch, extended thinking) each clear
   * {@code success} before their await. The effect re-runs only when {@code success} changes, so
   * the null committed in between guarantees a newer message gets its own full window. The two
   * messages the screen can show today never repeat back to back, so this is a guard rather than
   * something a current path exercises.
   *
   * <p>One case it does not cover: two requests for the same thing in flight at once (a
   * double-click on the extended-thinking toggle, which stays enabled mid-request). Both responses
   * carry the same string, so the second {@code setSuccess} is a no-op and the banner clears on the first response's timer,
   * exactly as it did before this change.
   */
  useEffect(() => {
    if (!success) return undefined;
    const timer = setTimeout(() => setSuccess(null), SUCCESS_BANNER_MS);
    return () => clearTimeout(timer);
  }, [success]);

  useEffect(() => {
    async function fetchModels() {
      try {
        setLoading(true);
        setError(null);
        const data = await getAvailableModels();
        // available is now [{name, version}, ...] — extract names for backward compat
        const available = (data.available || []).map((m) => (typeof m === 'string' ? m : m.name));
        setAvailableModels(available);
        setConfigs(data.configs || {});
        setExtendedThinkingConfig(data.extendedThinkingConfigs || {});
      } catch (err) {
        setError('Failed to load available models');
        console.error(err);
      } finally {
        setLoading(false);
      }
    }
    fetchModels();
  }, []);

  const handleModelSwitch = async (runType, model) => {
    if (model === configs[runType]) return;

    try {
      setSwitching(true);
      setError(null);
      setSuccess(null);
      const result = await setActiveModel(runType, model);
      setConfigs((prev) => ({ ...prev, [runType]: result.active }));
      const tabLabel = CONFIG_TABS.find((t) => t.key === runType)?.label || runType;
      setSuccess(`${tabLabel} model switched to ${modelLabel(result.active)}`);
    } catch (err) {
      setError(`Failed to switch model for ${runType}`);
      console.error(err);
    } finally {
      setSwitching(false);
    }
  };

  const handleExtendedThinkingToggle = async (runType) => {
    const current = !!extendedThinkingConfig[runType];
    const next = !current;
    try {
      setError(null);
      setSuccess(null);
      await setExtendedThinking(runType, next);
      setExtendedThinkingConfig((prev) => ({ ...prev, [runType]: next }));
      setSuccess(`Extended thinking ${next ? 'enabled' : 'disabled'} for Briefing`);
    } catch (err) {
      setError('Failed to update extended thinking setting');
      console.error(err);
    }
  };

  if (loading) {
    return (
      <div className="card">
        <p className="text-plex-text-secondary">Loading models...</p>
      </div>
    );
  }

  if (error && availableModels.length === 0) {
    return (
      <div className="card border border-red-800 bg-red-900/20">
        <p className="text-red-400 font-medium mb-2">Error loading models</p>
        <p className="text-red-300 text-sm">{error}</p>
      </div>
    );
  }

  const visibleTabs = CONFIG_TABS.filter((t) => !t.adminOnly || isAdmin);

  const activeModelForTab = configs[activeTab] || 'HAIKU';

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-bold text-plex-text mb-2">Run Configuration</h2>
        <p className="text-sm text-plex-text-secondary mb-4">
          Choose which Claude model to use for each forecast run type.
        </p>
      </div>

      {/* Config type sub-tabs */}
      <div className="flex gap-6 border-b border-plex-border flex-wrap">
        {visibleTabs.map((tab) => (
          <span key={tab.key} className="inline-flex items-center gap-1">
            <button
              onClick={() => setActiveTab(tab.key)}
              className={`pb-2 text-sm font-medium transition-colors border-b-2 ${
                activeTab === tab.key
                  ? 'text-plex-gold border-plex-gold'
                  : 'text-plex-text-secondary hover:text-plex-text border-transparent'
              }`}
              data-testid={`config-tab-${tab.key}`}
            >
              {tab.label}
              <span className="ml-2 text-xs opacity-60">
                ({modelLabel(configs[tab.key]) || modelLabel('HAIKU')})
              </span>
            </button>
            <InfoTip text={tab.tip} />
          </span>
        ))}
      </div>

      <ErrorBanner message={error} />

      {success && (
        <div className="bg-green-900/30 border border-green-700 text-green-400 px-4 py-3 rounded-lg text-sm">
          {success}
        </div>
      )}

      {/* Model cards for the active tab */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {availableModels.map((model) => {
          const info = modelInfoFor(model);
          const isActive = model === activeModelForTab;

          return (
            <div
              key={model}
              className={`card border-2 transition-colors ${
                isActive
                  ? 'border-plex-gold bg-plex-gold/5'
                  : 'border-plex-border hover:border-plex-border-light'
              }`}
            >
              <div className="flex items-start justify-between mb-3">
                <div>
                  <h3 className="text-lg font-semibold text-plex-text">{info.name}</h3>
                  {info.recommended && (
                    <span className="text-xs bg-plex-gold/20 text-plex-gold px-2 py-1 rounded mt-1 inline-block">
                      Recommended
                    </span>
                  )}
                </div>
                {isActive && (
                  <span className="text-xs bg-green-600/30 text-green-400 px-2 py-1 rounded">
                    Active
                  </span>
                )}
              </div>

              <p className="text-sm text-plex-text-secondary mb-3">
                {isBatchTab(activeTab)
                  ? batchDescriptionFor(model)
                  : (TAB_DESCRIPTIONS[activeTab] || info.description)}
              </p>

              <div className="space-y-2 mb-4 text-sm text-plex-text-secondary">
                <div>
                  <span className="font-medium">Typical cost:</span>{' '}
                  {info.typicalCostUsd == null ? (
                    <span className="text-plex-text">{UNKNOWN.toLowerCase()}</span>
                  ) : (
                    <>
                      <span className="text-plex-text">{toGbp(info.typicalCostUsd)}/call</span>
                      <span className="text-plex-text-muted font-normal"> (~${info.typicalCostUsd.toFixed(3)})</span>
                    </>
                  )}
                </div>
                <div>
                  <span className="font-medium">Speed:</span> {info.speed}
                </div>
                <div className="text-xs opacity-70">
                  Token rates: {info.tokenRates}
                </div>
              </div>

              <button
                onClick={() => handleModelSwitch(activeTab, model)}
                disabled={isActive || switching}
                className={`w-full py-2 px-3 rounded-lg text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-plex-border text-plex-text-muted cursor-default'
                    : 'bg-plex-gold hover:bg-plex-gold-light text-gray-900 disabled:opacity-50'
                }`}
                data-testid={`switch-${activeTab}-${model}`}
              >
                {isActive ? 'Active' : switching ? 'Switching...' : `Switch to ${info.name}`}
              </button>
            </div>
          );
        })}
      </div>

      {/* Extended Thinking toggle — Briefing tab only */}
      {activeTab === 'BRIEFING_BEST_BET' && (
        <div className="card border border-plex-border" data-testid="extended-thinking-panel">
          <div className="flex items-start justify-between gap-4">
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-plex-text">Extended Thinking</p>
              <p className="text-xs text-plex-text-secondary mt-1">
                Enables Claude&apos;s internal reasoning chain for deeper best-bet analysis
                (budgets up to 10 000 thinking tokens per call).
                {extendedThinkingConfig['BRIEFING_BEST_BET'] && activeModelForTab === 'HAIKU' && (
                  <span className="text-yellow-400 ml-1">
                    ⚠ Haiku 4.5 does not support extended thinking — switch to Sonnet or Opus.
                  </span>
                )}
              </p>
              {extendedThinkingConfig['BRIEFING_BEST_BET'] && activeModelForTab === 'HAIKU_55' && (
                <p className="text-xs text-yellow-400 mt-1" data-testid="extended-thinking-haiku-55-note">
                  Haiku 5.5 runs with thinking disabled, so this switch has no effect on it.
                </p>
              )}
              {extendedThinkingConfig['BRIEFING_BEST_BET'] && activeModelForTab === 'SONNET_55' && (
                <p className="text-xs text-yellow-400 mt-1" data-testid="extended-thinking-sonnet-55-note">
                  Sonnet 5.5 always thinks adaptively at low effort, so this switch has no effect on it.
                </p>
              )}
              {extendedThinkingConfig['BRIEFING_BEST_BET'] && activeModelForTab !== 'HAIKU'
                && activeModelForTab !== 'SONNET_55' && activeModelForTab !== 'HAIKU_55' && (
                <p className="text-xs text-violet-400 mt-1">
                  Active — adds ~10 000 thinking tokens per call (Sonnet 4.6: +~£0.12, Opus: +~£0.40).
                </p>
              )}
            </div>
            <button
              onClick={() => handleExtendedThinkingToggle('BRIEFING_BEST_BET')}
              className={`flex-shrink-0 px-3 py-1 rounded-full text-xs font-semibold transition-colors ${
                extendedThinkingConfig['BRIEFING_BEST_BET']
                  ? 'bg-violet-600/30 text-violet-300 hover:bg-violet-600/50'
                  : 'bg-plex-border text-plex-text-secondary hover:bg-plex-border-light'
              }`}
              data-testid="toggle-extended-thinking"
            >
              {extendedThinkingConfig['BRIEFING_BEST_BET'] ? 'ON' : 'OFF'}
            </button>
          </div>
        </div>
      )}

      {/* Batch: informational panel */}
      {isBatchTab(activeTab) && (
        <div className="bg-plex-surface border border-plex-border rounded-lg p-4 text-sm text-plex-text-secondary space-y-3" data-testid="batch-info-panel">
          <p className="font-medium text-plex-text">How scheduled batch runs work</p>
          <p>
            Scheduled runs evaluate all GO and MARGINAL locations overnight using the Anthropic Batch
            API, so results are ready before your first morning check. Locations are split into two
            tiers with independent model selection:
          </p>
          <div>
            <p className="font-medium text-plex-text mb-1">Near-term vs far-term</p>
            <div className="space-y-2 font-mono text-xs mb-2">
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">NEAR-TERM</span>
                <span>T+0 and T+1 — high-confidence forecasts, always evaluated.</span>
              </div>
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">FAR-TERM</span>
                <span>T+2 and T+3 — only evaluated when the weather stability classifier says SETTLED.</span>
              </div>
            </div>
            <p>
              Each tier can use a different model. Use a more capable model for near-term (higher
              confidence data) and a cheaper model for far-term (lower confidence, likely to be
              re-evaluated overnight anyway).
            </p>
          </div>
          <div>
            <p className="font-medium text-plex-text mb-2">Why some nights run further ahead than others</p>
            <p className="mb-2">
              The weather stability classifier reads pressure tendency, precipitation probability, and
              WMO weather codes for each grid cell. Based on this it assigns one of three stability levels:
            </p>
            <div className="space-y-2 font-mono text-xs">
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">SETTLED</span>
                <span>Clear high pressure pattern, low precipitation risk. Evaluates T through T+3.</span>
              </div>
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">TRANSITIONAL</span>
                <span>Mixed signals — possible frontal approach or model uncertainty. Evaluates T and T+1 only.</span>
              </div>
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">UNSETTLED</span>
                <span>Active weather, falling pressure, or high precipitation variance. Evaluates T only.</span>
              </div>
            </div>
          </div>
          <div>
            <p className="font-medium text-plex-text mb-1">Why a run might produce no results</p>
            <p>
              If every location is STANDDOWN after triage (solid overcast, no coastal tide alignment) the
              batch contains zero requests and is not submitted. This is expected — no spend on locations
              the weather has already ruled out.
            </p>
          </div>
          <div>
            <p className="font-medium text-plex-text mb-1">Results and the JFDI button</p>
            <p>
              Batch results populate the same cache as the &ldquo;Run full forecast&rdquo; button. If a batch has
              already scored a region overnight, opening the drill-down shows results immediately with no
              additional Claude call. If you hit &ldquo;Run full forecast&rdquo; while a batch is outstanding, the
              batch is cancelled and a real-time evaluation runs instead.
            </p>
          </div>
        </div>
      )}

      {/* Scheduled Batch: static cost table */}
      {isBatchTab(activeTab) && (
        <div className="card border border-plex-border" data-testid="batch-cost-table">
          <h3 className="font-semibold text-plex-text mb-1">Estimated batch run cost</h3>
          <p className="text-xs text-plex-text-secondary mb-3">
            Requests vary by weather stability and triage filtering — typically 50 to 400 per run.
          </p>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-plex-text-secondary border-b border-plex-border">
                  <th className="pb-2 pr-4"></th>
                  <th className="pb-2 pr-4 text-right">Per request</th>
                  <th className="pb-2 text-right">Typical run</th>
                </tr>
              </thead>
              <tbody>
                {BATCH_COST_ROWS.map(({ model, perRequest, typicalRun }) => {
                  const isActive = model === activeModelForTab;
                  return (
                    <tr key={model} className={isActive ? 'text-plex-gold' : 'text-plex-text-secondary'}>
                      <td className="py-1.5 pr-4 font-medium">
                        {modelInfoFor(model).name}
                        {isActive && <span className="text-xs ml-1.5 opacity-60">(active)</span>}
                      </td>
                      <td className="py-1.5 pr-4 text-right font-mono text-xs">{perRequest}</td>
                      <td className="py-1.5 text-right font-mono text-xs">{typicalRun}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="text-xs text-plex-text-muted mt-3">
            Batch API 50% discount and prompt caching (~90% on cached tokens) applied. Actual cost
            depends on how many locations pass triage and the stability classification for that night.
          </p>
        </div>
      )}

      {/* Cost estimate table for the active run type */}
      {(() => {
        const custom = CUSTOM_COST[activeTab];
        if (!custom) return null;
        const calls = custom.callsPerRun;
        const costLabel = custom.label;
        const callLabel = `${calls} Claude calls per run`;
        return (
          <div className="card border border-plex-border">
            <h3 className="font-semibold text-plex-text mb-1">
              Estimated run cost
              <span className="font-normal text-sm text-plex-text-secondary ml-2">
                ({costLabel})
              </span>
            </h3>
            <p className="text-xs text-plex-text-secondary mb-3">{callLabel}</p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm" data-testid="cost-estimate-table">
                <thead>
                  <tr className="text-left text-plex-text-secondary border-b border-plex-border">
                    <th className="pb-2 pr-4">Model</th>
                    <th className="pb-2 pr-4 text-right">Per call</th>
                    <th className="pb-2 text-right">Run total</th>
                  </tr>
                </thead>
                <tbody>
                  {availableModels.map((model) => {
                    const perCall = Object.prototype.hasOwnProperty.call(COST_PER_CALL, model)
                      ? COST_PER_CALL[model] : null;
                    const isActive = model === activeModelForTab;
                    return (
                      <tr
                        key={model}
                        className={isActive ? 'text-plex-gold' : 'text-plex-text-secondary'}
                      >
                        <td className="py-1.5 pr-4 font-medium">
                          {modelInfoFor(model).name}
                          {isActive && <span className="text-xs ml-1.5 opacity-60">(active)</span>}
                        </td>
                        <td className="py-1.5 pr-4 text-right font-mono text-xs">
                          {perCall == null ? '—' : (
                            <>
                              {toGbp(perCall)}
                              <span className="text-plex-text-muted"> (~${perCall.toFixed(3)})</span>
                            </>
                          )}
                        </td>
                        <td className="py-1.5 text-right font-mono text-xs">
                          {perCall == null ? '—' : (
                            <>
                              {toGbp(perCall * calls)}
                              <span className="text-plex-text-muted"> (${(perCall * calls).toFixed(2)})</span>
                            </>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        );
      })()}

      <div className="bg-plex-surface border border-plex-border rounded-lg p-4 text-sm text-plex-text-secondary">
        <p className="font-medium text-plex-text mb-2">About model configuration</p>
        <ul className="space-y-1 text-xs">
          <li>Each run type can use a different Claude model independently</li>
          <li>Use a more accurate model (Opus/Sonnet) for near-term batch runs, cheaper (Haiku) for far-term ones</li>
          <li>Wildlife-only locations automatically display weather data without AI evaluation</li>
          <li>Previous forecasts are preserved with their original model</li>
          <li>Haiku offers excellent value for most use cases</li>
        </ul>
      </div>
    </div>
  );
}
