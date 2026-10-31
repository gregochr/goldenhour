import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import AreasSection from './AreasSection.jsx';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';
import LocationListPane from './locations/LocationListPane.jsx';
import LocationPanel from './locations/LocationPanel.jsx';
import LocationEditForm from './locations/LocationEditForm.jsx';
import LocationAddForm from './locations/LocationAddForm.jsx';
import useCloseWhenFilteredOut from '../hooks/useCloseWhenFilteredOut.js';
import useHashSelection from '../hooks/useHashSelection.js';
import useLocationAdd from '../hooks/useLocationAdd.js';
import useLocationEditor from '../hooks/useLocationEditor.js';
import useLocationFailureReset from '../hooks/useLocationFailureReset.js';
import useLocationFilters from '../hooks/useLocationFilters.js';
import useLocationToggle from '../hooks/useLocationToggle.js';
import useLocationsData from '../hooks/useLocationsData.js';
import usePanelEscape from '../hooks/usePanelEscape.js';
import { needsAttention } from '../utils/locationAdmin.js';

/** The Manage tab key; a selected location adds `/{id}` ({@code #manage/locations/12}). */
const TAB = 'locations';

/** A location's id; module-level so the selection guard's dependencies stay stable. */
const locationId = (loc) => loc.id;

/**
 * Operations → Places → Locations, on the shared Data list-and-panel shell (data-tabs-redesign-plan.md §6, P6).
 *
 * <p>The list shows every location with its type, light, tides, Bortle and status; a row opens its panel
 * ({@code #manage/locations/{id}}), where the view, the edit form and the failure reset live. Add location takes over
 * the panel as a form, with no confirm step. Rows hold no buttons, and turning a location on or off happens in the
 * panel only. The Areas section stays below the list, as it was.
 *
 * <p>All the state and rules are in the hooks ({@code useLocationsData}, {@code useLocationEditor},
 * {@code useLocationAdd}, {@code useLocationToggle}, {@code useLocationFailureReset}, {@code useLocationFilters}); this
 * component wires them to the layout, Esc and the hash. {@code #manage/locations?region={id}} (the Regions tab's
 * "Show them") opens the list filtered to that region.
 *
 * @param {object} props
 * @param {function} props.onLocationsChanged called when a location is added, saved, turned on or off or has its
 *   failures reset (they change what readers see)
 * @param {function} [props.onAttentionCountChange] told how many locations need attention, once they have loaded and
 *   after each change, for the sub-tab's count
 */
export default function LocationManagementView({ onLocationsChanged, onAttentionCountChange = undefined }) {
  const data = useLocationsData();
  const {
    locations, regions, loading, loadError, areas, areasVersion, subRegions, aliasesFailed, refreshLocations, refreshAreas,
  } = data;
  const { selectedId, select, clear } = useHashSelection(TAB);
  const [adding, setAdding] = useState(false);
  const panelRef = useRef(null);
  // What is on screen now, for an add that resolves later: it selects the new location only if nothing moved meanwhile.
  const live = useRef({ selectedId, adding });

  const editor = useLocationEditor({
    areas,
    subRegions,
    aliasesByLocation: data.aliasesByLocation,
    setAliasesByLocation: data.setAliasesByLocation,
    refreshLocations,
    refreshAreas,
    onLocationsChanged,
  });
  const add = useLocationAdd({ refreshLocations, refreshAreas });
  const toggle = useLocationToggle({
    locations, setLocations: data.setLocations, refreshLocations, refreshAreas, onChanged: onLocationsChanged,
  });
  const failureReset = useLocationFailureReset(() => {
    refreshLocations();
    onLocationsChanged();
  });

  const shown = toggle.locations;
  const filters = useLocationFilters(shown, regions);
  const selected = shown.find((loc) => loc.id === selectedId) ?? null;
  // The failure box reads the stored place, so an optimistic turn on never makes it describe the wrong state.
  const stored = locations.find((loc) => loc.id === selectedId) ?? selected;
  const editing = selected != null && editor.editingId === selected.id && editor.values != null;
  const panelOpen = adding || selected != null;

  // The sub-tab's count follows the stored list (not an optimistic flip), once it has loaded.
  const attentionCount = useMemo(() => locations.filter(needsAttention).length, [locations]);
  useEffect(() => {
    if (!loading && !loadError && onAttentionCountChange) onAttentionCountChange(attentionCount);
  }, [loading, loadError, attentionCount, onAttentionCountChange]);

  // A new selection (a click, Back, Forward, a pasted link) leaves any open draft behind and leaves Add location.
  // Adjusting state while rendering, React's pattern for state derived from a changed value.
  const [seenSelection, setSeenSelection] = useState(selectedId);
  if (selectedId !== seenSelection) {
    setSeenSelection(selectedId);
    editor.discard();
    toggle.clearError();
    failureReset.clearErrors();
    if (selectedId != null) setAdding(false);
  }

  // A hash naming no location (a pasted unknown id) leaves the bare tab address, replacing the entry.
  useEffect(() => {
    if (!loading && !loadError && selectedId != null && selected == null) clear({ replace: true });
  }, [loading, loadError, selectedId, selected, clear]);

  useEffect(() => {
    live.current = { selectedId, adding };
  }, [selectedId, adding]);

  const cancelEdit = editor.cancel;
  const closePanel = useCallback(() => {
    setAdding(false);
    cancelEdit();
    clear();
  }, [clear, cancelEdit]);

  useCloseWhenFilteredOut({
    selectedId, items: filters.filtered, getId: locationId, filterKey: filters.filterKey, onClose: closePanel,
  });

  // Esc steps back from the edit form to the panel first, then closes it. The hook leaves the key alone when a dialog
  // is open, when focus is in a text field outside the panel's form, and when the Operations pane is hidden; in the
  // edit form's own fields it cancels the edit, as the inline editor did. A save in flight is not interrupted.
  usePanelEscape({
    active: panelOpen,
    panelRef,
    onEscape: () => {
      if (editor.saving || add.saving) return;
      if (editing) cancelEdit();
      else closePanel();
    },
  });

  function selectLocation(loc) {
    setAdding(false);
    select(loc.id);
  }

  function startAdd() {
    cancelEdit();
    add.reset();
    setAdding(true);
    clear();
  }

  async function handleAdd() {
    const before = live.current;
    const created = await add.submit();
    if (!created) return;
    onLocationsChanged();
    // The admin may have opened a row, or cancelled, while it was saving: then the form is already gone and the new
    // location is left for them to find in the list.
    const unchanged = live.current.adding && live.current.selectedId === before.selectedId;
    if (!unchanged) return;
    setAdding(false);
    if (created.id != null) {
      // The re-read may have failed; make sure the new location is known so the selection does not look dangling.
      data.setLocations((current) => (current.some((loc) => loc.id === created.id) ? current : [...current, created]));
      select(created.id);
    }
  }

  let panel = null;
  if (adding) {
    panel = (
      <LocationAddForm
        add={add}
        regions={regions}
        subRegions={subRegions}
        onSubmit={handleAdd}
        onCancel={closePanel}
      />
    );
  } else if (editing) {
    panel = (
      <LocationEditForm
        key={selected.id}
        location={selected}
        editor={editor}
        regions={regions}
        subRegions={subRegions}
        aliasesFailed={aliasesFailed}
        onClose={closePanel}
        onCancel={cancelEdit}
      />
    );
  } else if (selected) {
    panel = (
      <LocationPanel
        key={selected.id}
        location={selected}
        onClose={closePanel}
        onEdit={() => editor.start(selected)}
        onToggle={toggle.toggle}
        toggling={toggle.pendingIds.has(selected.id)}
        failureLocation={stored}
        toggleError={toggle.error?.id === selected.id ? toggle.error.message : ''}
        resetting={failureReset.pending.has(selected.name)}
        resetError={failureReset.errors[selected.name] ?? null}
        onReset={(fallback) => failureReset.reset(selected.name, fallback)}
      />
    );
  }

  let panelLabel = 'Location';
  if (adding) panelLabel = 'Add location';
  else if (selected) panelLabel = `Location ${selected.name}`;

  let selectionKey = null;
  if (adding) selectionKey = 'add';
  else if (selected) selectionKey = selected.id;
  let mode = 'view';
  if (adding) mode = 'add';
  else if (editing) mode = 'edit';

  return (
    <div className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible" data-testid="location-management">
      {loading && <p className="p-4 text-sm text-plex-text-muted animate-pulse">Loading locations...</p>}
      <ErrorBanner message={loadError ? "Couldn't load locations." : ''} className="m-4" data-testid="locations-load-error" />

      {!loading && !loadError && (
        <DataSplitLayout
          list={(
            <>
              <LocationListPane
                locations={shown}
                regions={regions}
                filters={filters}
                selectedId={selected ? selected.id : null}
                onSelect={selectLocation}
                onAdd={startAdd}
              />
              <div className="px-4 pb-4 max-[639px]:px-[14px]">
                <AreasSection locations={locations} reloadKey={areasVersion} onAreasChanged={() => refreshAreas(false)} />
              </div>
            </>
          )}
          panel={panel}
          onClose={closePanel}
          panelLabel={panelLabel}
          backLabel={editing ? selected.name : 'Locations'}
          onBack={editing ? cancelEdit : closePanel}
          panelRef={panelRef}
          selectionKey={selectionKey}
          focusKey={`${selectionKey}:${mode}`}
          rowTestIdPrefix="locations"
          testId="location"
        />
      )}
    </div>
  );
}

LocationManagementView.propTypes = {
  onLocationsChanged: PropTypes.func.isRequired,
  onAttentionCountChange: PropTypes.func,
};
