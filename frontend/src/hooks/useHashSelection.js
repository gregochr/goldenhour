import { useCallback, useEffect, useState } from 'react';

/**
 * The selected row's id in the URL hash for one Manage tab: {@code #manage/{tab}/12} gives 12; the tab's bare hash,
 * a non-numeric id or another tab gives null.
 *
 * @param {string} tab the Manage tab's key (e.g. {@code waitlist})
 * @returns {number|null}
 */
export function selectionFromHash(tab) {
  const [area, hashTab, id] = window.location.hash.replace('#', '').split('/');
  if (area !== 'manage' || hashTab !== tab || !/^\d+$/.test(id ?? '')) return null;
  return Number(id);
}

/** Writes a selection into the hash, the way the Manage tabs do, so it survives a reload and Back. */
function writeSelection(tab, id) {
  // Intentional browser navigation side effect, as ManageView does for its tabs.
  window.location.hash = id == null ? `manage/${tab}` : `manage/${tab}/${id}`;
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
 * @param {string} tab the Manage tab's key (e.g. {@code waitlist})
 * @returns {{selectedId: number|null, select: (id: number) => void, clear: (options?: {replace: boolean}) => void}}
 */
export default function useHashSelection(tab) {
  const [selectedId, setSelectedId] = useState(() => selectionFromHash(tab));

  useEffect(() => {
    const onHashChange = () => {
      if (window.location.hash.replace('#', '').split('/')[0] !== 'manage') return;
      setSelectedId(selectionFromHash(tab));
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, [tab]);

  const select = useCallback((id) => {
    setSelectedId(id);
    writeSelection(tab, id);
  }, [tab]);

  const clear = useCallback((options) => {
    setSelectedId(null);
    // `{replace: true}` rewrites the current entry instead of adding one: for a selection that has vanished (a
    // removed row, a pasted id that is not on the list), so Back does not land on a dead `#manage/{tab}/{id}`.
    // A click handler passes its event here, so only a literal `true` counts.
    if (options?.replace === true) {
      window.history.replaceState(window.history.state, '', `#manage/${tab}`);
    } else {
      writeSelection(tab, null);
    }
  }, [tab]);

  return { selectedId, select, clear };
}
