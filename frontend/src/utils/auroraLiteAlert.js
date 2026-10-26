import { ALERT_WORTHY } from './auroraBannerScores.js';
import { isStillCurrent } from './mapDates.js';
import { appNow } from './rewind.js';

/**
 * Whether a LITE reader's aurora alert is worth raising (docs/engineering/aurora-lite-alert-plan.md §2.3):
 * the alert is active, MODERATE or STRONG (the one {@link ALERT_WORTHY} the Pro banner uses), its night is
 * still the current one, and not every dark-sky site is overcast unless the storm is STRONG. The Lite
 * banner and the Map's viewline door both read it, so they never disagree about whether an alert is up.
 * A missing alert is not shown. Fails open on a missing night end, as {@link isStillCurrent} does.
 *
 * @param {?object} liteAlert the role-free alert from {@code GET /api/aurora/alert}, or null
 * @param {Date} [now] the instant the night's end is judged against; injectable so tests can pin it
 * @returns {boolean} true when the alert should be shown
 */
export function isLiteAlertShown(liteAlert, now = appNow()) {
  if (!liteAlert) return false;
  if (liteAlert.active !== true) return false;
  if (!ALERT_WORTHY.has(liteAlert.level)) return false;
  if (!isStillCurrent(liteAlert.currentNightEndsAt, now)) return false;
  return !(liteAlert.allOvercast === true && liteAlert.level !== 'STRONG');
}
