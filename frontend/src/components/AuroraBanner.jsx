import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

// Alert levels that should show the banner (MODERATE or STRONG)
const ALERT_WORTHY = new Set(['MODERATE', 'STRONG']);

/**
 * Formats the detection timestamp for the banner headline.
 *
 * - Same day: "21:34"
 * - Yesterday: "yesterday 21:34"
 * - Older: "3 Apr 21:34"
 *
 * @param {string} isoTimestamp - ISO 8601 instant from the API
 * @returns {string|null} formatted detection label, or null if invalid/missing
 */
export function formatDetectedAt(isoTimestamp) {
  if (!isoTimestamp) return null;
  const detected = new Date(isoTimestamp);
  if (isNaN(detected.getTime())) return null;

  const now = new Date();
  const time = detected.toLocaleTimeString('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterdayStart = new Date(todayStart.getTime() - 86400000);

  if (detected >= todayStart) return time;
  if (detected >= yesterdayStart) return `yesterday ${time}`;
  return `${detected.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${time}`;
}

/**
 * Returns a Bz status descriptor based on the nanoTesla value.
 * @param {number} bz
 * @returns {{ emoji: string, label: string, explanation: string }}
 */
export function bzStatus(bz) {
  if (bz < -1) {
    return {
      emoji: '✅',
      label: `Bz south (${bz.toFixed(1)} nT)`,
      explanation: 'solar wind coupling active',
    };
  }
  if (bz < 0) {
    return {
      emoji: '➖',
      label: `Bz near zero (${bz.toFixed(1)} nT)`,
      explanation: 'neutral',
    };
  }
  if (bz <= 5) {
    return {
      emoji: '⚠️',
      label: `Bz north (+${bz.toFixed(1)} nT)`,
      explanation: 'solar wind not coupling',
    };
  }
  return {
    emoji: '⚠️',
    label: `Bz north (+${bz.toFixed(1)} nT)`,
    explanation: 'solar wind not coupling',
  };
}

const PULSE_STYLE = `
  @keyframes aurora-pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.85; box-shadow: 0 0 20px rgba(255, 255, 255, 0.3); }
  }
`;

/** CSS for the dashed-border simulation indicator. */
const SIM_BORDER_STYLE = `
  .aurora-banner-simulated {
    background-image: repeating-linear-gradient(
      45deg,
      transparent,
      transparent 6px,
      rgba(255,255,255,0.08) 6px,
      rgba(255,255,255,0.08) 12px
    );
    border-color: rgba(255, 255, 255, 0.35) !important;
    border-style: dashed !important;
  }
`;

/**
 * Direction A styling — a calm dark banner where severity is carried by an
 * accent rail, an aurora roundel and a labelled pill rather than a flat neon
 * fill. All colour is derived from the single CSS variable --aurora-accent
 * (set from the API hexColour) via color-mix, so amber→red escalation and any
 * future level slot in automatically.
 */
const AURORA_A_STYLE = `
  .aurora-a { position: relative; overflow: hidden; border: 1px solid var(--color-plex-border); }
  .aurora-a .aurora-rail { position: absolute; top: 0; bottom: 0; left: 0; width: 4px;
    background: linear-gradient(180deg, color-mix(in srgb, var(--aurora-accent) 82%, white 14%), var(--aurora-accent)); }
  .aurora-a .aurora-glow { position: absolute; inset: 0; pointer-events: none;
    background: radial-gradient(120% 140% at 0% 0%, color-mix(in srgb, var(--aurora-accent) 30%, transparent) 0%, transparent 46%);
    opacity: 0.55; }
  .aurora-a .aurora-inner { position: relative; z-index: 1; display: flex; gap: 14px; align-items: flex-start; }
  .aurora-roundel { width: 42px; height: 42px; border-radius: 11px; flex-shrink: 0;
    display: flex; align-items: center; justify-content: center;
    background: linear-gradient(155deg, color-mix(in srgb, var(--aurora-accent) 88%, white 6%), color-mix(in srgb, var(--aurora-accent) 52%, black 36%)); }
  .aurora-roundel svg { width: 26px; height: 26px; }
  .aurora-body { flex: 1; min-width: 0; }
  .aurora-top { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
  .aurora-sev { font-size: 10px; font-weight: 600; letter-spacing: 0.09em; text-transform: uppercase;
    padding: 3px 8px; border-radius: 5px; display: inline-flex; align-items: center; gap: 6px; white-space: nowrap;
    background: color-mix(in srgb, var(--aurora-accent) 16%, transparent);
    color: color-mix(in srgb, var(--aurora-accent) 42%, white);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--aurora-accent) 42%, transparent); }
  .aurora-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--aurora-accent);
    box-shadow: 0 0 7px var(--aurora-accent); }
  .aurora-when { font-size: 10px; font-weight: 500; letter-spacing: 0.05em; text-transform: uppercase;
    color: var(--color-plex-text-muted); }
  .aurora-when [data-testid="aurora-banner-detected"] { color: var(--color-plex-text-secondary); }
  .aurora-title { font-size: 15px; font-weight: 700; line-height: 1.3; margin-top: 7px;
    color: var(--color-plex-text); text-wrap: pretty; }
  .aurora-meta { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; margin-top: 8px;
    font-size: 12px; color: var(--color-plex-text-secondary); }
  .aurora-meta .aurora-kp { font-weight: 600; color: var(--color-plex-text); }
  .aurora-meta .aurora-kp strong.italic { font-weight: 700; font-style: italic; }
  .aurora-div { width: 1px; height: 12px; background: var(--color-plex-border); display: inline-block; }
  .aurora-cta { font-weight: 600; color: color-mix(in srgb, var(--aurora-accent) 42%, white); }
  .aurora-sub { font-size: 12px; color: var(--color-plex-text-secondary); margin-top: 5px; }
  .aurora-x { color: var(--color-plex-text-secondary); font-size: 17px; line-height: 1; flex-shrink: 0;
    width: 26px; height: 26px; display: flex; align-items: center; justify-content: center;
    border-radius: 6px; background: transparent; border: 0; cursor: pointer; }
  .aurora-x:hover { background: rgba(255,255,255,0.06); color: var(--color-plex-text); }
`;

/** Aurora "curtains" glyph used in the roundel. */
function AuroraGlyph() {
  return (
    <svg viewBox="0 0 28 28" fill="none" aria-hidden="true">
      <g stroke="#fff" strokeWidth="1.5" strokeLinecap="round" opacity="0.92">
        <path d="M6 23c0-9 2-15 4-15" />
        <path d="M12 23c0-11 2-17 4-17" />
        <path d="M18 23c0-9 2-14 4-14" />
      </g>
      <circle cx="22" cy="5" r="1.6" fill="#fff" />
    </svg>
  );
}

/**
 * Full-width aurora alert banner displayed when NOAA SWPC reports a moderate or strong alert.
 *
 * Behaviour:
 * - Not rendered for free-tier users (403 from the API → status is null)
 * - Not rendered for QUIET or MINOR status
 * - Shows for MODERATE or STRONG with the alert colour as background
 * - Subtitle shows Kp and Bz status with plain-English explanation
 * - Pulses gently when Bz < −1 nT (favourable southward field)
 * - Dismissible per session; re-appears if the level escalates
 *
 * @param {object} props
 * @param {function} [props.onViewOnMap] - Called when the banner is activated for a
 *   live/forecast alert; the parent switches to the Map tab with the Aurora event
 *   pre-selected. When omitted, falls back to a plain hash navigation to the map.
 */
export default function AuroraBanner({ onViewOnMap = null }) {
  const { status } = useAuroraStatus();
  const viewlineEnabled = status != null && ALERT_WORTHY.has(status.level);
  const { viewline } = useAuroraViewline(viewlineEnabled);
  const [dismissedLevel, setDismissedLevel] = useState(null);

  // Not eligible (free-tier, 403) or not yet loaded
  if (!status) return null;

  // Not alert-worthy
  if (!ALERT_WORTHY.has(status.level)) return null;

  // Dismissed at this exact level — re-show only on escalation
  if (dismissedLevel === status.level) return null;

  const isSimulated = status.simulated === true;
  const detectedLabel = formatDetectedAt(status.detectedAt);

  let locationText = null;
  const allOvercast = status.darkSkyLocationCount > 0
    && status.clearLocationCount != null && status.clearLocationCount === 0;
  if (!allOvercast && status.darkSkyLocationCount > 0) {
    if (status.clearLocationCount != null) {
      const c = status.clearLocationCount;
      locationText = `${c} dark sky location${c !== 1 ? 's' : ''} clear`;
    } else {
      const c = status.darkSkyLocationCount;
      locationText = `${c} dark sky location${c !== 1 ? 's' : ''}`;
    }
  }

  const displayKp = status.forecastKp ?? status.kp;
  const isForecastTrigger = !isSimulated && status.triggerType === 'forecast';
  const kpText = displayKp != null
    ? (isSimulated
        ? `Kp ${Math.round(displayKp)} (SIMULATED)`
        : isForecastTrigger
          ? `Kp ${Math.round(displayKp)} forecast `
          : `Kp ${Math.round(displayKp)}`)
    : null;

  const bz = status.bzNanoTesla;
  const bzInfo = bz != null ? bzStatus(bz) : null;
  const bzText = bzInfo ? `${bzInfo.emoji} ${bzInfo.label} — ${bzInfo.explanation}` : null;

  const actionCta = isSimulated ? 'Generate scores →' : 'View on map →';

  const viewlineSummary = !isSimulated && viewline?.active ? viewline.summary : null;

  // Severity presentation. The accent hue comes from the API (amber for
  // MODERATE, red for STRONG); the level word + optional NOAA G-scale make the
  // escalation legible in text, not just colour.
  const accent = status.hexColour || '#F5991F';
  const levelWord = { MODERATE: 'Moderate', STRONG: 'Strong', SEVERE: 'Severe', EXTREME: 'Extreme' }[status.level]
    || (status.level ? status.level.charAt(0) + status.level.slice(1).toLowerCase() : 'Alert');
  const gScale = status.gScale || null;
  const sevLabel = gScale ? `${levelWord} · ${gScale}` : levelWord;

  const isFavourable = !isSimulated && !isForecastTrigger && bz != null && bz < -1;

  function handleDismiss(e) {
    e.stopPropagation();
    setDismissedLevel(status.level);
  }

  // Simulated alerts jump to the Manage tab; a real alert hands off to the Map tab
  // with the Aurora event pre-selected (via onViewOnMap), falling back to a plain
  // hash navigation when no handler is supplied.
  function handleActivate() {
    if (isSimulated) {
      window.location.hash = 'manage';
    } else if (onViewOnMap) {
      onViewOnMap();
    } else {
      window.location.hash = 'map';
    }
  }

  return (
    <>
      <style>{AURORA_A_STYLE}</style>
      {isFavourable && <style>{PULSE_STYLE}</style>}
      {isSimulated && <style>{SIM_BORDER_STYLE}</style>}
      {/* eslint-disable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
      <div
        role="alert"
        data-testid="aurora-banner"
        style={{
          '--aurora-accent': accent,
          backgroundColor: 'var(--color-plex-surface)',
          animation: isFavourable ? 'aurora-pulse 3s ease-in-out infinite' : undefined,
        }}
        className={`aurora-a px-4 py-3 rounded-xl select-none cursor-pointer${isSimulated ? ' aurora-banner-simulated' : ''}`}
        onClick={handleActivate}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') handleActivate();
        }}
        tabIndex={0}
      >
      {/* eslint-enable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
        <div className="aurora-rail" />
        <div className="aurora-glow" />
        <div className="aurora-inner">
          <div className="aurora-roundel">
            {isSimulated ? <span style={{ fontSize: '20px' }} aria-hidden="true">🧪</span> : <AuroraGlyph />}
          </div>
          <div className="aurora-body">
            <div className="aurora-top">
              <span className="aurora-sev">
                <span className="aurora-dot" />
                {isSimulated ? `${sevLabel} · simulated` : sevLabel}
              </span>
              <span className="aurora-when">
                {isSimulated
                  ? 'Simulated'
                  : isForecastTrigger
                    ? 'Aurora Forecast'
                    : 'Aurora Active Now'}
                {!isSimulated && !isForecastTrigger && detectedLabel && (
                  <>
                    {' · '}
                    <span data-testid="aurora-banner-detected">Detected {detectedLabel}</span>
                  </>
                )}
              </span>
            </div>

            <p className="aurora-title">{status.description}</p>

            <p className="aurora-meta">
              {kpText && (
                <span className="aurora-kp">
                  {kpText}
                  {isForecastTrigger && <strong className="italic">tonight</strong>}
                </span>
              )}
              {kpText && locationText && <span className="aurora-div" aria-hidden="true" />}
              {locationText && <span>{locationText}</span>}
              {(kpText || locationText) && <span className="aurora-div" aria-hidden="true" />}
              <span className="aurora-cta">{actionCta}</span>
            </p>

            {allOvercast && (
              <p data-testid="aurora-banner-overcast" className="aurora-sub">
                All locations overcast — no clear skies forecast <strong className="italic">tonight</strong>
              </p>
            )}
            {!isSimulated && bzText && (
              <p data-testid="aurora-banner-bz" className="aurora-sub">{bzText}</p>
            )}
            {viewlineSummary && (
              <p data-testid="aurora-banner-viewline" className="aurora-sub">🌌 {viewlineSummary}</p>
            )}
          </div>

          <button
            onClick={handleDismiss}
            aria-label="Dismiss aurora banner"
            data-testid="aurora-banner-dismiss"
            className="aurora-x"
          >
            ✕
          </button>
        </div>
      </div>
    </>
  );
}

AuroraBanner.propTypes = {
  onViewOnMap: PropTypes.func,
};
