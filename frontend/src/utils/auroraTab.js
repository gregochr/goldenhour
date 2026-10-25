import { isRealAlert } from './auroraBannerScores.js';
import { isStillCurrent } from './mapDates.js';
import { appNow } from './rewind.js';

/**
 * Whether the Map tab may stand on an aurora alert alone, with no sunrise or sunset forecast behind
 * it (overlay convergence C3, `docs/engineering/overlay-convergence-plan.md` §3 C3 and §5 D4).
 *
 * <p>The server twin is `AuroraNightsService` (`GET /api/aurora/nights`, the nights a Pro reader's window
 * control offers a row for, which drives the Lite aurora door); a change to this rule must change both.
 *
 * <p>One predicate, read by BOTH `App` (is the Map pane offered at all, and what date does it open
 * on) and `MapView` (does the live night get a window-control row), so the two can never disagree
 * about which alert counts.
 *
 * <p>The alert must be {@code active} AND a REAL alert at MODERATE or STRONG ({@link isRealAlert}),
 * AND its night must not have ended:
 * <ul>
 *   <li><b>MODERATE or STRONG</b> because per-location scores are fetched from that level up
 *       (`MapView` asks `getAuroraLocations` only then), so a weaker alert would offer a tab with a
 *       row, no scores and nothing to draw.</li>
 *   <li><b>Never a simulated alert</b> (`docs/engineering/aurora-banner-plan.md` D9, "a simulated
 *       alert asks for nothing"; CLAUDE.md "Admin simulation is isolated from every user-facing
 *       surface"): a simulation has no scores and no stored run, so a tab built on one would be an
 *       empty map dressed as a forecast. The banner still shows it, with its test tube.</li>
 *   <li><b>Never a stale status</b>: the status provider keeps the last answer when polls fail, so a
 *       status whose night has ended ({@code currentNightEndsAt} past, the test
 *       {@code resolveAuroraNight} applies) describes an alert that is over.</li>
 *   <li><b>PRO or ADMIN only</b>: aurora is a Pro feature (a LITE account has no aurora status at
 *       all, and this role test is the second lock, not the first). A positive test, so an unknown
 *       role is refused rather than admitted.</li>
 * </ul>
 *
 * @param {?object} status the shared aurora status ({@code useAuroraStatus})
 * @param {?string} role the signed-in role
 * @param {Date} [now] the instant to judge the night's end by; injectable for tests
 * @returns {boolean}
 */
export function auroraTabOffered(status, role, now = appNow()) {
  return (role === 'PRO_USER' || role === 'ADMIN') && auroraNightLive(status, now);
}

/**
 * Whether the status describes a live, REAL alert whose night has not ended - {@link auroraTabOffered}
 * without the role test, so the aurora banner (which has no role, only a status that a LITE account
 * never receives) asks the SAME question the Map pane's offer does rather than a second predicate.
 * A live night is what gives the window control its live aurora row (overlay convergence C3); a
 * stale or inactive status has none, and a banner door onto it would open the map on a solar window.
 *
 * @param {?object} status the shared aurora status
 * @param {Date} [now] the instant to judge the night's end by
 * @returns {boolean}
 */
export function auroraNightLive(status, now = appNow()) {
  return status?.active === true
    && isRealAlert(status)
    && isStillCurrent(status.currentNightEndsAt, now);
}
