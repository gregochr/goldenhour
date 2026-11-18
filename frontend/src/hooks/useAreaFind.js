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
  const [outlookRows, setOutlookRows] = useState(null);
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

  useEffect(() => {
    if (!active) return undefined;
    let live = true;
    fetchAreaOutlook()
      .then((rows) => { if (live && Array.isArray(rows)) setOutlookRows(rows); })
      .catch(() => { /* rows draw with no figure */ });
    return () => { live = false; };
  }, [active]);

  const outlook = useMemo(
    () => (outlookRows ? new Map(outlookRows.map((row) => [row.areaId, row.windows || []])) : null),
    [outlookRows],
  );
  return { directory, outlook };
}
