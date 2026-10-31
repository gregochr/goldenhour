import {
  startTransition, useCallback, useEffect, useMemo, useOptimistic, useRef, useState, useTransition,
} from 'react';
import { fetchRegions, setRegionEnabled } from '../api/regionApi.js';
import { fetchLocations } from '../api/forecastApi.js';
import { fetchAreas } from '../api/areaApi.js';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';
import RegionListPane from './regions/RegionListPane.jsx';
import RegionPanel from './regions/RegionPanel.jsx';
import RegionForm from './regions/RegionForm.jsx';
import useHashSelection from '../hooks/useHashSelection.js';
import usePanelEscape from '../hooks/usePanelEscape.js';
import { apiErrorMessage } from '../utils/apiError.js';
import { buildRegionRows } from '../utils/regionAdmin.js';

/** The Manage tab key; a selected region adds `/{id}` ({@code #manage/regions/3}). */
const TAB = 'regions';

/**
 * Operations → Places → Regions, on the shared Data list-and-panel shell (data-tabs-redesign-plan.md §5.4, P5).
 *
 * <p>The list holds the regions that are on, then a collapsible "Off" group (collapsed unless the hash selects an off
 * region). A row opens the region panel ({@code #manage/regions/{id}}): Details (Name, Base), Sub-regions and Region
 * actions. Add region, Rename and Edit base take over the panel body as one-purpose forms; Esc and the phone back bar
 * step back from a form to the panel before closing it.
 *
 * <p>Turning a region off is honest about the server rule (a region never holds a switched-on location): with
 * switched-on locations the button is disabled and links to them; otherwise it turns off at once. The toggle is
 * optimistic and reverts on a failure, whose server sentence shows under the action. Regions are the one required
 * fetch; the locations and the sub-regions only decorate (counts, the sub-region list), so their failure leaves the
 * list working with a notice.
 */
export default function RegionManagementView() {
  const [regions, setRegions] = useState([]);
  const [locations, setLocations] = useState([]);
  const [areas, setAreas] = useState([]);
  const [decorationFailed, setDecorationFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const { selectedId, select, clear } = useHashSelection(TAB);
  const [adding, setAdding] = useState(false);
  const [mode, setMode] = useState('view'); // the region panel's body: view | rename | base
  const [offOpen, setOffOpen] = useState(false);
  // The refused toggle's sentence, with the region it belongs to so it never shows under another region.
  const [toggleError, setToggleError] = useState(null);
  const panelRef = useRef(null);

  const [optimisticRegions, addOptimisticToggle] = useOptimistic(regions, (current, toggledId) =>
    current.map((r) => (r.id === toggledId ? { ...r, enabled: !r.enabled } : r)),
  );
  const [toggling, startToggleTransition] = useTransition();

  const rows = useMemo(
    () => buildRegionRows(optimisticRegions, locations, areas),
    [optimisticRegions, locations, areas],
  );
  const selected = rows.find((r) => r.id === selectedId) ?? null;
  const panelOpen = adding || selected != null;

  // A new selection (a click, Back, Forward, a pasted link) starts on the region's own view and leaves Add region.
  // Adjusting state while rendering, React's pattern for state derived from a changed value.
  const [seenSelection, setSeenSelection] = useState(selectedId);
  if (selectedId !== seenSelection) {
    setSeenSelection(selectedId);
    setMode('view');
    if (selectedId != null) setAdding(false);
  }

  // The Off group opens when the selection is (or becomes) an off region: a pasted link, or a region just turned off.
  // Read from the STORED regions, not the optimistic ones: a turn-off the server refuses must not leave the group open.
  const storedSelected = regions.find((r) => r.id === selectedId);
  const offSelectedId = storedSelected && !storedSelected.enabled ? storedSelected.id : null;
  const [seenOffSelected, setSeenOffSelected] = useState(null);
  if (offSelectedId !== seenOffSelected) {
    setSeenOffSelected(offSelectedId);
    if (offSelectedId != null) setOffOpen(true);
  }

  const refreshRegions = useCallback(async () => {
    try {
      setRegions(await fetchRegions());
    } catch {
      // Keep the list on screen if a refresh fails.
    }
  }, []);

  useEffect(() => {
    let live = true;
    // The locations and sub-regions only decorate the regions, so their failure is not the view's.
    Promise.all([
      fetchRegions(),
      fetchLocations().catch(() => null),
      fetchAreas().catch(() => null),
    ])
      .then(([regs, locs, ars]) => {
        if (!live) return;
        setRegions(regs);
        setLocations(Array.isArray(locs) ? locs : []);
        setAreas(Array.isArray(ars) ? ars : []);
        setDecorationFailed(!Array.isArray(locs) || !Array.isArray(ars));
      })
      .catch(() => { if (live) setLoadError("Couldn't load regions."); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, []);

  // A hash naming no region (a pasted unknown id) leaves the bare tab address, replacing the entry.
  useEffect(() => {
    if (!loading && !loadError && selectedId != null && selected == null) clear({ replace: true });
  }, [loading, loadError, selectedId, selected, clear]);

  const closePanel = useCallback(() => {
    setAdding(false);
    setMode('view');
    clear();
  }, [clear]);

  // Esc steps back from a form to the panel first, then closes it. The hook leaves the key alone when a dialog is
  // open, when focus is in a text field outside the panel's form, and when the Operations pane is hidden.
  usePanelEscape({
    active: panelOpen,
    panelRef,
    onEscape: !adding && mode !== 'view' ? () => setMode('view') : closePanel,
  });

  function selectRegion(region) {
    setAdding(false);
    setMode('view');
    select(region.id);
  }

  function startAdd() {
    setAdding(true);
    clear();
  }

  async function handleSaved(saved) {
    if (adding) {
      setAdding(false);
      if (saved?.id != null) {
        // The re-read may have failed; make sure the new region is known so the selection does not look dangling.
        setRegions((current) => (current.some((r) => r.id === saved.id) ? current : [...current, saved]));
        select(saved.id);
      }
      return;
    }
    setMode('view');
  }

  function handleToggle(region) {
    setToggleError(null);
    startToggleTransition(async () => {
      addOptimisticToggle(region.id);
      try {
        await setRegionEnabled(region.id, !region.enabled);
        // The re-read joins the action's transition (a bare setState after an await would not), so the optimistic
        // flip hands over to the stored value in one render rather than reverting for a frame first.
        const fresh = await fetchRegions().catch(() => null);
        if (fresh) startTransition(() => setRegions(fresh));
      } catch (err) {
        console.error('Failed to toggle region enabled:', err);
        // The optimistic flip reverts when the transition settles; the sentence stays under the action.
        setToggleError({ id: region.id, message: apiErrorMessage(err, 'Could not change this region.') });
      }
    });
  }

  let panel = null;
  if (adding) {
    panel = (
      <RegionForm
        mode="add"
        onClose={closePanel}
        onCancel={closePanel}
        onSaved={handleSaved}
        onRefresh={refreshRegions}
      />
    );
  } else if (selected && mode !== 'view') {
    panel = (
      <RegionForm
        key={`${selected.id}:${mode}`}
        mode={mode}
        region={selected}
        onClose={closePanel}
        onCancel={() => setMode('view')}
        onSaved={handleSaved}
        onRefresh={refreshRegions}
      />
    );
  } else if (selected) {
    panel = (
      <RegionPanel
        key={selected.id}
        region={selected}
        onClose={closePanel}
        onRename={() => setMode('rename')}
        onEditBase={() => setMode('base')}
        onToggle={handleToggle}
        toggling={toggling}
        toggleError={toggleError?.id === selected.id ? toggleError.message : ''}
      />
    );
  }

  let panelLabel = 'Region';
  if (adding) panelLabel = 'Add region';
  else if (selected) panelLabel = `Region ${selected.name}`;

  // On the phone page the back button steps back from a form to the region, as Esc does; from the region (or Add
  // region) it returns to the list.
  const inSubMode = !adding && selected != null && mode !== 'view';

  let selectionKey = null;
  if (adding) selectionKey = 'add';
  else if (selected) selectionKey = selected.id;

  return (
    <div className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible" data-testid="region-management">
      {loading && <p className="p-4 text-sm text-plex-text-muted animate-pulse">Loading regions...</p>}
      <ErrorBanner message={loadError} className="m-4" data-testid="regions-load-error" />

      {!loading && !loadError && (
        <DataSplitLayout
          list={(
            <RegionListPane
              regions={rows}
              selectedId={selected ? selected.id : null}
              offOpen={offOpen}
              onToggleOff={() => setOffOpen((open) => !open)}
              onSelect={selectRegion}
              onAdd={startAdd}
              notice={decorationFailed ? "Couldn't load every location and sub-region, so some counts may be missing." : ''}
            />
          )}
          panel={panel}
          onClose={closePanel}
          panelLabel={panelLabel}
          backLabel={inSubMode ? selected.name : 'Regions'}
          onBack={inSubMode ? () => setMode('view') : closePanel}
          panelRef={panelRef}
          selectionKey={selectionKey}
          focusKey={`${selectionKey}:${adding ? 'add' : mode}`}
          rowTestIdPrefix="regions"
          testId="region"
        />
      )}
    </div>
  );
}
