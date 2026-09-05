import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import useDialogFocus from '../../hooks/useDialogFocus.js';

const MAX_WIDTH = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-lg' };

/**
 * Shared modal overlay with a centred card panel.
 *
 * <p>When `bare` is true, only the overlay and backdrop are rendered — children must provide their
 * own panel element. Use this for modals with header/body/footer sections that own their padding.
 *
 * <h2>Focus</h2>
 *
 * <p>The dialog takes focus when it opens and hands it back to whatever had it when it closes —
 * see {@link useDialogFocus}, which also records why this is not a focus trap. Before that, `role`
 * and `aria-modal` were the whole of the implementation, and they are semantics: a keyboard user
 * had to Tab through the entire page behind the backdrop to reach a dialog that had just opened
 * over it.
 *
 * <h2>{@code stacked} — the one thing a caller may say about a dialog OVER a dialog</h2>
 *
 * <p>Opt-in and {@code false} by default, so a caller that does not pass it renders exactly what it
 * rendered before: {@code aria-modal="true"}, no {@code inert}. Most render sites are such a
 * caller, and a pinning test holds it. (Plan-matrix §3 rule 10 — "any edit to {@code Modal} must
 * leave v1 byte-identical" — is discharged along with the rest of the v1 UI it protected; see the
 * v1-retirement plan §4.3 for the ruling this component now runs under.)
 *
 * <p><b>What it is for.</b> The Plan screen stacks up to three of these — the window popup, a sheet
 * over it, and search over that — and M5 measured the result in a real browser: three elements
 * carrying {@code aria-modal="true"} at once, and a Tab walk that left the topmost sheet, crossed
 * the page behind it and landed <em>inside the popup underneath</em> on the seventeenth press.
 * "There is exactly one modal" was simply false, and the two attributes here are what make it true
 * again: a stacked layer is {@code inert} (so it holds no tab stops and leaves the accessibility
 * tree entirely) and drops {@code aria-modal} (so no two elements claim to be the modal at once).
 *
 * <p><b>⚠️ It is NOT a focus trap, and it is deliberately not a step towards one.</b>
 * {@link useDialogFocus} records at length why containment was refused for this app, and nothing
 * here reverses that: from the TOPMOST dialog a keyboard reader can still Tab out into the page
 * behind, exactly as they always could. What is fixed is the part
 * that only exists because of stacking — Tab reaching a LOWER dialog, and a screen reader being
 * offered two modals. {@code inert} costs no focusable query, no containment rule and no portal
 * special-case, which is what the whole of that hook's argument was against.
 *
 * <h2>⚠️ Stacking blurs, and the layer underneath has to remember for itself</h2>
 *
 * <p>{@code inert} takes focus off whatever inside it had it, and because {@code stacked} is a PROP
 * that lands in React's mutation phase, the blur happens before any effect in the commit — including
 * {@link useDialogFocus}'s, which is where the arriving dialog reads {@code document.activeElement}
 * to learn where to send focus back. So the naive version broke the thing the hook exists for: open
 * the location sheet from a field chip on the popup's map, press Escape, and focus landed on
 * {@code <body>} rather than on the chip. Measured in a real browser, both before and after — and
 * intermittently, since it is a race with how fast React flushes passive effects, which is exactly
 * the kind of defect that reaches a pilot.
 *
 * <p>The fix is here rather than in the hook, because the hook's reading is correct for every other
 * caller: a dialog records the last element focused <em>inside itself</em> from real focus events —
 * so it is remembered <b>before</b> anything can blur it — and puts focus back there when it stops
 * being stacked. It runs in a passive effect, and React destroys the departing layer's passive
 * effects before running this one, so the restore lands after the hook's and wins deterministically
 * rather than by luck. It refuses to act unless focus has actually been orphaned (body, or outside
 * this dialog), so a reader who moved on themselves is never yanked back.
 *
 * <p><b>⚠️ {@code inert} is a silent no-op in this project's jsdom</b> ({@code 'inert' in
 * HTMLElement.prototype} is {@code false}), so no jsdom test can observe its BEHAVIOUR — a test
 * asserting that Tab cannot leave would pass against an element with no guard at all. What jsdom
 * can see, and what the suites therefore assert, is the ATTRIBUTE; the behaviour is a browser
 * measurement, recorded in the plan's M5 row. Note also that React 19 drops {@code inert=""} (the
 * string form) entirely and renders the attribute only for the boolean — which is its own silent
 * no-op, and is why this passes {@code stacked || undefined} rather than an empty string.
 *
 * <h2>Escape is opt-in, and that is not timidity</h2>
 *
 * <p>The handler this replaces sat on the backdrop — an empty `div` with no `tabIndex` and no
 * children, so it could never be the keydown target nor an ancestor of one. It existed to satisfy
 * a lint rule about click handlers, and it never fired. Escape now works, but only where a caller
 * asks for it, because most render sites hold state a reader would lose: some carry unsaved
 * forms, one holds a generated password that has to be copied before it is gone, and one is a
 * deliberately unclosable spinner. Turning dismissal on everywhere would have converted a dead
 * handler into a data-loss handler.
 *
 * @param {object}   props
 * @param {string}   props.label            the dialog's accessible name
 * @param {Function} [props.onClose]        omit to make the dialog unclosable (the refresh spinner
 *                                          does exactly this)
 * @param {boolean}  [props.closeOnEscape]  opt in where dismissal loses nothing
 * @param {boolean}  [props.stacked]        whether ANOTHER dialog is currently over this one. The
 *                                          caller owns the stack, because only it knows the order;
 *                                          a stacked layer must also have {@code closeOnEscape}
 *                                          withheld, or one press answers two layers.
 */
export default function Modal({
  label,
  onClose,
  maxWidth = 'md',
  bare = false,
  closeOnEscape = false,
  stacked = false,
  className = '',
  'data-testid': testId,
  children,
}) {
  const dialogRef = useDialogFocus(true);
  /**
   * The last element focused INSIDE this dialog, recorded from real focus events.
   *
   * <p>A ref rather than state: it must not re-render, and it must be written before the commit that
   * makes this layer {@code inert} — which a focus event does and a render cannot.
   */
  const lastInside = useRef(null);

  /**
   * Puts focus back where it was when something stacked over this dialog.
   *
   * <p>Only on the stacked → not-stacked transition, and only when focus has actually been ORPHANED
   * — which here means {@code document.body} or nothing, and nothing else. A reader who Tabbed out
   * into the page while the top layer was up has chosen where they are, and yanking them back is
   * worse than leaving them. On mount {@code lastInside} is null, so a dialog that is never
   * stacked runs this to a no-op and behaves exactly as it did before.
   *
   * <p>⚠️ The guard was written as two branches ("inside this dialog" and "outside it") and an
   * adversarial review pointed out that their union is simply "focus is somewhere real": the
   * containment test decided nothing, and a maintainer editing one branch would have believed they
   * had changed behaviour the other already covered. One condition now, saying what it means.
   */
  useEffect(() => {
    if (stacked) return;
    const node = lastInside.current;
    if (!node || !document.contains(node)) return;
    const active = document.activeElement;
    if (active && active !== document.body) return;
    node.focus();
  }, [stacked]);

  useEffect(() => {
    if (!closeOnEscape || !onClose) return undefined;
    // Document-level: the dialog root is focusable but its descendants are where a reader actually
    // is, and keydown from a portalled child would not bubble through this subtree at all.
    const onKeyDown = (e) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [closeOnEscape, onClose]);

  return (
    <div
      ref={dialogRef}
      tabIndex={-1}
      // `p-4` is gone in favour of `app-safe-modal`, which resolves the gutter and the safe inset
      // as `max()` of the two rather than summing them — measured at exactly 16px on all four
      // sides, the same as the `p-4` it replaces, wherever no inset is reported.
      //
      // ⚠️ Removed as HYGIENE, not because it would have won: this class is unlayered and Tailwind
      // v4's utilities are layered, so it beats them outright — an element carrying both
      // `app-safe-modal` and `p-8` measures 16px, not 32px. Worth knowing before adding a padding
      // utility to this element and wondering why nothing moves.
      className="app-safe-modal fixed inset-0 z-50 flex items-center justify-center focus:outline-none"
      role="dialog"
      /* Exactly one element on the page may claim this, and it is the layer the reader is on. */
      aria-modal={stacked ? undefined : 'true'}
      /* `|| undefined` rather than the bare boolean: React 19 renders `inert` for `true` and omits
         it for `false`, so either form works here — but the explicit undefined is what documents
         that an unstacked dialog emits NO attribute, which is the half the pinning test depends on. */
      inert={stacked || undefined}
      aria-label={label}
      data-testid={testId}
      /* Recorded on the way IN, so it survives the blur `inert` causes on the way down. React's
         synthetic focus event bubbles (the DOM's does not), which is why this can sit on the root
         rather than on every control.

         ⚠️ AND ON POINTERDOWN, which is not belt-and-braces: **macOS and iOS Safari do not focus a
         `<button>` on click** unless Full Keyboard Access is on, and that is the default. So on the
         gesture this whole mechanism was measured against — click a chip on the popup's map, then
         press Escape — the focus event never fires there, `lastInside` stays null, and the fix
         degrades silently to the `<body>` behaviour it replaces. Both browser measurements behind it
         were headless Chromium; an adversarial review caught the engine assumption. `pointerdown`
         rather than `click` because the blur has to be beaten, and the target is filtered to a real
         element inside this dialog so a press on the backdrop records nothing. */
      onFocus={(e) => { if (e.target !== e.currentTarget) lastInside.current = e.target; }}
      onPointerDown={(e) => {
        const el = e.target instanceof HTMLElement ? e.target.closest('a,button,input,select,textarea,[tabindex]') : null;
        if (el && el !== e.currentTarget && e.currentTarget.contains(el)) lastInside.current = el;
      }}
    >
      <div
        className="absolute inset-0 bg-black/60"
        role="presentation"
        onClick={onClose}
        data-testid={testId ? `${testId}-backdrop` : undefined}
      />
      {bare ? (
        children
      ) : (
        <div
          className={`relative bg-plex-surface border border-plex-border rounded-xl shadow-2xl p-6 w-full ${MAX_WIDTH[maxWidth]} flex flex-col gap-4 ${className}`.trim()}
        >
          {children}
        </div>
      )}
    </div>
  );
}

Modal.propTypes = {
  label: PropTypes.string.isRequired,
  onClose: PropTypes.func,
  maxWidth: PropTypes.oneOf(['sm', 'md', 'lg']),
  bare: PropTypes.bool,
  closeOnEscape: PropTypes.bool,
  stacked: PropTypes.bool,
  className: PropTypes.string,
  'data-testid': PropTypes.string,
  children: PropTypes.node.isRequired,
};
