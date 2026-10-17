import { useCallback, useEffect, useState } from 'react';
import { getRewindEvents } from '../api/rewindApi.js';
import { setRewind } from '../utils/rewind.js';
import { formatDateLabel, formatEventTimeUk } from '../utils/conversions.js';

const EVENT_WORD = { SUNRISE: 'Sunrise', SUNSET: 'Sunset' };

/**
 * The backend's bound on a rewind, in days, until the payload has said what it is (`maxAgeDays`,
 * served beside the events off the filter's own constant). The custom-moment input is offered
 * while the events are still loading and after a failed load, and those two states are the only
 * readers of this copy; a loaded view, an empty roster included, reads the served figure.
 */
const DEFAULT_MAX_AGE_DAYS = 3;

const DAY_MS = 24 * 60 * 60 * 1000;

/** A Date as a `datetime-local` value in the browser's own zone, "YYYY-MM-DDTHH:MM". */
function toLocalInputValue(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * Whether a `datetime-local` value names a moment the backend will accept: readable, not in the
 * future, not more than `maxAgeDays` days ago. Mirrors `RewindFilter`'s own two bounds so the
 * button is disabled where the request would be a 400.
 */
export function customInRange(value, now = new Date(), maxAgeDays = DEFAULT_MAX_AGE_DAYS) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return false;
  const ms = date.getTime();
  return ms <= now.getTime() && ms >= now.getTime() - maxAgeDays * DAY_MS;
}

/** "Sun 4 Oct" for a YYYY-MM-DD date — the app's own day label, never "Today"/"Tomorrow" here. */
function dayLabel(dateStr) {
  return formatDateLabel(dateStr, undefined, true);
}

/**
 * Admin tool: rewind the app to just before a recent solar event, so the Plan and Map tabs render
 * the forecast as it stood then — for a screenshot of the forecast that sent you out, taken after
 * you got back.
 *
 * <p>Sets nothing on the server. Choosing a moment writes the module-level rewind
 * (`utils/rewind.js`); from then on every GET carries it as {@code X-Rewind-To}, every client clock
 * read answers with it, and the whole app remounts (`App.jsx`'s `RewindGate`) on the moment chosen.
 * The bar above every page names the moment and is the way back, and so is a reload — which is
 * why this view does not repeat it.
 */
export default function RewindView() {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [custom, setCustom] = useState('');

  const load = useCallback(async () => {
    try {
      setData(await getRewindEvents());
      setError(null);
    } catch {
      setError('Failed to load the recent solar events');
    }
  }, []);

  useEffect(() => {
    (async () => { await load(); })();
  }, [load]);

  const rewindToEvent = (event) => {
    setRewind(event.rewindTo, { date: event.date, eventType: event.eventType });
  };

  // Read at render so the bounds move with the clock while the view stays open.
  const wallNow = new Date();
  const maxAgeDays = data?.maxAgeDays ?? DEFAULT_MAX_AGE_DAYS;

  const rewindToCustom = () => {
    if (!customInRange(custom, new Date(), maxAgeDays)) return;
    setRewind(new Date(custom).toISOString());
  };

  const builtAfter = (event) => data?.briefingGeneratedAt
    && new Date(data.briefingGeneratedAt).getTime() > new Date(event.rewindTo).getTime();
  // Passed, and its day is still in the current briefing: the briefing is rebuilt for the real
  // today onward every cycle, and the Plan tab can only show a day that build kept — a rewind to
  // a day it dropped would restore the Map tab and leave the Plan tab empty (Codex review, #998).
  const offerable = (event) => event.passed && event.inBriefing;

  return (
    <div className="flex flex-col gap-5" data-testid="rewind-view">
      <p className="text-sm text-plex-text-secondary">
        Rewind the app to just before a solar event that has passed. The Plan and Map tabs then render
        the forecast as it stood at that moment — that window live, its verdict, stars and best bet in
        place — so you can screenshot the forecast that sent you out after you have got back.
      </p>

      {error && <p className="text-sm text-red-400">{error}</p>}
      {!error && !data && <p className="text-sm text-plex-text-muted">Loading…</p>}

      {data && (
        <ul className="flex flex-col gap-2" data-testid="rewind-events" aria-label="Recent solar events">
          {data.events.length === 0 && (
            <li className="text-sm text-plex-text-muted">No sky locations to time an event across.</li>
          )}
          {data.events.map((event) => {
            const key = `${event.date}-${event.eventType}`;
            return (
              <li
                key={key}
                data-testid={`rewind-event-${key}`}
                className="flex flex-wrap items-center justify-between gap-3 rounded border border-plex-border bg-plex-surface px-3 py-2"
              >
                <div className="flex flex-col">
                  <span className="text-sm text-plex-text">
                    {EVENT_WORD[event.eventType] ?? event.eventType} · {dayLabel(event.date)}
                  </span>
                  <span className="text-xs text-plex-text-muted">
                    {formatEventTimeUk(event.earliest)}–{formatEventTimeUk(event.latest)} UK across
                    {' '}{event.locationCount} {event.locationCount === 1 ? 'location' : 'locations'}
                    {event.passed ? '' : ' · still ahead'}
                    {event.passed && !event.inBriefing ? ' · no longer in the forecast' : ''}
                  </span>
                  {event.passed && !event.inBriefing && (
                    <span className="text-xs text-plex-text-muted" data-testid={`rewind-not-briefed-${key}`}>
                      The briefing has been rebuilt since and no longer holds this day, so the Plan tab
                      cannot show it.
                    </span>
                  )}
                  {event.passed && builtAfter(event) && (
                    <span className="text-xs text-plex-text-muted" data-testid={`rewind-built-after-${key}`}>
                      Forecast last built {formatEventTimeUk(data.briefingGeneratedAt)} UK, after this
                      moment — a passed window is not re-scored, so it normally still matches.
                    </span>
                  )}
                </div>
                <button
                  type="button"
                  data-testid={`rewind-to-${key}`}
                  disabled={!offerable(event)}
                  aria-label={`Rewind to ${formatEventTimeUk(event.rewindTo)}, before the ${(EVENT_WORD[event.eventType] ?? event.eventType).toLowerCase()} of ${dayLabel(event.date)}`}
                  onClick={() => rewindToEvent(event)}
                  className="rounded bg-plex-surface border border-plex-border px-3 py-1 text-xs font-semibold text-plex-text hover:border-plex-gold disabled:cursor-not-allowed disabled:opacity-40"
                >
                  Rewind to {formatEventTimeUk(event.rewindTo)}
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <div className="flex flex-col gap-2 border-t border-plex-border pt-3">
        <label className="text-xs text-plex-text-muted" htmlFor="rewind-custom">
          Or a moment of your own, in your browser’s time zone — within the last {maxAgeDays} days.
          The Plan tab shows only the days the current briefing holds.
        </label>
        <div className="flex flex-wrap items-center gap-2">
          <input
            id="rewind-custom"
            type="datetime-local"
            value={custom}
            min={toLocalInputValue(new Date(wallNow.getTime() - maxAgeDays * DAY_MS))}
            max={toLocalInputValue(wallNow)}
            onChange={(e) => setCustom(e.target.value)}
            className="rounded border border-plex-border bg-plex-surface px-2 py-1 text-sm text-plex-text"
            data-testid="rewind-custom"
          />
          <button
            type="button"
            data-testid="rewind-custom-go"
            disabled={!customInRange(custom, wallNow, maxAgeDays)}
            onClick={rewindToCustom}
            className="rounded bg-plex-surface border border-plex-border px-3 py-1 text-xs font-semibold text-plex-text hover:border-plex-gold disabled:cursor-not-allowed disabled:opacity-40"
          >
            Rewind
          </button>
        </div>
      </div>

      <p className="text-xs text-plex-text-muted">
        A rewind turns the clock back, not the data: you see today’s forecast as of that moment, and
        hot topics are recomputed from today’s readings. It is yours alone — nothing is set on the
        server, no other user sees it, and a reload or sign-out ends it.
      </p>
    </div>
  );
}
