import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { modelLabel } from '../utils/modelLabels.js';
import { getAvailableModels, setActiveModel, setExtendedThinking } from '../api/modelsApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import InfoTip from './InfoTip.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';

const CONFIG_TABS = [
  { key: 'BRIEFING_BEST_BET', label: 'Briefing', tip: 'Claude model for the best-bet photography advisor in the daily briefing.' },
  { key: 'AURORA_EVALUATION', label: 'Aurora', tip: 'Claude model for scoring aurora photography conditions per location.' },
  { key: 'BATCH_NEAR_TERM', label: 'Batch Near-Term', tip: 'Claude model for T+0 and T+1 in the nightly cycle, and for every intraday, evening and JFDI request.', adminOnly: true },
  { key: 'BATCH_FAR_TERM', label: 'Batch Far-Term', tip: 'Claude model for nightly T+2 (settled or transitional) and T+3 (settled), plus forced headline contenders.', adminOnly: true },
];

/** How long a transient success banner stays on screen before it clears itself. */
const SUCCESS_BANNER_MS = 3000;

/** Returns true if the given tab key is a batch tab. */
const isBatchTab = (key) => key === 'BATCH_NEAR_TERM' || key === 'BATCH_FAR_TERM';

/** Card details that are not money: the display name and a hand-written speed. */
const MODEL_INFO = {
  HAIKU: { name: modelLabel('HAIKU'), speed: 'Fast' },
  HAIKU_55: { name: modelLabel('HAIKU_55'), speed: 'Fast' },
  SONNET: { name: modelLabel('SONNET'), speed: 'Moderate' },
  SONNET_55: { name: modelLabel('SONNET_55'), speed: 'Moderate' },
  OPUS: { name: modelLabel('OPUS'), speed: 'Slower' },
};

const UNKNOWN_MODEL_DESCRIPTION = 'Newer model; no description yet.';
const UNKNOWN = 'Unknown';

/**
 * The card details for a model. A model without a hand-written entry (a newly added platform model)
 * gets a generic card with its speed marked unknown, so the backend's model list shows up here
 * without a frontend edit. Prices are never held here: they come from the served cost data.
 *
 * @param {string} model - EvaluationModel enum name
 * @returns {{name: string, speed: string}} the card details
 */
export function modelInfoFor(model) {
  if (Object.prototype.hasOwnProperty.call(MODEL_INFO, model)) return MODEL_INFO[model];
  return { name: modelLabel(model), speed: UNKNOWN };
}

/** Tab-specific model card descriptions. */
const TAB_DESCRIPTIONS = {
  BRIEFING_BEST_BET: 'Analyses region-level triage data to recommend the best photography opportunity',
  AURORA_EVALUATION: 'Evaluates aurora visibility conditions per location based on Kp forecast, cloud cover, and light pollution rating',
};

/** Per-model descriptions for the batch tabs. */
const BATCH_MODEL_DESCRIPTIONS = {
  HAIKU: 'Fast, cost-efficient model.',
  SONNET: 'Strong reasoning at low cost with batch pricing and prompt caching applied.',
  HAIKU_55: 'Selectable newer Haiku for scheduled batch runs, about a tenth of Haiku 4.5\'s price per token (its tokeniser counts about 30% more tokens).',
  SONNET_55: 'Selectable newer Sonnet for scheduled batch runs. Costs about the same as Sonnet 4.6 because its tokeniser counts more input tokens.',
  OPUS: 'The highest-priced model per token.',
};

/**
 * The batch tabs' description for a model, or the generic one for a model without an entry.
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

/** Length of the cost window, used only when a served profile does not carry its own. */
const DEFAULT_WINDOW_DAYS = 30;

/**
 * Formats a number with at most one decimal place: 1 -> "1", 2.33 -> "2.3", 143.4 -> "143".
 *
 * @param {number} n - the number
 * @returns {string} the display text
 */
function compactNumber(n) {
  if (n >= 10) return String(Math.round(n));
  return String(Number(n.toFixed(1)));
}

/**
 * Formats a USD amount in the served display currency: pounds when the backend sent the latest
 * exchange rate, dollars otherwise. Small amounts get more decimals.
 *
 * @param {number} usd - the amount in US dollars
 * @param {number|null} gbpPerUsd - the served GBP per USD rate, or null
 * @returns {string} the display text, e.g. "£0.031"
 */
export function formatMoney(usd, gbpPerUsd) {
  const value = gbpPerUsd ? usd * gbpPerUsd : usd;
  const symbol = gbpPerUsd ? '£' : '$';
  if (value >= 1) return `${symbol}${value.toFixed(2)}`;
  if (value >= 0.01) return `${symbol}${value.toFixed(3)}`;
  return `${symbol}${value.toFixed(4)}`;
}

/** Formats a token rate (USD per million tokens) in the served display currency. */
function formatRate(usdPerMtok, gbpPerUsd) {
  const value = gbpPerUsd ? usdPerMtok * gbpPerUsd : usdPerMtok;
  return `${gbpPerUsd ? '£' : '$'}${Number(value.toFixed(3))}`;
}

/**
 * The cost figure for one model on one tab: the measured one when the model ran, else the estimate.
 *
 * @param {object|undefined} profile - the tab's served cost profile
 * @param {string} model - EvaluationModel enum name
 * @returns {{usdPerCall: number, usdPerDay: number, source: string}|null} the figure, or null
 */
function costFor(profile, model) {
  if (!profile) return null;
  const measured = profile.measured?.[model];
  if (measured) return { ...measured, source: 'measured' };
  const estimate = profile.estimates?.[model];
  if (estimate) return { ...estimate, source: 'estimate' };
  return null;
}

/**
 * The per-model cost table for the active tab, rendered from the served cost profile.
 *
 * @param {object} props
 * @param {string} props.activeTab - the active run type key
 * @param {object} props.costProfiles - the served profiles keyed by run type
 * @param {number|null} props.gbpPerUsd - the served GBP per USD rate, or null
 * @param {string[]} props.models - the models to list
 * @param {string} props.activeModel - the model currently active on this tab
 */
function CostTable({ activeTab, costProfiles, gbpPerUsd, models, activeModel }) {
  const batch = isBatchTab(activeTab);
  const unit = batch ? 'request' : 'call';
  const runWord = batch ? 'cycle' : 'run';
  const profile = costProfiles?.[activeTab];
  const hasProfiles = !!costProfiles && Object.keys(costProfiles).length > 0;
  const unavailable = !hasProfiles || !profile;
  const empty = !unavailable && !(profile.runsPerDay > 0);

  let body;
  if (unavailable) {
    body = <p className="text-sm text-plex-text-secondary" data-testid="cost-table-unavailable">Cost figures unavailable</p>;
  } else if (empty) {
    body = (
      <p className="text-sm text-plex-text-secondary" data-testid="cost-table-empty">
        No calls in the last {profile?.windowDays ?? DEFAULT_WINDOW_DAYS} days
      </p>
    );
  } else {
    const perRun = profile.callsPerRun;
    body = (
      <>
        <p className="text-xs text-plex-text-secondary mb-3" data-testid="cost-table-summary">
          {compactNumber(profile.runsPerDay)} {runWord}s a day,{' '}
          {compactNumber(perRun)} {compactNumber(perRun) === '1' ? unit : `${unit}s`} per {runWord}, last{' '}
          {profile.windowDays} days
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-sm" data-testid="cost-table">
            <thead>
              <tr className="text-left text-plex-text-secondary border-b border-plex-border">
                <th className="pb-2 pr-4">Model</th>
                <th className="pb-2 pr-4 text-right">Per {unit}</th>
                <th className="pb-2 pr-4 text-right">Per day</th>
                <th className="pb-2 text-right">Basis</th>
              </tr>
            </thead>
            <tbody>
              {models.map((model) => {
                const cost = costFor(profile, model);
                const isActive = model === activeModel;
                return (
                  <tr key={model} className={isActive ? 'text-plex-gold' : 'text-plex-text-secondary'}>
                    <td className="py-1.5 pr-4 font-medium">
                      {modelInfoFor(model).name}
                      {isActive && <span className="text-xs ml-1.5 opacity-60">(active)</span>}
                    </td>
                    <td className="py-1.5 pr-4 text-right font-mono text-xs">
                      {cost ? formatMoney(cost.usdPerCall, gbpPerUsd) : '—'}
                    </td>
                    <td className="py-1.5 pr-4 text-right font-mono text-xs">
                      {cost ? formatMoney(cost.usdPerDay, gbpPerUsd) : '—'}
                    </td>
                    <td className="py-1.5 text-right text-xs">{cost ? cost.source : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </>
    );
  }

  return (
    <div className="card border border-plex-border" data-testid="cost-table-card">
      <h3 className="font-semibold text-plex-text mb-1">
        Cost
        <InfoTip text="Measured figures come from the calls that ran in the window. Estimates price the same token counts at that model's rates, ignoring tokeniser and thinking differences (Haiku 5.5 counts about 30% more tokens)." />
      </h3>
      {body}
      <p className="text-xs text-plex-text-muted mt-3" data-testid="cost-footnote">
        Costs are calculated from logged token counts at the configured prices.
        {batch && (
          <>
            {' '}Includes the Batch API discount and prompt caching. The cache-priming call is not logged, so it
            is not included.
          </>
        )}
        {activeTab === 'AURORA_EVALUATION' && (
          <>
            {' '}A refused or truncated synchronous aurora call is logged at zero cost though it was billed, and
            admin aurora runs for a chosen night and the aurora gloss log nothing, so these figures understate.
          </>
        )}
      </p>
    </div>
  );
}

CostTable.propTypes = {
  activeTab: PropTypes.string.isRequired,
  costProfiles: PropTypes.object,
  gbpPerUsd: PropTypes.number,
  models: PropTypes.arrayOf(PropTypes.string).isRequired,
  activeModel: PropTypes.string.isRequired,
};

/**
 * Admin-only view for selecting the active evaluation model per run type.
 */
export default function ModelSelectionView() {
  const { isAdmin } = useAuth();
  const [availableModels, setAvailableModels] = useState([]);
  const [configs, setConfigs] = useState({});
  const [extendedThinkingConfig, setExtendedThinkingConfig] = useState({});
  const [costProfiles, setCostProfiles] = useState(null);
  const [tokenRates, setTokenRates] = useState({});
  const [gbpPerUsd, setGbpPerUsd] = useState(null);
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
        setCostProfiles(data.costProfiles || null);
        setTokenRates(data.tokenRates || {});
        setGbpPerUsd(typeof data.gbpPerUsd === 'number' ? data.gbpPerUsd : null);
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
  const activeProfile = costProfiles?.[activeTab];
  const etOn = !!extendedThinkingConfig['BRIEFING_BEST_BET'];

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
          const cost = costFor(activeProfile, model);
          const rates = tokenRates?.[model];

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
                  : TAB_DESCRIPTIONS[activeTab]}
              </p>

              <div className="space-y-2 mb-4 text-sm text-plex-text-secondary">
                <div data-testid={`card-cost-${model}`}>
                  <span className="font-medium">Cost:</span>{' '}
                  {cost ? (
                    <span className="text-plex-text">
                      ~{formatMoney(cost.usdPerCall, gbpPerUsd)} per {isBatchTab(activeTab) ? 'request' : 'call'}
                      <span className="text-plex-text-muted font-normal"> ({cost.source})</span>
                    </span>
                  ) : (
                    <span className="text-plex-text">{UNKNOWN.toLowerCase()}</span>
                  )}
                </div>
                <div>
                  <span className="font-medium">Speed:</span> {info.speed}
                </div>
                <div className="text-xs opacity-70" data-testid={`card-rates-${model}`}>
                  Token rates:{' '}
                  {rates
                    ? `${formatRate(rates.inputUsdPerMtok, gbpPerUsd)}/MTok in, ${formatRate(rates.outputUsdPerMtok, gbpPerUsd)}/MTok out`
                    : UNKNOWN.toLowerCase()}
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
                Turns on adaptive thinking for Sonnet 4.6 and Opus 4.6. There is no fixed thinking budget;
                the token ceiling for thinking plus reply rises from 4,096 to 16,000.
                {etOn && activeModelForTab === 'HAIKU' && (
                  <span className="text-yellow-400 ml-1">
                    ⚠ Haiku 4.5 does not support extended thinking — switch to Sonnet or Opus.
                  </span>
                )}
              </p>
              {etOn && activeModelForTab === 'HAIKU_55' && (
                <p className="text-xs text-yellow-400 mt-1" data-testid="extended-thinking-haiku-55-note">
                  Haiku 5.5 runs with thinking disabled, so this switch has no effect on it.
                </p>
              )}
              {etOn && activeModelForTab === 'SONNET_55' && (
                <p className="text-xs text-yellow-400 mt-1" data-testid="extended-thinking-sonnet-55-note">
                  Sonnet 5.5 always thinks adaptively at low effort, so this switch has no effect on it,
                  and it always has the 16,000 ceiling.
                </p>
              )}
            </div>
            <button
              onClick={() => handleExtendedThinkingToggle('BRIEFING_BEST_BET')}
              className={`flex-shrink-0 px-3 py-1 rounded-full text-xs font-semibold transition-colors ${
                etOn
                  ? 'bg-violet-600/30 text-violet-300 hover:bg-violet-600/50'
                  : 'bg-plex-border text-plex-text-secondary hover:bg-plex-border-light'
              }`}
              data-testid="toggle-extended-thinking"
            >
              {etOn ? 'ON' : 'OFF'}
            </button>
          </div>
        </div>
      )}

      {/* Aurora: how thinking is set (no toggle) */}
      {activeTab === 'AURORA_EVALUATION' && (
        <div className="card border border-plex-border" data-testid="aurora-thinking-note">
          <p className="text-sm font-medium text-plex-text">Thinking and reply size</p>
          <p className="text-xs text-plex-text-secondary mt-1">
            One call rates every viable location. Sonnet 5.5 thinks adaptively at low effort; Haiku 5.5 runs
            with thinking off; the other models run without thinking. The reply budget is about 220 tokens per
            location, plus 4,096 for Sonnet 5.5&apos;s thinking.
          </p>
        </div>
      )}

      {/* Batch: informational panel */}
      {isBatchTab(activeTab) && (
        <div className="bg-plex-surface border border-plex-border rounded-lg p-4 text-sm text-plex-text-secondary space-y-3" data-testid="batch-info-panel">
          <p className="font-medium text-plex-text">How scheduled batch runs work</p>
          <p>
            Scheduled runs send every location that passes weather triage and has no fresh evaluation to the
            Anthropic Batch API, in the nightly, intraday and evening cycles. Requests are split into two
            tiers with independent model selection:
          </p>
          <div>
            <p className="font-medium text-plex-text mb-1">Near-term vs far-term</p>
            <div className="space-y-2 font-mono text-xs mb-2">
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">NEAR-TERM</span>
                <span>T+0 and T+1, at every stability; skipped only by triage or a fresh evaluation.</span>
              </div>
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">FAR-TERM</span>
                <span>
                  Nightly cycle only: T+2 when settled or transitional, T+3 when settled, plus up to 6 GO
                  headline contenders at T+2/T+3 that the stability gate would skip.
                </span>
              </div>
            </div>
          </div>
          <div>
            <p className="font-medium text-plex-text mb-2">Nightly cycle</p>
            <p className="mb-2">
              The weather stability classifier reads pressure tendency, precipitation probability, WMO weather
              codes and wind-gust variance for each grid cell. Based on this it assigns one of three stability
              levels:
            </p>
            <div className="space-y-2 font-mono text-xs">
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">SETTLED</span>
                <span>Clear high pressure pattern, low precipitation risk. T+0 to T+3.</span>
              </div>
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">TRANSITIONAL</span>
                <span>Mixed signals — possible frontal approach or model uncertainty. T+0 to T+2.</span>
              </div>
              <div className="flex gap-4">
                <span className="text-plex-gold w-24 flex-shrink-0">UNSETTLED</span>
                <span>Active weather, falling pressure, or high precipitation variance. T+0 and T+1.</span>
              </div>
            </div>
            <p className="mt-2">
              The intraday cycle covers today&apos;s sunset and tomorrow&apos;s sunrise and sunset; the evening
              cycle covers tomorrow&apos;s sunrise.
            </p>
          </div>
          <div>
            <p className="font-medium text-plex-text mb-1">Why a run might produce no results</p>
            <p>
              If every sky location is stood down by triage and no woodland or bluebell site is due, the
              batch has zero requests and is not submitted.
            </p>
          </div>
        </div>
      )}

      {/* Measured cost table for the active run type */}
      <CostTable
        activeTab={activeTab}
        costProfiles={costProfiles}
        gbpPerUsd={gbpPerUsd}
        models={availableModels}
        activeModel={activeModelForTab}
      />

      <div className="bg-plex-surface border border-plex-border rounded-lg p-4 text-sm text-plex-text-secondary">
        <p className="font-medium text-plex-text mb-2">About model configuration</p>
        <ul className="space-y-1 text-xs">
          <li>Each run type can use a different Claude model independently</li>
          <li>Wildlife-only locations automatically display weather data without AI evaluation</li>
          <li>Previous forecasts are preserved with their original model</li>
        </ul>
      </div>
    </div>
  );
}
