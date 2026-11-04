import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { useNlcSighting } from '../hooks/useNlcSighting.js';
import { formatInstantUk, parseUtcInstant } from '../utils/conversions.js';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';
import './NlcSightingBanner.css';

/**
 * Formats a sighting timestamp as a short relative label for the banner headline.
 *
 * - < 1 min:   "just now"
 * - < 60 min:  "45m ago"
 * - same day:  "2h ago"
 * - yesterday: "yesterday 23:10"
 * - older:     "3 Jul 23:10"
 *
 * <p>The first two bands are pure instant arithmetic and have no calendar in them. The last three
 * do, and they read the <b>UK</b> one: `reportedAt` is a UTC `Instant` describing a noctilucent
 * display over the UK, so both the clock time shown and the day it is credited to belong to that
 * sky rather than to the reader's device. The two have to move together — a UK "23:10" under a
 * device-calendar day label is worse than either basis alone.
 *
 * @param {string} isoTimestamp - ISO 8601 instant from the API
 * @param {Date} [now] - the instant "today" is read from; injectable so tests can pin it
 * @returns {string|null} formatted label, or null if invalid/missing
 */
export function formatReportedAt(isoTimestamp, now = new Date()) {
  const reported = parseUtcInstant(isoTimestamp);
  if (!reported) return null;

  const diffMin = Math.round((now - reported) / 60000);

  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;

  const time = formatInstantUk(reported, {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  const reportedDay = ukDateStr(reported);
  if (reportedDay === ukDateStr(now)) return `${Math.floor(diffMin / 60)}h ago`;
  if (reportedDay === ukDateStrOffset(-1, now)) return `yesterday ${time}`;
  return `${formatInstantUk(reported, { day: 'numeric', month: 'short' })} ${time}`;
}

/**
 * Full-width noctilucent-cloud sighting banner.
 *
 * A REACTIVE community signal, not a forecast: it appears when a fresh observer
 * report lands (e.g. from NLCNET) AND local skies are currently clear. It reads
 * deliberately differently from the aurora banner — no Kp / Bz / G-scale, no
 * probability — leading instead with "who saw it, where, and how long ago".
 *
 * Behaviour (mirrors AuroraBanner):
 * - Not rendered for free-tier users (403 from the API -> sighting is null)
 * - Not rendered when there is no active sighting (`active !== true`)
 * - Not rendered when local skies are not clear (`clearTonight !== true`) — the
 *   sighting-AND-clear gate agreed in design
 * - Dismissible per session, keyed by `reportedAt`; a NEWER report re-shows it
 * - Inert: no click or keyboard route to the map exists for it yet
 * - Calm: no pulse animation
 */
export default function NlcSightingBanner({ eligible = false }) {
  const { sighting } = useNlcSighting(eligible);
  const [dismissedKey, setDismissedKey] = useState(null);

  // Not eligible (free-tier, 403) or not yet loaded
  if (!sighting) return null;

  // No active sighting to surface
  if (sighting.active !== true) return null;

  // Gate: only show when local skies are currently clear (design decision —
  // "fresh sighting AND local skies clear"). If the backend already applies this
  // gate it can simply omit the field or leave it true.
  if (sighting.clearTonight === false) return null;

  // Dismissed for this exact report — re-show only when a newer report arrives
  const dismissKey = sighting.reportedAt || 'active';
  if (dismissedKey === dismissKey) return null;

  const accent = sighting.hexColour || '#8E86D6';
  const reportedLabel = formatReportedAt(sighting.reportedAt);
  const observer = sighting.observerLocation || null;
  const source = sighting.source || null;
  const lookDirection = sighting.lookDirection || null;
  const darkSkyCount = sighting.darkSkyLocationCount ?? 0;

  const sevLabel = reportedLabel ? `Sighting · ${reportedLabel}` : 'Sighting';
  const observerText = observer
    ? (source ? `${observer} via ${source}` : observer)
    : null;

  function handleDismiss() {
    setDismissedKey(dismissKey);
  }

  return (
    <>
      <div
        role="alert"
        data-testid="nlc-sighting-banner"
        style={{
          '--nlc-accent': accent,
          backgroundColor: 'var(--color-plex-surface)',
        }}
        className="nlc-a px-4 py-3 rounded-xl"
      >
        <div className="nlc-rail" />
        <div className="nlc-glow" />
        <div className="nlc-inner">
          <div className="nlc-roundel">
            <span aria-hidden="true">{'\u2726'}</span>
          </div>
          <div className="nlc-body">
            <div className="nlc-top">
              <span className="nlc-sev">
                <span className="nlc-dot" />
                {sevLabel}
              </span>
              <span className="nlc-when">Noctilucent · live report</span>
            </div>

            <p className="nlc-title">{sighting.description}</p>

            <p className="nlc-meta">
              {observerText && <span className="nlc-key">{observerText}</span>}
              {observerText && <span className="nlc-div" aria-hidden="true" />}
              <span>Clear skies <strong className="italic">tonight</strong></span>
              {lookDirection && <span className="nlc-div" aria-hidden="true" />}
              {lookDirection && <span>Look {lookDirection}, low</span>}
            </p>

            {darkSkyCount > 0 && (
              <p data-testid="nlc-sighting-darksky" className="nlc-sub">
                {darkSkyCount} dark-sky {darkSkyCount === 1 ? 'site' : 'sites'} clear on the northern horizon
              </p>
            )}
          </div>

          <button
            onClick={handleDismiss}
            aria-label="Dismiss noctilucent sighting banner"
            data-testid="nlc-sighting-dismiss"
            className="nlc-x"
          >
            ✕
          </button>
        </div>
      </div>
    </>
  );
}

NlcSightingBanner.propTypes = {
  /** Fails closed: false (the default) makes no request. App passes true for Pro and Admin only. */
  eligible: PropTypes.bool,
};
