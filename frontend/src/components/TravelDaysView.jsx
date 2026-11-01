import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { fetchTravelDays } from '../api/travelDayApi.js';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import TravelDaysListPane from './traveldays/TravelDaysListPane.jsx';
import TravelRangePanel from './traveldays/TravelRangePanel.jsx';
import TravelAddPanel from './traveldays/TravelAddPanel.jsx';
import useHashSelection from '../hooks/useHashSelection.js';
import usePanelEscape from '../hooks/usePanelEscape.js';
import { rangeText } from '../utils/travelDays.js';

/** The Manage tab key; the hash adds the open range's numeric id ({@code #manage/traveldays/12}). */
const TAB = 'traveldays';

/** Past starts collapsed; Upcoming open. */
const INITIAL_GROUPS = { upcoming: true, past: false };

/**
 * Operations → Travel days (runs-redesign-plan.md §5.6, R5): the ranges the overnight batch skips, on the shared Data
 * list-and-panel shell. The list pane carries the header, the five-week calendar and the ranges (Upcoming open, Past
 * collapsed); a row, or an away day on the calendar, opens that range's panel ({@code #manage/traveldays/{id}}) for
 * Edit and Remove; Add range, or an empty day on the calendar, opens the Add panel.
 *
 * <p>The list is the admin one with {@code includePast=true}, newest start first; the public endpoint the Plan, Map
 * and Ask read is untouched. Every clock read in this view is the real clock (R-D7): Operations does not follow
 * Rewind. A failed reload keeps the ranges already shown and says so in a banner.
 */
export default function TravelDaysView() {
  const [ranges, setRanges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(null);
  const [adding, setAdding] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [groupOpen, setGroupOpen] = useState(INITIAL_GROUPS);
  const addCount = useRef(0);
  const panelRef = useRef(null);

  const { selectedId, select, clear } = useHashSelection(TAB);

  const load = useCallback(async () => {
    try {
      setRanges(await fetchTravelDays({ includePast: true }));
      setError(null);
      setLoaded(true);
    } catch {
      setError('Failed to load travel days');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    (async () => {
      await load();
    })();
  }, [load]);

  const selectedRange = useMemo(
    () => (selectedId == null ? null : ranges.find((range) => range.id === selectedId) ?? null),
    [ranges, selectedId],
  );

  // A hash naming no range (a pasted id that is gone) leaves the bare tab address, replacing the entry. Only once the
  // ranges have loaded: a failed first load says nothing about what exists.
  useEffect(() => {
    if (loaded && selectedId != null && selectedRange == null) clear({ replace: true });
  }, [loaded, selectedId, selectedRange, clear]);

  // A different panel starts with no editor open (state adjusted while rendering, the pattern React documents).
  const [panelKey, setPanelKey] = useState(selectedId);
  if (panelKey !== selectedId) {
    setPanelKey(selectedId);
    setEditingId(null);
    // A range opened by Back, Forward or a pasted link takes the panel from an Add form that was open.
    if (selectedId != null) setAdding(null);
  }

  const editing = selectedRange != null && editingId === selectedRange.id;
  const panelOpen = adding != null || selectedRange != null;

  const closePanel = useCallback(() => {
    setAdding(null);
    setEditingId(null);
    clear();
  }, [clear]);
  const cancelEdit = useCallback(() => setEditingId(null), []);
  // Esc steps back from the editor first, then closes the panel.
  usePanelEscape({ active: panelOpen, panelRef, onEscape: editing ? cancelEdit : closePanel });

  const openRange = useCallback((range) => {
    setAdding(null);
    select(range.id);
  }, [select]);

  const openAdd = useCallback((from = '') => {
    addCount.current += 1;
    setEditingId(null);
    if (selectedId != null) clear();
    setAdding({ from, key: addCount.current });
  }, [selectedId, clear]);

  const handleCreated = useCallback(async (created) => {
    setAdding(null);
    await load();
    select(created.id);
  }, [load, select]);

  const handleSaved = useCallback(async () => {
    setEditingId(null);
    await load();
  }, [load]);

  const handleRemoved = useCallback(async () => {
    clear({ replace: true });
    await load();
  }, [clear, load]);

  const toggleGroup = useCallback((id) => setGroupOpen((open) => ({ ...open, [id]: !open[id] })), []);

  let panel = null;
  if (adding) {
    panel = <TravelAddPanel key={`add-${adding.key}`} initialFrom={adding.from} onClose={closePanel} onCreated={handleCreated} />;
  } else if (selectedRange) {
    panel = (
      <TravelRangePanel
        key={selectedRange.id}
        range={selectedRange}
        now={new Date()}
        editing={editing}
        onEditStart={() => setEditingId(selectedRange.id)}
        onEditCancel={cancelEdit}
        onSaved={handleSaved}
        onRemoved={handleRemoved}
        onClose={closePanel}
      />
    );
  }

  let selectionKey = null;
  if (adding) selectionKey = `add-${adding.key}`;
  else if (selectedRange) selectionKey = selectedRange.id;

  return (
    <div className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible" data-testid="travel-days-view">
      <DataSplitLayout
        list={(
          <TravelDaysListPane
            ranges={ranges}
            loading={loading}
            error={error}
            now={new Date()}
            selectedId={selectedRange?.id ?? null}
            groupOpen={groupOpen}
            onToggleGroup={toggleGroup}
            onSelect={openRange}
            onAdd={() => openAdd()}
            onAddFrom={openAdd}
          />
        )}
        panel={panel}
        onClose={closePanel}
        panelLabel={adding ? 'Add travel days' : `${selectedRange ? rangeText(selectedRange) : 'Travel'} range`}
        backLabel="Travel days"
        panelRef={panelRef}
        selectionKey={selectionKey}
        focusKey={`${selectionKey}-${editing ? 'edit' : 'view'}`}
        rowTestIdPrefix="travel-days"
        testId="travel-split"
      />
    </div>
  );
}
