/**
 * The words of the "set a postcode" nudge — one home for the tick line's button and every other
 * surface that has to say the same thing when there is no home to measure from.
 *
 * <p>Two renderings of one sentence, as the tick line draws them: the long form from the small
 * breakpoint up, and the short form on a phone. The long form is also the button's accessible name,
 * and it must stay a superstring of the short one (WCAG 2.5.3, label in name — see
 * {@code MastheadTickLine}). Ask PhotoCast's "Plan this" (F5) reuses them for its own empty state
 * rather than writing a third wording for a reader who has no postcode.
 */

/** The long form: the accessible name, and the visible text from the small breakpoint up. */
export const SET_POSTCODE_LONG = 'Set a postcode for light and drive times';

/** The short form, for a phone. */
export const SET_POSTCODE_SHORT = 'Set a postcode';
