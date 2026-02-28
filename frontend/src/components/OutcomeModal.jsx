import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { recordOutcome } from '../api/forecastApi.js';

/**
 * Modal dialog for recording an actual observed sunrise/sunset outcome.
 *
 * @param {object} props
 * @param {string} props.date - Target date (YYYY-MM-DD).
 * @param {string} props.type - SUNRISE or SUNSET.
 * @param {number} props.locationLat - Latitude.
 * @param {number} props.locationLon - Longitude.
 * @param {string} props.locationName - Human-readable location name.
 * @param {function} props.onClose - Called when the modal should close.
 * @param {function} props.onSaved - Called after a successful save.
 */
export default function OutcomeModal({
  date,
  type,
  locationLat,
  locationLon,
  locationName,
  onClose,
  onSaved,
}) {
  const [wentOut, setWentOut] = useState(null);
  const [fierySkyActual, setFierySkyActual] = useState('');
  const [goldenHourActual, setGoldenHourActual] = useState('');
  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setSaveError(null);
    try {
      await recordOutcome({
        locationLat,
        locationLon,
        locationName,
        outcomeDate: date,
        targetType: type,
        wentOut: wentOut === 'yes',
        fierySkyActual: fierySkyActual !== '' ? Number(fierySkyActual) : null,
        goldenHourActual: goldenHourActual !== '' ? Number(goldenHourActual) : null,
        notes,
      });
      setSaved(true);
      setTimeout(onSaved, 1500);
    } catch (err) {
      setSaveError(err.response?.data?.message || err.message || 'Failed to save outcome.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      role="dialog"
      aria-modal="true"
      aria-labelledby="outcome-modal-title"
    >
      <div data-testid="outcome-form" className="card w-full max-w-md mx-4">
        <h2 id="outcome-modal-title" className="text-lg font-semibold text-plex-text mb-4">
          Record {type.charAt(0) + type.slice(1).toLowerCase()} Outcome — {date}
        </h2>

        {saved ? (
          <div className="text-center py-4">
            <p className="text-green-400 font-medium text-lg" data-testid="outcome-saved-message">
              Outcome saved
            </p>
            <button className="btn-secondary mt-4" onClick={onClose}>
              Close
            </button>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <fieldset>
              <legend className="text-sm text-plex-text-secondary mb-2">Did you go out?</legend>
              <div className="flex gap-3">
                <button
                  type="button"
                  data-testid="went-out-yes"
                  className={`btn flex-1 ${wentOut === 'yes' ? 'bg-green-700 text-white' : 'btn-secondary'}`}
                  onClick={() => setWentOut('yes')}
                >
                  Yes
                </button>
                <button
                  type="button"
                  data-testid="went-out-no"
                  className={`btn flex-1 ${wentOut === 'no' ? 'bg-red-700 text-white' : 'btn-secondary'}`}
                  onClick={() => setWentOut('no')}
                >
                  No
                </button>
              </div>
            </fieldset>

            <div>
              <label htmlFor="fiery-sky-actual" className="text-sm text-plex-text-secondary block mb-1">
                Fiery Sky (0–100)
              </label>
              <input
                id="fiery-sky-actual"
                data-testid="fiery-sky-actual"
                type="range"
                min="0"
                max="100"
                value={fierySkyActual !== '' ? fierySkyActual : 0}
                onChange={(e) => setFierySkyActual(e.target.value)}
                className="w-full accent-[#E5A00D]"
              />
              <div className="flex justify-between text-xs text-plex-text-muted mt-0.5">
                <span>0</span>
                <span className="font-semibold text-plex-text">
                  {fierySkyActual !== '' ? fierySkyActual : '—'}
                </span>
                <span>100</span>
              </div>
            </div>

            <div>
              <label htmlFor="golden-hour-actual" className="text-sm text-plex-text-secondary block mb-1">
                Golden Hour (0–100)
              </label>
              <input
                id="golden-hour-actual"
                data-testid="golden-hour-actual"
                type="range"
                min="0"
                max="100"
                value={goldenHourActual !== '' ? goldenHourActual : 0}
                onChange={(e) => setGoldenHourActual(e.target.value)}
                className="w-full accent-[#E5A00D]"
              />
              <div className="flex justify-between text-xs text-plex-text-muted mt-0.5">
                <span>0</span>
                <span className="font-semibold text-plex-text">
                  {goldenHourActual !== '' ? goldenHourActual : '—'}
                </span>
                <span>100</span>
              </div>
            </div>

            <div>
              <label htmlFor="outcome-notes" className="text-sm text-plex-text-secondary block mb-1">
                Notes (optional)
              </label>
              <textarea
                id="outcome-notes"
                data-testid="outcome-notes"
                className="w-full bg-plex-surface-light border border-plex-border rounded-lg p-2 text-sm text-plex-text resize-none focus:outline-none focus:ring-2 focus:ring-plex-gold"
                rows={3}
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="Beautiful warm light on the cathedral..."
              />
            </div>

            {saveError && (
              <p className="text-red-400 text-sm" role="alert">
                {saveError}
              </p>
            )}

            <div className="flex gap-3 justify-end">
              <button type="button" className="btn-secondary" onClick={onClose}>
                Cancel
              </button>
              <button
                type="submit"
                data-testid="outcome-submit"
                className="btn-primary"
                disabled={saving}
              >
                {saving ? 'Saving…' : 'Save outcome'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

OutcomeModal.propTypes = {
  date: PropTypes.string.isRequired,
  type: PropTypes.oneOf(['SUNRISE', 'SUNSET']).isRequired,
  locationLat: PropTypes.number.isRequired,
  locationLon: PropTypes.number.isRequired,
  locationName: PropTypes.string.isRequired,
  onClose: PropTypes.func.isRequired,
  onSaved: PropTypes.func.isRequired,
};
