import React from 'react';
import PropTypes from 'prop-types';
import { useWarnings } from '../context/WarningsContext.jsx';
import WarningChip, { WarningFooter } from './WarningChip.jsx';
import { levelWord, validityText } from '../utils/warnings.js';

/**
 * The body of a Met Office warning card in "Coming up" (weather-warnings-plan.md W3b): the shared chip (official
 * level colour, the level word always printed, "Updated" for the served flag), the headline, what changed on an
 * update, the validity in UK time, a door to the plan when the warning reaches the scored days, and the attribution
 * with the Met Office link that the licence requires wherever a warning shows.
 *
 * <p>Everything is drawn from the polled warning that {@code reconcileFeed} attached, never from the feed's own copy,
 * so a changed level or text shows at the next two-minute poll. No stars, verdict colours or ramp: a warning is not a
 * rating, and nothing here reads or changes one. The stale line is printed once at the top of the pane, not per card.
 *
 * @param {object} props
 * @param {object} props.entry the entry view; {@code warning} is the polled warning, {@code action} the served door
 * @param {function} props.onGoToPlan switches to the Plan tab; takes the door's date
 */
export default function WindowComingUpWarning({ entry, onGoToPlan }) {
  const served = useWarnings();
  const { warning, action } = entry;
  if (!warning) return null;
  const raisedFrom = typeof warning.upgradedFrom === 'string' ? levelWord(warning.upgradedFrom) : null;
  const validity = validityText(warning);
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
      {warning.headline && (
        <p data-testid="coming-up-warning-headline" className="text-[13px] leading-snug text-plex-text">
          {warning.headline}
        </p>
      )}
      {warning.updated === true && warning.updateDescription && (
        <p data-testid="coming-up-warning-update" className="text-[12px] leading-snug text-plex-text-secondary">
          {warning.updateDescription}
        </p>
      )}
      {validity && (
        <p data-testid="coming-up-warning-validity" className="font-mono text-[11px] text-plex-text-secondary">
          {validity}
        </p>
      )}
      {planDoor && (
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
  onGoToPlan: PropTypes.func.isRequired,
};
