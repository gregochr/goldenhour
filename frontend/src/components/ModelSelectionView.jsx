import { useEffect, useState } from 'react';
import { getAvailableModels, setActiveModel, updateOptimisationStrategy } from '../api/modelsApi.js';
import { fetchLocations } from '../api/forecastApi.js';
import InfoTip from './InfoTip.jsx';

const CONFIG_TABS = [
  { key: 'VERY_SHORT_TERM', label: 'Very Short-Term (T, T+1)', tip: 'Imminent forecasts — today and tomorrow. Use a high-accuracy model here.' },
  { key: 'SHORT_TERM', label: 'Short-Term (T, T+1, T+2)', tip: 'Near-term forecasts — up to 2 days out. Good balance of accuracy and cost.' },
  { key: 'LONG_TERM', label: 'Long-Term (T+3 \u2013 T+5)', tip: 'Extended forecasts — 3 to 5 days out. Weather data is less precise, so a cheaper model is fine.' },
];

/** Approximate USD to GBP rate for display purposes. */
const USD_TO_GBP = 0.79;

const MODEL_INFO = {
  HAIKU: {
    name: 'Haiku',
    description: 'Fast, cost-efficient model. Returns 1-5 star rating.',
    typicalCostUsd: 0.002,
    tokenRates: '£0.80/MTok in, £4/MTok out',
    speed: 'Fast',
    recommended: true,
  },
  SONNET: {
    name: 'Sonnet',
    description: 'Advanced model. Returns detailed 0-100 scores for fiery sky and golden hour potential.',
    typicalCostUsd: 0.005,
    tokenRates: '£2.40/MTok in, £12/MTok out',
    speed: 'Moderate',
    recommended: false,
  },
  OPUS: {
    name: 'Opus',
    description: 'Highest accuracy model. Returns detailed 0-100 scores with the deepest reasoning.',
    typicalCostUsd: 0.008,
    tokenRates: '£4/MTok in, £20/MTok out',
    speed: 'Slower',
    recommended: false,
  },
};

/** Format a USD amount as approximate GBP. */
function toGbp(usd) {
  const gbp = usd * USD_TO_GBP;
  if (gbp < 0.01) return `~£${gbp.toFixed(4)}`;
  return `£${gbp.toFixed(2)}`;
}

/** Typical cost per call in USD, used for run cost estimates. */
const COST_PER_CALL = { HAIKU: 0.002, SONNET: 0.005, OPUS: 0.008 };

/** Number of forecast days per run type. Each day has 2 slots (sunrise + sunset). */
const DAYS_PER_RUN = { VERY_SHORT_TERM: 2, SHORT_TERM: 3, LONG_TERM: 3 };

const STRATEGY_INFO = {
  SKIP_LOW_RATED: {
    label: 'Skip Low-Rated',
    description: 'Skip slots where no prior evaluation exists, or the prior star rating is below the threshold. Saves cost by not re-evaluating locations with consistently poor conditions.',
    hasParam: true,
    paramLabel: 'Min rating',
    paramMin: 1,
    paramMax: 5,
  },
  SKIP_EXISTING: {
    label: 'Skip Already-Evaluated',
    description: 'Skip slots where a forecast already exists for this location, date, and time of day (sunrise or sunset). Avoids paying for a second evaluation when one already exists.',
  },
  FORCE_IMMINENT: {
    label: 'Always Evaluate Today',
    description: 'Override any skip rules for today\'s sunrise or sunset — always get a fresh evaluation for imminent events, even if an earlier run already scored them.',
  },
  FORCE_STALE: {
    label: 'Re-evaluate Stale Data',
    description: 'Override skip rules when the existing evaluation was generated before today. Ensures forecasts are refreshed with the latest weather data, even if a prior evaluation exists.',
  },
  EVALUATE_ALL: {
    label: 'Evaluate Everything (JFDI)',
    description: 'Ignore all skip logic — evaluate every slot for every location regardless of prior data. Useful for a full refresh, but maximises API cost.',
  },
  NEXT_EVENT_ONLY: {
    label: 'Next Event Only',
    description: 'Only evaluate the single nearest upcoming solar event per location. Skips all other sunrise/sunset slots. Ideal for a last-minute check before heading out.',
  },
  SENTINEL_SAMPLING: {
    label: 'Sentinel Sampling',
    description: 'Evaluate a handful of geographic sentinel locations per region first. If all sentinels rate at or below the threshold, skip the rest of that region with canned results. Saves cost when conditions are uniformly poor across a region.',
    hasParam: true,
    paramLabel: 'Max skip rating',
    paramMin: 1,
    paramMax: 5,
  },
};

const CONFLICTS = {
  EVALUATE_ALL: ['SKIP_LOW_RATED', 'SKIP_EXISTING', 'NEXT_EVENT_ONLY', 'SENTINEL_SAMPLING'],
  SKIP_LOW_RATED: ['SKIP_EXISTING', 'EVALUATE_ALL'],
  SKIP_EXISTING: ['SKIP_LOW_RATED', 'EVALUATE_ALL'],
  FORCE_IMMINENT: [],
  FORCE_STALE: [],
  NEXT_EVENT_ONLY: ['EVALUATE_ALL'],
  SENTINEL_SAMPLING: ['EVALUATE_ALL'],
};

/**
 * Admin-only view for selecting the active evaluation model per run type
 * and configuring cost optimisation strategies.
 */
export default function ModelSelectionView() {
  const [availableModels, setAvailableModels] = useState([]);
  const [configs, setConfigs] = useState({});
  const [strategies, setStrategies] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [switching, setSwitching] = useState(false);
  const [activeTab, setActiveTab] = useState('VERY_SHORT_TERM');
  const [locationCount, setLocationCount] = useState(0);

  useEffect(() => {
    fetchModels();
  }, []);

  const fetchModels = async () => {
    try {
      setLoading(true);
      setError(null);
      const [data, locations] = await Promise.all([getAvailableModels(), fetchLocations()]);
      // available is now [{name, version}, ...] — extract names for backward compat
      const available = (data.available || []).map((m) => (typeof m === 'string' ? m : m.name));
      setAvailableModels(available);
      setConfigs(data.configs || {});
      setStrategies(data.optimisationStrategies || {});
      // Count enabled non-wildlife locations (wildlife doesn't use Claude)
      const evalLocations = (locations || []).filter(
        (l) => l.enabled !== false && !(l.locationType?.length === 1 && l.locationType[0] === 'WILDLIFE')
      );
      setLocationCount(evalLocations.length);
    } catch (err) {
      setError('Failed to load available models');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const handleModelSwitch = async (runType, model) => {
    if (model === configs[runType]) return;

    try {
      setSwitching(true);
      setError(null);
      setSuccess(null);
      const result = await setActiveModel(runType, model);
      setConfigs((prev) => ({ ...prev, [runType]: result.active }));
      const tabLabel = CONFIG_TABS.find((t) => t.key === runType)?.label || runType;
      setSuccess(`${tabLabel} model switched to ${MODEL_INFO[result.active]?.name || result.active}`);
      setTimeout(() => setSuccess(null), 3000);
    } catch (err) {
      setError(`Failed to switch model for ${runType}`);
      console.error(err);
    } finally {
      setSwitching(false);
    }
  };

  const handleStrategyToggle = async (runType, strategyType, newEnabled, paramValue = null) => {
    try {
      setError(null);
      setSuccess(null);
      const result = await updateOptimisationStrategy(runType, strategyType, newEnabled, paramValue);
      // Update local state
      setStrategies((prev) => {
        const updated = { ...prev };
        const list = (updated[runType] || []).map((s) =>
          s.strategyType === result.strategyType
            ? { ...s, enabled: result.enabled, paramValue: result.paramValue || s.paramValue }
            : s
        );
        updated[runType] = list;
        return updated;
      });
      const info = STRATEGY_INFO[strategyType];
      setSuccess(`${info?.label || strategyType} ${newEnabled ? 'enabled' : 'disabled'}`);
      setTimeout(() => setSuccess(null), 3000);
    } catch (err) {
      const msg = err.response?.data?.message || err.response?.data || `Failed to update ${strategyType}`;
      setError(typeof msg === 'string' ? msg : `Failed to update ${strategyType}`);
      console.error(err);
    }
  };

  const handleParamChange = async (runType, strategyType, newParam) => {
    try {
      setError(null);
      await updateOptimisationStrategy(runType, strategyType, true, newParam);
      setStrategies((prev) => {
        const updated = { ...prev };
        const list = (updated[runType] || []).map((s) =>
          s.strategyType === strategyType ? { ...s, paramValue: newParam } : s
        );
        updated[runType] = list;
        return updated;
      });
    } catch (err) {
      setError(`Failed to update parameter for ${strategyType}`);
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

  const activeModelForTab = configs[activeTab] || 'HAIKU';
  const tabStrategies = strategies[activeTab] || [];

  // Determine which strategies are blocked by currently-enabled ones
  const enabledTypes = tabStrategies.filter((s) => s.enabled).map((s) => s.strategyType);
  const getConflictReason = (strategyType) => {
    if (enabledTypes.includes(strategyType)) return null; // already enabled, no conflict
    const conflicts = CONFLICTS[strategyType] || [];
    const blocking = conflicts.filter((c) => enabledTypes.includes(c));
    if (blocking.length === 0) return null;
    return `Conflicts with ${blocking.map((b) => STRATEGY_INFO[b]?.label || b).join(', ')}`;
  };

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
        {CONFIG_TABS.map((tab) => (
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
                ({MODEL_INFO[configs[tab.key]]?.name || configs[tab.key] || 'Haiku'})
              </span>
            </button>
            <InfoTip text={tab.tip} />
          </span>
        ))}
      </div>

      {error && (
        <div className="bg-red-900/30 border border-red-700 text-red-400 px-4 py-3 rounded-lg text-sm">
          {error}
        </div>
      )}

      {success && (
        <div className="bg-green-900/30 border border-green-700 text-green-400 px-4 py-3 rounded-lg text-sm">
          {success}
        </div>
      )}

      {/* Model cards for the active tab */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {availableModels.map((model) => {
          const info = MODEL_INFO[model];
          if (!info) return null;
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

              <p className="text-sm text-plex-text-secondary mb-3">{info.description}</p>

              <div className="space-y-2 mb-4 text-sm text-plex-text-secondary">
                <div>
                  <span className="font-medium">Typical cost:</span>{' '}
                  <span className="text-plex-text">{toGbp(info.typicalCostUsd)}/call</span>
                  <span className="text-plex-text-muted font-normal"> (~${info.typicalCostUsd.toFixed(3)})</span>
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

      {/* Cost estimate table for the active run type */}
      {DAYS_PER_RUN[activeTab] && locationCount > 0 && (
        <div className="card border border-plex-border">
          <h3 className="font-semibold text-plex-text mb-1">
            Estimated run cost
            <span className="font-normal text-sm text-plex-text-secondary ml-2">
              ({locationCount} location{locationCount !== 1 ? 's' : ''}, {DAYS_PER_RUN[activeTab]} days, sunrise + sunset)
            </span>
          </h3>
          <p className="text-xs text-plex-text-secondary mb-3">
            {locationCount * DAYS_PER_RUN[activeTab] * 2} Claude calls per run, before optimisation strategies
          </p>
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
                {['HAIKU', 'SONNET', 'OPUS'].map((model) => {
                  const calls = locationCount * DAYS_PER_RUN[activeTab] * 2;
                  const total = (COST_PER_CALL[model] * calls).toFixed(2);
                  const isActive = model === activeModelForTab;
                  return (
                    <tr
                      key={model}
                      className={isActive ? 'text-plex-gold' : 'text-plex-text-secondary'}
                    >
                      <td className="py-1.5 pr-4 font-medium">
                        {MODEL_INFO[model].name}
                        {isActive && <span className="text-xs ml-1.5 opacity-60">(active)</span>}
                      </td>
                      <td className="py-1.5 pr-4 text-right font-mono text-xs">
                        {toGbp(COST_PER_CALL[model])}
                        <span className="text-plex-text-muted"> (~${COST_PER_CALL[model].toFixed(3)})</span>
                      </td>
                      <td className="py-1.5 text-right font-mono text-xs">
                        {toGbp(COST_PER_CALL[model] * calls)}
                        <span className="text-plex-text-muted"> (${total})</span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Cost Optimisation Strategies */}
      {tabStrategies.length > 0 && (
        <div className="card border border-plex-border">
          <h3 className="font-semibold text-plex-text mb-4">Cost Optimisation</h3>
          <div className="space-y-3">
            {tabStrategies
              .filter((s) => STRATEGY_INFO[s.strategyType])
              .map((strategy) => {
                const info = STRATEGY_INFO[strategy.strategyType];
                const conflictReason = getConflictReason(strategy.strategyType);
                const isDisabled = conflictReason !== null;

                return (
                  <div
                    key={strategy.strategyType}
                    className={`flex items-center justify-between py-2 px-3 rounded-lg ${
                      strategy.enabled
                        ? 'bg-green-900/10 border border-green-800/30'
                        : isDisabled
                          ? 'bg-plex-surface opacity-60'
                          : 'bg-plex-surface hover:bg-plex-surface-light'
                    }`}
                    data-testid={`strategy-row-${strategy.strategyType}`}
                  >
                    <div className="flex-1 min-w-0 mr-3">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-plex-text">
                          {info.label}
                        </span>
                        <InfoTip text={info.description} />
                      </div>
                      {isDisabled && (
                        <p className="text-xs text-yellow-500 mt-1">{conflictReason}</p>
                      )}
                      {/* Parameter slider for SKIP_LOW_RATED */}
                      {info.hasParam && strategy.enabled && (
                        <div className="flex items-center gap-2 mt-2">
                          <span className="text-xs text-plex-text-secondary">{info.paramLabel}:</span>
                          <div className="flex gap-1">
                            {[1, 2, 3, 4, 5].map((val) => (
                              <button
                                key={val}
                                onClick={() => handleParamChange(activeTab, strategy.strategyType, val)}
                                className={`w-7 h-7 rounded text-xs font-medium transition-colors ${
                                  (strategy.paramValue || 3) === val
                                    ? 'bg-plex-gold text-gray-900'
                                    : 'bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border'
                                }`}
                                data-testid={`param-${strategy.strategyType}-${val}`}
                              >
                                {val}
                              </button>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                    <button
                      onClick={() =>
                        handleStrategyToggle(
                          activeTab,
                          strategy.strategyType,
                          !strategy.enabled,
                          strategy.paramValue
                        )
                      }
                      disabled={isDisabled && !strategy.enabled}
                      className={`flex-shrink-0 px-3 py-1 rounded-full text-xs font-semibold transition-colors ${
                        strategy.enabled
                          ? 'bg-green-600/30 text-green-400 hover:bg-green-600/50'
                          : isDisabled
                            ? 'bg-plex-border text-plex-text-muted cursor-not-allowed'
                            : 'bg-plex-border text-plex-text-secondary hover:bg-plex-border-light'
                      }`}
                      data-testid={`strategy-toggle-${strategy.strategyType}`}
                    >
                      {strategy.enabled ? 'ON' : 'OFF'}
                    </button>
                  </div>
                );
              })}
          </div>
        </div>
      )}

      <div className="bg-plex-surface border border-plex-border rounded-lg p-4 text-sm text-plex-text-secondary">
        <p className="font-medium text-plex-text mb-2">About model configuration</p>
        <ul className="space-y-1 text-xs">
          <li>Each run type can use a different Claude model independently</li>
          <li>Use a more accurate model (Opus/Sonnet) for imminent forecasts, cheaper (Haiku) for distant ones</li>
          <li>Cost optimisation strategies control which slots are skipped to save API costs</li>
          <li>Wildlife-only locations automatically display weather data without AI evaluation</li>
          <li>Previous forecasts are preserved with their original model</li>
          <li>Haiku offers excellent value for most use cases</li>
        </ul>
      </div>
    </div>
  );
}
