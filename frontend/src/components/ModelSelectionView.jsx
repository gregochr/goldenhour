import { useEffect, useState } from 'react';
import { getAvailableModels, setActiveModel } from '../api/modelsApi.js';

const CONFIG_TABS = [
  { key: 'VERY_SHORT_TERM', label: 'Very Short-Term (T, T+1)' },
  { key: 'SHORT_TERM', label: 'Short-Term (T, T+1, T+2)' },
  { key: 'LONG_TERM', label: 'Long-Term (T+3 \u2013 T+7)' },
];

const MODEL_INFO = {
  HAIKU: {
    name: 'Haiku',
    description: 'Fast, cost-efficient model. Returns 1-5 star rating.',
    costPerRun: '~\u00a32-3',
    speed: 'Fast',
    recommended: true,
  },
  SONNET: {
    name: 'Sonnet',
    description: 'Advanced model. Returns detailed 0-100 scores for fiery sky and golden hour potential.',
    costPerRun: '~\u00a36',
    speed: 'Moderate',
    recommended: false,
  },
  OPUS: {
    name: 'Opus',
    description: 'Highest accuracy model. Returns detailed 0-100 scores with the deepest reasoning.',
    costPerRun: '~\u00a330',
    speed: 'Slower',
    recommended: false,
  },
};

/**
 * Admin-only view for selecting the active evaluation model per run type.
 * Shows three sub-tabs (Very Short-Term, Short-Term, Long-Term) each with
 * an independent model picker.
 */
export default function ModelSelectionView() {
  const [availableModels, setAvailableModels] = useState([]);
  const [configs, setConfigs] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [switching, setSwitching] = useState(false);
  const [activeTab, setActiveTab] = useState('VERY_SHORT_TERM');

  useEffect(() => {
    fetchModels();
  }, []);

  const fetchModels = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await getAvailableModels();
      setAvailableModels(data.available || []);
      setConfigs(data.configs || {});
    } catch (err) {
      setError('Failed to load available models');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const handleModelSwitch = async (configType, model) => {
    if (model === configs[configType]) return;

    try {
      setSwitching(true);
      setError(null);
      setSuccess(null);
      const result = await setActiveModel(configType, model);
      setConfigs((prev) => ({ ...prev, [configType]: result.active }));
      const tabLabel = CONFIG_TABS.find((t) => t.key === configType)?.label || configType;
      setSuccess(`${tabLabel} model switched to ${MODEL_INFO[result.active]?.name || result.active}`);
      setTimeout(() => setSuccess(null), 3000);
    } catch (err) {
      setError(`Failed to switch model for ${configType}`);
      console.error(err);
    } finally {
      setSwitching(false);
    }
  };

  if (loading) {
    return (
      <div className="card border border-gray-800">
        <p className="text-gray-400">Loading models...</p>
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

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-bold text-gray-100 mb-2">Model Configuration</h2>
        <p className="text-sm text-gray-400 mb-4">
          Choose which Claude model to use for each forecast run type.
        </p>
      </div>

      {/* Config type sub-tabs */}
      <div className="inline-flex rounded-lg border border-gray-700 bg-gray-900 p-0.5 gap-0.5 self-start flex-wrap">
        {CONFIG_TABS.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`px-4 py-1.5 text-sm font-medium rounded-md transition-colors ${
              activeTab === tab.key
                ? 'bg-gray-700 text-gray-100'
                : 'text-gray-500 hover:text-gray-300'
            }`}
            data-testid={`config-tab-${tab.key}`}
          >
            {tab.label}
            <span className="ml-2 text-xs opacity-60">
              ({MODEL_INFO[configs[tab.key]]?.name || configs[tab.key] || 'Haiku'})
            </span>
          </button>
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
                  ? 'border-orange-500 bg-orange-900/10'
                  : 'border-gray-700 hover:border-gray-600'
              }`}
            >
              <div className="flex items-start justify-between mb-3">
                <div>
                  <h3 className="text-lg font-semibold text-gray-100">{info.name}</h3>
                  {info.recommended && (
                    <span className="text-xs bg-orange-600/30 text-orange-300 px-2 py-1 rounded mt-1 inline-block">
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

              <p className="text-sm text-gray-300 mb-3">{info.description}</p>

              <div className="space-y-2 mb-4 text-sm text-gray-400">
                <div>
                  <span className="font-medium">Cost per run:</span> {info.costPerRun}
                </div>
                <div>
                  <span className="font-medium">Speed:</span> {info.speed}
                </div>
              </div>

              <button
                onClick={() => handleModelSwitch(activeTab, model)}
                disabled={isActive || switching}
                className={`w-full py-2 px-3 rounded-lg text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-gray-700 text-gray-500 cursor-default'
                    : 'bg-orange-600 hover:bg-orange-500 text-white disabled:opacity-50'
                }`}
                data-testid={`switch-${activeTab}-${model}`}
              >
                {isActive ? 'Active' : switching ? 'Switching...' : `Switch to ${info.name}`}
              </button>
            </div>
          );
        })}
      </div>

      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-sm text-gray-300">
        <p className="font-medium text-gray-100 mb-2">About model configuration</p>
        <ul className="space-y-1 text-xs">
          <li>Each run type can use a different Claude model independently</li>
          <li>Use a more accurate model (Opus/Sonnet) for imminent forecasts, cheaper (Haiku) for distant ones</li>
          <li>Wildlife-only locations automatically display weather data without AI evaluation</li>
          <li>Previous forecasts are preserved with their original model</li>
          <li>Haiku offers excellent value for most use cases</li>
        </ul>
      </div>
    </div>
  );
}
