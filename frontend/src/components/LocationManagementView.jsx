import React, { useEffect, useState, useMemo } from 'react';
import PropTypes from 'prop-types';
import { fetchLocations, addLocation, updateLocation, setLocationEnabled, geocodePlace } from '../api/forecastApi.js';
import { fetchRegions } from '../api/regionApi.js';
import LocationAlerts from './LocationAlerts.jsx';

const GOLDEN_HOUR_TYPES = [
  { value: 'BOTH_TIMES', label: 'Both Times' },
  { value: 'SUNRISE', label: 'Sunrise' },
  { value: 'SUNSET', label: 'Sunset' },
  { value: 'ANYTIME', label: 'Anytime' },
];

const LOCATION_TYPES = [
  { value: 'LANDSCAPE', label: 'Landscape' },
  { value: 'SEASCAPE', label: 'Seascape' },
  { value: 'WILDLIFE', label: 'Wildlife' },
];

const TIDE_TYPES = [
  { value: 'ANY_TIDE', label: 'Any Tide' },
  { value: 'HIGH_TIDE', label: 'High Tide' },
  { value: 'LOW_TIDE', label: 'Low Tide' },
  { value: 'MID_TIDE', label: 'Mid Tide' },
  { value: 'NOT_COASTAL', label: 'Not Coastal' },
];

/**
 * Formats a location type set (array) into a readable label.
 *
 * @param {Array<string>} types - e.g. ['SEASCAPE']
 * @returns {string} Readable label.
 */
function formatLocationType(types) {
  if (!types || types.length === 0) return 'Landscape';
  return types.map((t) => {
    const found = LOCATION_TYPES.find((lt) => lt.value === t);
    return found ? found.label : t;
  }).join(', ');
}

/**
 * Formats a tide type set (array) into a readable label.
 *
 * @param {Array<string>} types - e.g. ['ANY_TIDE']
 * @returns {string} Readable label.
 */
function formatTideType(types) {
  if (!types || types.length === 0) return '—';
  const filtered = types.filter((t) => t !== 'NOT_COASTAL');
  if (filtered.length === 0) return '—';
  return filtered.map((t) => {
    const found = TIDE_TYPES.find((tt) => tt.value === t);
    return found ? found.label : t;
  }).join(', ');
}

/**
 * Formats a golden hour type enum into a readable label.
 *
 * @param {string} type - e.g. 'BOTH_TIMES'
 * @returns {string} Readable label.
 */
function formatGoldenHourType(type) {
  const found = GOLDEN_HOUR_TYPES.find((g) => g.value === type);
  return found ? found.label : type || 'Both Times';
}

/**
 * Extracts the first enum value from a set/array.
 *
 * @param {Array<string>} set - The set of enum values.
 * @param {string} fallback - Fallback value.
 * @returns {string} The first value or the fallback.
 */
function firstOrDefault(set, fallback) {
  if (!set || set.length === 0) return fallback;
  return set[0];
}

/**
 * Sortable, filterable header cell for data tables.
 *
 * @param {object} props
 * @param {string} props.label - Column header label.
 * @param {string} props.sortKey - Key used for sorting.
 * @param {string} props.currentSortKey - Currently active sort key.
 * @param {'asc'|'desc'} props.currentSortDir - Current sort direction.
 * @param {function} props.onSort - Called with the sort key when clicked.
 * @param {string} props.filterValue - Current filter value.
 * @param {function} props.onFilter - Called with new filter value.
 * @param {string} [props.filterPlaceholder] - Placeholder for filter input.
 */
function SortableHeader({ label, sortKey, currentSortKey, currentSortDir, onSort, filterValue, onFilter, filterPlaceholder }) {
  const active = currentSortKey === sortKey;
  const arrow = active ? (currentSortDir === 'asc' ? ' ▲' : ' ▼') : '';

  return (
    <th className="pb-1 font-medium align-bottom">
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className="text-xs text-plex-text-muted hover:text-plex-text cursor-pointer whitespace-nowrap"
      >
        {label}{arrow}
      </button>
      <div className="mt-1">
        <input
          type="text"
          value={filterValue}
          onChange={(e) => onFilter(e.target.value)}
          placeholder={filterPlaceholder || 'Filter…'}
          className="w-full bg-plex-surface-light border border-plex-border rounded px-1.5 py-0.5 text-xs text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
          data-testid={`filter-${sortKey}`}
        />
      </div>
    </th>
  );
}

SortableHeader.propTypes = {
  label: PropTypes.string.isRequired,
  sortKey: PropTypes.string.isRequired,
  currentSortKey: PropTypes.string.isRequired,
  currentSortDir: PropTypes.string.isRequired,
  onSort: PropTypes.func.isRequired,
  filterValue: PropTypes.string.isRequired,
  onFilter: PropTypes.func.isRequired,
  filterPlaceholder: PropTypes.string,
};

/**
 * Generic sort/filter hook for table data.
 *
 * @param {string} defaultSortKey - Initial sort column.
 * @param {'asc'|'desc'} defaultSortDir - Initial sort direction.
 * @param {Object<string, function>} accessors - Map of sort key to value accessor function.
 * @returns {object} Sort/filter state and handlers.
 */
function useSortAndFilter(defaultSortKey, defaultSortDir, accessors) {
  const [sortKey, setSortKey] = useState(defaultSortKey);
  const [sortDir, setSortDir] = useState(defaultSortDir);
  const [filters, setFilters] = useState({});

  function handleSort(key) {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  function setFilter(key, value) {
    setFilters((prev) => ({ ...prev, [key]: value }));
  }

  function getFilterValue(key) {
    return filters[key] || '';
  }

  function apply(items) {
    let result = [...items];

    // Filter
    for (const [key, value] of Object.entries(filters)) {
      if (!value) continue;
      const accessor = accessors[key];
      if (!accessor) continue;
      const lower = value.toLowerCase();
      result = result.filter((item) => {
        const val = accessor(item);
        return val != null && String(val).toLowerCase().includes(lower);
      });
    }

    // Sort
    const accessor = accessors[sortKey];
    if (accessor) {
      result.sort((a, b) => {
        const va = accessor(a);
        const vb = accessor(b);
        if (va == null && vb == null) return 0;
        if (va == null) return 1;
        if (vb == null) return -1;
        if (typeof va === 'string') {
          const cmp = va.localeCompare(vb, undefined, { sensitivity: 'base' });
          return sortDir === 'asc' ? cmp : -cmp;
        }
        if (typeof va === 'boolean') {
          const cmp = (va === vb) ? 0 : (va ? -1 : 1);
          return sortDir === 'asc' ? cmp : -cmp;
        }
        const cmp = va < vb ? -1 : va > vb ? 1 : 0;
        return sortDir === 'asc' ? cmp : -cmp;
      });
    }

    return result;
  }

  return { sortKey, sortDir, handleSort, setFilter, getFilterValue, apply };
}

/**
 * Location management view with list/add/edit modes, sorting, filtering, and geocoding.
 *
 * @param {object} props
 * @param {function} props.onLocationsChanged - Called when locations are added/updated/toggled.
 */
export default function LocationManagementView({ onLocationsChanged }) {
  const [locations, setLocations] = useState([]);
  const [regions, setRegions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [mode, setMode] = useState('list'); // list | add | edit
  const [editingLocation, setEditingLocation] = useState(null);

  // Add form state
  const [placeName, setPlaceName] = useState('');
  const [geocodeResult, setGeocodeResult] = useState(null);
  const [geocoding, setGeocoding] = useState(false);
  const [geocodeError, setGeocodeError] = useState('');
  const [manualEntry, setManualEntry] = useState(false);
  const [manualName, setManualName] = useState('');
  const [manualLat, setManualLat] = useState('');
  const [manualLon, setManualLon] = useState('');
  const [addGoldenHourType, setAddGoldenHourType] = useState('BOTH_TIMES');
  const [addLocationType, setAddLocationType] = useState('LANDSCAPE');
  const [addTideType, setAddTideType] = useState('NOT_COASTAL');
  const [addRegionId, setAddRegionId] = useState('');

  // Edit form state
  const [editName, setEditName] = useState('');
  const [editGoldenHourType, setEditGoldenHourType] = useState('BOTH_TIMES');
  const [editLocationType, setEditLocationType] = useState('LANDSCAPE');
  const [editTideType, setEditTideType] = useState('NOT_COASTAL');
  const [editRegionId, setEditRegionId] = useState('');

  // Confirm modal state
  const [confirmData, setConfirmData] = useState(null);

  // Save state
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const locationAccessors = useMemo(() => ({
    name: (loc) => loc.name,
    region: (loc) => loc.region?.name || '',
    type: (loc) => formatLocationType(loc.locationType),
    solar: (loc) => formatGoldenHourType(loc.goldenHourType),
    tide: (loc) => formatTideType(loc.tideType),
    created: (loc) => loc.createdAt || '',
    status: (loc) => loc.enabled ? 'Enabled' : 'Disabled',
  }), []);

  const sf = useSortAndFilter('name', 'asc', locationAccessors);

  const filteredLocations = useMemo(() => sf.apply(locations), [sf, locations]);

  async function refreshLocations() {
    try {
      const data = await fetchLocations();
      setLocations(data);
    } catch {
      // Keep existing list on failure
    }
  }

  useEffect(() => {
    Promise.all([fetchLocations(), fetchRegions()])
      .then(([locs, regs]) => { setLocations(locs); setRegions(regs); })
      .finally(() => setLoading(false));
  }, []);

  function handleStartAdd() {
    setMode('add');
    setPlaceName('');
    setGeocodeResult(null);
    setGeocodeError('');
    setManualEntry(false);
    setManualName('');
    setManualLat('');
    setManualLon('');
    setAddGoldenHourType('BOTH_TIMES');
    setAddLocationType('LANDSCAPE');
    setAddTideType('NOT_COASTAL');
    setAddRegionId('');
    setError('');
  }

  function handleStartEdit(loc) {
    setMode('edit');
    setEditingLocation(loc);
    setEditName(loc.name);
    setEditGoldenHourType(loc.goldenHourType || 'BOTH_TIMES');
    setEditLocationType(firstOrDefault(loc.locationType, 'LANDSCAPE'));
    setEditTideType(firstOrDefault(loc.tideType, 'NOT_COASTAL'));
    setEditRegionId(loc.region?.id ? String(loc.region.id) : '');
    setError('');
  }

  function handleCancel() {
    setMode('list');
    setEditingLocation(null);
    setConfirmData(null);
    setError('');
  }

  async function handleGeocode() {
    const trimmed = placeName.trim();
    if (!trimmed) {
      setGeocodeError('Please enter a place name.');
      return;
    }
    setGeocoding(true);
    setGeocodeError('');
    setGeocodeResult(null);
    try {
      const result = await geocodePlace(trimmed);
      setGeocodeResult(result);
    } catch (err) {
      setGeocodeError(err.message || 'Geocoding failed.');
    } finally {
      setGeocoding(false);
    }
  }

  function handleAddReviewConfirm() {
    if (manualEntry) {
      const trimmedName = manualName.trim();
      const lat = parseFloat(manualLat);
      const lon = parseFloat(manualLon);
      if (!trimmedName) { setError('Name is required.'); return; }
      if (isNaN(lat) || lat < -90 || lat > 90) { setError('Latitude must be between -90 and 90.'); return; }
      if (isNaN(lon) || lon < -180 || lon > 180) { setError('Longitude must be between -180 and 180.'); return; }
      setError('');
      setConfirmData({
        mode: 'add',
        name: trimmedName,
        lat,
        lon,
        displayName: `${lat.toFixed(4)}, ${lon.toFixed(4)}`,
        goldenHourType: addGoldenHourType,
        locationType: addLocationType,
        tideType: addLocationType === 'SEASCAPE' ? addTideType : 'NOT_COASTAL',
        regionId: addRegionId ? Number(addRegionId) : null,
      });
    } else {
      if (!geocodeResult) return;
      setConfirmData({
        mode: 'add',
        name: placeName.trim(),
        lat: geocodeResult.lat,
        lon: geocodeResult.lon,
        displayName: geocodeResult.displayName,
        goldenHourType: addGoldenHourType,
        locationType: addLocationType,
        tideType: addLocationType === 'SEASCAPE' ? addTideType : 'NOT_COASTAL',
        regionId: addRegionId ? Number(addRegionId) : null,
      });
    }
  }

  function handleEditReviewConfirm() {
    if (!editingLocation) return;
    const trimmedName = editName.trim();
    if (!trimmedName) {
      setError('Name cannot be blank.');
      return;
    }
    setConfirmData({
      mode: 'edit',
      id: editingLocation.id,
      name: trimmedName,
      lat: editingLocation.lat,
      lon: editingLocation.lon,
      goldenHourType: editGoldenHourType,
      locationType: editLocationType,
      tideType: editLocationType === 'SEASCAPE' ? editTideType : 'NOT_COASTAL',
      regionId: editRegionId ? Number(editRegionId) : null,
    });
  }

  async function handleConfirmSave() {
    setSaving(true);
    setError('');
    try {
      if (confirmData.mode === 'add') {
        await addLocation({
          name: confirmData.name,
          lat: confirmData.lat,
          lon: confirmData.lon,
          goldenHourType: confirmData.goldenHourType,
          locationType: confirmData.locationType,
          tideType: confirmData.tideType,
          regionId: confirmData.regionId,
        });
      } else {
        await updateLocation(confirmData.id, {
          name: confirmData.name,
          goldenHourType: confirmData.goldenHourType,
          locationType: confirmData.locationType,
          tideType: confirmData.tideType,
          regionId: confirmData.regionId,
        });
      }
      await refreshLocations();
      handleCancel();
    } catch (err) {
      setError(err?.response?.data?.error ?? err.message ?? 'Failed to save location.');
    } finally {
      setSaving(false);
    }
  }

  async function handleToggleEnabled(loc) {
    try {
      await setLocationEnabled(loc.id, !loc.enabled);
      await refreshLocations();
    } catch (err) {
      console.error('Failed to toggle location enabled:', err);
    }
  }

  // Auto-set tide when location type changes
  function handleAddLocationTypeChange(value) {
    setAddLocationType(value);
    if (value === 'SEASCAPE') {
      setAddTideType('ANY_TIDE');
    } else {
      setAddTideType('NOT_COASTAL');
    }
  }

  function handleEditLocationTypeChange(value) {
    setEditLocationType(value);
    if (value === 'SEASCAPE') {
      setEditTideType('ANY_TIDE');
    } else {
      setEditTideType('NOT_COASTAL');
    }
  }

  const selectClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold';
  const labelClass = 'block text-xs text-plex-text-secondary mb-1';

  return (
    <div className="flex flex-col gap-4">

      {/* List mode */}
      {mode === 'list' && (
        <>
          <div className="flex items-center justify-between gap-4">
            <p className="text-sm font-semibold text-plex-text">Location Management</p>
            <button
              className="btn-secondary text-xs shrink-0"
              onClick={handleStartAdd}
              data-testid="add-location-btn"
            >
              + Add Location
            </button>
          </div>

          <LocationAlerts
            locations={locations}
            onReenabledLocation={() => { refreshLocations(); onLocationsChanged(); }}
          />

          {loading && (
            <p className="text-sm text-plex-text-muted animate-pulse">Loading locations...</p>
          )}

          {!loading && locations.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm text-left" data-testid="locations-table">
                <thead>
                  <tr className="text-xs text-plex-text-muted border-b border-plex-border">
                    <SortableHeader label="Name" sortKey="name" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('name')} onFilter={(v) => sf.setFilter('name', v)} />
                    <th className="pb-1 font-medium align-top">
                      <span className="text-xs text-plex-text-muted whitespace-nowrap">Coords</span>
                      <div className="mt-1 h-[26px]" />
                    </th>
                    <SortableHeader label="Type" sortKey="type" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('type')} onFilter={(v) => sf.setFilter('type', v)} />
                    <SortableHeader label="Region" sortKey="region" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('region')} onFilter={(v) => sf.setFilter('region', v)} />
                    <SortableHeader label="Solar" sortKey="solar" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('solar')} onFilter={(v) => sf.setFilter('solar', v)} />
                    <SortableHeader label="Tide" sortKey="tide" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('tide')} onFilter={(v) => sf.setFilter('tide', v)} />
                    <SortableHeader label="Created" sortKey="created" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('created')} onFilter={(v) => sf.setFilter('created', v)} />
                    <SortableHeader label="Status" sortKey="status" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('status')} onFilter={(v) => sf.setFilter('status', v)} />
                    <th className="pb-1 font-medium align-top">
                      <span className="text-xs text-plex-text-muted whitespace-nowrap">Actions</span>
                      <div className="mt-1 h-[26px]" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {filteredLocations.map((loc) => (
                    <tr
                      key={loc.id}
                      className={`border-b border-plex-surface last:border-0 ${!loc.enabled ? 'opacity-50' : ''}`}
                    >
                      <td className="py-2 text-plex-text">
                        {loc.name}
                        {loc.consecutiveFailures > 0 && !loc.disabledReason && (
                          <span
                            className="ml-1.5 text-xs px-1.5 py-0.5 rounded bg-amber-900/40 text-amber-400"
                            title={`${loc.consecutiveFailures} consecutive failure(s)${loc.lastFailureAt ? ' — last: ' + new Date(loc.lastFailureAt).toLocaleString() : ''}`}
                          >
                            {loc.consecutiveFailures}
                          </span>
                        )}
                      </td>
                      <td className="py-2 text-plex-text-secondary text-xs">
                        {loc.lat.toFixed(3)}, {loc.lon.toFixed(3)}
                      </td>
                      <td className="py-2 text-plex-text-secondary text-xs">
                        {formatLocationType(loc.locationType)}
                      </td>
                      <td className="py-2 text-plex-text-secondary text-xs">
                        {loc.region?.name || '—'}
                      </td>
                      <td className="py-2 text-plex-text-secondary text-xs">
                        {formatGoldenHourType(loc.goldenHourType)}
                      </td>
                      <td className="py-2 text-plex-text-secondary text-xs">
                        {formatTideType(loc.tideType)}
                      </td>
                      <td className="py-2 text-plex-text-muted text-xs">
                        {loc.createdAt ? loc.createdAt.slice(0, 10) : '—'}
                      </td>
                      <td className="py-2">
                        <button
                          onClick={() => handleToggleEnabled(loc)}
                          className={`text-xs px-2 py-0.5 rounded cursor-pointer ${
                            loc.enabled
                              ? 'bg-green-900/40 text-green-400 hover:bg-green-900/60'
                              : 'bg-red-900/40 text-red-400 hover:bg-red-900/60'
                          }`}
                          data-testid={`toggle-enabled-${loc.id}`}
                        >
                          {loc.enabled ? 'Enabled' : 'Disabled'}
                        </button>
                      </td>
                      <td className="py-2">
                        <button
                          className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text"
                          onClick={() => handleStartEdit(loc)}
                          data-testid={`edit-location-${loc.id}`}
                        >
                          Edit
                        </button>
                      </td>
                    </tr>
                  ))}
                  {filteredLocations.length === 0 && locations.length > 0 && (
                    <tr>
                      <td colSpan={9} className="py-4 text-center text-xs text-plex-text-muted">
                        No locations match the current filters.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          {!loading && locations.length === 0 && (
            <p className="text-sm text-plex-text-muted">No locations configured. Add one to get started.</p>
          )}
        </>
      )}

      {/* Add mode */}
      {mode === 'add' && (
        <div className="flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">Add New Location</p>

          {!manualEntry && (
            <>
              <div>
                <label htmlFor="place-name" className={labelClass}>Place name</label>
                <div className="flex gap-2">
                  <input
                    id="place-name"
                    type="text"
                    className="flex-1 bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                    placeholder="e.g. Bamburgh, Northumberland"
                    value={placeName}
                    onChange={(e) => setPlaceName(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') handleGeocode(); }}
                    data-testid="place-name-input"
                  />
                  <button
                    className="btn-secondary text-xs shrink-0"
                    onClick={handleGeocode}
                    disabled={geocoding}
                    data-testid="geocode-btn"
                  >
                    {geocoding ? 'Looking up...' : 'Look up'}
                  </button>
                </div>
              </div>

              {geocodeError && (
                <div className="flex flex-col gap-1">
                  <p className="text-xs text-red-400">{geocodeError}</p>
                  <button
                    type="button"
                    className="text-xs text-plex-gold hover:text-plex-text self-start"
                    onClick={() => { setManualEntry(true); setManualName(placeName.trim()); setGeocodeError(''); }}
                    data-testid="manual-entry-btn"
                  >
                    Enter coordinates manually
                  </button>
                </div>
              )}

              {geocodeResult && (
                <div className="bg-plex-surface-light border border-plex-border rounded px-3 py-2 text-xs text-plex-text-secondary">
                  <p className="font-medium text-plex-text">
                    {geocodeResult.lat.toFixed(4)}, {geocodeResult.lon.toFixed(4)}
                  </p>
                  <p className="mt-0.5">{geocodeResult.displayName}</p>
                </div>
              )}
            </>
          )}

          {manualEntry && (
            <div className="flex flex-col gap-3">
              <div>
                <label htmlFor="manual-name" className={labelClass}>Name</label>
                <input
                  id="manual-name"
                  type="text"
                  className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                  placeholder="e.g. Bamburgh Castle"
                  value={manualName}
                  onChange={(e) => setManualName(e.target.value)}
                  data-testid="manual-name-input"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor="manual-lat" className={labelClass}>Latitude</label>
                  <input
                    id="manual-lat"
                    type="number"
                    step="any"
                    className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                    placeholder="e.g. 55.6089"
                    value={manualLat}
                    onChange={(e) => setManualLat(e.target.value)}
                    data-testid="manual-lat-input"
                  />
                </div>
                <div>
                  <label htmlFor="manual-lon" className={labelClass}>Longitude</label>
                  <input
                    id="manual-lon"
                    type="number"
                    step="any"
                    className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                    placeholder="e.g. -1.7099"
                    value={manualLon}
                    onChange={(e) => setManualLon(e.target.value)}
                    data-testid="manual-lon-input"
                  />
                </div>
              </div>
              <button
                type="button"
                className="text-xs text-plex-gold hover:text-plex-text self-start"
                onClick={() => { setManualEntry(false); setError(''); }}
                data-testid="back-to-geocode-btn"
              >
                Back to place lookup
              </button>
            </div>
          )}

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label htmlFor="add-golden-hour-type" className={labelClass}>Golden Hour Type</label>
              <select
                id="add-golden-hour-type"
                className={selectClass}
                value={addGoldenHourType}
                onChange={(e) => setAddGoldenHourType(e.target.value)}
                data-testid="add-golden-hour-type"
              >
                {GOLDEN_HOUR_TYPES.map((g) => (
                  <option key={g.value} value={g.value}>{g.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="add-location-type" className={labelClass}>Location Type</label>
              <select
                id="add-location-type"
                className={selectClass}
                value={addLocationType}
                onChange={(e) => handleAddLocationTypeChange(e.target.value)}
                data-testid="add-location-type"
              >
                {LOCATION_TYPES.map((lt) => (
                  <option key={lt.value} value={lt.value}>{lt.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="add-tide-type" className={labelClass}>Tide Preference</label>
              <select
                id="add-tide-type"
                className={selectClass}
                value={addTideType}
                onChange={(e) => setAddTideType(e.target.value)}
                disabled={addLocationType !== 'SEASCAPE'}
                data-testid="add-tide-type"
              >
                {TIDE_TYPES.filter((t) => addLocationType === 'SEASCAPE' || t.value === 'NOT_COASTAL').map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="add-region" className={labelClass}>Region</label>
              <select
                id="add-region"
                className={selectClass}
                value={addRegionId}
                onChange={(e) => setAddRegionId(e.target.value)}
                data-testid="add-region"
              >
                <option value="">— None —</option>
                {regions.filter((r) => r.enabled).map((r) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </select>
            </div>
          </div>

          {error && <p className="text-xs text-red-400">{error}</p>}

          <div className="flex justify-between">
            <button className="btn-secondary text-sm" onClick={handleCancel}>
              Cancel
            </button>
            <button
              className="btn-primary text-sm"
              onClick={handleAddReviewConfirm}
              disabled={manualEntry ? (!manualName.trim() || !manualLat || !manualLon) : !geocodeResult}
              data-testid="review-confirm-btn"
            >
              Review & Confirm
            </button>
          </div>
        </div>
      )}

      {/* Edit mode */}
      {mode === 'edit' && editingLocation && (
        <div className="flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">
            Edit Location: {editingLocation.name}
          </p>

          <div>
            <label htmlFor="edit-name" className={labelClass}>Name</label>
            <input
              id="edit-name"
              type="text"
              className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold"
              value={editName}
              onChange={(e) => setEditName(e.target.value)}
              data-testid="edit-name"
            />
            <p className="mt-1 text-xs text-plex-text-muted">
              {editingLocation.lat.toFixed(4)}, {editingLocation.lon.toFixed(4)}
            </p>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label htmlFor="edit-golden-hour-type" className={labelClass}>Golden Hour Type</label>
              <select
                id="edit-golden-hour-type"
                className={selectClass}
                value={editGoldenHourType}
                onChange={(e) => setEditGoldenHourType(e.target.value)}
                data-testid="edit-golden-hour-type"
              >
                {GOLDEN_HOUR_TYPES.map((g) => (
                  <option key={g.value} value={g.value}>{g.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="edit-location-type" className={labelClass}>Location Type</label>
              <select
                id="edit-location-type"
                className={selectClass}
                value={editLocationType}
                onChange={(e) => handleEditLocationTypeChange(e.target.value)}
                data-testid="edit-location-type"
              >
                {LOCATION_TYPES.map((lt) => (
                  <option key={lt.value} value={lt.value}>{lt.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="edit-tide-type" className={labelClass}>Tide Preference</label>
              <select
                id="edit-tide-type"
                className={selectClass}
                value={editTideType}
                onChange={(e) => setEditTideType(e.target.value)}
                disabled={editLocationType !== 'SEASCAPE'}
                data-testid="edit-tide-type"
              >
                {TIDE_TYPES.filter((t) => editLocationType === 'SEASCAPE' || t.value === 'NOT_COASTAL').map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="edit-region" className={labelClass}>Region</label>
              <select
                id="edit-region"
                className={selectClass}
                value={editRegionId}
                onChange={(e) => setEditRegionId(e.target.value)}
                data-testid="edit-region"
              >
                <option value="">— None —</option>
                {regions.filter((r) => r.enabled || String(r.id) === editRegionId).map((r) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </select>
            </div>
          </div>

          {error && <p className="text-xs text-red-400">{error}</p>}

          <div className="flex justify-between">
            <button className="btn-secondary text-sm" onClick={handleCancel}>
              Cancel
            </button>
            <button
              className="btn-primary text-sm"
              onClick={handleEditReviewConfirm}
              data-testid="review-confirm-btn"
            >
              Review & Confirm
            </button>
          </div>
        </div>
      )}

      {/* Confirm modal */}
      {confirmData && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
          role="dialog"
          aria-modal="true"
          aria-label="Confirm location"
          data-testid="confirm-location-modal"
        >
          <div className="bg-plex-surface border border-plex-border rounded-xl shadow-2xl p-6 w-full max-w-md flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">
              {confirmData.mode === 'add' ? 'Confirm New Location' : 'Confirm Changes'}
            </p>

            <div className="text-sm space-y-2">
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Name</span>
                <span className="text-plex-text font-medium">{confirmData.name}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Coordinates</span>
                <span className="text-plex-text">
                  {confirmData.lat.toFixed(4)} N, {Math.abs(confirmData.lon).toFixed(4)} {confirmData.lon < 0 ? 'W' : 'E'}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Solar</span>
                <span className="text-plex-text">{formatGoldenHourType(confirmData.goldenHourType)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Type</span>
                <span className="text-plex-text">
                  {LOCATION_TYPES.find((lt) => lt.value === confirmData.locationType)?.label ?? confirmData.locationType}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Tide</span>
                <span className="text-plex-text">
                  {confirmData.tideType === 'NOT_COASTAL' ? '—' : (TIDE_TYPES.find((t) => t.value === confirmData.tideType)?.label ?? confirmData.tideType)}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Region</span>
                <span className="text-plex-text">
                  {confirmData.regionId ? (regions.find((r) => r.id === confirmData.regionId)?.name ?? '—') : '—'}
                </span>
              </div>
            </div>

            {error && <p className="text-xs text-red-400">{error}</p>}

            <div className="flex justify-between">
              <button
                className="btn-secondary text-sm"
                onClick={() => setConfirmData(null)}
                disabled={saving}
              >
                Back
              </button>
              <button
                className="btn-primary text-sm"
                onClick={handleConfirmSave}
                disabled={saving}
                data-testid="confirm-save-btn"
              >
                {saving ? 'Saving...' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

LocationManagementView.propTypes = {
  onLocationsChanged: PropTypes.func.isRequired,
};
