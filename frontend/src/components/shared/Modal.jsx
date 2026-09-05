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
 * <p>{@code inert} takes focus off whatever inside it had it, racing {@link useDialogFocus}'s read
 * of {@code document.activeElement} — which is where the arriving dialog learns where to send focus
 * back. ⚠️ <b>This paragraph used to say the blur "happens before any effect in the commit", and
 * that is measurably false</b>: driven through Playwright against both Chromium and WebKit, setting
 * {@code inert} does NOT blur synchronously — the fix-up lands two to four animation frames later,
 * in both engines, whether the attribute sits on the focused element or an ancestor. So the blur
 * usually arrives AFTER the covering layer has already captured, and the sentence had the race the
 * wrong way round while being right that there is one. The recorder below is what makes the outcome
 * not matter. So the naive version broke the thing the hook exists for: open
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
   * Whether anything has ever stacked over this dialog.
   *
   * <p>⚠️ Load-bearing, and it replaces a guard that used to be implicit. The restore below once
   * read "{@code lastInside} is null" as its proof that this was a MOUNT rather than an uncover —
   * true only while a null recorded control meant "do nothing", which stopped being true when the
   * root fallback landed. Without this ref an unstacked dialog that mounts while focus happens to
   * sit on {@code <body>} would take the root in a passive effect, beating {@link useDialogFocus}'s
   * deliberate frame and the consumer-autofocus yield that rides on it.
   */
  const wasStacked = useRef(false);

  /**
   * Puts focus back where it was when something stacked over this dialog.
   *
   * <p>Only on the stacked → not-stacked transition, and only when focus has actually been ORPHANED
   * — which here means {@code document.body} or nothing, and nothing else. A reader who Tabbed out
   * into the page while the top layer was up has chosen where they are, and yanking them back is
   * worse than leaving them.
   *
   * <p>⚠️ The guard was written as two branches ("inside this dialog" and "outside it") and an
   * adversarial review pointed out that their union is simply "focus is somewhere real": the
   * containment test decided nothing, and a maintainer editing one branch would have believed they
   * had changed behaviour the other already covered. One condition now, saying what it means.
   *
   * <h3>⚠️ The root is a RESTORE here, not a consolation prize</h3>
   *
   * <p>{@code lastInside} records a CONTROL, never this dialog's own root — deliberately, so that an
   * uncovered dialog returns a reader to the chip they were on rather than to its container. The
   * consequence went unnoticed: a reader who opens a dialog and touches nothing inside it leaves
   * {@code lastInside} null, so the uncover restored nothing and left them on {@code <body>}
   * beneath a layer that is once again claiming {@code aria-modal="true"} — an AT hiding the rest
   * of the page while focus sits outside the dialog, and the next Tab walking the page behind the
   * backdrop. Reachable by keyboard: open the popup, press {@code /} for search, then Escape.
   *
   * <p>⚠️ <b>Not every-time on every engine, and the first draft of this said otherwise.</b> Paired
   * old-vs-new runs through Playwright: on WebKit the reader is stranded on every attempt; on
   * Chromium only when the covering layer mounts a frame or more after the press, because a cover
   * that mounts in the same frame captures this root as its own return address and hands it back on
   * unmount. That delay is the normal case on first use — {@code PlanSearch},
   * {@code WindowSheetDialog} and {@code LocationFourDaySheet} are all {@code lazy()} behind a
   * {@code Suspense} boundary — and the rare one afterwards, when the chunk is warm.
   *
   * <p>⚠️ <b>Keyboard-only in practice, and an earlier draft of this comment claimed otherwise.</b>
   * It argued the tap-a-chip route reached this too, because macOS and iOS Safari do not focus a
   * {@code <button>} on click — which reads the {@code onPointerDown} recorder below exactly
   * backwards. That handler exists <em>precisely</em> to cover those engines, {@code pointerdown}
   * fires there, and every control that stacks a layer over this one is a real button inside it. So
   * on a pointer route {@code lastInside} IS populated and this fallback is never reached. Caught by
   * an adversarial review; recorded because the mistake is an easy one to make twice.
   *
   * <p><b>Why the root is the right target is narrower than it first looks, and the obvious
   * argument for it is wrong.</b> It is tempting to say "this is just what an OPEN does", and
   * borrow {@link useDialogFocus}'s three reasons for preferring the container. All three fail
   * here: its "nothing focusable inside" case cites the settings modal's spinner, and
   * {@code UserSettingsModal} never passes {@code stacked} at all (only four Plan-shell dialogs do —
   * and only two of those can reach this branch in production, since {@code WindowSpotSheet} and
   * {@code WindowPickDialog} are stacked only while search is open, which the shell refuses to open
   * over them — every one of which has a close button); its "content still loading" case cannot apply to a
   * dialog that has been mounted for the whole life of the layer that covered it; and its "do not
   * fight a consumer autofocus" case has nothing to fight, because an uncover re-runs no consumer
   * effect. An adversarial review caught that borrowing — worth keeping, because a maintainer who
   * notices the spinner never stacks would otherwise have been handed a reason to delete this.
   *
   * <p>The real reason is stronger and specific to this path: the precondition for reaching the
   * fallback is that <em>nothing inside was ever touched</em>, which means the root is <b>the exact
   * node {@code inert} blurred them off</b>. Returning them to it is a true restore. Focusing the
   * first control instead would drop the reader into content they have never read — and they have
   * never read it; that is the precondition — on a control they did not choose.
   *
   * <p>⚠️ <b>The ordering this depends on is a browser fact no test here can check.</b> {@code inert}
   * makes {@code focus()} a silent no-op, so the fallback only works because React clears the
   * attribute in the MUTATION phase and this passive effect runs after it. jsdom implements no
   * {@code inert} at all, so the suite would stay green either way. Measured directly in Chrome 148:
   * {@code focus()} on an inert root leaves {@code document.activeElement} on {@code <body>}, and
   * the same call after clearing {@code inert} lands on the root — both in the same task and across
   * a microtask. The pre-existing {@code node.focus()} branch has always relied on the identical
   * ordering; this only gives it a measurement.
   *
   * <p>So a recording that is null, detached, or <em>refuses the focus</em> now lands on the root
   * rather than doing nothing. That last case is the one an early {@code return} hid: {@code focus()}
   * is a silent no-op on an attached but unfocusable node — a disabled control, or one under an
   * {@code inert} ancestor — so the old code could believe it had restored a reader it had in fact
   * left on {@code <body>}, reaching this same defect one step later. Verified rather than assumed
   * now, which costs one comparison. "Do nothing" was never a decision; it was the shape of the
   * {@code !node} early return.
   *
   * <p>⚠️ <b>Not measured:</b> that a screen reader ANNOUNCES the dialog on landing. The role and
   * the accessible name are both present and pinned, and focusing a {@code tabindex="-1"} dialog
   * container is the APG's own sanctioned alternative to the first control — but no AT was driven
   * here, and iOS VoiceOver in particular does not always move its cursor on a programmatic
   * {@code focus()} of a non-input. Worth an actual VoiceOver pass before anyone leans harder on
   * the announcement than "focus is inside the dialog again, where Tab works".
   */
  useEffect(() => {
    if (stacked) {
      wasStacked.current = true;
      return;
    }
    if (!wasStacked.current) return;
    const active = document.activeElement;
    // ⚠️ `documentElement` as well as `<body>`. Both mean "nowhere", and this app has MEASURED the
    // second: `WindowFirstShell`'s tab-select records `activeElement` landing on the document root
    // after the overlay's map hatch. Whether an `inert`-driven blur can also land there is
    // unverified — jsdom always yields `<body>`, and a focus experiment needs a browser pane that
    // actually holds focus — so the guard covers both rather than betting on one. Nothing focuses
    // `<html>` deliberately, so widening it cannot swallow a reader's own choice.
    if (active && active !== document.body && active !== document.documentElement) return;
    const node = lastInside.current;
    if (node && document.contains(node)) {
      node.focus();
      // Confirmed, not assumed: a disabled control takes no focus and reports no failure.
      if (document.activeElement === node) return;
    }
    // Nothing usable was recorded inside. The root is where an OPEN would have put them.
    const dialog = dialogRef.current;
    if (dialog) dialog.focus();
    // ⚠️ `dialogRef` must be a STABLE `useRef` object, and that is a correctness precondition, not
    // the lint concession it looks like. `wasStacked` never resets, so "has stacked before" is
    // permanently true after the first cover — which is safe only because this effect then re-runs
    // solely when `stacked` flips. Measured: make `dialogRef` unstable and the effect fires on
    // every render, yanking a reader who has since moved away back into the dialog root, twenty
    // times over twenty re-renders. `useDialogFocus` returns a `useRef`, so this holds today.
  }, [stacked, dialogRef]);

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
