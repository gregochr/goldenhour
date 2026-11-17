import { useCallback, useEffect, useState } from 'react';
import { getWarningsStatus, simulateWarning } from '../api/warningsAdminApi.js';
import KvList from './data/KvList.jsx';
import { inputClass, labelClass, selectClass, sectionLabelClass } from './users/userFormClasses.js';

const LEVELS = ['Yellow', 'Amber', 'Red'];
const DEFAULT_FORM = {
  level: 'Amber',
  weatherTypes: 'WIND',
  headline: '',
  durationHours: '6',
  latitude: '',
  longitude: '',
};
const MAX_HOURS = 72;
const FAILURE_WORDS = {
  NOT_CONFIGURED: 'Not configured (no feed URL or key)',
  UNAUTHORISED: 'Unauthorised (the key was refused)',
  RATE_LIMITED: 'Rate limited by the Met Office',
  UNREACHABLE: 'Feed unreachable',
  MALFORMED_FEED: 'Feed could not be read',
};
const BUTTON_CLASS = 'rounded border border-plex-border px-3 py-1.5 text-sm text-plex-text hover:border-plex-gold '
  + 'focus-visible:outline-2 focus-visible:outline-plex-gold disabled:opacity-50 max-[639px]:min-h-11';

/**
 * An ISO instant as a UK date and time for the operator, or an em dash when absent. Display only: it reads nothing
 * from the clock.
 */
function formatInstant(iso) {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString('en-GB', {
    timeZone: 'Europe/London', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
  });
}

/** A failure reason in words; an unlisted one (HTTP_500) is shown as it was recorded. */
function failureWords(reason) {
  if (!reason) return null;
  return FAILURE_WORDS[reason] ?? reason;
}

/** Whether a number-ish form value is a finite number. */
function isNumber(value) {
  return value !== '' && Number.isFinite(Number(value));
}

/** The first problem with the simulate form, or null when it can be sent. */
function formProblem(form) {
  if (!isNumber(form.latitude) || Math.abs(Number(form.latitude)) > 90) {
    return 'Latitude must be a number between -90 and 90';
  }
  if (!isNumber(form.longitude) || Math.abs(Number(form.longitude)) > 180) {
    return 'Longitude must be a number between -180 and 180';
  }
  const hours = Number(form.durationHours);
  if (!Number.isInteger(hours) || hours < 1 || hours > MAX_HOURS) {
    return `Duration must be a whole number of hours between 1 and ${MAX_HOURS}`;
  }
  return null;
}

/** The request body for the simulate endpoint. */
function toRequest(form) {
  const types = form.weatherTypes.split(',').map((t) => t.trim().toUpperCase()).filter(Boolean);
  return {
    level: form.level,
    weatherTypes: types.length > 0 ? types : ['WIND'],
    headline: form.headline.trim() || undefined,
    durationHours: Number(form.durationHours),
    latitude: Number(form.latitude),
    longitude: Number(form.longitude),
  };
}

/**
 * Operations: the Met Office weather-warnings ingest, read only, plus a form that inserts a clearly simulated
 * warning. "Last checked" is an Operations-only detail (docs/engineering/weather-warnings-plan.md); no user surface
 * shows it. Reads the real clock only to format what the server sent. The endpoint answers 403 to a non-admin, so
 * nothing is hidden here by UI alone.
 */
export default function WarningsAdminPanel() {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);
  const [form, setForm] = useState(DEFAULT_FORM);
  const [formError, setFormError] = useState(null);
  const [created, setCreated] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await getWarningsStatus());
      setError(null);
    } catch {
      setError('Failed to load the warnings status');
    }
  }, []);

  useEffect(() => {
    getWarningsStatus()
      .then(setStatus)
      .catch(() => setError('Failed to load the warnings status'));
  }, []);

  const setField = (name) => (event) => setForm((f) => ({ ...f, [name]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    const problem = formProblem(form);
    setCreated(null);
    setFormError(problem);
    if (problem) return;
    setBusy(true);
    try {
      const stored = await simulateWarning(toRequest(form));
      setCreated(stored.warningId);
      await load();
    } catch {
      setFormError('The warning could not be simulated');
    } finally {
      setBusy(false);
    }
  };

  if (error) {
    return (
      <div className="flex flex-col gap-3" data-testid="warnings-admin-panel">
        <p role="alert" className="text-sm text-red-400" data-testid="warnings-admin-error">{error}</p>
        <button type="button" className={BUTTON_CLASS} onClick={load} data-testid="warnings-admin-retry">Retry</button>
      </div>
    );
  }
  if (!status) {
    return <p className="text-sm text-plex-text-muted" data-testid="warnings-admin-loading">Loading…</p>;
  }

  const attempt = status.lastAttempt;
  const unknown = Object.entries(status.unknownTypes ?? {});
  const active = status.activeWarnings ?? [];

  return (
    <div className="flex flex-col gap-6" data-testid="warnings-admin-panel">
      <section aria-labelledby="warnings-admin-ingest" className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <h3 id="warnings-admin-ingest" className={sectionLabelClass}>Ingest</h3>
          <button type="button" className={BUTTON_CLASS} onClick={load} data-testid="warnings-admin-refresh">
            Refresh
          </button>
        </div>
        <KvList
          testId="warnings-admin-ingest-list"
          items={[
            { label: 'Feature flag', value: status.enabled ? 'On' : 'Off', testId: 'warnings-admin-enabled' },
            { label: 'API key', value: status.apiKeyConfigured ? 'Set' : 'Not set' },
            { label: 'Feed URL', value: status.feedUrlConfigured ? 'Set' : 'Not set' },
            { label: 'Last checked', value: formatInstant(attempt?.at), testId: 'warnings-admin-last-checked' },
            { label: 'Last success', value: formatInstant(status.lastSuccessAt), testId: 'warnings-admin-last-success' },
            { label: 'Freshness', value: status.stale ? 'Stale' : 'Fresh', testId: 'warnings-admin-stale' },
            {
              label: 'Failure reason',
              value: attempt && !attempt.succeeded ? failureWords(attempt.failureReason) : null,
              testId: 'warnings-admin-failure',
            },
            { label: 'Feed entries', value: attempt?.feedEntries, mono: true },
            { label: 'Stored last poll', value: attempt?.stored, mono: true },
            { label: 'Already held', value: attempt?.alreadyStored, mono: true },
            { label: 'Gone (404/410)', value: attempt?.goneSkipped, mono: true, testId: 'warnings-admin-gone' },
            { label: 'Malformed', value: attempt?.malformedSkipped, mono: true, testId: 'warnings-admin-malformed' },
            { label: 'Stored versions', value: status.storedVersions, mono: true, testId: 'warnings-admin-stored' },
            { label: 'Simulated versions', value: status.simulatedVersions, mono: true },
          ]}
        />
        <p className="text-xs text-plex-text-muted">
          The poll record resets when the app restarts. Stored warnings are never blanked by a failed poll.
        </p>
      </section>

      <section aria-labelledby="warnings-admin-unknown" className="flex flex-col gap-2">
        <h3 id="warnings-admin-unknown" className={sectionLabelClass}>Unknown weather types</h3>
        {unknown.length === 0 ? (
          <p className="text-sm text-plex-text-secondary" data-testid="warnings-admin-unknown-none">
            None seen since the app started.
          </p>
        ) : (
          <ul className="flex flex-col gap-1 text-sm text-plex-text" data-testid="warnings-admin-unknown-list">
            {unknown.map(([type, count]) => (
              <li key={type}><span className="font-mono">{type}</span> seen {count}</li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="warnings-admin-active" className="flex flex-col gap-2">
        <h3 id="warnings-admin-active" className={sectionLabelClass}>Warnings in force ({active.length})</h3>
        {active.length === 0 ? (
          <p className="text-sm text-plex-text-secondary" data-testid="warnings-admin-active-none">
            No warning is in force.
          </p>
        ) : (
          <ul className="flex flex-col gap-3" data-testid="warnings-admin-active-list">
            {active.map((w) => (
              <li
                key={`${w.warningId}-${w.warningVersion}`}
                className="rounded border border-plex-border p-3 text-sm text-plex-text"
                data-testid={`warnings-admin-warning-${w.warningId}`}
              >
                <p className="font-semibold">
                  <span className="font-mono">{w.warningId}</span> v{w.warningVersion} · {w.level}
                  {w.simulated && <span className="ml-2 text-verdict-marginal">Simulated</span>}
                  {w.upgradedFrom && (
                    <span className="ml-2 text-verdict-marginal">Upgraded from {w.upgradedFrom}</span>
                  )}
                </p>
                <p className="text-plex-text-secondary">{(w.weatherTypes ?? []).join(', ') || '—'}</p>
                {w.headline && <p>{w.headline}</p>}
                <p className="text-xs text-plex-text-muted">
                  Until {formatInstant(w.validTo) ?? '—'} · covers {(w.locationIds ?? []).length} enabled
                  {' '}{(w.locationIds ?? []).length === 1 ? 'place' : 'places'}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="warnings-admin-simulate" className="flex flex-col gap-3">
        <h3 id="warnings-admin-simulate" className={sectionLabelClass}>Simulate a warning</h3>
        <p className="text-xs text-plex-text-muted">
          Inserts a half-degree square around a point. It is shown here only: no reader, whatever their role, sees a
          simulated warning anywhere else.
        </p>
        <form onSubmit={submit} className="grid grid-cols-1 gap-3 sm:grid-cols-2" data-testid="warnings-simulate-form" noValidate>
          <div>
            <label htmlFor="warnings-sim-level" className={labelClass}>Level</label>
            <select id="warnings-sim-level" className={selectClass} value={form.level} onChange={setField('level')}>
              {LEVELS.map((l) => <option key={l} value={l}>{l}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="warnings-sim-types" className={labelClass}>Weather types (comma separated)</label>
            <input id="warnings-sim-types" className={inputClass} value={form.weatherTypes} onChange={setField('weatherTypes')} />
          </div>
          <div>
            <label htmlFor="warnings-sim-lat" className={labelClass}>Latitude</label>
            <input id="warnings-sim-lat" className={inputClass} inputMode="decimal" value={form.latitude} onChange={setField('latitude')} />
          </div>
          <div>
            <label htmlFor="warnings-sim-lon" className={labelClass}>Longitude</label>
            <input id="warnings-sim-lon" className={inputClass} inputMode="decimal" value={form.longitude} onChange={setField('longitude')} />
          </div>
          <div>
            <label htmlFor="warnings-sim-hours" className={labelClass}>Valid for (hours, 1 to {MAX_HOURS})</label>
            <input id="warnings-sim-hours" className={inputClass} inputMode="numeric" value={form.durationHours} onChange={setField('durationHours')} />
          </div>
          <div>
            <label htmlFor="warnings-sim-headline" className={labelClass}>Headline (optional)</label>
            <input id="warnings-sim-headline" className={inputClass} value={form.headline} onChange={setField('headline')} />
          </div>
          <div className="sm:col-span-2 flex flex-wrap items-center gap-3">
            <button type="submit" className={BUTTON_CLASS} disabled={busy} data-testid="warnings-simulate-submit">
              {busy ? 'Simulating…' : 'Simulate warning'}
            </button>
            {formError && (
              <p role="alert" className="text-sm text-red-400" data-testid="warnings-simulate-error">{formError}</p>
            )}
            {created && (
              <p role="status" className="text-sm text-plex-text-secondary" data-testid="warnings-simulate-done">
                Created <span className="font-mono">{created}</span>
              </p>
            )}
          </div>
        </form>
      </section>
    </div>
  );
}
