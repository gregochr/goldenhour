import { useEffect, useRef } from 'react';

/**
 * Moves focus into a dialog when it opens and puts it back where it was when it closes.
 *
 * <h2>What was wrong, and why this is the fix rather than a focus trap</h2>
 *
 * <p>Every dialog in this app carried {@code role="dialog"} and {@code aria-modal="true"} and
 * nothing else. Those are <b>semantics, not behaviour</b>: they tell a screen reader how to
 * describe the element, and they do not move focus, hold it, or give it back. So opening a dialog
 * left focus wherever it had been — behind the backdrop — and a keyboard user had to Tab through
 * the entire page to reach the thing that had just appeared over it. Closing left focus on a
 * detached node, which browsers reset to {@code <body>}, sending the next Tab back to the top of
 * the document.
 *
 * <p><b>Focus-in and restore, deliberately not containment.</b> A full trap buys one extra thing —
 * Tab cannot leave — and costs a live focusable query that would have to cope with Leaflet
 * mutating its own tab stops inside the map overlay, a containment rule that would have to
 * special-case two {@code document.body} portals, and a fallback for a dialog with no focusable
 * children at all (the settings modal's refresh spinner is exactly that). Every plausible failure
 * mode lives in the containment half. A keyboard user who can Tab out of a dialog is inconvenienced;
 * one who cannot reach a bottom sheet at all is stuck.
 *
 * <p><b>Ruling (v1-retirement plan §4.3): this stays as-is.</b> "At most one modal" is held
 * route-by-route — the masthead's search refuses a third layer, the cog closes every Plan dialog
 * before opening settings, the map handoffs close the popup first — rather than by a single
 * shell-wide containment. The three reasons above are none of them about v1: they are live
 * facts about this app (Leaflet's own tab-stop mutation, the body-portalled bottom sheet, the
 * spinner with nothing focusable) that survive v1's departure unchanged. What v1's departure DID
 * remove is plan-matrix §3 rule 10 (any edit to {@code Modal} needed a v1-identical fallback) — a
 * freedom, not a reason to revisit the ruling. The structural alternative (shell-root {@code inert}
 * while any dialog is open) is a named follow-on, not adopted here: it would need App-level sibling
 * dialogs brought inside the guarded tree and {@code stacked} gated on the covering layer having
 * mounted first, both unstarted.
 *
 * <p><b>Not the native {@code <dialog>} element or {@code inert} either</b>, and that is empirical
 * rather than taste: in this project's jsdom, {@code HTMLDialogElement.prototype.showModal} is
 * {@code undefined} and {@code 'inert' in HTMLElement.prototype} is {@code false}. A rewrite onto
 * either would break every existing dialog test, and {@code inert}'s absence is the worse of the
 * two — it fails as a <em>silent no-op</em>, so the tests would go green while asserting nothing
 * about the guard. {@code showModal} also puts the dialog in the top layer, which the bottom sheet
 * at {@code z-index: 10000} is not, so on mobile the sheet would render behind the map overlay.
 *
 * <h2>The container takes focus, not the first control</h2>
 *
 * <p>Focusing the dialog's own root (via {@code tabIndex={-1}}) rather than hunting for its first
 * button is what makes this safe across every render site. It works when there is nothing
 * focusable inside; it works when the content is still loading and the real controls do not exist
 * yet; and it does not fight a consumer that autofocuses a particular field of its own, because
 * that effect runs after this one and simply wins. A screen reader reads the dialog's accessible
 * name on landing, which is the announcement the role was there to promise.
 *
 * @param {boolean} active whether the dialog is currently open
 * @returns {React.RefObject} attach to the element carrying {@code role="dialog"}; it needs
 *          {@code tabIndex={-1}} so it can accept focus without entering the tab order
 */
export default function useDialogFocus(active = true) {
  const dialogRef = useRef(null);

  useEffect(() => {
    if (!active) return undefined;
    const previous = document.activeElement;
    // rAF, not a bare call: a dialog that mounts in the same commit as its content would otherwise
    // take focus before the browser has laid anything out, and Safari drops the focus silently.
    const frame = requestAnimationFrame(() => {
      const dialog = dialogRef.current;
      if (!dialog) return;
      // Yield to a consumer that has already placed focus on one of its own controls. Deferring by
      // a frame is what makes this necessary: the settings modal focuses its postcode field the
      // moment its fetch resolves, and without this guard the container would take it straight
      // back a frame later. Ordering alone cannot be relied on — which effect lands first depends
      // on how fast that fetch is, so both orders have to end in the same place.
      if (dialog.contains(document.activeElement) && document.activeElement !== dialog) return;
      dialog.focus();
    });

    return () => {
      cancelAnimationFrame(frame);
      // Only restore if the trigger is still in the document. A dialog can be closed by something
      // other than the user — a poll, an SSE event, a parent re-render that drops the row the
      // trigger lived on — and focusing a detached node throws away the user's place entirely
      // rather than returning it. Doing nothing leaves focus where the browser put it, which is
      // no worse than today.
      if (!(previous instanceof HTMLElement) || !document.contains(previous)) return;

      // ⚠️ **AND only if the reader has not chosen somewhere else in the meantime**
      // (`map-tab-v2-plan.md` O-20 arm C, found by an accessibility lens on #794).
      //
      // <p>This is `Modal`'s own uncover-restore guard, mirrored — same condition, same reasoning
      // it states there: "a reader who Tabbed out into the page while the top layer was up has
      // chosen where they are, and yanking them back is worse than leaving them". This hook refuses
      // containment app-wide, so Tabbing out is a supported thing to do; a restore that fires
      // regardless is that refusal taking away with one hand what it grants with the other.
      //
      // <p>The route that made it visible: with the four-day sheet open over the map, Tab out onto
      // the pane, open the drilldown, press Escape. Since #794 the panel correctly stands down and
      // only the sheet closes — and this cleanup then moved focus OUT of the still-open panel onto
      // the callout button beneath it. New as of that fix, because the panel used to close on the
      // same press, which made moving focus out of it coherent.
      //
      // ⚠️ **The normal close is unaffected, and that is measured rather than assumed.** The worry
      // is the mirror-image defect this increment fixed five times: if focus were still inside the
      // closing dialog here, the guard would skip the restore and strand the reader on `<body>`. It
      // is not. Detaching a focused node — or a subtree containing focus — puts `activeElement` on
      // `<body>` first, so by the time this runs the guard passes and the restore happens as
      // before. Measured in BOTH environments on 2026-09-07, because focus/blur timing is exactly
      // where this project has been burned by the jsdom/browser difference before: jsdom reports
      // `<body>` and a detached ref, and Chromium reports `<body>` for the focused-node and
      // focused-subtree cases alike.
      //
      // <p>`documentElement` as well as `<body>`: both mean "nowhere", and `Modal`'s own copy of
      // this guard records a MEASURED case of `activeElement` landing on the document root after
      // the overlay's map hatch. Nothing focuses `<html>` deliberately, so widening it cannot
      // swallow a reader's own choice.
      const active = document.activeElement;
      if (active && active !== document.body && active !== document.documentElement) return;

      previous.focus();
    };
  }, [active]);

  return dialogRef;
}
