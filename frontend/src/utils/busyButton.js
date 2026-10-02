/**
 * A button that says it is busy WITHOUT `disabled`. Measured in Chromium, WebKit and Firefox: a
 * focused button that becomes `disabled` loses focus — at once in Chromium, within one to four
 * frames in the other two — so the reader who pressed it is dropped on `<body>`, and a failed
 * request leaves them there. `aria-disabled` keeps it focusable; these copy `.btn-primary`'s own
 * `disabled:` treatment, and the handler refuses a second press.
 *
 * <p>⚠️ So it looks as it did in ordinary rendering, and not in two cases. Forced-colours mode
 * repaints a `disabled` button's text and border in the system's GrayText and leaves an
 * `aria-disabled` one in ButtonText (measured in Chromium), so there the busy state shows only as
 * the dimming. And a reader who pressed it from the keyboard keeps focus on it, so its focus
 * indicator stays drawn through the request where `disabled` used to take the focus, and the
 * indicator, away — dimmed with the button to 40%.
 *
 * <p>For `.btn-primary`. A `.btn-secondary` button uses {@link BUSY_BUTTON_SECONDARY}, whose hover
 * background is its own, not gold.
 */
export const BUSY_BUTTON = 'aria-disabled:opacity-40 aria-disabled:cursor-not-allowed aria-disabled:hover:bg-plex-gold';

/** The same busy treatment for `.btn-secondary`, copying its `disabled:hover:bg-plex-surface-light`. */
export const BUSY_BUTTON_SECONDARY = 'aria-disabled:opacity-40 aria-disabled:cursor-not-allowed aria-disabled:hover:bg-plex-surface-light';
