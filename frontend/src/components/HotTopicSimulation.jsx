import { useState, useEffect, useCallback } from 'react';
import {
  getSimulationState,
  toggleSimulation,
  toggleTopicType,
} from '../api/hotTopicSimulationApi.js';

/**
 * Admin tool for toggling simulated Hot Topics in the Plan tab.
 *
 * Allows the admin to enable/disable simulation mode and select which
 * topic types appear as pills — without waiting for real conditions to trigger.
 */
export default function HotTopicSimulation() {
  const [state, setState] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const loadState = useCallback(async () => {
    try {
      const data = await getSimulationState();
      setState(data);
      setError(null);
    } catch {
      setError('Failed to load simulation state');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadState();
  }, [loadState]);

  const handleToggleEnabled = async () => {
    try {
      const updated = await toggleSimulation();
      setState(updated);
    } catch {
      setError('Failed to toggle simulation');
    }
  };

  const handleToggleType = async (type) => {
    try {
      const updated = await toggleTopicType(type);
      setState(updated);
    } catch {
      setError(`Failed to toggle ${type}`);
    }
  };

  if (loading) {
    return (
      <p className="text-plex-text-muted text-sm">Loading…</p>
    );
  }

  if (error) {
    return (
      <p className="text-red-400 text-sm">{error}</p>
    );
  }

  const activeCount = state.types.filter((t) => t.active).length;
  const totalCount = state.types.length;

  return (
    <div className="flex flex-col gap-5">

      {/* Master toggle */}
      <div className="flex items-center gap-4">
        <span className="text-sm text-plex-text-secondary">Simulation mode</span>
        <button
          data-testid="simulation-master-toggle"
          onClick={handleToggleEnabled}
          className={`px-4 py-1.5 rounded text-xs font-semibold transition-colors ${
            state.enabled
              ? 'bg-plex-gold text-plex-bg hover:bg-plex-gold/80'
              : 'bg-plex-surface border border-plex-border text-plex-text-muted hover:text-plex-text'
          }`}
        >
          {state.enabled ? 'ON' : 'OFF'}
        </button>
      </div>

      {/* Type list */}
      <div
        className={`flex flex-col gap-2 transition-opacity ${
          state.enabled ? 'opacity-100' : 'opacity-40 pointer-events-none'
        }`}
      >
        {state.types.map((t) => (
          <label
            key={t.type}
            className="flex items-center gap-2.5 cursor-pointer group"
            data-testid={`simulation-type-${t.type}`}
          >
            <input
              type="checkbox"
              checked={t.active}
              onChange={() => handleToggleType(t.type)}
              className="accent-plex-gold"
              data-testid={`simulation-checkbox-${t.type}`}
            />
            <span className="text-sm text-plex-text group-hover:text-plex-text">
              {t.label}
            </span>
          </label>
        ))}
      </div>

      {/* Counter and hint */}
      <div className="flex flex-col gap-1 pt-1 border-t border-plex-border">
        <p className="text-xs text-plex-text-muted">
          {state.enabled
            ? `Simulating ${activeCount} of ${totalCount} topics`
            : `${activeCount} of ${totalCount} topics selected — toggle ON to activate`}
        </p>
        {state.enabled && (
          <p className="text-xs text-plex-text-muted">
            Switch to the Plan tab to see the pills
          </p>
        )}
      </div>
    </div>
  );
}
