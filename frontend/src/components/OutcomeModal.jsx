import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { recordOutcome } from '../api/forecastApi.js';

const MAX_RATING = 5;

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
  const [actualRating, setActualRating] = useState(null);
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
        date,
        type,
        wentOut: wentOut === 'yes',
        actualRating: actualRating ? Number(actualRating) : null,
        notes,
      });
      setSaved(true);
      onSaved();
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
        <h2 id="outcome-modal-title" className="text-lg font-semibold text-gray-100 mb-4">
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
              <legend className="text-sm text-gray-400 mb-2">Did you go out?</legend>
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

            <fieldset>
              <legend className="text-sm text-gray-400 mb-2">Your rating (1–5 stars)</legend>
              <div className="flex gap-2">
                {Array.from({ length: MAX_RATING }).map((_, i) => {
                  const val = String(i + 1);
                  return (
                    <button
                      key={val}
                      type="button"
                      data-testid={`actual-rating-${val}`}
                      className={`btn flex-1 text-base ${
                        actualRating === val ? 'bg-amber-600 text-white' : 'btn-secondary'
                      }`}
                      onClick={() => setActualRating(val)}
                      aria-label={`${val} star${i > 0 ? 's' : ''}`}
                    >
                      {i < (actualRating ? Number(actualRating) : 0) ? '★' : '☆'}
                    </button>
                  );
                })}
              </div>
            </fieldset>

            <div>
              <label htmlFor="outcome-notes" className="text-sm text-gray-400 block mb-1">
                Notes (optional)
              </label>
              <textarea
                id="outcome-notes"
                data-testid="outcome-notes"
                className="w-full bg-gray-800 border border-gray-700 rounded-lg p-2 text-sm text-gray-100 resize-none focus:outline-none focus:ring-2 focus:ring-orange-500"
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
