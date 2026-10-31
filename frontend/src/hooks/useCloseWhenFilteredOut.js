import { useEffect, useRef } from 'react';

/**
 * Keeps an open list-and-panel selection honest about the list's filters: when the set of items the list shows
 * changes (a search, a chip) and the selected item is no longer in it, the panel closes; while the item is still
 * in the set (a page change, a sort, an unrelated filter) the panel stays open (data-tabs-redesign-plan.md §12).
 *
 * <p>It reacts to the filtered set changing, not to the selection changing, so opening or creating an item the
 * filters hide (Add user while a search is on) is the admin's own act and stays open. A selection that is not on
 * the page being shown is still "in the set": paging never closes the panel.
 *
 * @param {object} options
 * @param {string|number|null} options.selectedId the open item's id, or null when no panel (or an add form) is open
 * @param {Array<object>} options.items the filtered set (before paging)
 * @param {(item: object) => (string|number)} options.getId an item's id (keep it a stable function)
 * @param {() => void} options.onClose closes the panel
 */
export default function useCloseWhenFilteredOut({ selectedId, items, getId, onClose }) {
  const selected = useRef(selectedId);
  const close = useRef(onClose);
  useEffect(() => {
    selected.current = selectedId;
    close.current = onClose;
  }, [selectedId, onClose]);

  useEffect(() => {
    if (selected.current == null) return;
    if (!items.some((item) => getId(item) === selected.current)) close.current();
  }, [items, getId]);
}
