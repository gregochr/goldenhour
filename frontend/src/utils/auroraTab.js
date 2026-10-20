import { isRealAlert } from './auroraBannerScores.js';

/**
 * Whether the Map tab may stand on an aurora alert alone, with no sunrise or sunset forecast behind
 * it (overlay convergence C3, `docs/engineering/overlay-convergence-plan.md` §3 C3 and §5 D4).
 *
 * <p>One predicate, read by BOTH `App` (is the Map pane offered at all, and what date does it open
 * on) and `MapView` (does the live night get a window-control row), so the two can never disagree
 * about which alert counts.
 *
 * <p>The alert must be {@code active} AND a REAL alert at MODERATE or STRONG ({@link isRealAlert}):
 * <ul>
 *   <li><b>MODERATE or STRONG</b> because per-location scores are fetched from that level up
 *       (`MapView` asks `getAuroraLocations` only then), so a weaker alert would offer a tab with a
 *       row, no scores and nothing to draw.</li>
 *   <li><b>Never a simulated alert</b> (plan §4 D9, CLAUDE.md "Admin simulation is isolated from
 *       every user-facing surface"): a simulation has no scores and no stored run, so a tab built
 *       on one would be an empty map dressed as a forecast. The banner still shows it, with its
 *       test tube; it just does not open a map that has nothing real to say.</li>
 *   <li><b>Never LITE</b>: aurora is PRO/ADMIN (a LITE account has no aurora status at all, and the
 *       role test here is the second lock, not the first).</li>
 * </ul>
 *
 * @param {?object} status the shared aurora status ({@code useAuroraStatus})
 * @param {?string} role the signed-in role
 * @returns {boolean}
 */
export function auroraTabOffered(status, role) {
  return role !== 'LITE_USER' && status?.active === true && isRealAlert(status);
}
