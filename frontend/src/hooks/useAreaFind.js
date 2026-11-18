import { useEffect, useMemo, useRef, useState } from 'react';
import { fetchAreaDirectory, fetchAreaOutlook } from '../api/areasApi.js';

/**
 * The areas behind Find, fetched lazily: nothing is requested until Find (or an area sheet) is first open.
 *
 * <p>The directory is roster data and is fetched once; the outlook is the reader's own figure and is fetched again
 * each time Find opens, so it is as fresh as the scores behind the open dialog. Either failing leaves its half
 * absent (no area rows, or rows with no figure): Find still works, and an area never blocks a place or a window.
 *
 * @param {boolean} active whether Find or an area sheet is open
 * @returns {{directory: ?Array, outlook: ?Map<number, Array>}} the directory, and the outlook by area id
 */
export default function useAreaFind(active) {
  const [directory, setDirectory] = useState(null);
  // How many times Find (or an area sheet) has been opened. The directory is requested on an opening that finds it
  // absent, so a failed read is retried on the NEXT opening and never in a loop within one.
  const [opens, setOpens] = useState(0);
  const [wasActive, setWasActive] = useState(false);
  const haveDirectory = useRef(false);
  // Adjusted during render on the rising edge (React's "derive state from a changed prop" shape).
  if (active !== wasActive) {
    setWasActive(active);
    if (active) setOpens((n) => n + 1);
  }

  useEffect(() => {
    if (opens === 0 || haveDirectory.current) return undefined;
    let live = true;
    fetchAreaDirectory()
      .then((rows) => {
        if (!live || !Array.isArray(rows)) return;
        haveDirectory.current = true;
        setDirectory(rows);
      })
      .catch(() => { /* Find works without areas; the next opening asks again */ });
    return () => { live = false; };
  }, [opens]);

  const outlook = useAreaOutlook(active);
  return { directory, outlook };
}

/**
 * The reader's own area outlook ({@code GET /api/areas/outlook}), fetched each time {@code active} turns true and kept
 * while it is false. Personal and never cached; a failure leaves it null (rows draw with no figure). Shared by Find and
 * the Coming up watchlist, so both read the one served figure and neither derives an area's best.
 *
 * @param {boolean} active whether a surface that shows the figures is open
 * @returns {?Map<number, Array>} the windows by area id, or null before it lands
 */
export function useAreaOutlook(active) {
  const [rows, setRows] = useState(null);
  useEffect(() => {
    if (!active) return undefined;
    let live = true;
    fetchAreaOutlook()
      .then((body) => { if (live && Array.isArray(body)) setRows(body); })
      .catch(() => { /* rows draw with no figure */ });
    return () => { live = false; };
  }, [active]);
  return useMemo(
    () => (rows ? new Map(rows.map((row) => [row.areaId, row.windows || []])) : null),
    [rows],
  );
}
