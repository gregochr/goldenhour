import React from 'react';
import PropTypes from 'prop-types';
import { resolveRegionDisplay } from '../../utils/tierUtils.js';

/**
 * Colour pill for a display signal. Accepts either a {@code displayVerdict}
 * ({@code WORTH_IT} / {@code MAYBE} / {@code STAND_DOWN} / {@code AWAITING})
 * — preferred, it already reflects Claude ratings when available — or falls
 * back to the legacy triage {@code verdict} ({@code GO} / {@code MARGINAL} /
 * {@code STANDDOWN}). Signal resolution delegates to
 * {@link resolveRegionDisplay} so the verdict→display mapping has one home.
 *
 * <p>Optional {@code label} prop overrides the default text — used by the
 * Gate 2 honesty patch to surface "Too unsettled to forecast" on STAND_DOWN
 * regions where no Claude evaluation was produced.
 */
export default function VerdictPill({ displayVerdict = null, verdict = null, label = null }) {
  const signal = resolveRegionDisplay({ displayVerdict, verdict });
  const colours = {
    WORTH_IT: 'bg-green-600 text-white',
    MAYBE: 'bg-amber-600 text-white',
    STAND_DOWN: 'bg-red-900/60 text-red-200/70',
    AWAITING: 'bg-plex-surface text-plex-text-secondary border border-plex-border',
  };
  const labels = {
    WORTH_IT: 'Worth it',
    MAYBE: 'Maybe',
    STAND_DOWN: 'Stand down',
    AWAITING: 'Awaiting',
  };
  return (
    <span
      data-testid="verdict-pill"
      className={`inline-block px-2 py-0.5 rounded text-[12px] font-bold ${colours[signal] || 'bg-plex-surface text-plex-text-secondary'}`}
    >
      {label || labels[signal] || signal}
    </span>
  );
}

VerdictPill.propTypes = {
  displayVerdict: PropTypes.string,
  verdict: PropTypes.string,
  label: PropTypes.string,
};
