import { useEffect, useRef } from 'react';

/**
 * Closes an open list-and-panel selection when the reader changes the list's FILTERS (a search, a chip) so that the
 * selected item is no longer in the filtered set (data-tabs-redesign-plan.md §12). A page change, a sort, and above
 * all a data refresh never close it.
 *
 * <p>It is keyed on {@code filterKey} (a stable signature of the filter inputs), never on the derived items, because
 * the admin's own actions refetch the list: Enable under the Disabled chip, Clear lock under Locked, a role edit
 * under Lite, an email edit under a search all move the item out of the set they were opened from, and the panel
 * must stay so the admin sees the result. For the same reason a selection that changes (Back, Forward, a new
 * reader) is not checked: only a filter change closes.
 *
 * @param {object} options
 * @param {string|number|null} options.selectedId the open item's id, or null when no panel (or an add form) is open
 * @param {Array<object>} options.items the filtered set (before paging)
 * @param {(item: object) => (string|number)} options.getId an item's id
 * @param {string} options.filterKey a string that changes exactly when a filter input does
 * @param {() => void} options.onClose closes the panel
 */
export default function useCloseWhenFilteredOut({ selectedId, items, getId, filterKey, onClose }) {
  const seenKey = useRef(filterKey);
  useEffect(() => {
    if (seenKey.current === filterKey) return;
    seenKey.current = filterKey;
    if (selectedId == null) return;
    if (!items.some((item) => getId(item) === selectedId)) onClose();
  }, [selectedId, items, getId, filterKey, onClose]);
}
