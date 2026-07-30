import { useState, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal';
import { getSettings, lookupPostcode, saveHome, refreshDriveTimes } from '../api/settingsApi';
import { formatRelativeAge } from '../utils/relativeTime.js';

const ROLE_LABELS = {
  ADMIN: { text: 'Admin', cls: 'bg-red-900/40 border-red-500/50 text-red-300' },
  PRO_USER: { text: 'Pro', cls: 'bg-amber-900/40 border-amber-500/50 text-amber-300' },
  LITE_USER: { text: 'Lite', cls: 'bg-sky-900/40 border-sky-500/50 text-sky-300' },
};

/**
 * Settings modal — user profile, home postcode, and drive time management.
 */
/**
 * Slider fallback when the user has never chosen a radius. Display only — the value is not sent
 * unless the user actually moves the control, so the server keeps deciding what null means.
 */
const DEFAULT_RADIUS_MILES = 22;

export default function UserSettingsModal({ onClose, onDriveTimesRefreshed }) {
  const [settings, setSettings] = useState(null);
  const [loading, setLoading] = useState(true);
  const [postcode, setPostcode] = useState('');
  // Null until settings load, then the saved value or the 22-mile default. Kept separate from
  // the postcode's lookup/save cycle because the radius can be changed on its own.
  // Null until settings load. NOT defaulted to 22 here: a client-side default gets written back
  // on the next save and would silently override a server-configured one for a user who never
  // touched the slider.
  const [radius, setRadius] = useState(null);
  const [radiusSaving, setRadiusSaving] = useState(false);
  const [radiusError, setRadiusError] = useState(false);
  const [lookupResult, setLookupResult] = useState(null);
  const [lookupError, setLookupError] = useState(null);
  const [lookingUp, setLookingUp] = useState(false);
  const [saving, setSaving] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshResult, setRefreshResult] = useState(null);
  const [refreshError, setRefreshError] = useState(null);
  // Tracks the postcode that drive times were last calculated for.
  // Initialised from settings if drive times have been calculated before.
  const [driveTimesPostcode, setDriveTimesPostcode] = useState(null);
  // Current timestamp for relative "X min ago" labels. Read via an effect (not
  // during render) so the render stays pure (react-hooks/purity), and refreshed
  // every minute so the label stays current.
  const [now, setNow] = useState(null);

  const fetchSettings = useCallback(async () => {
    try {
      const data = await getSettings();
      setSettings(data);
      if (data.homePostcode) setPostcode(data.homePostcode);
      setRadius(data.localRadiusMiles ?? DEFAULT_RADIUS_MILES);
      if (data.driveTimesCalculatedAt && data.homePostcode) {
        setDriveTimesPostcode(data.homePostcode);
      }
    } catch {
      // Settings fetch failed — modal will show skeleton state
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    function tick() {
      setNow(Date.now());
    }
    tick();
    const id = setInterval(tick, 60_000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    (async () => {
      await fetchSettings();
    })();
  }, [fetchSettings]);

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
      const updated = await saveHome(lookupResult.postcode, lookupResult.latitude,
        lookupResult.longitude, radius);
      setSettings(updated);
      setLookupResult(null);
    } catch {
      // Save failed — leave lookup result visible for retry
    } finally {
      setSaving(false);
    }
  };

  /**
   * Persists the radius on its own.
   *
   * Requires a saved home, because the radius is measured FROM it — the field is disabled without
   * one. Sends the stored postcode back unchanged rather than inventing a radius-only endpoint:
   * the two settings belong to the same record and the same screen.
   */
  const handleRadiusCommit = async (next) => {
    if (!settings?.homePostcode || settings.homeLatitude == null) return;
    setRadiusSaving(true);
    setRadiusError(false);
    try {
      const updated = await saveHome(settings.homePostcode, settings.homeLatitude,
        settings.homeLongitude, next);
      setSettings(updated);
      // Trust the server's echo over the local value — it clamps to 10-50.
      if (updated?.localRadiusMiles != null) setRadius(updated.localRadiusMiles);
    } catch {
      // Surfaced, not swallowed. A silent failure left the slider showing a value that was never
      // persisted, so the setting looked applied and the panel disagreed with it.
      setRadiusError(true);
    } finally {
      setRadiusSaving(false);
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
      setDriveTimesPostcode(settings?.homePostcode ?? null);
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
  const isPro = settings?.role === 'ADMIN' || settings?.role === 'PRO_USER';

  const normalise = (pc) => (pc || '').replace(/\s+/g, '').toLowerCase();
  const postcodeChanged = hasHome && normalise(settings.homePostcode) !== normalise(driveTimesPostcode);

  // Verbose style ("Just now" / "5 min ago"); tiers live in the shared helper, which adds the
  // days tier this stamp needs — it only moved on a button press, so "744h ago" was reachable.
  const formatCalcTime = (iso) => (now == null ? null : formatRelativeAge(iso, { verbose: true, now }));

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
            <div className={!isPro ? 'opacity-45 pointer-events-none' : undefined}>
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
                  disabled={!isPro}
                />
                <button
                  className="btn-primary text-sm"
                  onClick={handleLookup}
                  disabled={!isPro || lookingUp || !postcode.trim()}
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
                    disabled={!isPro || saving}
                    data-testid="settings-save-home-btn"
                  >
                    {saving ? 'Saving...' : 'Save'}
                  </button>
                </div>
              )}

              {/* Local radius — directly beneath the postcode it is measured from. */}
              <div className="mt-4" data-testid="settings-local-radius">
                <label
                  htmlFor="local-radius"
                  className="block text-sm text-plex-text mb-1"
                >
                  Local radius
                  <span className="text-plex-text-muted ml-2 text-xs">
                    How far counts as close to home.
                  </span>
                </label>
                <div className="flex items-center gap-3">
                  <input
                    id="local-radius"
                    type="range"
                    min="10"
                    max="50"
                    step="2"
                    value={radius ?? DEFAULT_RADIUS_MILES}
                    // NOT disabled while saving: toggling disabled mid-interaction blurs the
                    // slider and drops the keyboard focus a user is still arrow-keying with.
                    disabled={!isPro || !settings?.homePostcode}
                    onChange={(e) => setRadius(Number(e.target.value))}
                    // Commit on release, not on every drag frame: the slider spans 21 stops and
                    // a PUT per stop would hammer the endpoint for one decision.
                    onMouseUp={(e) => handleRadiusCommit(Number(e.target.value))}
                    onTouchEnd={(e) => handleRadiusCommit(Number(e.target.value))}
                    onKeyUp={(e) => handleRadiusCommit(Number(e.target.value))}
                    className="flex-1"
                    data-testid="settings-radius-slider"
                  />
                  <span
                    className="font-mono text-sm text-plex-text w-16 text-right"
                    data-testid="settings-radius-value"
                  >
                    {radius ?? DEFAULT_RADIUS_MILES} miles
                  </span>
                </div>
                {!settings?.homePostcode && (
                  <p className="text-xs text-plex-text-muted mt-1">
                    Set a home postcode first — the radius is measured from it.
                  </p>
                )}
                {radiusError && (
                  <p className="text-xs text-red-400 mt-1" data-testid="settings-radius-error">
                    Could not save the radius. Try again.
                  </p>
                )}
              </div>
            </div>
          </section>

          {/* Drive Times */}
          <section>
            <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Drive Times</h3>
            <div className={!isPro ? 'opacity-45 pointer-events-none' : undefined}>
              <button
                className="btn-primary text-sm w-full"
                onClick={handleRefresh}
                disabled={!isPro || !hasHome || !postcodeChanged}
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
            </div>
          </section>
          {!isPro && (
            <p className="text-center text-plex-text-secondary" style={{ fontSize: '13px' }}>
              Upgrade to Pro for personalised drive times
            </p>
          )}
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
