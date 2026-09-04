/**
 * The map doors' shared close-then-move-and-merge entry (doors D2, D3, D4 —
 * `plan-to-map-doors-plan.md` §3). Extracted as a PURE function, separate from
 * `WindowFirstShell.jsx`'s own `openMapTab` wrapper, for one reason: no door UI ships in D2 (plan
 * §3 D2 task 1's own text — D3 wires the sheet footer, D4 the popup field), so the wrapper itself
 * has no caller yet and cannot be exercised through a rendered button. This function is the "test-
 * only caller" plan §3 D2 task 1 promises D2 delivers — a plain function taking its collaborators
 * as arguments, callable directly from a test with no component render at all.
 *
 * <p><b>Ordering.</b> Closes the popup and the window sheet FIRST, mirroring every other existing
 * map route in `WindowFirstShell.jsx` (the location sheet's own `onShowOnMap` wrapper) — a door
 * onto the map must never leave a Plan dialog mounted underneath the Map tab.
 *
 * <p><b>The lens merge is read-through, not copied.</b> `door` itself may carry stale or absent
 * `minRating`/`limitMinutes` (a caller building the payload ahead of the tap); this function
 * always OVERWRITES both with whatever `ratingLens`/`reachLens` report right now, so what reaches
 * `onOpenMapTab` is the Plan's lens value at the moment of the close, never a snapshot taken
 * earlier. Both default to `null` when the lens itself, or its nested `tier`, is absent — never
 * `undefined`, which `onOpenMapTab`'s own consumers (`App.jsx`'s `openMapTabFromPlan`) branch on.
 *
 * @param {object} params
 * @param {Function} params.openOverPopup closes the popup/pick/sheet-by-key layer (`(null) => void`)
 * @param {Function} params.openWindow closes the window sheet (`(null) => void`)
 * @param {?Function} params.onOpenMapTab `App.jsx`'s `openMapTabFromPlan`, or undefined when there
 *        is nothing to map (`allDates.length === 0`) — optional-chained, so this function still
 *        performs the close even with nothing to hand the payload to
 * @param {?{minRating: ?number}} params.ratingLens the Plan's rating lens, read live
 * @param {?{tier: ?{limitMinutes: ?number}}} params.reachLens the Plan's reach lens, read live
 * @param {{date: string, targetType: string, region: ?string, locationName: ?string}} params.door
 *        the door's own identity fields — everything BUT the lens values, which this function
 *        supplies
 */
export function openMapDoor({
  openOverPopup, openWindow, onOpenMapTab, ratingLens, reachLens, door,
}) {
  openOverPopup(null);
  openWindow(null);
  onOpenMapTab?.({
    ...door,
    minRating: ratingLens?.minRating ?? null,
    limitMinutes: reachLens?.tier?.limitMinutes ?? null,
  });
}
