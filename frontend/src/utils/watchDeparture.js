/**
 * Starts watching {@code node} for leaving while it holds focus, and returns the ref cleanup that
 * answers it — for a control that can be taken away under the reader (swapped for another element,
 * or removed by its own press), so that its owner can hand their focus on rather than leave it on
 * {@code <body>}.
 *
 * <p>The answer is recorded in {@code departed.current}: the node, if it held focus as its ref was
 * detached. It is asked in the ref's cleanup because React 19 calls that BEFORE it removes the node
 * (measured on React 19.3: the cleanup sees the node still connected and still focused), which is
 * the last moment the question can be answered — after the removal every departure looks the same.
 * Never from focus events: Chromium fires {@code blur} on a focused node as it is removed, and WebKit
 * and Firefox fire nothing, so a flag cleared on blur would already be cleared in Chromium by the
 * time the removal commits (measured).
 *
 * <h2>⚠️ What the owner must do, because this cannot do it for them</h2>
 *
 * <ul>
 *   <li>Call it from a callback ref on an element the owner renders itself, and stay mounted when
 *       that element leaves: {@code useCallback((node) => (node ? watchDeparture(departed, node) :
 *       undefined), [])}. A watch kept inside a child that unmounts with the node leaves nobody to
 *       spend the record.</li>
 *   <li>Spend the record in a layout effect with NO dependency array, so it runs in the very commit
 *       that made the record, whatever else that commit changed.</li>
 *   <li>Clear the record first, unconditionally, and only then decide whether to act. A record left
 *       standing because this commit did not act is spent by some later render, which pulls a
 *       reader who has since moved on back onto the landing.</li>
 *   <li>Move focus only while focus is nowhere (none, {@code <body>} or {@code <html>}): something
 *       else in the commit may have placed it, and a reader's position is never taken.</li>
 * </ul>
 *
 * <p>Kept to those rules, a record the owner acts on was made in that same commit, while the reader
 * was on the node (a hidden subtree is the exception below). `MastheadTickLine` and `MapBreadcrumb`
 * each say in their class comment why their landing is the right one. Compare
 * `hooks/useRowFocusRescue.js`, which records from focus events and keeps the record after focus
 * has gone, so it can act while the reader's attention is elsewhere — which is why it guards against
 * a foreign modal over the pane and a hidden pane.
 *
 * <p>⚠️ <b>A detach is not always a departure.</b> React also runs the cleanup when an
 * {@code <Activity mode="hidden">}, or a {@code <Suspense>} whose shown content suspends again, hides
 * the subtree — before hiding it, so a focused node is recorded. Showing the subtree attaches the
 * ref again, which clears the record (the guard below). A node DELETED while hidden gets neither:
 * its record is spent whenever the subtree is shown, however much later, and puts a reader whose
 * focus is nowhere on the owner's landing (read in react-dom 19.3, reproduced in jsdom). No owner
 * sits under an {@code <Activity>}, or a {@code <Suspense>} anything can make suspend again, today;
 * one that does needs each record tied to the commit that made it.
 *
 * @param {{current: ?Element}} departed the record the owner's handoff spends
 * @param {Element} node the element that has just attached
 * @returns {Function} the ref cleanup
 */
export function watchDeparture(departed, node) {
  // ⚠️ A node attaching here cannot also have departed, so its own record is cleared. React
  // re-attaches a node that never left in three ways, each of which would otherwise leave a record
  // standing: StrictMode's re-run of a newly mounted node's ref (cleanup, then setup, after the
  // commit), a ref callback recreated on a render, and a hidden subtree shown again (see the doc).
  // The StrictMode case is the one that shipped. The tick line's statement is both watched and a
  // landing, so a handoff that focused it in the commit that mounted it came back through the
  // cleanup still focused. Left standing, that record was spent by the line's NEXT render: the
  // light arriving pulled a reader who had since clicked away back onto the statement, and a tab
  // switch that really removed the statement put them on the origin button instead (both
  // reproduced under StrictMode in jsdom, the second found by review after a narrower first fix).
  // Nothing in the app focuses ⌂ or the breadcrumb's `clear` as it mounts today, but no record may
  // rest on that: `watchDeparture.test.js` pins this line for any node.
  if (departed.current === node) departed.current = null;
  return () => {
    if (document.activeElement === node) departed.current = node;
  };
}
