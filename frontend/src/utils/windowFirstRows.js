/**
 * The tide vocabulary shared by the Map's tide strip, the Glance thumbnails' tide wording and Ask's pick facts.
 *
 * <p>The window's attribute rows this file used to build (the tide row and its sparkline, the topic facts) have been
 * deleted; only the two word tables survive, because several live surfaces name a tide the same way.
 */

/**
 * Water level → the words the client states it in.
 *
 * <p>Reused rather than copied (`docs/engineering/tide-window-plan.md` T5 and T6): `utils/mapTideFit.js#wantPhrase`
 * joins a location's wanted {@code TideType} set into words ("high water or low water") for the callout's jump and
 * denial lines, and the tide strip reads it for its header phrase and footer "wants" clause. One vocabulary for what a
 * tide STATE is called, wherever the client names one.
 */
export const STATE_WORD = { HIGH: 'high water', MID: 'mid tide', LOW: 'low water' };

/**
 * Which way the water is going — a separate axis from the level, and both are needed.
 *
 * <p>Exported alongside {@link STATE_WORD} for the same reason (tide-window-plan.md T6).
 */
export const DIRECTION_WORD = { RISING: 'rising', FALLING: 'falling' };
