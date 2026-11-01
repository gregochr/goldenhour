import { useCallback, useEffect, useState } from 'react';

/** The hash's path and its query, without the leading {@code #}. */
function splitHash() {
  const [path, ...query] = window.location.hash.replace('#', '').split('?');
  return { path, query: query.join('?') };
}

/** The inbound query as a suffix ({@code ?region=3}), or an empty string. */
function querySuffix() {
  const { query } = splitHash();
  return query === '' ? '' : `?${query}`;
}

/**
 * The selection in the URL hash for one Manage tab. With no {@code parse}, the default is numeric:
 * {@code #manage/{tab}/12} gives 12, and the tab's bare hash, a non-numeric id or another tab gives null. With a
 * {@code parse}, it receives <b>every path segment after the tab</b> ({@code ['job', '12']}) and returns the
 * selection, or null for a path that names nothing.
 *
 * @param {string} tab the Manage tab's key (e.g. {@code waitlist})
 * @param {((segments: string[]) => *)} [parse] reads the segments after the tab into a selection
 * @returns {*} the selection, or null
 */
export function selectionFromHash(tab, parse = undefined) {
  // A query (a cross-tab link's `?region=3`) is not part of the tab or the id.
  const [area, hashTab, ...segments] = splitHash().path.split('/');
  if (area !== 'manage' || hashTab !== tab) return null;
  if (parse) return parse(segments.filter((segment) => segment !== '')) ?? null;
  const id = segments[0];
  if (!/^\d+$/.test(id ?? '')) return null;
  return Number(id);
}

/** Writes a selection into the hash, the way the Manage tabs do, so it survives a reload and Back. */
function writeSelection(tab, path, keepQuery) {
  // Intentional browser navigation side effect, as ManageView does for its tabs.
  window.location.hash = `${path == null ? `manage/${tab}` : `manage/${tab}/${path}`}${keepQuery ? querySuffix() : ''}`;
}

/** Whether two selections are the same value (an object a parser builds anew each time is not a change). */
function sameSelection(a, b) {
  return a === b || (a != null && b != null && typeof a === 'object' && JSON.stringify(a) === JSON.stringify(b));
}

/**
 * The selected row of a Manage list-and-panel tab, mirrored in the URL hash ({@code #manage/{tab}/{id}}).
 * Generalised from {@code UserManagementView}: the id is read at mount, written on {@code select} and
 * {@code clear} (each a history entry, so Back steps out of the panel), and followed when the hash moves
 * (Back, Forward, a pasted link, a cross-tab link while Manage is mounted).
 *
 * <p>A hash outside {@code manage/} (a one-time-token strip, another area writing the fragment) is ignored: the
 * selection stays as it was. {@code manage/{tab}} with no id, or another tab's hash, clears it.
 *
 * <p>The Runs tab passes {@code {parse, format}} because its hash is not one number ({@code job/12}, {@code start}):
 * {@code parse} reads the segments after the tab, and {@code format} turns a selection into that path. It also passes
 * {@code keepQuery}, so an inbound {@code ?range=&kind=&attention=} (the filters a link seeded) survives opening and
 * closing a panel. That is opt-in: Locations' {@code ?region=} is a filter the reader can change in the page, and a
 * query written back after they did would be read as a fresh link and put the old filter back.
 *
 * @param {string} tab the Manage tab's key (e.g. {@code waitlist})
 * @param {{parse?: (segments: string[]) => *, format?: (selection: *) => string, keepQuery?: boolean}} [options]
 *   a custom hash shape (the default is a single numeric id), and whether {@code select} and {@code clear} keep an
 *   inbound {@code ?query} (the default is not)
 * @returns {{selectedId: *, select: (id: *) => void, clear: (options?: {replace: boolean}) => void}}
 */
export default function useHashSelection(tab, options = undefined) {
  const parse = options?.parse;
  const format = options?.format;
  const keepQuery = options?.keepQuery === true;
  const [selectedId, setSelectedId] = useState(() => selectionFromHash(tab, parse));

  useEffect(() => {
    const onHashChange = () => {
      if (window.location.hash.replace('#', '').split('/')[0].split('?')[0] !== 'manage') return;
      const next = selectionFromHash(tab, parse);
      setSelectedId((current) => (sameSelection(current, next) ? current : next));
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, [tab, parse]);

  const select = useCallback((id) => {
    setSelectedId(id);
    writeSelection(tab, id == null ? null : (format ? format(id) : id), keepQuery);
  }, [tab, format, keepQuery]);

  const clear = useCallback((clearOptions) => {
    setSelectedId(null);
    // `{replace: true}` rewrites the current entry instead of adding one: for a selection that has vanished (a
    // removed row, a pasted id that is not on the list), so Back does not land on a dead `#manage/{tab}/{id}`.
    // A click handler passes its event here, so only a literal `true` counts.
    if (clearOptions?.replace === true) {
      window.history.replaceState(window.history.state, '', `#manage/${tab}${keepQuery ? querySuffix() : ''}`);
    } else {
      writeSelection(tab, null, keepQuery);
    }
  }, [tab, keepQuery]);

  return { selectedId, select, clear };
}
