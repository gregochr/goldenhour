import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import WarningChip, { WarningFooter } from '../WarningChip.jsx';
import { useWarnings } from '../../context/WarningsContext.jsx';
import { instantMs, validityText, warningLabel } from '../../utils/warnings.js';

/**
 * How the polled list is ordered against the answer's facts. Both carry the time the Met Office feed was last read
 * (the same server clock). 'current': the list is at least as new, so it can judge the facts. 'older': it knows
 * nothing of a warning issued since, so it must never retire one (and is refreshed). 'unknown': the list has not
 * arrived (or has never been read), so there is nothing to judge with yet; the provider's own poll is already on its
 * way. An answer with no check time (the feed had never been read) is older than any list that has one.
 *
 * @param {?string} answerCheckedAt the answer's facts' feed-check instant
 * @param {object} served the shared polled warnings
 * @returns {'current'|'older'|'unknown'}
 */
function listAgainstAnswer(answerCheckedAt, served) {
  if (!served.enabled) return 'unknown';
  const listMs = instantMs(served.lastCheckedAt);
  if (Number.isNaN(listMs)) return 'unknown';
  const answerMs = instantMs(answerCheckedAt);
  return Number.isNaN(answerMs) || listMs >= answerMs ? 'current' : 'older';
}

/** The feed's version number, or NaN when it is missing or unreadable. */
function versionNumber(version) {
  return version == null ? Number.NaN : Number.parseFloat(String(version));
}

/**
 * Whether a warning an answer carried is still the one in force, judged against the polled
 * {@code GET /api/warnings} list (the same authority every other warning surface reads), so an answer left open
 * cannot keep showing a warning the Met Office has since cancelled or re-issued (plan §12, the ten-minute bound). Only
 * a list at least as new as the answer's facts counts: otherwise the answer's own facts stand. Retired only when that
 * newer list lacks the id (gone) or holds a higher version (updated); an older or unreadable version is "not yet
 * known", never "updated".
 *
 * @param {object} item the warning as the answer carried it
 * @param {object} served the shared polled warnings
 * @param {boolean} comparable whether the list is at least as new as the answer's facts
 * @returns {'current'|'gone'|'updated'}
 */
function standing(item, served, comparable) {
  if (!comparable) return 'current';
  const live = served.warnings.find((w) => w.warningId === item.warningId);
  if (!live) return 'gone';
  return versionNumber(live.warningVersion) > versionNumber(item.warningVersion) ? 'updated' : 'current';
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
 * against the polled list when that list is at least as new as the answer's facts: one that has gone or been updated
 * is retired visibly ("no longer in force" or "has been updated", check the Met Office) instead of shown as current. A stale feed, the answer's or the provider's, says so in
 * plain words. A warning never changes a pick or a rating: this block sits beside them and touches neither.
 *
 * @param {object} props
 * @param {?{stale: boolean, attribution: string, url: string, items: object[]}} props.warnings the normalised
 *        {@code answer.warnings}, or null for an answer that mentions none
 */
export default function AskWarnings({ warnings }) {
  const served = useWarnings();
  const [refreshFailed, setRefreshFailed] = useState(false);
  const order = warnings ? listAgainstAnswer(warnings.checkedAt, served) : 'unknown';
  const comparable = order === 'current';
  const needsRefresh = order === 'older';
  const { refresh, lastCheckedAt } = served;
  // A list older than the answer's facts is brought up to date now, not at the next poll; meanwhile the answer's own
  // facts stand. It re-runs when a poll brings a different (still older) list, but not for the same one, so an
  // unchanged feed cannot loop. A failed refresh is said out loud (the stale line), never read as "still in force".
  useEffect(() => {
    if (!needsRefresh) return undefined;
    let current = true;
    refresh().then((ok) => { if (current) setRefreshFailed(ok === false); });
    return () => { current = false; };
  }, [needsRefresh, refresh, lastCheckedAt]);
  if (!warnings) return null;
  const stale = warnings.stale || (served.enabled && served.stale) || (needsRefresh && refreshFailed);
  return (
    <div
      className="flex flex-col gap-1.5 py-1.5"
      role="group"
      aria-label="Met Office weather warnings mentioned in this answer"
      data-testid="ask-warnings"
    >
      {warnings.items.map((warning) => {
        const state = standing(warning, served, comparable);
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
    checkedAt: PropTypes.string,
    items: PropTypes.arrayOf(PropTypes.object),
  }),
};
