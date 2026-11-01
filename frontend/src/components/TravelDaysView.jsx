import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { addTravelDay, fetchTravelDays } from '../api/travelDayApi.js';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import TravelDaysListPane from './traveldays/TravelDaysListPane.jsx';
import TravelRangePanel from './traveldays/TravelRangePanel.jsx';
import TravelAddPanel from './traveldays/TravelAddPanel.jsx';
import useHashSelection from '../hooks/useHashSelection.js';
import usePanelEscape from '../hooks/usePanelEscape.js';
import { ukDateStr } from '../utils/mapDates.js';
import {
  draftSpan, isPastRange, monthOf, placeServerError, rangeText,
} from '../utils/travelDays.js';

/** The Manage tab key; the hash adds the open range's numeric id ({@code #manage/traveldays/12}). */
const TAB = 'traveldays';

/** Past starts collapsed; Upcoming open. */
const INITIAL_GROUPS = { upcoming: true, past: false };

/**
 * Operations → Travel days (runs-redesign-plan.md §5.6, R5; travel-days-calendar-plan.md): the ranges the overnight
 * batch skips, on the shared Data list-and-panel shell. The list pane carries the header, the month calendar and the
 * ranges (Upcoming open, Past collapsed); a row, or an away day on the calendar, opens that range's panel
 * ({@code #manage/traveldays/{id}}) for Edit and Remove; Add range opens the Add panel, and a drag across free days
 * on the calendar drafts a range (its bar adds it, or, with the Add panel open, the draft fills From and To).
 *
 * <p>This view owns the shown month, the draft and the Add panel's fill, because a list row, the hash, a created range
 * and the Add panel all move them. Any selection that does not come from a calendar cell moves the calendar to the
 * range's start month; a cell press never does.
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
  const [viewMonth, setViewMonth] = useState(() => monthOf(ukDateStr(new Date())));
  const [draft, setDraft] = useState(null);
  const [draftSaving, setDraftSaving] = useState(false);
  const [draftError, setDraftError] = useState('');
  // The range a calendar cell last opened (consumed once, so Back to it later still moves the month), the day that
  // opened the open panel, and the request that puts focus back on it.
  const [cellSelectId, setCellSelectId] = useState(null);
  const [originIso, setOriginIso] = useState(null);
  const [focusRequest, setFocusRequest] = useState(null);
  const addCount = useRef(0);
  const panelRef = useRef(null);
  // Read inside async callbacks, so they see the panel as it is when a request returns, not as it was when it began.
  const savingRef = useRef(false);
  const draftPosting = useRef(false);
  const selectedIdRef = useRef(null);
  const addKeyRef = useRef(null);

  const { selectedId, select, clear } = useHashSelection(TAB);

  const load = useCallback(async () => {
    try {
      setRanges(await fetchTravelDays({ includePast: true }));
      setError(null);
      setLoaded(true);
      return true;
    } catch {
      setError('Failed to load travel days');
      return false;
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

  // A range that did not come from a calendar cell (a row, Back, Forward, a pasted link once the ranges have loaded)
  // takes the calendar to its start month. Seeded null, so a deep link on first load counts.
  const [shownRangeId, setShownRangeId] = useState(null);
  const resolvedId = selectedRange?.id ?? null;
  if (shownRangeId !== resolvedId) {
    setShownRangeId(resolvedId);
    if (resolvedId != null) {
      if (cellSelectId === resolvedId) setCellSelectId(null);
      else setViewMonth(monthOf(selectedRange.startDate));
    }
  }

  // A different panel starts with no editor open (state adjusted while rendering, the pattern React documents).
  const [panelKey, setPanelKey] = useState(selectedId);
  if (panelKey !== selectedId) {
    setPanelKey(selectedId);
    setEditingId(null);
    // A range opened by Back, Forward or a pasted link takes the panel from an Add form that was open.
    if (selectedId != null) {
      setAdding(null);
      if (adding != null) setDraft(null);
    }
  }

  const editing = selectedRange != null && editingId === selectedRange.id;
  const panelOpen = adding != null || selectedRange != null;

  // Closing a panel that a calendar day opened puts focus back on that day (the grid, with its cursor there).
  const [wasPanelOpen, setWasPanelOpen] = useState(false);
  if (wasPanelOpen !== panelOpen) {
    setWasPanelOpen(panelOpen);
    if (wasPanelOpen && !panelOpen && originIso != null) {
      setFocusRequest((current) => ({ iso: originIso, seq: (current?.seq ?? 0) + 1 }));
      setOriginIso(null);
    }
  }

  useEffect(() => {
    selectedIdRef.current = selectedId;
    addKeyRef.current = adding?.key ?? null;
  }, [selectedId, adding]);

  // A save or remove in flight is never interrupted: Esc, the ×, the scrim and Back do nothing until it ends.
  const handleSavingChange = useCallback((saving) => { savingRef.current = saving; }, []);

  const closePanel = useCallback(() => {
    if (savingRef.current) return;
    // A draft never outlives the Add panel it was filling (it would reappear as a bar on a phone).
    if (adding != null) setDraft(null);
    setAdding(null);
    setEditingId(null);
    clear();
  }, [adding, clear]);
  const cancelEdit = useCallback(() => {
    if (savingRef.current) return;
    setEditingId(null);
  }, []);
  // Esc steps back from the editor first, then closes the panel.
  usePanelEscape({ active: panelOpen, panelRef, onEscape: editing ? cancelEdit : closePanel });

  const openRange = useCallback((range) => {
    setOriginIso(null);
    setCellSelectId(null);
    setViewMonth(monthOf(range.startDate));
    if (adding != null) setDraft(null);
    setAdding(null);
    select(range.id);
  }, [adding, select]);

  const openFromCell = useCallback((range, iso) => {
    setOriginIso(iso);
    if (range.id !== selectedId) setCellSelectId(range.id);
    // The Past group is collapsed by default: open it so the selected row is visible.
    if (isPastRange(range, new Date())) setGroupOpen((open) => ({ ...open, past: true }));
    if (adding != null) setDraft(null);
    setAdding(null);
    select(range.id);
  }, [adding, selectedId, select]);

  // Header Add range. A draft made first seeds From and To (the reader dragged, then wanted a note), unless the bar's own
  // save is posting it.
  const openAdd = useCallback(() => {
    addCount.current += 1;
    setEditingId(null);
    setOriginIso(null);
    if (selectedId != null) clear();
    let from = '';
    let to = '';
    if (draft && !draftPosting.current) {
      from = draft.start;
      to = draft.end ?? '';
      setDraft(null);
    }
    setAdding({
      from, to, key: addCount.current, fillSeq: 0, fillStart: '', fillEnd: '',
    });
  }, [selectedId, clear, draft]);

  // The calendar's draft. A completed one fills the open Add panel (and leaves no bar); otherwise it is kept for the
  // bar. Ignored while the bar's save is posting, and a fill is not applied to an Add whose save is in flight.
  const handleDraftChange = useCallback((next) => {
    if (draftPosting.current) return;
    setDraftError('');
    const span = draftSpan(next);
    if (span?.end && adding != null) {
      if (savingRef.current) return;
      setDraft(null);
      setAdding((current) => (current == null ? current : {
        ...current, fillSeq: current.fillSeq + 1, fillStart: span.start, fillEnd: span.end,
      }));
      return;
    }
    setDraft(span);
  }, [adding]);

  // The reload comes first, so the panel never closes during the round trip. Then the Add gives way to the new range,
  // but only if that same Add is still the open panel: a reader who clicked elsewhere is not pulled away. If the
  // reload failed, the created range is merged in, so it still opens rather than being cleared as an unknown id.
  const handleCreated = useCallback(async (created, key) => {
    const reloaded = await load();
    if (!reloaded) setRanges((current) => (current.some((r) => r.id === created.id) ? current : [created, ...current]));
    if (addKeyRef.current !== key) return;
    setAdding(null);
    setDraft(null);
    setCellSelectId(null);
    select(created.id);
  }, [load, select]);

  // The draft bar's Add range: posts the draft with no note. The draft is cleared only if it is still the one posted
  // (a newer drag survives), and the new range is opened only if the reader has not opened a panel meanwhile. A
  // refusal shows under the bar and keeps the draft.
  const addDraft = useCallback(async () => {
    if (draftPosting.current || !draft?.end) return;
    const posted = draft;
    const selectionAtPress = selectedIdRef.current;
    draftPosting.current = true;
    setDraftSaving(true);
    setDraftError('');
    try {
      const created = await addTravelDay({ startDate: posted.start, endDate: posted.end, note: null });
      setDraft((current) => (current && current.start === posted.start && current.end === posted.end ? null : current));
      const reloaded = await load();
      if (!reloaded) setRanges((current) => (current.some((r) => r.id === created.id) ? current : [created, ...current]));
      if (addKeyRef.current == null && selectedIdRef.current === selectionAtPress) {
        setCellSelectId(null);
        setViewMonth(monthOf(created.startDate ?? posted.start));
        select(created.id);
      }
    } catch (err) {
      setDraftError(placeServerError(err, 'Failed to add travel range').message);
    } finally {
      draftPosting.current = false;
      setDraftSaving(false);
    }
  }, [draft, load, select]);

  const handleSaved = useCallback(async () => {
    setEditingId(null);
    await load();
  }, [load]);

  // Only the removed range's own panel is closed: if the reader has since opened another, it stays.
  const handleRemoved = useCallback(async (removedId) => {
    if (selectedIdRef.current === removedId) clear({ replace: true });
    await load();
  }, [clear, load]);

  const toggleGroup = useCallback((id) => setGroupOpen((open) => ({ ...open, [id]: !open[id] })), []);

  let panel = null;
  if (adding) {
    panel = (
      <TravelAddPanel
        key={`add-${adding.key}`}
        initialFrom={adding.from}
        initialTo={adding.to}
        fillSeq={adding.fillSeq}
        fillStart={adding.fillStart}
        fillEnd={adding.fillEnd}
        now={new Date()}
        onClose={closePanel}
        onCreated={(created) => handleCreated(created, adding.key)}
        onSavingChange={handleSavingChange}
      />
    );
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
        onRemoved={() => handleRemoved(selectedRange.id)}
        onClose={closePanel}
        onSavingChange={handleSavingChange}
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
            onAdd={openAdd}
            calendar={{
              viewMonth,
              onViewMonthChange: setViewMonth,
              draft,
              onDraftChange: handleDraftChange,
              addOpen: adding != null,
              onSelectRange: openFromCell,
              onAddDraft: addDraft,
              draftSaving,
              draftError,
              focusRequest,
            }}
          />
        )}
        panel={panel}
        onClose={closePanel}
        panelLabel={adding ? 'Add travel days' : `${selectedRange ? rangeText(selectedRange, new Date()) : 'Travel'} range`}
        backLabel={editing ? rangeText(selectedRange, new Date()) : 'Travel days'}
        onBack={editing ? cancelEdit : closePanel}
        panelRef={panelRef}
        selectionKey={selectionKey}
        focusKey={`${selectionKey}-${editing ? 'edit' : 'view'}`}
        rowTestIdPrefix="travel-days"
        testId="travel-split"
      />
    </div>
  );
}
