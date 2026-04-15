import React, { useEffect, useOptimistic, useState, useTransition, useMemo } from 'react';
import PropTypes from 'prop-types';
import { fetchLocations, addLocation, updateLocation, setLocationEnabled, geocodePlace, enrichLocation } from '../api/forecastApi.js';
import { fetchRegions } from '../api/regionApi.js';
import { fetchTideStats } from '../api/tideApi.js';
import LocationAlerts from './LocationAlerts.jsx';
import InfoTip from './InfoTip.jsx';
import { formatTimestampUk } from '../utils/conversions';
import Pagination from './Pagination.jsx';
import usePagination from '../hooks/usePagination.js';
import Modal from './shared/Modal.jsx';

const SOLAR_EVENT_TYPES = [
  { value: 'SUNRISE', label: 'Sunrise', emoji: '🌅' },
  { value: 'SUNSET', label: 'Sunset', emoji: '🌇' },
  { value: 'ALLDAY', label: 'All Day', emoji: '☀️' },
];

const LOCATION_TYPES = [
  { value: 'LANDSCAPE', label: 'Landscape', emoji: '🏔️' },
  { value: 'SEASCAPE', label: 'Seascape', emoji: '🌊' },
  { value: 'WILDLIFE', label: 'Wildlife', emoji: '🐾' },
  { value: 'WATERFALL', label: 'Waterfall', emoji: '💦' },
];

const TIDE_TYPES = [
  { value: 'HIGH', label: 'H', fullLabel: 'High' },
  { value: 'MID',  label: 'M', fullLabel: 'Mid' },
  { value: 'LOW',  label: 'L', fullLabel: 'Low' },
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
 * @param {Array<string>} types - e.g. ['HIGH', 'LOW']
 * @returns {string} Readable label.
 */
function formatTideType(types) {
  if (!types || types.length === 0) return '—';
  return types.map((t) => {
    const found = TIDE_TYPES.find((tt) => tt.value === t);
    return found ? found.fullLabel : t;
  }).join(', ');
}

/**
 * Compact toggle chip group for multi-select tide types.
 *
 * @param {object} props
 * @param {Array<string>} props.selected - Currently selected tide type values.
 * @param {function} props.onChange - Called with new array of selected values.
 * @param {boolean} [props.disabled=false] - When true, all chips are muted and non-interactive.
 */
function TideToggleChips({ selected, onChange, disabled = false, readOnly = false }) {
  function toggle(value) {
    if (disabled || readOnly) return;
    const isSelected = selected.includes(value);
    if (isSelected && selected.length <= 1) return; // prevent deselecting the last chip
    const next = isSelected ? selected.filter((v) => v !== value) : [...selected, value];
    onChange(next);
  }

  // Non-coastal: show dash instead of empty chips
  if (readOnly && (!selected || selected.length === 0)) {
    return <span className="text-plex-text-muted text-xs">—</span>;
  }

  return (
    <div className="inline-flex gap-0.5 whitespace-nowrap" data-testid="tide-toggle-chips">
      {TIDE_TYPES.map((t) => {
        const isOn = selected.includes(t.value);
        return (
          <button
            key={t.value}
            type="button"
            title={t.fullLabel}
            onClick={() => toggle(t.value)}
            disabled={disabled || readOnly}
            className={`text-xs px-1.5 py-0.5 rounded font-semibold transition-colors ${
              readOnly
                ? isOn
                  ? 'bg-plex-gold/20 text-plex-gold border border-plex-gold/40 cursor-default'
                  : 'bg-plex-surface-light text-plex-text-muted border border-plex-border/30 cursor-default opacity-30'
                : disabled
                  ? 'bg-plex-surface-light text-plex-text-muted cursor-default opacity-50'
                  : isOn
                    ? 'bg-plex-gold/20 text-plex-gold border border-plex-gold/40 cursor-pointer'
                    : 'bg-plex-surface-light text-plex-text-muted border border-plex-border cursor-pointer hover:border-plex-gold/30'
            }`}
            data-testid={`tide-chip-${t.value}`}
          >
            {t.label}
          </button>
        );
      })}
    </div>
  );
}

TideToggleChips.propTypes = {
  selected: PropTypes.arrayOf(PropTypes.string).isRequired,
  onChange: PropTypes.func.isRequired,
  disabled: PropTypes.bool,
  readOnly: PropTypes.bool,
};

/**
 * Emoji chip group for location type (single-select).
 *
 * @param {object} props
 * @param {string} props.selected - Active type value, e.g. 'SEASCAPE'.
 * @param {function} [props.onChange] - Called with new value on click (omit for read-only).
 * @param {boolean} [props.readOnly=false] - Disable interaction.
 */
function LocationTypeChips({ selected, onChange, readOnly = false }) {
  return (
    <div className="inline-flex gap-0.5 whitespace-nowrap" data-testid="location-type-chips">
      {LOCATION_TYPES.map((lt) => {
        const isOn = lt.value === selected;
        return (
          <button
            key={lt.value}
            type="button"
            title={lt.label}
            disabled={readOnly}
            onClick={() => { if (!readOnly && onChange) onChange(lt.value); }}
            className={`text-sm leading-none px-0.5 py-0.5 rounded transition-colors ${
              isOn
                ? 'opacity-100 cursor-default'
                : readOnly
                  ? 'opacity-50 grayscale cursor-default'
                  : 'opacity-50 grayscale cursor-pointer hover:opacity-70 hover:grayscale-0'
            }`}
            data-testid={`type-chip-${lt.value}`}
          >
            <span style={lt.value === 'WILDLIFE' ? { filter: 'brightness(2) contrast(1.5)' } : undefined}>{lt.emoji}</span>
          </button>
        );
      })}
    </div>
  );
}

LocationTypeChips.propTypes = {
  selected: PropTypes.string.isRequired,
  onChange: PropTypes.func,
  readOnly: PropTypes.bool,
};

/**
 * Emoji chip group for multi-select solar event types (🌅/🌇/☀️).
 *
 * @param {object} props
 * @param {Array<string>} props.selected - Currently selected solar event type values.
 * @param {function} props.onChange - Called with new array of selected values.
 * @param {boolean} [props.readOnly=false] - Disable interaction.
 */
function SolarToggleChips({ selected, onChange, readOnly = false }) {
  function toggle(value) {
    if (readOnly) return;
    const isSelected = selected.includes(value);
    if (isSelected && selected.length <= 1) return; // prevent deselecting the last chip
    const next = isSelected ? selected.filter((v) => v !== value) : [...selected, value];
    onChange(next);
  }

  return (
    <div className="inline-flex gap-0.5 whitespace-nowrap" data-testid="solar-toggle-chips">
      {SOLAR_EVENT_TYPES.map((s) => {
        const isOn = selected.includes(s.value);
        return (
          <button
            key={s.value}
            type="button"
            title={s.label}
            onClick={() => toggle(s.value)}
            disabled={readOnly}
            className={`text-sm leading-none px-0.5 py-0.5 rounded transition-colors ${
              readOnly
                ? isOn
                  ? 'opacity-100 cursor-default'
                  : 'opacity-30 grayscale cursor-default'
                : isOn
                  ? 'opacity-100 cursor-pointer'
                  : 'opacity-50 grayscale cursor-pointer hover:opacity-70 hover:grayscale-0'
            }`}
            data-testid={`solar-chip-${s.value}`}
          >
            {s.emoji}
          </button>
        );
      })}
    </div>
  );
}

SolarToggleChips.propTypes = {
  selected: PropTypes.arrayOf(PropTypes.string).isRequired,
  onChange: PropTypes.func.isRequired,
  readOnly: PropTypes.bool,
};

/**
 * Formats a solar event type set (array) into a readable label.
 *
 * @param {Array<string>} types - e.g. ['SUNRISE', 'SUNSET']
 * @returns {string} Readable label.
 */
function formatSolarEventType(types) {
  if (!types || types.length === 0) return 'Sunrise, Sunset';
  return types.map((t) => {
    const found = SOLAR_EVENT_TYPES.find((s) => s.value === t);
    return found ? found.label : t;
  }).join(', ');
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
function SortableHeader({ label, sortKey, currentSortKey, currentSortDir, onSort, filterValue, onFilter, filterPlaceholder, className = '' }) {
  const active = currentSortKey === sortKey;
  const arrow = active ? (currentSortDir === 'asc' ? ' ▲' : ' ▼') : '';

  return (
    <th className={`pb-1 font-medium align-bottom ${className}`}>
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
  className: PropTypes.string,
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
      if (!value || (Array.isArray(value) && value.length === 0)) continue;
      const accessor = accessors[key];
      if (!accessor) continue;
      if (Array.isArray(value)) {
        result = result.filter((item) => {
          const val = accessor(item);
          if (val == null) return false;
          const lower = String(val).toLowerCase();
          return value.every((v) => lower.includes(v.toLowerCase()));
        });
      } else {
        const lower = value.toLowerCase();
        result = result.filter((item) => {
          const val = accessor(item);
          return val != null && String(val).toLowerCase().includes(lower);
        });
      }
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
  const [mode, setMode] = useState('list'); // list | add
  const [editingRowId, setEditingRowId] = useState(null);
  const [editValues, setEditValues] = useState(null);
  const [editError, setEditError] = useState('');
  const [editSaving, setEditSaving] = useState(false);

  // Add form state
  const [placeName, setPlaceName] = useState('');
  const [geocodeResult, setGeocodeResult] = useState(null);
  const [geocoding, setGeocoding] = useState(false);
  const [geocodeError, setGeocodeError] = useState('');
  const [manualEntry, setManualEntry] = useState(false);
  const [manualName, setManualName] = useState('');
  const [manualLat, setManualLat] = useState('');
  const [manualLon, setManualLon] = useState('');
  const [addSolarEventTypes, setAddSolarEventTypes] = useState(['SUNRISE', 'SUNSET']);
  const [addLocationType, setAddLocationType] = useState('LANDSCAPE');
  const [addTideTypes, setAddTideTypes] = useState([]);
  const [addRegionId, setAddRegionId] = useState('');
  const [enrichmentData, setEnrichmentData] = useState(null);
  const [enriching, setEnriching] = useState(false);
  const [addOverlooksWater, setAddOverlooksWater] = useState(false);
  const [addCoastalTidal, setAddCoastalTidal] = useState(false);

  // Confirm modal state
  const [confirmData, setConfirmData] = useState(null);

  // Tide stats modal state
  const [tideStatsModal, setTideStatsModal] = useState(null); // { name, stats, loading }

  // Save state
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');


  const locationAccessors = useMemo(() => ({
    name: (loc) => loc.name,
    region: (loc) => loc.region?.name || '',
    type: (loc) => formatLocationType(loc.locationType),
    solar: (loc) => formatSolarEventType(loc.solarEventType),
    tide: (loc) => formatTideType(loc.tideType),
    created: (loc) => loc.createdAt || '',
    status: (loc) => loc.enabled ? 'Enabled' : 'Disabled',
  }), []);

  const [optimisticLocations, addOptimisticLocation] = useOptimistic(locations, (current, toggledId) =>
    current.map((loc) => loc.id === toggledId ? { ...loc, enabled: !loc.enabled } : loc),
  );
  const [, startToggleTransition] = useTransition();

  const sf = useSortAndFilter('name', 'asc', locationAccessors);

  const filteredLocations = useMemo(() => sf.apply(optimisticLocations), [sf, optimisticLocations]);

  const { pageItems: pageLocations, ...pagination } = usePagination(filteredLocations);

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

  // Cancel inline edit when page changes (edited row may no longer be visible)
  useEffect(() => {
    if (editingRowId) {
      setEditingRowId(null);
      setEditValues(null);
      setEditError('');
    }
  }, [pagination.page]); // eslint-disable-line react-hooks/exhaustive-deps

  function handleStartAdd() {
    handleEditCancel();
    setMode('add');
    setPlaceName('');
    setGeocodeResult(null);
    setGeocodeError('');
    setManualEntry(false);
    setManualName('');
    setManualLat('');
    setManualLon('');
    setAddSolarEventTypes(['SUNRISE', 'SUNSET']);
    setAddLocationType('LANDSCAPE');
    setAddTideTypes([]);
    setAddRegionId('');
    setEnrichmentData(null);
    setEnriching(false);
    setAddOverlooksWater(false);
    setAddCoastalTidal(false);
    setError('');
  }

  function handleStartEdit(loc) {
    setEditingRowId(loc.id);
    setEditValues({
      name: loc.name,
      lat: String(loc.lat),
      lon: String(loc.lon),
      solarEventTypes: Array.isArray(loc.solarEventType) ? [...loc.solarEventType] : ['SUNRISE', 'SUNSET'],
      locationType: firstOrDefault(loc.locationType, 'LANDSCAPE'),
      tideTypes: Array.isArray(loc.tideType) ? [...loc.tideType] : [],
      regionId: loc.region?.id ? String(loc.region.id) : '',
    });
    setEditError('');
  }

  async function handleShowTideStats(loc) {
    setTideStatsModal({ name: loc.name, stats: null, loading: true });
    try {
      const stats = await fetchTideStats(loc.name);
      setTideStatsModal({ name: loc.name, stats, loading: false });
    } catch {
      setTideStatsModal({ name: loc.name, stats: null, loading: false, error: 'Failed to load tide stats.' });
    }
  }

  function handleEditCancel() {
    setEditingRowId(null);
    setEditValues(null);
    setEditError('');
  }

  function handleEditChange(field, value) {
    setEditValues((prev) => {
      const next = { ...prev, [field]: value };
      if (field === 'locationType') {
        next.tideTypes = value === 'SEASCAPE' ? ['HIGH', 'MID', 'LOW'] : [];
      }
      return next;
    });
  }

  async function handleEditSave() {
    if (!editValues.name.trim()) {
      setEditError('Name cannot be blank.');
      return;
    }
    const parsedLat = parseFloat(editValues.lat);
    const parsedLon = parseFloat(editValues.lon);
    if (isNaN(parsedLat) || parsedLat < -90 || parsedLat > 90) {
      setEditError('Latitude must be between -90 and 90.');
      return;
    }
    if (isNaN(parsedLon) || parsedLon < -180 || parsedLon > 180) {
      setEditError('Longitude must be between -180 and 180.');
      return;
    }
    if (editValues.locationType === 'SEASCAPE' && (!editValues.tideTypes || editValues.tideTypes.length === 0)) {
      setEditError('Coastal locations require at least one tide preference (High, Mid, or Low).');
      return;
    }
    setEditSaving(true);
    setEditError('');
    try {
      await updateLocation(editingRowId, {
        name: editValues.name.trim(),
        lat: parsedLat,
        lon: parsedLon,
        solarEventTypes: editValues.solarEventTypes,
        locationType: editValues.locationType,
        tideTypes: editValues.locationType === 'SEASCAPE' ? editValues.tideTypes : [],
        regionId: editValues.regionId ? Number(editValues.regionId) : null,
      });
      await refreshLocations();
      handleEditCancel();
      onLocationsChanged();
    } catch (err) {
      setEditError(err?.response?.data?.error ?? err.message ?? 'Failed to save location.');
    } finally {
      setEditSaving(false);
    }
  }

  function handleCancel() {
    setMode('list');
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
    setEnrichmentData(null);
    try {
      const result = await geocodePlace(trimmed);
      setGeocodeResult(result);
      // Fire enrichment in background — don't block geocode result
      setEnriching(true);
      enrichLocation(result.lat, result.lon)
        .then((data) => setEnrichmentData(data))
        .catch((err) => {
          console.error('Enrichment failed:', err);
          setEnrichmentData({ bortleClass: null, skyBrightnessSqm: null, elevationMetres: null, gridLat: null, gridLng: null });
        })
        .finally(() => setEnriching(false));
    } catch (err) {
      setGeocodeError(err.message || 'Geocoding failed.');
    } finally {
      setGeocoding(false);
    }
  }

  function handleAddReviewConfirm() {
    if (addLocationType === 'SEASCAPE' && (!addTideTypes || addTideTypes.length === 0)) {
      setError('Coastal locations require at least one tide preference (High, Mid, or Low).');
      return;
    }
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
        solarEventTypes: addSolarEventTypes,
        locationType: addLocationType,
        tideTypes: addLocationType === 'SEASCAPE' ? addTideTypes : [],
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
        solarEventTypes: addSolarEventTypes,
        locationType: addLocationType,
        tideTypes: addLocationType === 'SEASCAPE' ? addTideTypes : [],
        regionId: addRegionId ? Number(addRegionId) : null,
        bortleClass: enrichmentData?.bortleClass ?? null,
        skyBrightnessSqm: enrichmentData?.skyBrightnessSqm ?? null,
        elevationMetres: enrichmentData?.elevationMetres ?? null,
        gridLat: enrichmentData?.gridLat ?? null,
        gridLng: enrichmentData?.gridLng ?? null,
        overlooksWater: addOverlooksWater,
        coastalTidal: addCoastalTidal,
      });
    }
  }

  async function handleConfirmSave() {
    setSaving(true);
    setError('');
    try {
      await addLocation({
        name: confirmData.name,
        lat: confirmData.lat,
        lon: confirmData.lon,
        solarEventTypes: confirmData.solarEventTypes,
        locationType: confirmData.locationType,
        tideTypes: confirmData.tideTypes,
        regionId: confirmData.regionId,
        bortleClass: confirmData.bortleClass,
        skyBrightnessSqm: confirmData.skyBrightnessSqm,
        elevationMetres: confirmData.elevationMetres,
        gridLat: confirmData.gridLat,
        gridLng: confirmData.gridLng,
        overlooksWater: confirmData.overlooksWater,
        coastalTidal: confirmData.coastalTidal,
      });
      await refreshLocations();
      handleCancel();
    } catch (err) {
      setError(err?.response?.data?.error ?? err.message ?? 'Failed to save location.');
    } finally {
      setSaving(false);
    }
  }

  function handleToggleEnabled(loc) {
    startToggleTransition(async () => {
      addOptimisticLocation(loc.id);
      try {
        await setLocationEnabled(loc.id, !loc.enabled);
        await refreshLocations();
      } catch (err) {
        console.error('Failed to toggle location enabled:', err);
      }
    });
  }

  // Auto-set tide when location type changes
  function handleAddLocationTypeChange(value) {
    setAddLocationType(value);
    if (value === 'SEASCAPE') {
      setAddTideTypes(['HIGH', 'MID', 'LOW']);
    } else {
      setAddTideTypes([]);
    }
  }

  const inlineInputClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-1.5 py-0.5 text-xs text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold';
  const inlineSelectClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-1 py-0.5 text-xs text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold';

  const selectClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold';
  const labelClass = 'block text-xs text-plex-text-secondary mb-1';

  return (
    <div className="flex flex-col gap-4">

      {/* List mode */}
      {mode === 'list' && (
        <>
          <div className="flex items-center justify-between gap-4">
            <p className="text-sm font-semibold text-plex-text">Location Management</p>
            <div className="flex items-center gap-2 shrink-0">
              <button
                className="btn-secondary text-xs"
                onClick={handleStartAdd}
                data-testid="add-location-btn"
              >
                + Add Location
              </button>
            </div>
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
              <table className="w-full text-sm text-left table-fixed" data-testid="locations-table">
                <thead>
                  <tr className="text-xs text-plex-text-muted border-b border-plex-border">
                    <SortableHeader label="Name" sortKey="name" className="w-[20%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('name')} onFilter={(v) => sf.setFilter('name', v)} />
                    <th className="pb-1 font-medium align-top w-[9%]">
                      <span className="text-xs text-plex-text-muted whitespace-nowrap">Coords</span>
                      <div className="mt-1 h-[26px]" />
                    </th>
                    <th className="pb-1 font-medium align-bottom w-[10%]">
                      <button
                        type="button"
                        onClick={() => sf.handleSort('type')}
                        className="text-xs text-plex-text-muted hover:text-plex-text cursor-pointer whitespace-nowrap"
                      >
                        Type{sf.sortKey === 'type' ? (sf.sortDir === 'asc' ? ' ▲' : ' ▼') : ''}
                      </button>
                      <div className="mt-1 flex gap-0.5">
                        {LOCATION_TYPES.map((lt) => {
                          const isActive = sf.getFilterValue('type') === lt.label;
                          return (
                            <button
                              key={lt.value}
                              type="button"
                              title={`Filter: ${lt.label}`}
                              onClick={() => sf.setFilter('type', isActive ? '' : lt.label)}
                              className={`text-xs leading-none px-0.5 py-0.5 rounded transition-colors ${
                                isActive ? 'opacity-100 ring-1 ring-plex-gold' : 'opacity-40 grayscale hover:opacity-70 hover:grayscale-0'
                              }`}
                              data-testid={`filter-type-${lt.value}`}
                            >
                              <span style={lt.value === 'WILDLIFE' ? { filter: 'brightness(2) contrast(1.5)' } : undefined}>{lt.emoji}</span>
                            </button>
                          );
                        })}
                      </div>
                    </th>
                    <SortableHeader label="Region" sortKey="region" className="w-[15%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('region')} onFilter={(v) => sf.setFilter('region', v)} />
                    <th className="pb-1 font-medium align-bottom w-[10%]">
                      <button
                        type="button"
                        onClick={() => sf.handleSort('solar')}
                        className="text-xs text-plex-text-muted hover:text-plex-text cursor-pointer whitespace-nowrap"
                      >
                        Solar{sf.sortKey === 'solar' ? (sf.sortDir === 'asc' ? ' ▲' : ' ▼') : ''}
                      </button>
                      <div className="mt-1 flex gap-0.5">
                        {SOLAR_EVENT_TYPES.map((s) => {
                          const activeArr = sf.getFilterValue('solar') || [];
                          const isActive = Array.isArray(activeArr) && activeArr.includes(s.label);
                          return (
                            <button
                              key={s.value}
                              type="button"
                              title={`Filter: ${s.label}`}
                              onClick={() => {
                                const current = Array.isArray(activeArr) ? activeArr : [];
                                const next = isActive
                                  ? current.filter((v) => v !== s.label)
                                  : [...current, s.label];
                                sf.setFilter('solar', next.length > 0 ? next : '');
                              }}
                              className={`text-sm leading-none px-0.5 py-0.5 rounded transition-colors ${
                                isActive ? 'opacity-100 ring-1 ring-plex-gold' : 'opacity-40 grayscale hover:opacity-70 hover:grayscale-0'
                              }`}
                              data-testid={`filter-solar-${s.value}`}
                            >
                              {s.emoji}
                            </button>
                          );
                        })}
                      </div>
                    </th>
                    <th className="pb-1 font-medium align-bottom w-[10%]">
                      <button
                        type="button"
                        onClick={() => sf.handleSort('tide')}
                        className="text-xs text-plex-text-muted hover:text-plex-text cursor-pointer whitespace-nowrap"
                      >
                        Tide{sf.sortKey === 'tide' ? (sf.sortDir === 'asc' ? ' ▲' : ' ▼') : ''}
                      </button>
                      <div className="mt-1 flex gap-0.5">
                        {TIDE_TYPES.map((t) => {
                          const activeArr = sf.getFilterValue('tide') || [];
                          const isActive = Array.isArray(activeArr) && activeArr.includes(t.fullLabel);
                          return (
                            <button
                              key={t.value}
                              type="button"
                              title={`Filter: ${t.fullLabel}`}
                              onClick={() => {
                                const current = Array.isArray(activeArr) ? activeArr : [];
                                const next = isActive
                                  ? current.filter((v) => v !== t.fullLabel)
                                  : [...current, t.fullLabel];
                                sf.setFilter('tide', next.length > 0 ? next : '');
                              }}
                              className={`text-xs px-1.5 py-0.5 rounded font-semibold transition-colors ${
                                isActive
                                  ? 'bg-plex-gold/20 text-plex-gold border border-plex-gold/40'
                                  : 'bg-plex-surface-light text-plex-text-muted border border-plex-border cursor-pointer hover:border-plex-gold/30'
                              }`}
                              data-testid={`filter-tide-${t.value}`}
                            >
                              {t.label}
                            </button>
                          );
                        })}
                      </div>
                    </th>
                    <th className="pb-1 font-medium align-top w-[6%]">
                      <span className="flex items-center gap-1 text-xs text-plex-text-muted whitespace-nowrap">
                        Light pollution
                        <InfoTip text={"Light pollution rating (1–9).\n1–2: Excellent dark sky\n3–4: Moderate — aurora-friendly\n5–9: Light-polluted\n\nRun Refresh Light Pollution to populate."} />
                      </span>
                      <div className="mt-1 h-[26px]" />
                    </th>
                    <SortableHeader label="Status" sortKey="status" className="w-[9%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('status')} onFilter={(v) => sf.setFilter('status', v)} />
                    <th className="pb-1 font-medium align-top w-[13%]">
                      <span className="text-xs text-plex-text-muted whitespace-nowrap">Actions</span>
                      <div className="mt-1 h-[26px]" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {pageLocations.map((loc) => {
                    const isEditing = loc.id === editingRowId;
                    return (
                      <React.Fragment key={loc.id}>
                        <tr
                          className={`border-b border-plex-surface last:border-0 ${!loc.enabled && !isEditing ? 'opacity-50' : ''}`}
                          onKeyDown={isEditing ? (e) => { if (e.key === 'Escape') handleEditCancel(); } : undefined}
                        >
                          {isEditing ? (
                            <>
                              <td className="py-2">
                                <input
                                  type="text"
                                  className={inlineInputClass}
                                  value={editValues.name}
                                  onChange={(e) => handleEditChange('name', e.target.value)}
                                  data-testid="inline-edit-name"
                                />
                              </td>
                              <td className="py-2 text-plex-text-secondary text-xs">
                                {loc.lat.toFixed(3)}, {loc.lon.toFixed(3)}
                              </td>
                              <td className="py-2" data-testid="inline-edit-type">
                                <LocationTypeChips
                                  selected={editValues.locationType}
                                  onChange={(val) => handleEditChange('locationType', val)}
                                />
                              </td>
                              <td className="py-2">
                                <select
                                  className={inlineSelectClass}
                                  value={editValues.regionId}
                                  onChange={(e) => handleEditChange('regionId', e.target.value)}
                                  data-testid="inline-edit-region"
                                >
                                  <option value="">— None —</option>
                                  {regions.filter((r) => r.enabled || String(r.id) === editValues.regionId).map((r) => (
                                    <option key={r.id} value={r.id}>{r.name}</option>
                                  ))}
                                </select>
                              </td>
                              <td className="py-2" data-testid="inline-edit-solar">
                                <SolarToggleChips
                                  selected={editValues.solarEventTypes}
                                  onChange={(next) => setEditValues((prev) => ({ ...prev, solarEventTypes: next }))}
                                />
                              </td>
                              <td className="py-2" data-testid="inline-edit-tide">
                                <TideToggleChips
                                  selected={editValues.tideTypes}
                                  onChange={(next) => setEditValues((prev) => ({ ...prev, tideTypes: next }))}
                                  disabled={editValues.locationType !== 'SEASCAPE'}
                                />
                              </td>
                              <td className="py-2 text-plex-text-secondary text-xs" data-testid={`bortle-edit-${loc.id}`}>
                                {loc.bortleClass != null ? loc.bortleClass : '—'}
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
                                <div className="flex gap-1">
                                  <button
                                    className="text-xs px-2 py-0.5 rounded bg-green-900/40 text-green-400 hover:bg-green-900/60 disabled:opacity-50"
                                    onClick={handleEditSave}
                                    disabled={editSaving}
                                    data-testid="inline-edit-save"
                                  >
                                    {editSaving ? 'Saving…' : 'Save'}
                                  </button>
                                  <button
                                    className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-muted hover:bg-plex-border hover:text-plex-text"
                                    onClick={handleEditCancel}
                                    disabled={editSaving}
                                    data-testid="inline-edit-cancel"
                                  >
                                    Cancel
                                  </button>
                                </div>
                              </td>
                            </>
                          ) : (
                            <>
                              <td className="py-2 text-plex-text truncate" title={loc.name}>
                                {loc.name}
                                {loc.consecutiveFailures > 0 && !loc.disabledReason && (
                                  <span className="inline-flex items-center gap-0.5 ml-1.5">
                                    <span className="text-xs px-1.5 py-0.5 rounded bg-amber-900/40 text-amber-400">
                                      {loc.consecutiveFailures}
                                    </span>
                                    <InfoTip text={`${loc.consecutiveFailures} consecutive failure(s)${loc.lastFailureAt ? ' \u2014 last: ' + formatTimestampUk(loc.lastFailureAt) : ''}`} className="text-amber-400" />
                                  </span>
                                )}
                              </td>
                              <td className="py-2 text-plex-text-secondary text-xs">
                                {loc.lat.toFixed(3)}, {loc.lon.toFixed(3)}
                              </td>
                              <td className="py-2">
                                <LocationTypeChips selected={(loc.locationType && loc.locationType[0]) || 'LANDSCAPE'} readOnly />
                              </td>
                              <td className="py-2 text-plex-text-secondary text-xs">
                                {loc.region?.name || '—'}
                              </td>
                              <td className="py-2">
                                <SolarToggleChips
                                  selected={loc.solarEventType || ['SUNRISE', 'SUNSET']}
                                  onChange={() => {}}
                                  readOnly
                                />
                              </td>
                              <td className="py-2">
                                <TideToggleChips
                                  selected={loc.tideType || []}
                                  onChange={() => {}}
                                  disabled
                                  readOnly
                                />
                              </td>
                              <td className="py-2 text-plex-text-secondary text-xs" data-testid={`bortle-${loc.id}`}>
                                {loc.bortleClass != null ? loc.bortleClass : '—'}
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
                                <div className="flex gap-1">
                                  <button
                                    className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text"
                                    onClick={() => handleStartEdit(loc)}
                                    data-testid={`edit-location-${loc.id}`}
                                  >
                                    Edit
                                  </button>
                                  {(loc.locationType || []).includes('SEASCAPE') && (
                                    <button
                                      className="text-xs px-2 py-0.5 rounded bg-cyan-900/40 text-cyan-400 hover:bg-cyan-900/60"
                                      onClick={() => handleShowTideStats(loc)}
                                      data-testid={`tides-location-${loc.id}`}
                                    >
                                      Tides
                                    </button>
                                  )}
                                </div>
                              </td>
                            </>
                          )}
                        </tr>
                        {isEditing && (
                          <tr className="border-b border-plex-surface">
                            <td colSpan={10} className="py-1.5 pl-1">
                              <div className="flex items-center gap-2 text-xs">
                                <span className="text-plex-text-muted">Lat</span>
                                <input
                                  type="number"
                                  step="any"
                                  className={inlineInputClass + ' w-28'}
                                  value={editValues.lat}
                                  onChange={(e) => handleEditChange('lat', e.target.value)}
                                  data-testid="inline-edit-lat"
                                />
                                <span className="text-plex-text-muted">Lon</span>
                                <input
                                  type="number"
                                  step="any"
                                  className={inlineInputClass + ' w-28'}
                                  value={editValues.lon}
                                  onChange={(e) => handleEditChange('lon', e.target.value)}
                                  data-testid="inline-edit-lon"
                                />
                              </div>
                            </td>
                          </tr>
                        )}
                        {isEditing && editError && (
                          <tr>
                            <td colSpan={10} className="py-1 text-xs text-red-400 pl-1" data-testid="inline-edit-error">
                              {editError}
                            </td>
                          </tr>
                        )}
                      </React.Fragment>
                    );
                  })}
                  {filteredLocations.length === 0 && locations.length > 0 && (
                    <tr>
                      <td colSpan={10} className="py-4 text-center text-xs text-plex-text-muted">
                        No locations match the current filters.
                      </td>
                    </tr>
                  )}
                  {pageLocations.length > 0 && pageLocations.length < pagination.pageSize && (
                    Array.from({ length: pagination.pageSize - pageLocations.length }, (_, i) => (
                      <tr key={`spacer-${i}`} aria-hidden="true">
                        <td colSpan={9} className="py-2 text-sm">&nbsp;</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
              <Pagination
                page={pagination.page}
                totalPages={pagination.totalPages}
                pageSize={pagination.pageSize}
                totalItems={filteredLocations.length}
                onNextPage={pagination.nextPage}
                onPrevPage={pagination.prevPage}
                onFirstPage={pagination.firstPage}
                onLastPage={pagination.lastPage}
                onSetPageSize={pagination.setPageSize}
              />
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

              {enriching && (
                <p className="text-xs text-plex-text-muted animate-pulse" data-testid="enriching-spinner">
                  Detecting elevation, bortle, grid cell…
                </p>
              )}

              {!enriching && enrichmentData && (
                <div className="border border-plex-border rounded px-3 py-2" data-testid="enrichment-panel">
                  <p className="text-xs font-medium text-plex-text-secondary mb-1.5">Auto-detected</p>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
                    <div className="flex justify-between">
                      <span className="text-plex-text-secondary">Elevation</span>
                      <span className="text-plex-text" data-testid="enrichment-elevation">
                        {enrichmentData.elevationMetres != null ? `${enrichmentData.elevationMetres}m` : '—'}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-plex-text-secondary">Grid cell</span>
                      <span className="text-plex-text" data-testid="enrichment-grid">
                        {enrichmentData.gridLat != null && enrichmentData.gridLng != null
                          ? `${enrichmentData.gridLat}, ${enrichmentData.gridLng}`
                          : '—'}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-plex-text-secondary">Bortle</span>
                      <span className="text-plex-text" data-testid="enrichment-bortle">
                        {enrichmentData.bortleClass != null ? enrichmentData.bortleClass : '—'}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-plex-text-secondary">SQM</span>
                      <span className="text-plex-text" data-testid="enrichment-sqm">
                        {enrichmentData.skyBrightnessSqm != null ? enrichmentData.skyBrightnessSqm : '—'}
                      </span>
                    </div>
                  </div>
                </div>
              )}

              {!enriching && enrichmentData && (
                <div className="border border-plex-border rounded px-3 py-2" data-testid="manual-toggles-panel">
                  <p className="text-xs font-medium text-plex-text-secondary mb-1.5">Manual</p>
                  <div className="flex gap-6">
                    <label className="flex items-center gap-1.5 text-xs text-plex-text cursor-pointer">
                      <input
                        type="checkbox"
                        checked={addOverlooksWater}
                        onChange={(e) => setAddOverlooksWater(e.target.checked)}
                        data-testid="overlooks-water-checkbox"
                      />
                      Overlooks water
                    </label>
                    <label className="flex items-center gap-1.5 text-xs text-plex-text cursor-pointer">
                      <input
                        type="checkbox"
                        checked={addCoastalTidal}
                        onChange={(e) => setAddCoastalTidal(e.target.checked)}
                        data-testid="coastal-tidal-checkbox"
                      />
                      Coastal tidal
                    </label>
                  </div>
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
              <label htmlFor="add-solar-event-type-chips" className={labelClass}>Solar Event Type</label>
              <div id="add-solar-event-type-chips" className="py-1.5" data-testid="add-solar-event-type">
                <SolarToggleChips
                  selected={addSolarEventTypes}
                  onChange={setAddSolarEventTypes}
                />
              </div>
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
              <label htmlFor="add-tide-type-chips" className={labelClass}>Tide Preference</label>
              <div id="add-tide-type-chips" className="py-1.5" data-testid="add-tide-type">
                <TideToggleChips
                  selected={addTideTypes}
                  onChange={setAddTideTypes}
                  disabled={addLocationType !== 'SEASCAPE'}
                />
              </div>
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

          <div className="flex flex-col gap-2">
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
            {!manualEntry && !geocodeResult && (
              <p className="text-xs text-plex-text-muted text-right">Search for a place above, or switch to manual entry</p>
            )}
          </div>
        </div>
      )}

      {/* Confirm modal (add only) */}
      {confirmData && (
        <Modal label="Confirm location" onClose={() => setConfirmData(null)} data-testid="confirm-location-modal">
            <p className="text-sm font-semibold text-plex-text">
              Confirm New Location
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
                <span className="text-plex-text">{formatSolarEventType(confirmData.solarEventTypes)}</span>
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
                  {formatTideType(confirmData.tideTypes)}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Region</span>
                <span className="text-plex-text">
                  {confirmData.regionId ? (regions.find((r) => r.id === confirmData.regionId)?.name ?? '—') : '—'}
                </span>
              </div>
              <hr className="border-plex-border" />
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Elevation</span>
                <span className="text-plex-text">
                  {confirmData.elevationMetres != null ? `${confirmData.elevationMetres}m` : '—'}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Bortle</span>
                <span className="text-plex-text">{confirmData.bortleClass ?? '—'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">SQM</span>
                <span className="text-plex-text">{confirmData.skyBrightnessSqm ?? '—'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Grid cell</span>
                <span className="text-plex-text">
                  {confirmData.gridLat != null && confirmData.gridLng != null
                    ? `${confirmData.gridLat}, ${confirmData.gridLng}`
                    : '—'}
                </span>
              </div>
              <hr className="border-plex-border" />
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Overlooks water</span>
                <span className="text-plex-text">{confirmData.overlooksWater ? 'Yes' : 'No'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Coastal tidal</span>
                <span className="text-plex-text">{confirmData.coastalTidal ? 'Yes' : 'No'}</span>
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
        </Modal>
      )}

      {/* Tide stats modal */}
      {tideStatsModal && (
        <Modal label="Tide statistics" onClose={() => setTideStatsModal(null)} maxWidth="lg" data-testid="tide-stats-modal">
            <p className="text-sm font-semibold text-plex-text">
              Tide Statistics: <span className="text-cyan-400">{tideStatsModal.name}</span>
            </p>

            {tideStatsModal.loading && (
              <p className="text-sm text-plex-text-muted animate-pulse">Loading...</p>
            )}

            {tideStatsModal.error && (
              <p className="text-xs text-red-400">{tideStatsModal.error}</p>
            )}

            {!tideStatsModal.loading && !tideStatsModal.error && !tideStatsModal.stats && (
              <p className="text-sm text-plex-text-muted">No tide data stored for this location.</p>
            )}

            {tideStatsModal.stats && (
              <div className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
                <div>
                  <span className="text-plex-text-muted text-xs block">Data Points</span>
                  <span className="text-plex-text font-medium">{tideStatsModal.stats.dataPoints}</span>
                </div>
                <div>
                  <span className="text-plex-text-muted text-xs block">Avg Range</span>
                  <span className="text-plex-text font-medium">{tideStatsModal.stats.avgRangeMetres != null ? `${Number(tideStatsModal.stats.avgRangeMetres).toFixed(2)} m` : '—'}</span>
                </div>
                <div>
                  <span className="text-plex-text-muted text-xs block">Avg High</span>
                  <span className="text-plex-text font-medium">{Number(tideStatsModal.stats.avgHighMetres).toFixed(2)} m</span>
                </div>
                <div>
                  <span className="text-plex-text-muted text-xs block">Max High</span>
                  <span className="text-amber-400 font-medium">{Number(tideStatsModal.stats.maxHighMetres).toFixed(2)} m</span>
                </div>
                <div>
                  <span className="text-plex-text-muted text-xs block">Avg Low</span>
                  <span className="text-plex-text font-medium">{Number(tideStatsModal.stats.avgLowMetres).toFixed(2)} m</span>
                </div>
                <div>
                  <span className="text-plex-text-muted text-xs block">Min Low</span>
                  <span className="text-blue-400 font-medium">{Number(tideStatsModal.stats.minLowMetres).toFixed(2)} m</span>
                </div>

                <div className="col-span-2 border-t border-plex-border pt-3 mt-1">
                  <span className="text-plex-text-muted text-xs block mb-2">Percentiles (High Tides)</span>
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <span className="text-plex-text-muted text-xs block">P75</span>
                      <span className="text-plex-text font-medium">{tideStatsModal.stats.p75HighMetres != null ? `${Number(tideStatsModal.stats.p75HighMetres).toFixed(2)} m` : '—'}</span>
                    </div>
                    <div>
                      <span className="text-plex-text-muted text-xs block">P90</span>
                      <span className="text-plex-text font-medium">{tideStatsModal.stats.p90HighMetres != null ? `${Number(tideStatsModal.stats.p90HighMetres).toFixed(2)} m` : '—'}</span>
                    </div>
                    <div>
                      <span className="text-plex-text-muted text-xs block">P95 (King)</span>
                      <span className="text-amber-300 font-medium">{tideStatsModal.stats.p95HighMetres != null ? `${Number(tideStatsModal.stats.p95HighMetres).toFixed(2)} m` : '—'}</span>
                    </div>
                  </div>
                </div>

                <div className="col-span-2 border-t border-plex-border pt-3 mt-1">
                  <span className="text-plex-text-muted text-xs block mb-2">Spring Tides (&gt;125% avg high)</span>
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <span className="text-plex-text-muted text-xs block">Threshold</span>
                      <span className="text-plex-text font-medium">{tideStatsModal.stats.springTideThreshold != null ? `${Number(tideStatsModal.stats.springTideThreshold).toFixed(2)} m` : '—'}</span>
                    </div>
                    <div>
                      <span className="text-plex-text-muted text-xs block">Count</span>
                      <span className="text-plex-text font-medium">{tideStatsModal.stats.springTideCount}</span>
                    </div>
                    <div>
                      <span className="text-plex-text-muted text-xs block">Frequency</span>
                      <span className="text-plex-text font-medium">{tideStatsModal.stats.springTideFrequency != null ? `${(Number(tideStatsModal.stats.springTideFrequency) * 100).toFixed(1)}%` : '—'}</span>
                    </div>
                  </div>
                </div>

                <div className="col-span-2 border-t border-plex-border pt-3 mt-1">
                  <span className="text-plex-text-muted text-xs block mb-2">King Tides (&gt;P95)</span>
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <span className="text-plex-text-muted text-xs block">Threshold</span>
                      <span className="text-amber-300 font-medium">{tideStatsModal.stats.kingTideThreshold != null ? `${Number(tideStatsModal.stats.kingTideThreshold).toFixed(2)} m` : '—'}</span>
                    </div>
                    <div>
                      <span className="text-plex-text-muted text-xs block">Count</span>
                      <span className="text-plex-text font-medium">{tideStatsModal.stats.kingTideCount ?? '—'}</span>
                    </div>
                    <div />
                  </div>
                </div>
              </div>
            )}

            <button
              className="btn-primary text-sm self-end"
              onClick={() => setTideStatsModal(null)}
              data-testid="close-tide-stats-modal"
            >
              Close
            </button>
        </Modal>
      )}
    </div>
  );
}

LocationManagementView.propTypes = {
  onLocationsChanged: PropTypes.func.isRequired,
};
