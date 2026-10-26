import { useEffect, useRef, useState } from 'react';
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
 * Operations: the admin setting for how many sub-regions a Lite reader may pick (sub-regions-plan D5, S2).
 * Shows the saved value, says how many Lite readers hold more picks than a lowered value BEFORE it is saved
 * (they keep them until they next change), and saves. Admin only: the tab that mounts it is.
 */
export default function SubRegionPolicyCard() {
  const [policy, setPolicy] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [draft, setDraft] = useState('');
  const [overLimit, setOverLimit] = useState(0);
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
        setOverLimit(loaded.liteUsersOverLimit);
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

  function handleChange(event) {
    const text = event.target.value;
    setDraft(text);
    setSavedNote('');
    setSaveError('');
    latestDraft.current = text;
    const next = parseLimit(text, policy.maxLimit);
    if (next === null || next === policy.liteLimit) {
      setOverLimit(next === null ? 0 : policy.liteUsersOverLimit);
      return;
    }
    getSubRegionPolicy(next)
      .then((preview) => {
        // A slower answer for an earlier keystroke must not overwrite the count for the current one.
        if (latestDraft.current === text) setOverLimit(preview.liteUsersOverLimit);
      })
      .catch(() => {
        if (latestDraft.current === text) setOverLimit(0);
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
      setOverLimit(saved.liteUsersOverLimit);
      setSavedNote(`Saved: Lite readers may now pick up to ${saved.liteLimit}.`);
    } catch (err) {
      setSaveError(apiErrorMessage(err, 'Could not save the sub-region limit.'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card flex flex-col gap-3" data-testid="sub-region-policy">
      <p className="text-sm font-semibold text-plex-text">Lite sub-region picks</p>
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
            className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-hidden focus:ring-1 focus:ring-plex-gold"
          />
        </div>
        <button
          type="button"
          className="btn-primary text-sm disabled:opacity-50 disabled:cursor-not-allowed"
          onClick={handleSave}
          disabled={!changed || saving}
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
      </div>
      {candidate === null && (
        <p className="text-xs text-red-400" role="alert">
          Enter a whole number from 1 to {policy.maxLimit}.
        </p>
      )}
      {candidate !== null && overLimit > 0 && (
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
