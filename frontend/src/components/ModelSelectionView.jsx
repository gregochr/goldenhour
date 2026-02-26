import { useEffect, useState } from 'react';
import { getAvailableModels, setActiveModel } from '../api/modelsApi.js';

/**
 * Admin-only view for selecting the active evaluation model.
 * Shows available models and allows switching between them.
 */
export default function ModelSelectionView() {
  const [availableModels, setAvailableModels] = useState([]);
  const [activeModel, setActiveModelLocal] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);
  const [switching, setSwitching] = useState(false);

  useEffect(() => {
    fetchModels();
  }, []);

  const fetchModels = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await getAvailableModels();
      setAvailableModels(data.available || []);
      setActiveModelLocal(data.active);
    } catch (err) {
      setError('Failed to load available models');
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const handleModelSwitch = async (model) => {
    if (model === activeModel) return; // Already active

    try {
      setSwitching(true);
      setError(null);
      setSuccess(false);
      const result = await setActiveModel(model);
      setActiveModelLocal(result.active);
      setSuccess(true);
      setTimeout(() => setSuccess(false), 3000); // Hide success message after 3s
    } catch (err) {
      setError(`Failed to switch to ${model}`);
      console.error(err);
    } finally {
      setSwitching(false);
    }
  };

  const modelInfo = {
    HAIKU: {
      name: 'Haiku',
      description: 'Fast, cost-efficient model. Returns 1-5 star rating.',
      costPerRun: '~£2-3',
      speed: 'Fast',
      recommended: true,
    },
    SONNET: {
      name: 'Sonnet',
      description: 'Advanced model. Returns detailed 0-100 scores for fiery sky and golden hour potential.',
      costPerRun: '~£6',
      speed: 'Moderate',
      recommended: false,
    },
  };

  if (loading) {
    return (
      <div className="card border border-gray-800">
        <p className="text-gray-400">Loading models...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="card border border-red-800 bg-red-900/20">
        <p className="text-red-400 font-medium mb-2">⚠️ Error loading models</p>
        <p className="text-red-300 text-sm">{error}</p>
      </div>
    );
  }

  if (availableModels.length === 0) {
    return (
      <div className="card border border-gray-800">
        <p className="text-gray-400">No models available</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-bold text-gray-100 mb-2">Evaluation Model</h2>
        <p className="text-sm text-gray-400 mb-4">
          Choose which Claude model to use for sunrise/sunset evaluations.
        </p>
      </div>

      {error && (
        <div className="bg-red-900/30 border border-red-700 text-red-400 px-4 py-3 rounded-lg text-sm">
          {error}
        </div>
      )}

      {success && (
        <div className="bg-green-900/30 border border-green-700 text-green-400 px-4 py-3 rounded-lg text-sm">
          Model switched successfully!
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {availableModels.map((model) => {
          const info = modelInfo[model];
          const isActive = model === activeModel;

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
                onClick={() => handleModelSwitch(model)}
                disabled={isActive || switching}
                className={`w-full py-2 px-3 rounded-lg text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-gray-700 text-gray-500 cursor-default'
                    : 'bg-orange-600 hover:bg-orange-500 text-white disabled:opacity-50'
                }`}
              >
                {isActive ? 'Active' : switching ? 'Switching...' : `Switch to ${info.name}`}
              </button>
            </div>
          );
        })}
      </div>

      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-sm text-gray-300">
        <p className="font-medium text-gray-100 mb-2">ℹ️ About model selection</p>
        <ul className="space-y-1 text-xs">
          <li>• Changing the model affects future forecast runs for colour/landscape locations</li>
          <li>• Wildlife-only locations automatically display weather data without AI evaluation</li>
          <li>• Previous forecasts are preserved with their original model</li>
          <li>• You can later compare forecasts between models using the same weather data</li>
          <li>• Haiku offers excellent value for most use cases</li>
        </ul>
      </div>
    </div>
  );
}
