import React, { useState } from 'react';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

// Alert levels that should show the banner (MODERATE or STRONG)
const ALERT_WORTHY = new Set(['MODERATE', 'STRONG']);

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
    border: 2px dashed rgba(255, 255, 255, 0.6);
  }
`;

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
 */
export default function AuroraBanner() {
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

  const count = status.eligibleLocations;
  const locationText = count > 0
    ? `${count} location${count !== 1 ? 's' : ''} available`
    : null;

  const displayKp = status.forecastKp ?? status.kp;
  const kpText = displayKp != null
    ? (isSimulated
        ? `Kp ${Math.round(displayKp)} (SIMULATED)`
        : status.triggerType === 'forecast'
          ? `Kp ${Math.round(displayKp)} forecast tonight`
          : `Kp ${Math.round(displayKp)}`)
    : null;

  const bz = status.bzNanoTesla;
  const bzInfo = bz != null ? bzStatus(bz) : null;
  const bzText = bzInfo ? `${bzInfo.emoji} ${bzInfo.label} — ${bzInfo.explanation}` : null;

  const actionHint = isSimulated
    ? 'Go to Forecast Runs → Aurora to generate scores'
    : locationText
      ? `${locationText} · Tap to view on map`
      : null;

  const viewlineSummary = !isSimulated && viewline?.active ? viewline.summary : null;
  const subtitleParts = [kpText, actionHint].filter(Boolean);

  const isFavourable = !isSimulated && bz != null && bz < -1;

  function handleDismiss(e) {
    e.stopPropagation();
    setDismissedLevel(status.level);
  }

  return (
    <>
      {isFavourable && <style>{PULSE_STYLE}</style>}
      {isSimulated && <style>{SIM_BORDER_STYLE}</style>}
      <div
        role="alert"
        data-testid="aurora-banner"
        style={{
          backgroundColor: status.hexColour,
          animation: isFavourable ? 'aurora-pulse 3s ease-in-out infinite' : undefined,
        }}
        className={`px-4 py-2 text-white cursor-pointer select-none${isSimulated ? ' aurora-banner-simulated' : ''}`}
        onClick={() => {
          window.location.hash = isSimulated ? 'manage' : 'map';
        }}
      >
        <div className="max-w-4xl mx-auto flex items-center justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <span className="text-lg shrink-0" aria-hidden="true">{isSimulated ? '🧪' : '🌌'}</span>
            <div className="min-w-0">
              <p className="text-sm font-bold leading-tight">
                {isSimulated ? 'SIMULATED — ' : 'Aurora Alert — '}{status.description}
              </p>
              {subtitleParts.length > 0 && (
                <p className="text-xs opacity-90 mt-0.5">{subtitleParts.join(' · ')}</p>
              )}
              {!isSimulated && bzText && (
                <p data-testid="aurora-banner-bz" className="text-xs opacity-90 mt-0.5">{bzText}</p>
              )}
              {viewlineSummary && (
                <p data-testid="aurora-banner-viewline" className="text-xs opacity-90 mt-0.5">
                  🌌 {viewlineSummary}
                </p>
              )}
            </div>
          </div>
          <button
            onClick={handleDismiss}
            aria-label="Dismiss aurora banner"
            data-testid="aurora-banner-dismiss"
            className="text-white opacity-80 hover:opacity-100 text-lg shrink-0 px-2 -mr-2"
          >
            ✕
          </button>
        </div>
      </div>
    </>
  );
}
