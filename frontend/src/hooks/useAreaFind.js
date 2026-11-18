import { useEffect, useMemo, useState } from 'react';
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
  const [requested, setRequested] = useState(false);

  // Adjusted during render on the first open (React's "derive state from a changed prop" shape), so the fetch
  // effect below has a stable thing to key on and the directory is never requested twice.
  if (active && !requested) setRequested(true);

  useEffect(() => {
    if (!requested) return undefined;
    let live = true;
    fetchAreaDirectory()
      .then((rows) => { if (live && Array.isArray(rows)) setDirectory(rows); })
      .catch(() => { /* Find works without areas */ });
    return () => { live = false; };
  }, [requested]);

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
