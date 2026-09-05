import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import useDialogFocus from '../hooks/useDialogFocus.js';

/**
 * Mobile bottom sheet overlay. Slides up from the bottom of the viewport
 * with a semi-transparent backdrop. Tap the backdrop or close button to dismiss.
 *
 * <p>Full-bleed (`left-0 right-0`), not the design bundle's own `left/right: 10px` inset —
 * map-tab-v2-plan.md §3 P12's Filters/Regions phone sheets keep this component exactly as every
 * other caller already has it, trading the bundle's literal pixel spec for one shared layout every
 * `BottomSheet` in the app already agrees on, rather than growing a second, inset-only variant.
 *
 * @param {object} props
 * @param {boolean} props.open - Whether the sheet is visible.
 * @param {function} props.onClose - Called when the user dismisses the sheet.
 * @param {string} [props.label] - The sheet's accessible name.
 * @param {boolean} [props.modal] - ARIA SEMANTICS ONLY — whether this sheet claims modality
 *        (`aria-modal="true"`). Defaults to `true`, unchanged for every existing caller. Pass
 *        `false` for a sheet that is standing in for a DISCLOSURE widget rather than a dialog —
 *        map-tab-v2-plan.md §3 P12's phone Filters/Regions menus, which are `FiltersPopover`/
 *        `RegionsJump`'s own popovers on desktop (no `aria-modal` there either). Focus is never
 *        trapped either way — `useDialogFocus` below is focus-in-and-restore, not containment, the
 *        app-wide rule `useDialogFocus`'s own class doc records. ⚠️ Every OTHER behaviour of this
 *        component is unaffected by `modal` and stays sheet-standard regardless: the full-viewport
 *        backdrop still catches every pointer event behind it and still dismisses on tap, and body
 *        scroll still locks while the sheet is open. That is a deliberate choice for a phone sheet
 *        standing over a pannable map — a `false` disclosure widget that let the map underneath pan
 *        or scroll through its own backdrop would be new behaviour, not a straight `aria-modal`
 *        swap, and is out of this prop's scope entirely.
 * @param {boolean} [props.reserveCloseStrip] - Keep the close button's own band clear of scrolling
 *        content. ⚠️ The button is `absolute` on the SHEET (y 8→40px) while the content below is a
 *        SCROLL CONTAINER starting at y 16px, so its top 24px sits under the button permanently —
 *        and padding inside the scroller does NOT fix it, because a scroll container's own padding
 *        scrolls away with its content. Whatever row is at the top of the scrollport is obscured
 *        and its taps are taken, at every scroll position but one. Opt in and a spacer is rendered
 *        BETWEEN the handle and the scroller, with the scroll budget shortened to match, so the
 *        scrollport begins below the button and nothing can ever reach it. Off by default: every
 *        existing caller keeps its exact geometry, and a sheet whose first row has nothing at its
 *        right edge does not need the 24px.
 * @param {React.ReactNode} props.children - Content rendered inside the sheet.
 */
export default function BottomSheet({
  open, onClose, label = 'Details', modal = true, reserveCloseStrip = false, children,
}) {
  // Prevent body scroll while open
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, [open]);

  // Gated on `open`, unlike Modal's — this component returns null rather than unmounting, so the
  // hook has to be told when the sheet is actually on screen.
  const dialogRef = useDialogFocus(open);

  if (!open) return null;

  return createPortal(
    <div data-testid="bottom-sheet-root">
      {/* Backdrop */}
      <div
        data-testid="bottom-sheet-overlay"
        className="fixed inset-0 bg-black/50"
        style={{ zIndex: 9999 }}
        role="button"
        tabIndex={-1}
        aria-label="Close"
        onClick={onClose}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onClose(); }}
      />

      {/* Sheet */}
      <div
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal={modal ? 'true' : undefined}
        // Named at last. A role="dialog" with no accessible name announces as "dialog" and nothing
        // else, so a screen-reader user was told something had opened but not what.
        aria-label={label}
        data-testid="bottom-sheet"
        // `left-0 right-0` is gone in favour of `app-safe-sheet`, which owns `left`/`right`/
        // `padding-bottom` together — see index.css for why the sides are inset while the foot is
        // padded. Identical to the old `left-0 right-0 bottom-0` on any device reporting no insets.
        //
        // ⚠️ Removed as HYGIENE, not because it would have won. Measured in Chromium against the
        // built stylesheet with the insets substituted for real values (47px sides): the sheet's
        // `left`/`right` resolve to 47px WITH `left-0 right-0` still on the element. Tailwind v4
        // emits its utilities into a cascade layer and these rules are unlayered, so they beat
        // every utility regardless of specificity or source order. What a left-behind `left-0`
        // would be is a declaration that never applies — markup telling a reader the sheet is
        // pinned to the viewport edges when it is not.
        className="app-safe-sheet fixed bottom-0 rounded-t-2xl bg-plex-surface border-t border-plex-border animate-slide-up focus:outline-none"
        style={{ zIndex: 10000, maxHeight: '60vh' }}
      >
        {/* Drag handle */}
        <div className="flex justify-center pt-2 pb-1">
          <div className="w-10 h-1 rounded-full bg-plex-border" />
        </div>

        {/* Close button */}
        <button
          data-testid="bottom-sheet-close"
          onClick={onClose}
          className="absolute top-2 right-3 w-8 h-8 flex items-center justify-center rounded-full text-plex-text-muted hover:text-plex-text transition-colors"
          aria-label="Close"
        >
          &#x2715;
        </button>

        {/* The close button's own band, held OUTSIDE the scroll container — see `reserveCloseStrip`.
            `aria-hidden` because it is geometry: the button it clears is a real sibling above. */}
        {reserveCloseStrip && <div aria-hidden="true" className="h-6" />}

        {/* Scrollable content */}
        {/* `- var(--safe-b)`: the outer sheet is `60vh` INCLUDING its new safe padding, so this
            budget has to give the same ground back or the scroll viewport overruns the padding box
            and ends inside the home-indicator zone. Resting text cleared it either way via `pb-6`,
            which is luck rather than design — this makes it the padding's job. The strip above is
            subtracted for the same reason — it is 24px the scroller no longer has. */}
        <div className="overflow-y-auto px-4 pb-6"
             style={{ maxHeight: `calc(60vh - ${reserveCloseStrip ? 64 : 40}px - var(--safe-b))` }}>
          {children}
        </div>
      </div>
    </div>,
    document.body,
  );
}

BottomSheet.propTypes = {
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  label: PropTypes.string,
  modal: PropTypes.bool,
  reserveCloseStrip: PropTypes.bool,
  children: PropTypes.node,
};
