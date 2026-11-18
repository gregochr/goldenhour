import React from 'react';
import PropTypes from 'prop-types';
import { useWarnings } from '../context/WarningsContext.jsx';
import WarningChip, { WarningFooter } from './WarningChip.jsx';
import { WarningAdviceLink, WarningDetail } from './WindowWarningRow.jsx';
import { levelWord } from '../utils/warnings.js';

/**
 * The body of a Met Office warning card in "Coming up" (weather-warnings-plan.md W3b).
 *
 * <p>Always drawn, open or closed: the shared chip (official level colour, the level word always printed, "Updated"
 * for the served flag), "Raised from X" while the 24 h flag holds, and the attribution with the Met Office link the
 * licence requires wherever a warning shows. Open, it adds the warning in full through the same {@code WarningDetail}
 * the window popup uses (headline, update text, every what-to-expect line, what should I do, further details, validity
 * in UK time, the matrix), the style guide's advice link and a door to the plan when the warning touches the scored
 * days. A warning that starts after the scored days only shows here, so the full text must be reachable here.
 *
 * <p>Everything is drawn from the polled warning that {@code reconcileFeed} attached, never from the feed's own copy.
 * No stars, verdict colours or ramp: a warning is not a rating. The stale line prints once at the top of the pane.
 *
 * @param {object} props
 * @param {object} props.entry the entry view; {@code warning} is the polled warning, {@code action} the served door
 * @param {boolean} props.open whether the card is open
 * @param {function} props.onGoToPlan switches to the Plan tab; takes the door's date
 */
export default function WindowComingUpWarning({ entry, open, onGoToPlan }) {
  const served = useWarnings();
  const { warning, action } = entry;
  if (!warning) return null;
  const raisedFrom = typeof warning.upgradedFrom === 'string' ? levelWord(warning.upgradedFrom) : null;
  const planDoor = action?.kind === 'plan' && action.date;
  return (
    <div className="flex flex-col gap-1.5" data-testid="coming-up-warning">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <WarningChip warning={warning} href={served.metOfficeUrl} />
        {raisedFrom && (
          <span data-testid="coming-up-warning-raised" className="font-mono text-[11px] text-plex-text-secondary">
            {`Raised from ${raisedFrom}`}
          </span>
        )}
      </div>
      {open && <WarningDetail warning={warning} showChip={false} />}
      {open && <WarningAdviceLink />}
      {open && planDoor && (
        <button
          type="button"
          className="wf-cu-action self-start"
          data-testid="coming-up-warning-plan-door"
          onClick={() => onGoToPlan(action.date)}
        >
          {action.label}
        </button>
      )}
      <WarningFooter
        showAttribution
        stale={false}
        lastCheckedAt={served.lastCheckedAt}
        attribution={served.attribution}
        href={served.metOfficeUrl}
      />
    </div>
  );
}

WindowComingUpWarning.propTypes = {
  entry: PropTypes.shape({
    warning: PropTypes.object,
    action: PropTypes.shape({
      kind: PropTypes.string,
      label: PropTypes.string,
      date: PropTypes.string,
    }),
  }).isRequired,
  open: PropTypes.bool.isRequired,
  onGoToPlan: PropTypes.func.isRequired,
};
