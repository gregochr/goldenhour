import { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getSubRegionPolicy, saveSubRegionPolicy } from '../api/subRegionPolicyApi.js';
import { apiErrorMessage } from '../utils/apiError.js';
import ErrorBanner from './shared/ErrorBanner.jsx';

/**
 * Parses the number box: a whole number from 1 to `max`, or null when it is not one.
 *
 * @param {string} text the box's text
 * @param {number} max the largest limit that can be saved
 * @returns {number|null}
 */
function parseLimit(text, max) {
  if (!/^\d+$/.test(text.trim())) return null;
  const value = Number(text);
  return value >= 1 && value <= max ? value : null;
}

/**
 * The admin setting for how many sub-regions a Lite reader may pick (sub-regions-plan D5, S2).
 * Shows the saved value, says how many Lite readers hold more picks than a lowered value BEFORE it is saved
 * (they keep them until they next change), and saves. Admin only: it lives in the Lite limit dialog on
 * Manage → Data → Users, which only the Manage screen (admin) mounts.
 *
 * @param {object} props
 * @param {(saved: object) => void} [props.onSaved] called with the saved policy after a successful save only
 * @param {boolean} [props.bare] drop the card chrome and the title, for a host (the dialog) that supplies both
 */
export default function SubRegionPolicyCard({ onSaved, bare = false }) {
  const [policy, setPolicy] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [draft, setDraft] = useState('');
  // The readers-affected check for the current draft: { state: 'ready' | 'checking' | 'failed', count }.
  const [preview, setPreview] = useState({ state: 'ready', count: 0 });
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [savedNote, setSavedNote] = useState('');
  const latestDraft = useRef('');

  useEffect(() => {
    let cancelled = false;
    getSubRegionPolicy()
      .then((loaded) => {
        if (cancelled) return;
        setPolicy(loaded);
        setDraft(String(loaded.liteLimit));
        latestDraft.current = String(loaded.liteLimit);
        setPreview({ state: 'ready', count: loaded.liteUsersOverLimit });
      })
      .catch((err) => {
        if (!cancelled) setLoadError(apiErrorMessage(err, 'Could not load the sub-region limit.'));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loadError) {
    return <ErrorBanner message={loadError} data-testid="sub-region-policy-error" />;
  }
  if (!policy) {
    return <p className="text-sm text-plex-text-muted animate-pulse">Loading sub-region limit...</p>;
  }

  const candidate = parseLimit(draft, policy.maxLimit);
  const changed = candidate !== null && candidate !== policy.liteLimit;
  const overLimit = preview.count;

  function handleChange(event) {
    const text = event.target.value;
    setDraft(text);
    setSavedNote('');
    setSaveError('');
    latestDraft.current = text;
    const next = parseLimit(text, policy.maxLimit);
    if (next === null || next === policy.liteLimit) {
      setPreview({ state: 'ready', count: next === null ? 0 : policy.liteUsersOverLimit });
      return;
    }
    setPreview({ state: 'checking', count: 0 });
    getSubRegionPolicy(next)
      .then((answer) => {
        // A slower answer for an earlier keystroke must not overwrite the count for the current one.
        if (latestDraft.current === text) setPreview({ state: 'ready', count: answer.liteUsersOverLimit });
      })
      .catch(() => {
        if (latestDraft.current === text) setPreview({ state: 'failed', count: 0 });
      });
  }

  async function handleSave() {
    if (!changed) return;
    setSaving(true);
    setSaveError('');
    setSavedNote('');
    try {
      const saved = await saveSubRegionPolicy(candidate);
      setPolicy(saved);
      setDraft(String(saved.liteLimit));
      latestDraft.current = String(saved.liteLimit);
      setPreview({ state: 'ready', count: saved.liteUsersOverLimit });
      setSavedNote(`Saved: Lite readers may now pick up to ${saved.liteLimit}.`);
      if (onSaved) onSaved(saved);
    } catch (err) {
      setSaveError(apiErrorMessage(err, 'Could not save the sub-region limit.'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={`${bare ? '' : 'card '}flex flex-col gap-3`} data-testid="sub-region-policy">
      {!bare && <p className="text-sm font-semibold text-plex-text">Lite sub-region picks</p>}
      <p className="text-xs text-plex-text-secondary">
        How many sub-regions a Lite reader may choose. Pro and Admin readers see every sub-region and never choose.
      </p>
      <p className="text-sm text-plex-text" data-testid="sub-region-policy-current">
        Current limit: {policy.liteLimit}
      </p>
      <div className="flex items-end gap-3">
        <div className="w-32">
          <label htmlFor="sub-region-limit" className="block text-xs text-plex-text-secondary mb-1">
            Picks per Lite reader
          </label>
          <input
            id="sub-region-limit"
            type="number"
            min={1}
            max={policy.maxLimit}
            value={draft}
            onChange={handleChange}
            disabled={saving}
            className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-hidden focus:ring-1 focus:ring-plex-gold"
          />
        </div>
        <button
          type="button"
          className="btn-primary text-sm disabled:opacity-50 disabled:cursor-not-allowed"
          onClick={handleSave}
          disabled={!changed || saving || preview.state !== 'ready'}
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
      </div>
      {candidate === null && (
        <p className="text-xs text-red-400" role="alert">
          Enter a whole number from 1 to {policy.maxLimit}.
        </p>
      )}
      {candidate !== null && preview.state === 'checking' && (
        <p className="text-xs text-plex-text-muted" role="status" data-testid="sub-region-policy-checking">
          Checking…
        </p>
      )}
      {candidate !== null && preview.state === 'failed' && (
        <p className="text-xs text-red-400" role="alert" data-testid="sub-region-policy-check-failed">
          Couldn&apos;t check how many readers are affected.
        </p>
      )}
      {candidate !== null && preview.state === 'ready' && overLimit > 0 && (
        <p className="text-xs text-amber-400" role="status" data-testid="sub-region-policy-warning">
          {overLimit} Lite {overLimit === 1 ? 'reader holds' : 'readers hold'} more than {candidate}{' '}
          {candidate === 1 ? 'pick' : 'picks'}. They keep them until they next change.
        </p>
      )}
      {savedNote && (
        <p className="text-xs text-green-400" role="status" data-testid="sub-region-policy-saved">
          {savedNote}
        </p>
      )}
      <ErrorBanner message={saveError} data-testid="sub-region-policy-save-error" />
    </div>
  );
}

SubRegionPolicyCard.propTypes = {
  onSaved: PropTypes.func,
  bare: PropTypes.bool,
};
