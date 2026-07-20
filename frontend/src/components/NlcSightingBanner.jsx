import React, { useState } from 'react';
import { useNlcSighting } from '../hooks/useNlcSighting.js';

/**
 * Formats a sighting timestamp as a short relative label for the banner headline.
 *
 * - < 1 min:   "just now"
 * - < 60 min:  "45m ago"
 * - same day:  "2h ago"
 * - yesterday: "yesterday 23:10"
 * - older:     "3 Jul 23:10"
 *
 * @param {string} isoTimestamp - ISO 8601 instant from the API
 * @returns {string|null} formatted label, or null if invalid/missing
 */
export function formatReportedAt(isoTimestamp) {
  if (!isoTimestamp) return null;
  const reported = new Date(isoTimestamp);
  if (isNaN(reported.getTime())) return null;

  const now = new Date();
  const diffMin = Math.round((now - reported) / 60000);

  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;

  const time = reported.toLocaleTimeString('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (reported >= todayStart) return `${Math.floor(diffMin / 60)}h ago`;

  const yesterdayStart = new Date(todayStart.getTime() - 86400000);
  if (reported >= yesterdayStart) return `yesterday ${time}`;
  return `${reported.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${time}`;
}

/**
 * Direction A styling — the calm dark banner shared with the aurora alert, but
 * accented in the NLC violet. All colour derives from the single CSS variable
 * --nlc-accent (set from the API hexColour) via color-mix, so the treatment
 * stays consistent with AuroraBanner while reading as a distinct signal.
 *
 * Deliberately has NO pulse animation: an NLC sighting is a reactive community
 * report, not an escalating geophysical storm, so it should feel calm.
 */
const NLC_A_STYLE = `
  .nlc-a { position: relative; overflow: hidden; border: 1px solid var(--color-plex-border); }
  .nlc-a .nlc-rail { position: absolute; top: 0; bottom: 0; left: 0; width: 4px;
    background: linear-gradient(180deg, color-mix(in srgb, var(--nlc-accent) 82%, white 14%), var(--nlc-accent)); }
  .nlc-a .nlc-glow { position: absolute; inset: 0; pointer-events: none;
    background: radial-gradient(120% 140% at 0% 0%, color-mix(in srgb, var(--nlc-accent) 30%, transparent) 0%, transparent 46%);
    opacity: 0.55; }
  .nlc-a .nlc-inner { position: relative; z-index: 1; display: flex; gap: 14px; align-items: flex-start; }
  .nlc-roundel { width: 42px; height: 42px; border-radius: 11px; flex-shrink: 0;
    display: flex; align-items: center; justify-content: center; font-size: 22px; line-height: 1; color: #fff;
    background: linear-gradient(155deg, color-mix(in srgb, var(--nlc-accent) 88%, white 6%), color-mix(in srgb, var(--nlc-accent) 52%, black 36%)); }
  .nlc-body { flex: 1; min-width: 0; }
  .nlc-top { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
  .nlc-sev { font-size: 10px; font-weight: 600; letter-spacing: 0.09em; text-transform: uppercase;
    padding: 3px 8px; border-radius: 5px; display: inline-flex; align-items: center; gap: 6px; white-space: nowrap;
    background: color-mix(in srgb, var(--nlc-accent) 16%, transparent);
    color: color-mix(in srgb, var(--nlc-accent) 42%, white);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--nlc-accent) 42%, transparent); }
  .nlc-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--nlc-accent);
    box-shadow: 0 0 7px var(--nlc-accent); }
  .nlc-when { font-size: 10px; font-weight: 500; letter-spacing: 0.05em; text-transform: uppercase;
    color: var(--color-plex-text-muted); }
  .nlc-title { font-size: 15px; font-weight: 700; line-height: 1.3; margin-top: 7px;
    color: var(--color-plex-text); text-wrap: pretty; }
  .nlc-meta { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; margin-top: 8px;
    font-size: 12px; color: var(--color-plex-text-secondary); }
  .nlc-meta .nlc-key { font-weight: 600; color: var(--color-plex-text); }
  .nlc-meta strong.italic { font-weight: 700; font-style: italic; }
  .nlc-div { width: 1px; height: 12px; background: var(--color-plex-border); display: inline-block; }
  .nlc-cta { font-weight: 600; color: color-mix(in srgb, var(--nlc-accent) 42%, white); }
  .nlc-sub { font-size: 12px; color: var(--color-plex-text-secondary); margin-top: 5px; }
  .nlc-x { color: var(--color-plex-text-secondary); font-size: 17px; line-height: 1; flex-shrink: 0;
    width: 26px; height: 26px; display: flex; align-items: center; justify-content: center;
    border-radius: 6px; background: transparent; border: 0; cursor: pointer; }
  .nlc-x:hover { background: rgba(255,255,255,0.06); color: var(--color-plex-text); }
`;

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
 * - Click navigates to the map (same as the aurora banner CTA)
 * - Calm: no pulse animation
 */
export default function NlcSightingBanner() {
  const { sighting } = useNlcSighting();
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

  function handleDismiss(e) {
    e.stopPropagation();
    setDismissedKey(dismissKey);
  }

  return (
    <>
      <style>{NLC_A_STYLE}</style>
      {/* eslint-disable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
      <div
        role="alert"
        data-testid="nlc-sighting-banner"
        style={{
          '--nlc-accent': accent,
          backgroundColor: 'var(--color-plex-surface)',
        }}
        className="nlc-a px-4 py-3 rounded-xl select-none cursor-pointer"
        onClick={() => { window.location.hash = 'map'; }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') window.location.hash = 'map';
        }}
        tabIndex={0}
      >
      {/* eslint-enable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
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
              <span className="nlc-div" aria-hidden="true" />
              <span className="nlc-cta">Show on map →</span>
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
