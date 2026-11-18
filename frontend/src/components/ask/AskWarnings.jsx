import PropTypes from 'prop-types';
import WarningChip, { WarningFooter } from '../WarningChip.jsx';
import { validityText } from '../../utils/warnings.js';

/**
 * The Met Office weather warnings an Ask answer mentions (weather-warnings-plan.md §14, W6; owner example: an Amber
 * warning of snow at Hadrian's Wall).
 *
 * <p>Drawn from the facts the server attached, never from the model's sentence: the shared chip (the official level
 * colour, the level word always printed, "Updated" for a re-issued warning, no icon and no logo), the Met Office's own
 * headline and the validity in UK time, then the attribution and link the licence requires. A stale feed says so in
 * plain words, so an answer built on old warnings never reads as complete. A warning never changes a pick or a
 * rating: this block sits beside them and touches neither.
 *
 * @param {object} props
 * @param {?{stale: boolean, attribution: string, url: string, items: object[]}} props.warnings the normalised
 *        {@code answer.warnings}, or null for an answer that mentions none
 */
export default function AskWarnings({ warnings }) {
  if (!warnings) return null;
  return (
    <div
      className="flex flex-col gap-1.5 py-1.5"
      role="group"
      aria-label="Met Office weather warnings mentioned in this answer"
      data-testid="ask-warnings"
    >
      {warnings.items.map((warning) => {
        const validity = validityText(warning);
        return (
          <div key={warning.warningId} className="flex flex-col gap-0.5" data-testid="ask-warning">
            <WarningChip warning={warning} href={warnings.url} />
            {warning.headline && (
              <span className="text-[12px] leading-snug text-plex-text" data-testid="ask-warning-headline">
                {warning.headline}
              </span>
            )}
            {validity && (
              <span className="font-mono text-[11px] text-plex-text-secondary" data-testid="ask-warning-validity">
                {validity}
              </span>
            )}
          </div>
        );
      })}
      {warnings.stale && (
        <p className="font-mono text-[11px] text-plex-text-secondary" data-testid="ask-warnings-stale">
          These warnings may be out of date. Check the Met Office.
        </p>
      )}
      <WarningFooter showAttribution stale={false} attribution={warnings.attribution} href={warnings.url} />
    </div>
  );
}

AskWarnings.propTypes = {
  warnings: PropTypes.shape({
    stale: PropTypes.bool,
    attribution: PropTypes.string,
    url: PropTypes.string,
    items: PropTypes.arrayOf(PropTypes.object),
  }),
};
