import React, { useEffect, useOptimistic, useRef, useState, useTransition, useMemo } from 'react';
import PropTypes from 'prop-types';
import { fetchLocations, addLocation, updateLocation, setLocationEnabled, geocodePlace, enrichLocation, fetchValleyFloorCandidates, fetchValleyFloorCell } from '../api/forecastApi.js';
import { fetchRegions } from '../api/regionApi.js';
import { fetchTideStats } from '../api/tideApi.js';
import { fetchAreas, fetchLocationAliases, setLocationAliases } from '../api/areaApi.js';
import { parseAliases, formatAliases } from '../utils/aliasText.js';
import AreasSection from './AreasSection.jsx';
import LocationAlerts from './LocationAlerts.jsx';
import InfoTip from './InfoTip.jsx';
import { formatTimestampUk } from '../utils/conversions';
import Pagination from './Pagination.jsx';
import usePagination from '../hooks/usePagination.js';
import SortableHeader from './shared/SortableHeader.jsx';
import useSortAndFilter from '../hooks/useSortAndFilter.js';
import Modal from './shared/Modal.jsx';
import { LOCATION_TYPE_META, DISPLAY_TYPES, locationTypeLabel } from '../utils/locationTypes.js';

const SOLAR_EVENT_TYPES = [
  { value: 'SUNRISE', label: 'Sunrise', emoji: '🌅' },
  { value: 'SUNSET', label: 'Sunset', emoji: '🌇' },
  { value: 'ALLDAY', label: 'All Day', emoji: '☀️' },
];

/** Assignable types, derived so a new backend constant needs no edit here. */
const LOCATION_TYPES = DISPLAY_TYPES.map((value) => ({ value, ...LOCATION_TYPE_META[value] }));

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
  return types.map(locationTypeLabel).join(', ');
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
 * Emoji chip group for location types (multi-select).
 *
 * A location can legitimately be more than one thing — a wooded clifftop is both SEASCAPE and
 * WOODLAND, and the two route to different evaluation lanes. This was single-select while the
 * entity stored a set, so opening any location for edit collapsed its types to one.
 *
 * @param {object} props
 * @param {Array<string>} props.selected - Currently selected type values, e.g. ['SEASCAPE'].
 * @param {function} [props.onChange] - Called with the new array (omit for read-only).
 * @param {boolean} [props.readOnly=false] - Disable interaction.
 */
function LocationTypeChips({ selected, onChange, readOnly = false }) {
  function toggle(value) {
    if (readOnly || !onChange) return;
    const isSelected = selected.includes(value);
    // A location with no type routes to no evaluation lane at all, so the last chip is sticky —
    // the same rule TideToggleChips uses, and the backend rejects an empty set regardless.
    if (isSelected && selected.length <= 1) return;
    onChange(isSelected ? selected.filter((v) => v !== value) : [...selected, value]);
  }

  return (
    <div className="inline-flex gap-0.5 whitespace-nowrap" data-testid="location-type-chips">
      {LOCATION_TYPES.map((lt) => {
        const isOn = selected.includes(lt.value);
        return (
          <button
            key={lt.value}
            type="button"
            title={lt.label}
            aria-pressed={isOn}
            disabled={readOnly}
            onClick={() => toggle(lt.value)}
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
  selected: PropTypes.arrayOf(PropTypes.string).isRequired,
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
 * Normalises a location's type set for editing, falling back to LANDSCAPE.
 *
 * <p>Deliberately keeps every value. The predecessor of this helper returned {@code set[0]},
 * which silently discarded the rest — and because the set arrives from a Java HashSet, which
 * element survived was not even stable between requests.
 *
 * @param {Array<string>} types - The stored type values.
 * @returns {Array<string>} A copy of the types, or ['LANDSCAPE'] when absent.
 */
function typesOrDefault(types) {
  if (!Array.isArray(types) || types.length === 0) return ['LANDSCAPE'];
  return [...types];
}

/**
 * Location management view with list/add/edit modes, sorting, filtering, and geocoding.
 *
 * @param {object} props
 * @param {function} props.onLocationsChanged - Called when locations are added/updated/toggled.
 */
/**
 * The sub-region select shared by the edit row and the add form: only the sub-regions of the chosen region, and
 * nothing at all when that region has none. The empty choice is offered only when it means something (a
 * location with a sub-region cannot be left with none by a plain pick, since the API has no "clear").
 */
function SubRegionSelect({ subRegions, regionId, value, onChange, canBeEmpty, className, id, testId }) {
  const options = subRegions.filter((a) => String(a.regionId) === String(regionId));
  if (options.length === 0) return null;
  return (
    <select
      id={id}
      className={className}
      aria-label="Sub-region"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      data-testid={testId}
    >
      {canBeEmpty && <option value="">— No sub-region —</option>}
      {options.map((a) => (
        <option key={a.id} value={a.id}>{a.name}</option>
      ))}
    </select>
  );
}

SubRegionSelect.propTypes = {
  subRegions: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.number.isRequired,
    name: PropTypes.string.isRequired,
    regionId: PropTypes.number,
  })).isRequired,
  regionId: PropTypes.oneOfType([PropTypes.string, PropTypes.number]).isRequired,
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
  canBeEmpty: PropTypes.bool.isRequired,
  className: PropTypes.string,
  id: PropTypes.string,
  testId: PropTypes.string.isRequired,
};

const MIN_HEIGHT_M = -50;
const MAX_HEIGHT_M = 1500;
// Inversion scoring reads a site's own height from here up (InversionScoreCalculator.MIN_ELEVATION_METRES).
const INVERSION_MIN_ELEVATION_M = 200;

/** Parses a whole-metre input: '' is null, a non-number is NaN, anything else is rounded. */
function parseMetres(value) {
  if (value === '' || value == null) return null;
  const n = Number(value);
  return Number.isFinite(n) ? Math.round(n) : NaN;
}

/** True when the edited lat/lon text is a valid coordinate pair. */
function coordsValid(lat, lon) {
  const a = parseFloat(lat);
  const b = parseFloat(lon);
  return !isNaN(a) && a >= -90 && a <= 90 && !isNaN(b) && b >= -180 && b <= 180;
}

/** True when an elevation edit moves an overlooks-water site across the 200 m inversion threshold. */
function crossesInversionThreshold(values) {
  if (!values.overlooksWater) return false;
  const next = parseMetres(values.elevation);
  if (next === null || Number.isNaN(next)) return false;
  const was = (values.elevationBaseline ?? -Infinity) >= INVERSION_MIN_ELEVATION_M;
  return was !== (next >= INVERSION_MIN_ELEVATION_M);
}

/** True when the edited lat/lon differ from the stored coordinates. */
function coordinatesChanged(values) {
  const lat = parseFloat(values.lat);
  const lon = parseFloat(values.lon);
  if (isNaN(lat) || isNaN(lon)) return false;
  return lat !== values.latBaseline || lon !== values.lonBaseline;
}

export default function LocationManagementView({ onLocationsChanged }) {
  const [locations, setLocations] = useState([]);
  const [regions, setRegions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [mode, setMode] = useState('list'); // list | add
  const [editingRowId, setEditingRowId] = useState(null);
  const [editValues, setEditValues] = useState(null);
  const [editError, setEditError] = useState('');
  const [editSaving, setEditSaving] = useState(false);
  // Identifies the latest valley-floor request of the open editor. Bumped on open, Cancel, Clear and every
  // new request, so a reply that no longer belongs to the editor on screen is dropped.
  const floorToken = useRef(0);
  // Other names per location id; null until loaded, and stays null if the load fails so the field is
  // disabled rather than offering to overwrite names it never saw.
  // Every area and sub-region (null until read). A location's sub-region is the SUB_REGION area listing it; it is
  // read here, never derived from names, and written through the location's own save (subRegionId).
  const [areas, setAreas] = useState(null);
  const [areasVersion, setAreasVersion] = useState(0);
  const [aliasesByLocation, setAliasesByLocation] = useState(null);
  const [aliasesFailed, setAliasesFailed] = useState(false);
  const [aliasDetail, setAliasDetail] = useState('');

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
  const [addLocationTypes, setAddLocationTypes] = useState(['LANDSCAPE']);
  const [addTideTypes, setAddTideTypes] = useState([]);
  const [addRegionId, setAddRegionId] = useState('');
  const [addSubRegionId, setAddSubRegionId] = useState('');
  const [enrichmentData, setEnrichmentData] = useState(null);
  const [enriching, setEnriching] = useState(false);
  const [addOverlooksWater, setAddOverlooksWater] = useState(false);
  const [addCoastalTidal, setAddCoastalTidal] = useState(false);
  const [addMistProne, setAddMistProne] = useState(false);

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

  const subRegions = useMemo(() => (areas ?? []).filter((a) => a.kind === 'SUB_REGION'), [areas]);

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

  /** Reads the areas again after a location was saved, and tells the Areas section to do the same. */
  async function refreshAreas(bump = true) {
    if (bump) setAreasVersion((v) => v + 1);
    try {
      setAreas(await fetchAreas());
    } catch {
      // Keep what was read before
    }
  }

  useEffect(() => {
    fetchAreas()
      .then((data) => {
        setAreas(data);
        // An editor opened before the areas landed starts its sub-region now.
        setEditValues((prev) => {
          if (!prev || prev.subRegionsLoaded) return prev;
          const current = data.find((a) => a.kind === 'SUB_REGION' && a.locationIds.includes(prev.locId));
          const id = current ? String(current.id) : '';
          return { ...prev, subRegionId: id, subRegionBaseline: id, subRegionsLoaded: true };
        });
      })
      .catch(() => { /* no sub-region select; the Areas section reports its own load failure */ });
    Promise.all([fetchLocations(), fetchRegions()])
      .then(([locs, regs]) => { setLocations(locs); setRegions(regs); })
      .finally(() => setLoading(false));
    fetchLocationAliases()
      .then((aliases) => {
        const map = aliases ?? {};
        setAliasesByLocation(map);
        // An editor opened before the names landed starts the field (and its baseline) now; it was disabled,
        // so nothing the admin typed can be lost.
        setEditValues((prev) => {
          if (!prev || prev.aliasesLoaded) return prev;
          const text = formatAliases(map[prev.locId]);
          return { ...prev, aliases: text, aliasesBaseline: text, aliasesLoaded: true };
        });
      })
      .catch(() => { setAliasesFailed(true); });
  }, []);

  // Cancel inline edit when page changes (edited row may no longer be visible)
  useEffect(() => {
    if (editingRowId) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect; runs synchronously this tick.
      (async () => {
        setEditingRowId(null);
        setEditValues(null);
        setEditError('');
      })();
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
    setAddLocationTypes(['LANDSCAPE']);
    setAddTideTypes([]);
    setAddRegionId('');
    setAddSubRegionId('');
    setEnrichmentData(null);
    setEnriching(false);
    setAddOverlooksWater(false);
    setAddCoastalTidal(false);
    setAddMistProne(false);
    setError('');
  }

  function currentSubRegionId(locationId) {
    const current = subRegions.find((a) => a.locationIds.includes(locationId));
    return current ? String(current.id) : '';
  }

  function handleStartEdit(loc) {
    floorToken.current += 1;
    const hasStoredFloor = loc.valleyFloorMetres != null && loc.valleyFloorLat != null && loc.valleyFloorLng != null;
    setEditingRowId(loc.id);
    setEditValues({
      name: loc.name,
      lat: String(loc.lat),
      lon: String(loc.lon),
      solarEventTypes: Array.isArray(loc.solarEventType) ? [...loc.solarEventType] : ['SUNRISE', 'SUNSET'],
      locationTypes: typesOrDefault(loc.locationType),
      tideTypes: Array.isArray(loc.tideType) ? [...loc.tideType] : [],
      regionId: loc.region?.id ? String(loc.region.id) : '',
      regionBaseline: loc.region?.id ? String(loc.region.id) : '',
      subRegionId: currentSubRegionId(loc.id),
      subRegionBaseline: currentSubRegionId(loc.id),
      subRegionsLoaded: areas !== null,
      mistProne: loc.mistProne === true,
      locId: loc.id,
      aliases: formatAliases(aliasesByLocation?.[loc.id]),
      aliasesBaseline: formatAliases(aliasesByLocation?.[loc.id]),
      aliasesLoaded: aliasesByLocation !== null,
      // Heights: the baselines are what the server holds; a save sends a height field only when it differs.
      elevation: loc.elevationMetres != null ? String(loc.elevationMetres) : '',
      elevationBaseline: loc.elevationMetres ?? null,
      overlooksWater: loc.overlooksWater === true,
      latBaseline: loc.lat,
      lonBaseline: loc.lon,
      floorMetres: hasStoredFloor ? String(loc.valleyFloorMetres) : '',
      floorLat: hasStoredFloor ? loc.valleyFloorLat : null,
      floorLng: hasStoredFloor ? loc.valleyFloorLng : null,
      floorBaseline: hasStoredFloor
        ? { metres: loc.valleyFloorMetres, lat: loc.valleyFloorLat, lng: loc.valleyFloorLng }
        : null,
      floorCleared: false,
      floorCandidates: null,
      floorCell: null,
      floorStatus: '',
      floorBusy: false,
    });
    setEditError('');
    setAliasDetail('');
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
    floorToken.current += 1;
    setEditingRowId(null);
    setEditValues(null);
    setEditError('');
    setAliasDetail('');
  }

  function handleEditChange(field, value) {
    setEditValues((prev) => {
      const next = { ...prev, [field]: value };
      if (field === 'regionId') {
        // A sub-region belongs to one region: keep the pick only while it is still in the chosen region, and
        // put the location's own back when the region goes back to what it was.
        const stillInRegion = subRegions.some(
          (a) => String(a.id) === prev.subRegionId && String(a.regionId) === value,
        );
        if (value === prev.regionBaseline) next.subRegionId = prev.subRegionBaseline;
        else if (!stillInRegion) next.subRegionId = '';
      }
      if (field === 'locationTypes') {
        const wasCoastal = (prev.locationTypes || []).includes('SEASCAPE');
        const isCoastal = value.includes('SEASCAPE');
        // Only re-seed on an actual coastal transition. Rewriting on every change would wipe a
        // curated tide preference the moment an unrelated chip (say WOODLAND) was added to a
        // coastal site.
        if (isCoastal && !wasCoastal) next.tideTypes = ['HIGH', 'MID', 'LOW'];
        else if (!isCoastal) next.tideTypes = [];
      }
      return next;
    });
  }

  async function handleSuggestFloor() {
    const token = ++floorToken.current;
    const lat = parseFloat(editValues.lat);
    const lon = parseFloat(editValues.lon);
    setEditValues((prev) => ({ ...prev, floorBusy: true, floorStatus: '' }));
    try {
      const result = await fetchValleyFloorCandidates(lat, lon);
      if (token !== floorToken.current) return;
      const list = result?.candidates ?? [];
      setEditValues((prev) => (prev ? {
        ...prev,
        floorBusy: false,
        floorCandidates: list,
        floorStatus: list.length === 0 ? 'No valley floor 30 m below within 5 km' : '',
      } : prev));
    } catch (err) {
      if (token !== floorToken.current) return;
      setEditValues((prev) => (prev ? {
        ...prev,
        floorBusy: false,
        floorStatus: err?.response?.data?.error ?? 'Could not look up valley floors; try again shortly.',
      } : prev));
    }
  }

  async function handlePickFloor(candidate) {
    const token = ++floorToken.current;
    setEditValues((prev) => ({
      ...prev,
      floorMetres: String(candidate.metres),
      floorLat: candidate.lat,
      floorLng: candidate.lng,
      floorCleared: false,
      floorCell: null,
      floorStatus: '',
      floorBusy: true,
    }));
    try {
      const cell = await fetchValleyFloorCell(candidate.lat, candidate.lng, candidate.metres);
      if (token !== floorToken.current) return;
      setEditValues((prev) => (prev ? { ...prev, floorBusy: false, floorCell: cell } : prev));
    } catch (err) {
      if (token !== floorToken.current) return;
      setEditValues((prev) => (prev ? {
        ...prev,
        floorBusy: false,
        floorStatus: err?.response?.data?.error ?? 'Could not look up the model cell for that point.',
      } : prev));
    }
  }

  function handleClearFloor() {
    floorToken.current += 1;
    setEditValues((prev) => ({
      ...prev,
      floorMetres: '',
      floorLat: null,
      floorLng: null,
      floorCleared: true,
      floorCell: null,
      floorStatus: '',
      floorBusy: false,
    }));
  }

  // Height fields of the save payload, each present only when it differs from what the server holds.
  // Returns { error } for a value that cannot be sent, otherwise { fields }.
  function heightPayload() {
    const fields = {};
    const elevation = parseMetres(editValues.elevation);
    if (elevation === null && editValues.elevationBaseline !== null) {
      return { error: "Elevation can't be cleared; enter a value" };
    }
    if (elevation !== null) {
      if (Number.isNaN(elevation) || elevation < MIN_HEIGHT_M || elevation > MAX_HEIGHT_M) {
        return { error: 'Elevation must be a whole number between -50 and 1,500 m.' };
      }
      if (elevation !== editValues.elevationBaseline) fields.elevationMetres = elevation;
    }
    const baseline = editValues.floorBaseline;
    if (editValues.floorCleared) {
      if (baseline) fields.clearValleyFloor = true;
    } else if (editValues.floorLat !== null && editValues.floorLng !== null) {
      const metres = parseMetres(editValues.floorMetres);
      if (metres === null || Number.isNaN(metres) || metres < MIN_HEIGHT_M || metres > MAX_HEIGHT_M) {
        return { error: 'Valley floor height must be a whole number between -50 and 1,500 m, or clear the floor.' };
      }
      if (!baseline || metres !== baseline.metres
        || editValues.floorLat !== baseline.lat || editValues.floorLng !== baseline.lng) {
        fields.valleyFloorMetres = metres;
        fields.valleyFloorLat = editValues.floorLat;
        fields.valleyFloorLng = editValues.floorLng;
      }
    }
    return { fields };
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
    const editIsCoastal = editValues.locationTypes.includes('SEASCAPE');
    if (editIsCoastal && (!editValues.tideTypes || editValues.tideTypes.length === 0)) {
      setEditError('Coastal locations require at least one tide preference (High, Mid, or Low).');
      return;
    }
    if (editValues.locationTypes.length === 0) {
      setEditError('A location must have at least one type.');
      return;
    }
    const heights = heightPayload();
    if (heights.error) {
      setEditError(heights.error);
      return;
    }
    setEditSaving(true);
    setEditError('');
    setAliasDetail('');
    const subRegionChange = editValues.subRegionId && editValues.subRegionId !== editValues.subRegionBaseline
      ? { subRegionId: Number(editValues.subRegionId) }
      : {};
    try {
      await updateLocation(editingRowId, {
        name: editValues.name.trim(),
        lat: parsedLat,
        lon: parsedLon,
        solarEventTypes: editValues.solarEventTypes,
        locationTypes: editValues.locationTypes,
        tideTypes: editIsCoastal ? editValues.tideTypes : [],
        regionId: editValues.regionId ? Number(editValues.regionId) : null,
        mistProne: editValues.mistProne === true,
        ...subRegionChange,
        ...heights.fields,
      });
      // The region and sub-region may both have moved memberships: read them again.
      await refreshAreas();
      // Other names are a separate write, AFTER the location's own save succeeds, and only when the field
      // was edited: an unedited field must never overwrite names it may not have seen.
      // The comparison is against the text the field STARTED with (recorded when the editor opened, or when
      // the names landed), never the live map: an editor opened before the load must not read as "cleared".
      if (editValues.aliasesLoaded && editValues.aliases !== editValues.aliasesBaseline) {
        try {
          const savedAliases = await setLocationAliases(editingRowId, parseAliases(editValues.aliases));
          setAliasesByLocation((prev) => ({ ...prev, [editingRowId]: savedAliases }));
        } catch (aliasErr) {
          await refreshLocations();
          onLocationsChanged();
          // The location itself is saved: what the form holds for region and sub-region is now the stored truth.
          setEditValues((prev) => (prev
            ? { ...prev, regionBaseline: prev.regionId, subRegionBaseline: prev.subRegionId }
            : prev));
          setEditError('Location saved; its other names were not');
          setAliasDetail(aliasErr?.response?.data?.error ?? '');
          return;
        }
      }
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
    const addIsCoastal = addLocationTypes.includes('SEASCAPE');
    if (addIsCoastal && (!addTideTypes || addTideTypes.length === 0)) {
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
        locationTypes: addLocationTypes,
        tideTypes: addIsCoastal ? addTideTypes : [],
        regionId: addRegionId ? Number(addRegionId) : null,
        subRegionId: addSubRegionId ? Number(addSubRegionId) : null,
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
        locationTypes: addLocationTypes,
        tideTypes: addIsCoastal ? addTideTypes : [],
        regionId: addRegionId ? Number(addRegionId) : null,
        subRegionId: addSubRegionId ? Number(addSubRegionId) : null,
        bortleClass: enrichmentData?.bortleClass ?? null,
        skyBrightnessSqm: enrichmentData?.skyBrightnessSqm ?? null,
        elevationMetres: enrichmentData?.elevationMetres ?? null,
        gridLat: enrichmentData?.gridLat ?? null,
        gridLng: enrichmentData?.gridLng ?? null,
        overlooksWater: addOverlooksWater,
        coastalTidal: addCoastalTidal,
        mistProne: addMistProne,
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
        locationTypes: confirmData.locationTypes,
        tideTypes: confirmData.tideTypes,
        regionId: confirmData.regionId,
        bortleClass: confirmData.bortleClass,
        skyBrightnessSqm: confirmData.skyBrightnessSqm,
        elevationMetres: confirmData.elevationMetres,
        gridLat: confirmData.gridLat,
        gridLng: confirmData.gridLng,
        overlooksWater: confirmData.overlooksWater,
        coastalTidal: confirmData.coastalTidal,
        mistProne: confirmData.mistProne === true,
        ...(confirmData.subRegionId ? { subRegionId: confirmData.subRegionId } : {}),
      });
      await refreshLocations();
      await refreshAreas();
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
        // The "no sub-region" count only covers enabled locations.
        await refreshAreas();
      } catch (err) {
        console.error('Failed to toggle location enabled:', err);
      }
    });
  }

  // Seed tide preferences on an actual coastal transition, not on every type change — adding
  // WOODLAND to a coastal site must not reset a curated tide preference.
  function handleAddLocationTypesChange(next) {
    const wasCoastal = addLocationTypes.includes('SEASCAPE');
    const isCoastal = next.includes('SEASCAPE');
    setAddLocationTypes(next);
    if (isCoastal && !wasCoastal) setAddTideTypes(['HIGH', 'MID', 'LOW']);
    else if (!isCoastal) setAddTideTypes([]);
  }

  const inlineInputClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-1.5 py-0.5 text-xs text-plex-text focus:outline-hidden focus:ring-1 focus:ring-plex-gold';
  const inlineSelectClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-1 py-0.5 text-xs text-plex-text focus:outline-hidden focus:ring-1 focus:ring-plex-gold';

  const selectClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-hidden focus:ring-1 focus:ring-plex-gold';
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
                                  selected={editValues.locationTypes}
                                  onChange={(next) => handleEditChange('locationTypes', next)}
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
                                <SubRegionSelect
                                  subRegions={subRegions}
                                  regionId={editValues.regionId}
                                  value={editValues.subRegionId}
                                  onChange={(v) => handleEditChange('subRegionId', v)}
                                  canBeEmpty={!editValues.subRegionBaseline || editValues.regionId !== editValues.regionBaseline}
                                  className={`${inlineSelectClass} mt-1`}
                                  testId="inline-edit-sub-region"
                                />
                                <label className="mt-1 flex items-start gap-1 text-xs text-plex-text-secondary cursor-pointer">
                                  <input
                                    className="shrink-0 mt-0.5"
                                    type="checkbox"
                                    checked={editValues.mistProne === true}
                                    onChange={(e) => handleEditChange('mistProne', e.target.checked)}
                                    data-testid="inline-edit-mist-prone"
                                  />
                                  Mist forms here: valley floor, riverside, lake shore
                                </label>
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
                                  disabled={!editValues.locationTypes.includes('SEASCAPE')}
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
                                    disabled={editSaving || editValues.floorBusy === true}
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
                                {loc.mistProne === true && (
                                  <span
                                    className="ml-1.5 text-xs px-1.5 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary"
                                    title="Mist forms here: valley floor, riverside, lake shore"
                                    data-testid={`mist-prone-badge-${loc.id}`}
                                  >
                                    Mist
                                  </span>
                                )}
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
                                <LocationTypeChips selected={typesOrDefault(loc.locationType)} readOnly />
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
                                {!loc.enabled && loc.disabledReason && (
                                  <div
                                    className="mt-1 flex items-center gap-0.5"
                                    data-testid={`auto-disabled-${loc.id}`}
                                  >
                                    <span className="text-xs px-1.5 py-0.5 rounded bg-red-900/40 text-red-400">
                                      auto
                                    </span>
                                    <InfoTip text={loc.disabledReason} className="text-red-400" />
                                  </div>
                                )}
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
                              <div className="flex flex-wrap items-center gap-2 text-xs">
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
                                <label htmlFor={`inline-edit-elevation-${loc.id}`} className="text-plex-text-muted">
                                  Elevation (m)
                                </label>
                                <input
                                  id={`inline-edit-elevation-${loc.id}`}
                                  type="number"
                                  step="1"
                                  inputMode="numeric"
                                  className={inlineInputClass + ' w-24'}
                                  value={editValues.elevation}
                                  onChange={(e) => handleEditChange('elevation', e.target.value)}
                                  data-testid="inline-edit-elevation"
                                />
                              </div>
                              {crossesInversionThreshold(editValues) && (
                                <div className="mt-1 text-xs text-amber-400" data-testid="inline-edit-elevation-warning">
                                  {parseMetres(editValues.elevation) >= INVERSION_MIN_ELEVATION_M
                                    ? 'This makes the site eligible for inversion scoring (200 m or higher, overlooks water).'
                                    : 'This takes the site out of inversion scoring (below 200 m).'}
                                </div>
                              )}
                              {coordinatesChanged(editValues) && (
                                <div className="mt-1 text-xs text-amber-400" data-testid="inline-edit-coords-hint">
                                  Coordinates changed: check elevation and valley floor
                                </div>
                              )}
                              <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs" data-testid="inline-edit-valley-floor">
                                <span className="text-plex-text-muted">Valley floor</span>
                                {editValues.floorLat !== null ? (
                                  <>
                                    <label htmlFor={`inline-edit-floor-height-${loc.id}`} className="sr-only">
                                      Valley floor height (m)
                                    </label>
                                    <input
                                      id={`inline-edit-floor-height-${loc.id}`}
                                      type="number"
                                      step="1"
                                      inputMode="numeric"
                                      className={inlineInputClass + ' w-20'}
                                      value={editValues.floorMetres}
                                      onChange={(e) => {
                                        handleEditChange('floorMetres', e.target.value);
                                        handleEditChange('floorCell', null);
                                      }}
                                      data-testid="inline-edit-valley-floor-height"
                                    />
                                    <span className="text-plex-text-muted">
                                      m at {editValues.floorLat.toFixed(4)}, {editValues.floorLng.toFixed(4)}
                                    </span>
                                  </>
                                ) : (
                                  <span className="text-plex-text-muted" data-testid="inline-edit-valley-floor-none">—</span>
                                )}
                                <button
                                  type="button"
                                  className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-muted hover:bg-plex-border hover:text-plex-text disabled:opacity-50"
                                  onClick={handleSuggestFloor}
                                  disabled={!coordsValid(editValues.lat, editValues.lon) || editValues.floorBusy === true}
                                  data-testid="inline-edit-valley-floor-suggest"
                                >
                                  Suggest
                                </button>
                                <button
                                  type="button"
                                  className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-muted hover:bg-plex-border hover:text-plex-text disabled:opacity-50"
                                  onClick={handleClearFloor}
                                  disabled={editValues.floorLat === null}
                                  data-testid="inline-edit-valley-floor-clear"
                                >
                                  Clear
                                </button>
                                {editValues.floorStatus && (
                                  <span className="text-plex-text-secondary" data-testid="inline-edit-valley-floor-status">
                                    {editValues.floorStatus}
                                  </span>
                                )}
                              </div>
                              {editValues.floorCandidates && editValues.floorCandidates.length > 0 && (
                                <ul className="mt-1 flex flex-wrap gap-1 text-xs">
                                  {editValues.floorCandidates.map((c, i) => (
                                    <li key={`${c.lat}-${c.lng}`}>
                                      <button
                                        type="button"
                                        className="px-2 py-0.5 rounded bg-plex-surface-light text-plex-text hover:bg-plex-border"
                                        onClick={() => handlePickFloor(c)}
                                        data-testid={`inline-edit-valley-floor-candidate-${i}`}
                                      >
                                        {c.metres} m, {c.distanceKm.toFixed(1)} km {c.bearing}
                                      </button>
                                    </li>
                                  ))}
                                </ul>
                              )}
                              {editValues.floorCell && (
                                <div className="mt-1 text-xs text-plex-text-muted" data-testid="inline-edit-valley-floor-cell">
                                  Model cell {editValues.floorCell.cellLat}, {editValues.floorCell.cellLng},{' '}
                                  {Math.round(editValues.floorCell.cellElevationMetres)} m,{' '}
                                  {editValues.floorCell.cellDistanceKm.toFixed(1)} km from the floor
                                </div>
                              )}
                              <div className="mt-1.5 flex items-center gap-2 text-xs">
                                <label htmlFor={`inline-edit-aliases-${loc.id}`} className="text-plex-text-muted shrink-0">
                                  Also known as
                                </label>
                                <input
                                  id={`inline-edit-aliases-${loc.id}`}
                                  type="text"
                                  className={inlineInputClass}
                                  value={editValues.aliases}
                                  onChange={(e) => handleEditChange('aliases', e.target.value)}
                                  disabled={!editValues.aliasesLoaded}
                                  placeholder={!editValues.aliasesLoaded
                                    ? (aliasesFailed ? 'Other names could not be loaded' : 'Loading other names...')
                                    : 'Other names, comma-separated (e.g. The Heugh)'}
                                  data-testid="inline-edit-aliases"
                                />
                              </div>
                            </td>
                          </tr>
                        )}
                        {isEditing && editError && (
                          <tr>
                            <td colSpan={10} className="py-1 text-xs text-red-400 pl-1" data-testid="inline-edit-error">
                              {editError}
                              {aliasDetail && (
                                <span className="ml-1 text-plex-text-secondary" data-testid="inline-edit-alias-detail">
                                  ({aliasDetail})
                                </span>
                              )}
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

          {!loading && <AreasSection locations={locations} reloadKey={areasVersion} onAreasChanged={() => refreshAreas(false)} />}
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
                    className="flex-1 bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold"
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
                  <div className="flex flex-wrap gap-x-6 gap-y-1.5">
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
                    <label className="flex items-center gap-1.5 text-xs text-plex-text cursor-pointer">
                      <input
                        type="checkbox"
                        checked={addMistProne}
                        onChange={(e) => setAddMistProne(e.target.checked)}
                        data-testid="mist-prone-checkbox"
                      />
                      Mist forms here: valley floor, riverside, lake shore
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
                  className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold"
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
                    className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold"
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
                    className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold"
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
              <span id="add-solar-event-type-label" className={labelClass}>Solar Event Type</span>
              <div id="add-solar-event-type-chips" role="group" aria-labelledby="add-solar-event-type-label"
                   className="py-1.5" data-testid="add-solar-event-type">
                <SolarToggleChips
                  selected={addSolarEventTypes}
                  onChange={setAddSolarEventTypes}
                />
              </div>
            </div>
            <div>
              <span id="add-location-type-label" className={labelClass}>
                Location Type <span className="text-plex-text-muted">(one or more)</span>
              </span>
              <div id="add-location-type" role="group" aria-labelledby="add-location-type-label"
                   className="py-1.5" data-testid="add-location-type">
                <LocationTypeChips
                  selected={addLocationTypes}
                  onChange={handleAddLocationTypesChange}
                />
              </div>
              <p className="text-xs text-plex-text-muted mt-0.5">
                {formatLocationType(addLocationTypes)}
              </p>
            </div>
            <div>
              <span id="add-tide-type-label" className={labelClass}>Tide Preference</span>
              <div id="add-tide-type-chips" role="group" aria-labelledby="add-tide-type-label"
                   className="py-1.5" data-testid="add-tide-type">
                <TideToggleChips
                  selected={addTideTypes}
                  onChange={setAddTideTypes}
                  disabled={!addLocationTypes.includes('SEASCAPE')}
                />
              </div>
            </div>
            <div>
              <label htmlFor="add-region" className={labelClass}>Region</label>
              <select
                id="add-region"
                className={selectClass}
                value={addRegionId}
                onChange={(e) => { setAddRegionId(e.target.value); setAddSubRegionId(''); }}
                data-testid="add-region"
              >
                <option value="">— None —</option>
                {regions.filter((r) => r.enabled).map((r) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </select>
              <SubRegionSelect
                subRegions={subRegions}
                regionId={addRegionId}
                value={addSubRegionId}
                onChange={setAddSubRegionId}
                canBeEmpty
                id="add-sub-region"
                className={`${selectClass} mt-2`}
                testId="add-sub-region"
              />
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
                  {formatLocationType(confirmData.locationTypes)}
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
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Mist forms here</span>
                <span className="text-plex-text" data-testid="confirm-mist-prone">
                  {confirmData.mistProne ? 'Yes' : 'No'}
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
