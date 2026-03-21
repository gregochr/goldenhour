import React, { useState } from 'react';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';

// Alert levels that should show the banner (MODERATE or STRONG)
const ALERT_WORTHY = new Set(['MODERATE', 'STRONG']);

/**
 * Full-width aurora alert banner displayed when NOAA SWPC reports a moderate or strong alert.
 *
 * Behaviour:
 * - Not rendered for free-tier users (403 from the API → status is null)
 * - Not rendered for QUIET or MINOR status
 * - Shows for MODERATE or STRONG with the alert colour as background
 * - Dismissible per session; re-appears if the level escalates
 */
export default function AuroraBanner() {
  const { status } = useAuroraStatus();
  const [dismissedLevel, setDismissedLevel] = useState(null);

  // Not eligible (free-tier, 403) or not yet loaded
  if (!status) return null;

  // Not alert-worthy
  if (!ALERT_WORTHY.has(status.level)) return null;

  // Dismissed at this exact level — re-show only on escalation
  if (dismissedLevel === status.level) return null;

  const count = status.eligibleLocations;
  const locationText = count > 0
    ? `${count} location${count !== 1 ? 's' : ''} available`
    : null;

  const displayKp = status.forecastKp ?? status.kp;
  const kpText = displayKp != null
    ? (status.triggerType === 'forecast'
        ? `Kp ${Math.round(displayKp)} forecast tonight`
        : `Kp ${Math.round(displayKp)}`)
    : null;
  const subtitleParts = [kpText, locationText ? `${locationText} · Tap to view on map` : null]
    .filter(Boolean);

  function handleDismiss(e) {
    e.stopPropagation();
    setDismissedLevel(status.level);
  }

  return (
    <div
      role="alert"
      data-testid="aurora-banner"
      style={{ backgroundColor: status.hexColour }}
      className="px-4 py-2 text-white cursor-pointer select-none"
      onClick={() => {
        // Navigate to map view — handled by parent via hash
        window.location.hash = 'map';
      }}
    >
      <div className="max-w-4xl mx-auto flex items-center justify-between gap-4">
        <div className="flex items-center gap-3 min-w-0">
          <span className="text-lg shrink-0" aria-hidden="true">🌌</span>
          <div className="min-w-0">
            <p className="text-sm font-bold leading-tight">
              Aurora Alert — {status.description}
            </p>
            {subtitleParts.length > 0 && (
              <p className="text-xs opacity-90 mt-0.5">{subtitleParts.join(' · ')}</p>
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
  );
}
