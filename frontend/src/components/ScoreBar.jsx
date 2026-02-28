import React from 'react';
import PropTypes from 'prop-types';

/**
 * Horizontal 0–100 percentage bar with a label and numeric value.
 *
 * @param {object} props
 * @param {string} props.label - Descriptive label shown to the left.
 * @param {number|null} props.score - Score from 0 to 100, or null if unavailable.
 * @param {string} [props.testId] - Optional data-testid for the root element.
 */
export default function ScoreBar({ label, score, testId }) {
  const pct = score != null ? Math.min(100, Math.max(0, score)) : null;

  const barColour =
    pct == null  ? 'bg-plex-text-muted' :
    pct >= 75    ? 'bg-plex-gold' :
    pct >= 50    ? 'bg-plex-gold-dark' :
    pct >= 25    ? 'bg-amber-700' :
                   'bg-plex-text-muted';

  return (
    <div data-testid={testId} className="flex flex-col gap-1">
      <div className="flex items-center justify-between text-xs">
        <span className="text-plex-text-secondary">{label}</span>
        <span className="font-semibold text-plex-text">
          {pct != null ? `${pct}` : '—'}
        </span>
      </div>
      <div className="h-2 rounded-full bg-plex-border overflow-hidden">
        <div
          className={`h-full rounded-full transition-all ${barColour}`}
          style={{ width: pct != null ? `${pct}%` : '0%' }}
        />
      </div>
    </div>
  );
}

ScoreBar.propTypes = {
  label: PropTypes.string.isRequired,
  score: PropTypes.number,
  testId: PropTypes.string,
};
