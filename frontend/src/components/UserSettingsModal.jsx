import { useState, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal';
import { getSettings, lookupPostcode, saveHome, refreshDriveTimes } from '../api/settingsApi';

const ROLE_LABELS = {
  ADMIN: { text: 'Admin', cls: 'bg-red-900/40 border-red-500/50 text-red-300' },
  PRO_USER: { text: 'Pro', cls: 'bg-amber-900/40 border-amber-500/50 text-amber-300' },
  LITE_USER: { text: 'Lite', cls: 'bg-sky-900/40 border-sky-500/50 text-sky-300' },
};

/**
 * Settings modal — user profile, home postcode, and drive time management.
 */
export default function UserSettingsModal({ onClose, onDriveTimesRefreshed }) {
  const [settings, setSettings] = useState(null);
  const [loading, setLoading] = useState(true);
  const [postcode, setPostcode] = useState('');
  const [lookupResult, setLookupResult] = useState(null);
  const [lookupError, setLookupError] = useState(null);
  const [lookingUp, setLookingUp] = useState(false);
  const [saving, setSaving] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshResult, setRefreshResult] = useState(null);
  const [refreshError, setRefreshError] = useState(null);

  const fetchSettings = useCallback(async () => {
    try {
      const data = await getSettings();
      setSettings(data);
      if (data.homePostcode) setPostcode(data.homePostcode);
    } catch {
      // Settings fetch failed — modal will show skeleton state
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchSettings(); }, [fetchSettings]);

  const handleLookup = async () => {
    if (!postcode.trim()) return;
    setLookingUp(true);
    setLookupError(null);
    setLookupResult(null);
    try {
      const result = await lookupPostcode(postcode.trim());
      setLookupResult(result);
    } catch {
      setLookupError('Invalid postcode');
    } finally {
      setLookingUp(false);
    }
  };

  const handleSave = async () => {
    if (!lookupResult) return;
    setSaving(true);
    try {
      const updated = await saveHome(lookupResult.postcode, lookupResult.latitude, lookupResult.longitude);
      setSettings(updated);
      setLookupResult(null);
    } catch {
      // Save failed — leave lookup result visible for retry
    } finally {
      setSaving(false);
    }
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    setRefreshResult(null);
    setRefreshError(null);
    try {
      const result = await refreshDriveTimes();
      setRefreshResult(result);
      setSettings((prev) => prev ? { ...prev, driveTimesCalculatedAt: result.calculatedAt } : prev);
      onDriveTimesRefreshed?.();
    } catch (err) {
      const status = err?.response?.status;
      if (status === 429) {
        setRefreshError(err.response?.data?.message || 'Drive times were refreshed recently. Please wait before trying again.');
      } else if (status === 400) {
        setRefreshError('Set a home location first.');
      } else {
        setRefreshError('Something went wrong — please try again.');
      }
    } finally {
      setRefreshing(false);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') handleLookup();
  };

  const roleBadge = settings?.role ? ROLE_LABELS[settings.role] : null;
  const hasHome = settings?.homePostcode != null;

  const formatCalcTime = (iso) => {
    if (!iso) return null;
    const d = new Date(iso);
    const mins = Math.round((Date.now() - d.getTime()) / 60000);
    if (mins < 1) return 'Just now';
    if (mins < 60) return `${mins} min ago`;
    const hrs = Math.round(mins / 60);
    return `${hrs}h ago`;
  };

  // Blocking spinner during drive time refresh
  if (refreshing) {
    return (
      <Modal label="Calculating drive times" data-testid="settings-modal">
        <div className="flex flex-col items-center gap-3 py-6">
          <div className="w-8 h-8 border-2 border-plex-gold border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-plex-text">Calculating drive times...</p>
          <p className="text-xs text-plex-text-muted">Please wait — this takes a few seconds</p>
        </div>
      </Modal>
    );
  }

  // Success flash after refresh
  if (refreshResult) {
    return (
      <Modal label="Drive times updated" onClose={() => setRefreshResult(null)} data-testid="settings-modal">
        <div className="flex flex-col items-center gap-3 py-6">
          <span className="text-3xl">&#x2705;</span>
          <p className="text-sm text-plex-text">Done — {refreshResult.locationsUpdated} locations updated</p>
          <button
            className="btn-secondary text-xs mt-2"
            onClick={() => setRefreshResult(null)}
            data-testid="settings-refresh-dismiss"
          >
            Back to settings
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal label="Settings" onClose={onClose} data-testid="settings-modal">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-plex-text">Settings</h2>
        <button onClick={onClose} className="text-plex-text-muted hover:text-plex-text text-lg" aria-label="Close">&times;</button>
      </div>

      {loading ? (
        <p className="text-sm text-plex-text-muted py-4">Loading...</p>
      ) : settings ? (
        <div className="flex flex-col gap-5">
          {/* Profile */}
          <section>
            <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Profile</h3>
            <div className="flex flex-col gap-1 text-sm">
              <div className="flex items-center gap-2">
                <span className="text-plex-text">{settings.username}</span>
                {roleBadge && (
                  <span className={`text-xs px-2 py-0.5 rounded-full border ${roleBadge.cls}`} data-testid="settings-role-badge">
                    {roleBadge.text}
                  </span>
                )}
              </div>
              {settings.email && <span className="text-plex-text-secondary">{settings.email}</span>}
            </div>
          </section>

          {/* Home Location */}
          <section>
            <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Home Location</h3>
            {hasHome && !lookupResult && (
              <p className="text-sm text-plex-text mb-2" data-testid="settings-home-current">
                <span className="inline-block w-2 h-2 rounded-full bg-green-500 mr-1.5" />
                {settings.homePlaceName || settings.homePostcode}
              </p>
            )}
            <div className="flex gap-2">
              <input
                type="text"
                value={postcode}
                onChange={(e) => setPostcode(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Enter UK postcode"
                className="flex-1 px-3 py-1.5 text-sm bg-plex-bg border border-plex-border rounded-lg text-plex-text placeholder:text-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                data-testid="settings-postcode-input"
              />
              <button
                className="btn-primary text-sm"
                onClick={handleLookup}
                disabled={lookingUp || !postcode.trim()}
                data-testid="settings-lookup-btn"
              >
                {lookingUp ? 'Looking up...' : 'Look up'}
              </button>
            </div>
            {lookupError && (
              <p className="text-sm text-red-400 mt-1" data-testid="settings-lookup-error">{lookupError}</p>
            )}
            {lookupResult && (
              <div className="mt-2 flex items-center gap-3" data-testid="settings-lookup-result">
                <div>
                  <p className="text-sm text-plex-text">
                    <span className="inline-block w-2 h-2 rounded-full bg-green-500 mr-1.5" />
                    {lookupResult.placeName}
                  </p>
                  <p className="text-xs text-plex-text-muted">
                    {lookupResult.latitude.toFixed(4)}, {lookupResult.longitude.toFixed(4)}
                  </p>
                </div>
                <button
                  className="btn-primary text-sm ml-auto"
                  onClick={handleSave}
                  disabled={saving}
                  data-testid="settings-save-home-btn"
                >
                  {saving ? 'Saving...' : 'Save'}
                </button>
              </div>
            )}
          </section>

          {/* Drive Times */}
          <section>
            <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Drive Times</h3>
            <button
              className="btn-primary text-sm w-full"
              onClick={handleRefresh}
              disabled={!hasHome}
              data-testid="settings-refresh-drive-btn"
            >
              Refresh drive times
            </button>
            {!hasHome && (
              <p className="text-xs text-plex-text-muted mt-1">Set a home location first</p>
            )}
            {settings.driveTimesCalculatedAt && (
              <p className="text-xs text-plex-text-muted mt-1" data-testid="settings-drive-calc-time">
                Last calculated: {formatCalcTime(settings.driveTimesCalculatedAt)}
              </p>
            )}
            {refreshError && (
              <p className="text-sm text-red-400 mt-1" data-testid="settings-refresh-error">{refreshError}</p>
            )}
          </section>
        </div>
      ) : (
        <p className="text-sm text-red-400 py-4">Failed to load settings.</p>
      )}
    </Modal>
  );
}

UserSettingsModal.propTypes = {
  onClose: PropTypes.func.isRequired,
  onDriveTimesRefreshed: PropTypes.func,
};
