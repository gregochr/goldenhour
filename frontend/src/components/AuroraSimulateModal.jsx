import React, { useState } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';
import { simulateAurora, clearSimulation } from '../api/auroraApi';

const PRESETS = [
  { label: 'Moderate G2', kp: 6.0, ovationProbability: 30, bzNanoTesla: -8.0, gScale: 'G2' },
  { label: 'Strong G3', kp: 7.0, ovationProbability: 45, bzNanoTesla: -12.0, gScale: 'G3' },
  { label: 'Extreme G5', kp: 9.0, ovationProbability: 80, bzNanoTesla: -25.0, gScale: 'G5' },
];

const G_SCALE_OPTIONS = ['', 'G1', 'G2', 'G3', 'G4', 'G5'];

/**
 * Admin-only modal for injecting a simulated aurora event into the state machine.
 *
 * Activating a simulation puts the aurora banner and forecast-run UI into the
 * "active" state using fake NOAA values. No Claude API calls are made until the
 * admin explicitly runs a Forecast Run via the Forecast Runs panel.
 *
 * @param {boolean}  isActive  - whether a simulation is currently active
 * @param {function} onClose   - called when the user dismisses the modal
 * @param {function} onSuccess - called after a successful simulate or clear action
 */
function AuroraSimulateModal({ isActive, onClose, onSuccess }) {
  const [form, setForm] = useState({ kp: 7.0, ovationProbability: 45, bzNanoTesla: -12.0, gScale: 'G3' });
  const [submitting, setSubmitting] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [error, setError] = useState(null);

  function applyPreset(preset) {
    setForm({ kp: preset.kp, ovationProbability: preset.ovationProbability, bzNanoTesla: preset.bzNanoTesla, gScale: preset.gScale });
  }

  function handleChange(field, value) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  async function handleSimulate() {
    setSubmitting(true);
    setError(null);
    try {
      await simulateAurora({
        kp: parseFloat(form.kp),
        ovationProbability: parseFloat(form.ovationProbability),
        bzNanoTesla: parseFloat(form.bzNanoTesla),
        gScale: form.gScale || null,
      });
      onSuccess('Simulation activated. The aurora banner will appear shortly.');
      onClose();
    } catch {
      setError('Failed to activate simulation. Check the server logs.');
      setSubmitting(false);
    }
  }

  async function handleClear() {
    setClearing(true);
    setError(null);
    try {
      await clearSimulation();
      onSuccess('Simulation cleared.');
      onClose();
    } catch {
      setError('Failed to clear simulation.');
      setClearing(false);
    }
  }

  return (
    <Modal label="Aurora simulation controls" onClose={onClose} bare data-testid="aurora-simulate-modal">
      {/* Modal panel */}
      <div className="relative w-full max-w-md bg-plex-surface border border-plex-border rounded-xl shadow-2xl z-10">
        {/* Header */}
        <div className="px-5 py-4 border-b border-plex-border">
          <h2 className="text-base font-semibold text-plex-text">🧪 Simulate Aurora Event</h2>
          <p className="text-xs text-plex-text-muted mt-1">
            Admin only. Injects fake NOAA data to test the aurora UI end-to-end.
          </p>
        </div>

        {/* Body */}
        <div className="px-5 py-4 space-y-4">
          {/* Presets */}
          <div>
            <p className="text-xs text-plex-text-muted mb-2">Quick presets:</p>
            <div className="flex gap-2 flex-wrap">
              {PRESETS.map((p) => (
                <button
                  key={p.label}
                  onClick={() => applyPreset(p)}
                  className="px-2.5 py-1 text-xs border border-indigo-500/50 bg-indigo-900/20 text-indigo-300 rounded hover:bg-indigo-800/40 transition-colors"
                  data-testid={`simulate-preset-${p.gScale}`}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          {/* Form fields */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs text-plex-text-muted mb-1">Kp index</label>
              <input
                type="number"
                min="0"
                max="9"
                step="0.5"
                value={form.kp}
                onChange={(e) => handleChange('kp', e.target.value)}
                className="w-full bg-plex-bg border border-plex-border rounded px-2 py-1.5 text-sm text-plex-text focus:outline-none focus:border-indigo-500"
                data-testid="simulate-kp"
              />
            </div>
            <div>
              <label className="block text-xs text-plex-text-muted mb-1">OVATION 55°N (%)</label>
              <input
                type="number"
                min="0"
                max="100"
                step="1"
                value={form.ovationProbability}
                onChange={(e) => handleChange('ovationProbability', e.target.value)}
                className="w-full bg-plex-bg border border-plex-border rounded px-2 py-1.5 text-sm text-plex-text focus:outline-none focus:border-indigo-500"
                data-testid="simulate-ovation"
              />
            </div>
            <div>
              <label className="block text-xs text-plex-text-muted mb-1">Solar wind Bz (nT)</label>
              <input
                type="number"
                step="0.5"
                value={form.bzNanoTesla}
                onChange={(e) => handleChange('bzNanoTesla', e.target.value)}
                className="w-full bg-plex-bg border border-plex-border rounded px-2 py-1.5 text-sm text-plex-text focus:outline-none focus:border-indigo-500"
                data-testid="simulate-bz"
              />
            </div>
            <div>
              <label className="block text-xs text-plex-text-muted mb-1">G-Scale</label>
              <select
                value={form.gScale}
                onChange={(e) => handleChange('gScale', e.target.value)}
                className="w-full bg-plex-bg border border-plex-border rounded px-2 py-1.5 text-sm text-plex-text focus:outline-none focus:border-indigo-500"
                data-testid="simulate-gscale"
              >
                {G_SCALE_OPTIONS.map((g) => (
                  <option key={g} value={g}>{g || '—'}</option>
                ))}
              </select>
            </div>
          </div>

          {/* Disclaimer */}
          <p className="text-xs text-plex-text-muted bg-plex-bg border border-plex-border rounded px-3 py-2">
            This activates the aurora banner and enables the Aurora forecast type. No Claude API
            calls are made until you manually run a Forecast Run.
          </p>

          {error && (
            <p className="text-xs text-red-400" data-testid="simulate-error">{error}</p>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-4 border-t border-plex-border flex items-center justify-between gap-3">
          <div>
            {isActive && (
              <button
                onClick={handleClear}
                disabled={clearing || submitting}
                className="px-3 py-1.5 text-xs border border-amber-600/50 bg-amber-900/20 text-amber-300 rounded-lg hover:bg-amber-800/30 transition-colors disabled:opacity-50"
                data-testid="simulate-clear-btn"
              >
                {clearing ? 'Clearing…' : 'Clear Simulation'}
              </button>
            )}
          </div>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              disabled={submitting || clearing}
              className="px-4 py-1.5 text-sm border border-plex-border text-plex-text-secondary rounded-lg hover:bg-plex-border/30 transition-colors disabled:opacity-50"
              data-testid="simulate-cancel-btn"
            >
              Cancel
            </button>
            <button
              onClick={handleSimulate}
              disabled={submitting || clearing}
              className="px-4 py-1.5 text-sm font-medium bg-indigo-700 hover:bg-indigo-600 text-white rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              data-testid="simulate-submit-btn"
            >
              {submitting ? 'Activating…' : 'Simulate'}
            </button>
          </div>
        </div>
      </div>
    </Modal>
  );
}

AuroraSimulateModal.propTypes = {
  isActive: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  onSuccess: PropTypes.func.isRequired,
};

export default AuroraSimulateModal;
