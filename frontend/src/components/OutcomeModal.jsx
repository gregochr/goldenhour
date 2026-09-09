import React, { useActionState, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { recordOutcome } from '../api/forecastApi.js';
import Modal from './shared/Modal.jsx';

/** How long the "saved" confirmation shows before the dialog hands back to its opener. */
const SAVED_HANDOFF_MS = 1500;

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
  const [saved, setSaved] = useState(false);
  const savedTimer = useRef(null);
  const mounted = useRef(true);

  /**
   * Cancels the pending hand-off if the dialog goes away first, and refuses to arm one at all once
   * it has.
   *
   * <p>⚠️ The guard carries this, not the cleanup. The timer is armed *after* {@code recordOutcome}
   * resolves, so unmounting while the save is in flight — Cancel stays enabled during
   * {@code isPending}, and the backdrop closes too — runs the cleanup against a still-null ref and
   * the continuation then arms a timer nothing owns. That is the same fire-after-teardown class the
   * change set out to remove — though not the same symptom: {@code onSaved} is caller-supplied, so
   * firing it late reads no {@code window} and throws nothing. The measured unhandled error came
   * from {@code ModelSelectionView}'s {@code setSuccess}, never from here.
   *
   * <p>⚠️ This component has **no production render site** — outcome recording has been API-only
   * since 2026-02-27 (see CLAUDE.md and {@code v1-retirement-plan.md} §8), and the only
   * {@code onSaved} that has ever run is a test double. So the defect fixed here is latent, and
   * whether a manual Close inside the confirmation window should still notify the opener is an open
   * product question for whoever gives this component a caller — deliberately not decided here.
   */
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (savedTimer.current) clearTimeout(savedTimer.current);
    };
  }, []);

  const [saveError, submitAction, isPending] = useActionState(async () => {
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
      if (mounted.current) {
        savedTimer.current = setTimeout(onSaved, SAVED_HANDOFF_MS);
      }
      return null;
    } catch (err) {
      return err.response?.data?.message || err.message || 'Failed to save outcome.';
    }
  }, null);

  return (
    <Modal label={`Record ${type.charAt(0) + type.slice(1).toLowerCase()} Outcome — ${date}`} onClose={onClose} bare>
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
          <form action={submitAction} className="flex flex-col gap-4">
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
                disabled={isPending}
              >
                {isPending ? 'Saving…' : 'Save outcome'}
              </button>
            </div>
          </form>
        )}
      </div>
    </Modal>
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
