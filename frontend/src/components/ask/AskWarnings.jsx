import PropTypes from 'prop-types';
import WarningChip, { WarningFooter } from '../WarningChip.jsx';
import { useWarnings } from '../../context/WarningsContext.jsx';
import { validityText, warningLabel } from '../../utils/warnings.js';

/**
 * Whether a warning an answer carried is still the one in force, judged against the polled
 * {@code GET /api/warnings} list (the same authority every other warning surface reads), so an answer left open
 * cannot keep showing a warning the Met Office has since updated, cancelled or let expire (plan §12, the ten-minute
 * bound). Before the first poll, outside a provider and with the feature off the list is unknown, so the answer's own
 * facts stand.
 *
 * @param {object} item the warning as the answer carried it
 * @param {object} served the shared polled warnings
 * @returns {'current'|'gone'|'updated'}
 */
function standing(item, served) {
  if (!served.enabled) return 'current';
  const live = served.warnings.find((w) => w.warningId === item.warningId);
  if (!live) return 'gone';
  const sameVersion = item.warningVersion == null || live.warningVersion == null
    || String(live.warningVersion) === String(item.warningVersion);
  return sameVersion ? 'current' : 'updated';
}

const RETIRED_TEXT = {
  gone: 'this warning is no longer in force.',
  updated: 'this warning has been updated since this answer.',
};

/**
 * The Met Office weather warnings an Ask answer mentions (weather-warnings-plan.md §14, W6; owner example: an Amber
 * warning of snow at Hadrian's Wall).
 *
 * <p>Drawn from the facts the server attached, never from the model's sentence: the shared chip (the official level
 * colour, the level word always printed, "Updated" for a re-issued warning, no icon and no logo), the Met Office's own
 * headline and the validity in UK time, then the attribution and link the licence requires. Each warning is checked
 * against the live polled list: one that has gone or been updated is retired visibly ("no longer in force" or "has been
 * updated", check the Met Office) instead of shown as current. A stale feed, the answer's or the provider's, says so in
 * plain words. A warning never changes a pick or a rating: this block sits beside them and touches neither.
 *
 * @param {object} props
 * @param {?{stale: boolean, attribution: string, url: string, items: object[]}} props.warnings the normalised
 *        {@code answer.warnings}, or null for an answer that mentions none
 */
export default function AskWarnings({ warnings }) {
  const served = useWarnings();
  if (!warnings) return null;
  const stale = warnings.stale || (served.enabled && served.stale);
  return (
    <div
      className="flex flex-col gap-1.5 py-1.5"
      role="group"
      aria-label="Met Office weather warnings mentioned in this answer"
      data-testid="ask-warnings"
    >
      {warnings.items.map((warning) => {
        const state = standing(warning, served);
        if (state !== 'current') {
          return (
            <p
              key={warning.warningId}
              className="font-mono text-[11px] leading-snug text-plex-text"
              data-testid="ask-warning-retired"
              data-state={state}
            >
              {`${warningLabel(warning) ?? 'Weather warning'}: ${RETIRED_TEXT[state]} Check the Met Office.`}
            </p>
          );
        }
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
      {stale && (
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
