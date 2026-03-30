import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { getAuroraForecastPreview, runAuroraForecast } from '../api/auroraApi';
import Modal from './shared/Modal.jsx';

/** Rough cost estimate per Claude call in USD (Haiku model). */
const COST_PER_NIGHT_USD = 0.01;

/**
 * Night selector modal for manual aurora forecast runs.
 *
 * Loads the 3-night Kp preview from the server, pre-selects recommended nights
 * (Kp ≥ threshold), and lets the user choose which nights to generate forecasts for.
 * Shows an estimated Claude API cost before the user commits.
 *
 * @param {function} onClose  - called when the user cancels or after a successful run
 * @param {function} onComplete - called with the run response after a successful run
 */
function AuroraForecastModal({ onClose, onComplete }) {
  const [preview, setPreview] = useState(null);
  const [selectedNights, setSelectedNights] = useState(new Set());
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState(null);

  // Fetch the 3-night preview on mount
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    getAuroraForecastPreview()
      .then((data) => {
        if (cancelled) return;
        setPreview(data);
        // Pre-select recommended nights
        const preSelected = new Set(
          data.nights.filter((n) => n.recommended).map((n) => n.date),
        );
        setSelectedNights(preSelected);
      })
      .catch(() => {
        if (!cancelled) setError('Failed to load aurora forecast preview. Please try again.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, []);

  function toggleNight(date) {
    setSelectedNights((prev) => {
      const next = new Set(prev);
      if (next.has(date)) {
        next.delete(date);
      } else {
        next.add(date);
      }
      return next;
    });
  }

  async function handleRun() {
    if (selectedNights.size === 0) return;
    setRunning(true);
    setError(null);
    try {
      const nights = Array.from(selectedNights).sort();
      const result = await runAuroraForecast(nights);
      onComplete(result);
      onClose();
    } catch {
      setError('Aurora forecast run failed. Check the logs for details.');
      setRunning(false);
    }
  }

  const selectedCount = selectedNights.size;
  const estimatedCost = selectedCount > 0
    ? `~$${(selectedCount * COST_PER_NIGHT_USD).toFixed(2)}`
    : null;

  return (
    <Modal label="Aurora forecast night selector" onClose={onClose} bare data-testid="aurora-forecast-modal">
      <div className="relative w-full max-w-md bg-plex-surface border border-plex-border rounded-xl shadow-2xl z-10">
        {/* Header */}
        <div className="px-5 py-4 border-b border-plex-border">
          <h2 className="text-base font-semibold text-plex-text">🌌 Aurora Forecast Run</h2>
          <p className="text-xs text-plex-text-muted mt-1">
            Select which nights to generate aurora forecasts. Each night uses one Claude API call.
          </p>
        </div>

        {/* Body */}
        <div className="px-5 py-4 space-y-3 min-h-[160px]">
          {loading && (
            <p className="text-sm text-plex-text-muted text-center py-6">
              Loading forecast preview…
            </p>
          )}
          {error && !loading && (
            <p className="text-sm text-red-400" data-testid="aurora-modal-error">{error}</p>
          )}
          {!loading && !error && preview && preview.nights.map((night) => {
            const isSelected = selectedNights.has(night.date);
            const isRecommended = night.recommended;
            const isSimulated = preview.simulated === true;
            return (
              <label
                key={night.date}
                data-testid={`night-row-${night.date}`}
                className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-colors ${
                  isSelected
                    ? isRecommended
                      ? 'bg-indigo-900/30 border-indigo-500/50'
                      : 'bg-plex-border/30 border-plex-border-light'
                    : 'bg-plex-bg border-plex-border hover:bg-plex-border/20'
                }`}
              >
                <input
                  type="checkbox"
                  checked={isSelected}
                  onChange={() => toggleNight(night.date)}
                  className="mt-0.5 accent-indigo-500"
                  data-testid={`night-checkbox-${night.date}`}
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`text-sm font-medium ${isRecommended ? 'text-indigo-200' : 'text-plex-text'}`}>
                      {night.label}
                    </span>
                    {night.gScale && (
                      <span className="px-1.5 py-0.5 text-xs font-medium rounded bg-indigo-800/60 text-indigo-300 border border-indigo-600/40">
                        {night.gScale}
                      </span>
                    )}
                    {isSimulated && (
                      <span
                        className="px-1.5 py-0.5 text-xs font-medium rounded bg-amber-900/40 text-amber-300 border border-amber-600/40"
                        title="Using simulated geomagnetic data"
                      >
                        🧪 SIM
                      </span>
                    )}
                  </div>
                  <p className={`text-xs mt-0.5 ${isRecommended ? 'text-indigo-400' : 'text-plex-text-muted'}`}>
                    {night.summary}
                    {night.eligibleLocations > 0 && (
                      <span className="ml-2 text-plex-text-muted">
                        · {night.eligibleLocations} location{night.eligibleLocations !== 1 ? 's' : ''}
                      </span>
                    )}
                  </p>
                  {!isRecommended && !isSimulated && (
                    <p className="text-xs text-plex-text-muted/60 mt-0.5">Not recommended — quiet conditions</p>
                  )}
                </div>
              </label>
            );
          })}
          {!loading && !error && preview?.simulated && (
            <p className="text-xs text-amber-400 bg-amber-900/20 border border-amber-600/30 rounded px-3 py-2">
              ⚠️ Using simulated geomagnetic data. Weather and lunar data are real.
            </p>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-4 border-t border-plex-border flex items-center justify-between gap-3">
          <div className="text-xs text-plex-text-muted">
            {selectedCount === 0 ? (
              <span>No nights selected</span>
            ) : (
              <span data-testid="aurora-modal-cost">
                {selectedCount} night{selectedCount !== 1 ? 's' : ''} selected · Est. cost: {estimatedCost}
              </span>
            )}
          </div>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              disabled={running}
              className="px-4 py-1.5 text-sm border border-plex-border text-plex-text-secondary rounded-lg hover:bg-plex-border/30 transition-colors disabled:opacity-50"
              data-testid="aurora-modal-cancel"
            >
              Cancel
            </button>
            <button
              onClick={handleRun}
              disabled={selectedCount === 0 || running}
              className="px-4 py-1.5 text-sm font-medium bg-indigo-700 hover:bg-indigo-600 text-white rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              data-testid="aurora-modal-run"
            >
              {running ? '⟳ Running…' : 'Run Forecast'}
            </button>
          </div>
        </div>
      </div>
    </Modal>
  );
}

AuroraForecastModal.propTypes = {
  onClose: PropTypes.func.isRequired,
  onComplete: PropTypes.func.isRequired,
};

export default AuroraForecastModal;
