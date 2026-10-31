import React, { useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import AreasSection from './AreasSection.jsx';
import LocationAlerts from './LocationAlerts.jsx';
import InfoTip from './InfoTip.jsx';
import { formatTimestampUk } from '../utils/conversions';
import Pagination from './Pagination.jsx';
import usePagination from '../hooks/usePagination.js';
import SortableHeader from './shared/SortableHeader.jsx';
import useSortAndFilter from '../hooks/useSortAndFilter.js';
import Modal from './shared/Modal.jsx';
import useLocationsData from '../hooks/useLocationsData.js';
import useLocationEditor from '../hooks/useLocationEditor.js';
import useLocationAdd from '../hooks/useLocationAdd.js';
import useLocationToggle from '../hooks/useLocationToggle.js';
import { fetchTideStats } from '../api/tideApi.js';
import {
  SOLAR_EVENT_TYPES, LOCATION_TYPES, TIDE_TYPES, INVERSION_MIN_ELEVATION_M, formatLocationType, formatTideType,
  formatSolarEventType, typesOrDefault, parseMetres, coordsValid, crossesInversionThreshold, coordinatesChanged,
} from '../utils/locationEdit.js';

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

/**
 * Location management view with list/add/edit modes, sorting, filtering, and geocoding.
 *
 * @param {object} props
 * @param {function} props.onLocationsChanged - Called when locations are added/updated/toggled.
 */
export default function LocationManagementView({ onLocationsChanged }) {
  const data = useLocationsData();
  const {
    locations, regions, loading, areas, areasVersion, subRegions, aliasesFailed, refreshLocations, refreshAreas,
  } = data;
  const [mode, setMode] = useState('list'); // list | add
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
  const toggle = useLocationToggle({ locations, refreshLocations, refreshAreas });
  const { form } = add;

  // Tide stats modal state
  const [tideStatsModal, setTideStatsModal] = useState(null); // { name, stats, loading }

  const locationAccessors = useMemo(() => ({
    name: (loc) => loc.name,
    region: (loc) => loc.region?.name || '',
    type: (loc) => formatLocationType(loc.locationType),
    solar: (loc) => formatSolarEventType(loc.solarEventType),
    tide: (loc) => formatTideType(loc.tideType),
    created: (loc) => loc.createdAt || '',
    status: (loc) => loc.enabled ? 'Enabled' : 'Disabled',
  }), []);

  const sf = useSortAndFilter('name', 'asc', locationAccessors);

  const filteredLocations = useMemo(() => sf.apply(toggle.locations), [sf, toggle.locations]);

  const { pageItems: pageLocations, ...pagination } = usePagination(filteredLocations);

  // Cancel inline edit when page changes (edited row may no longer be visible)
  useEffect(() => {
    if (editor.editingId) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect; runs synchronously this tick.
      (async () => {
        editor.cancel();
      })();
    }
  }, [pagination.page]); // eslint-disable-line react-hooks/exhaustive-deps

  function handleStartAdd() {
    editor.cancel();
    setMode('add');
    add.reset();
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

  function handleCancel() {
    setMode('list');
    add.setConfirm(null);
    add.setError('');
  }

  async function handleConfirmSave() {
    if (await add.confirmSave()) handleCancel();
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

          {toggle.error && (
            <p role="alert" className="text-xs text-red-400" data-testid="location-toggle-error">{toggle.error.message}</p>
          )}

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
                    const isEditing = loc.id === editor.editingId;
                    return (
                      <React.Fragment key={loc.id}>
                        <tr
                          className={`border-b border-plex-surface last:border-0 ${!loc.enabled && !isEditing ? 'opacity-50' : ''}`}
                          onKeyDown={isEditing ? (e) => { if (e.key === 'Escape') editor.cancel(); } : undefined}
                        >
                          {isEditing ? (
                            <>
                              <td className="py-2">
                                <input
                                  type="text"
                                  className={inlineInputClass}
                                  value={editor.values.name}
                                  onChange={(e) => editor.change('name', e.target.value)}
                                  data-testid="inline-edit-name"
                                />
                              </td>
                              <td className="py-2 text-plex-text-secondary text-xs">
                                {loc.lat.toFixed(3)}, {loc.lon.toFixed(3)}
                              </td>
                              <td className="py-2" data-testid="inline-edit-type">
                                <LocationTypeChips
                                  selected={editor.values.locationTypes}
                                  onChange={(next) => editor.change('locationTypes', next)}
                                />
                              </td>
                              <td className="py-2">
                                <select
                                  className={inlineSelectClass}
                                  value={editor.values.regionId}
                                  onChange={(e) => editor.change('regionId', e.target.value)}
                                  data-testid="inline-edit-region"
                                >
                                  <option value="">— None —</option>
                                  {regions.filter((r) => r.enabled || String(r.id) === editor.values.regionId).map((r) => (
                                    <option key={r.id} value={r.id}>{r.name}</option>
                                  ))}
                                </select>
                                <SubRegionSelect
                                  subRegions={subRegions}
                                  regionId={editor.values.regionId}
                                  value={editor.values.subRegionId}
                                  onChange={(v) => editor.change('subRegionId', v)}
                                  canBeEmpty={!editor.values.subRegionBaseline || editor.values.regionId !== editor.values.regionBaseline}
                                  className={`${inlineSelectClass} mt-1`}
                                  testId="inline-edit-sub-region"
                                />
                                <label className="mt-1 flex items-start gap-1 text-xs text-plex-text-secondary cursor-pointer">
                                  <input
                                    className="shrink-0 mt-0.5"
                                    type="checkbox"
                                    checked={editor.values.mistProne === true}
                                    onChange={(e) => editor.change('mistProne', e.target.checked)}
                                    data-testid="inline-edit-mist-prone"
                                  />
                                  Mist forms here: valley floor, riverside, lake shore
                                </label>
                              </td>
                              <td className="py-2" data-testid="inline-edit-solar">
                                <SolarToggleChips
                                  selected={editor.values.solarEventTypes}
                                  onChange={(next) => editor.patch({ solarEventTypes: next })}
                                />
                              </td>
                              <td className="py-2" data-testid="inline-edit-tide">
                                <TideToggleChips
                                  selected={editor.values.tideTypes}
                                  onChange={(next) => editor.patch({ tideTypes: next })}
                                  disabled={!editor.values.locationTypes.includes('SEASCAPE')}
                                />
                              </td>
                              <td className="py-2 text-plex-text-secondary text-xs" data-testid={`bortle-edit-${loc.id}`}>
                                {loc.bortleClass != null ? loc.bortleClass : '—'}
                              </td>
                              <td className="py-2">
                                <button
                                  onClick={() => toggle.toggle(loc)}
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
                                    onClick={editor.save}
                                    disabled={editor.saving || editor.values.floorBusy === true}
                                    data-testid="inline-edit-save"
                                  >
                                    {editor.saving ? 'Saving…' : 'Save'}
                                  </button>
                                  <button
                                    className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-muted hover:bg-plex-border hover:text-plex-text"
                                    onClick={editor.cancel}
                                    disabled={editor.saving}
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
                                  onClick={() => toggle.toggle(loc)}
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
                                    onClick={() => editor.start(loc)}
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
                                  value={editor.values.lat}
                                  onChange={(e) => editor.change('lat', e.target.value)}
                                  data-testid="inline-edit-lat"
                                />
                                <span className="text-plex-text-muted">Lon</span>
                                <input
                                  type="number"
                                  step="any"
                                  className={inlineInputClass + ' w-28'}
                                  value={editor.values.lon}
                                  onChange={(e) => editor.change('lon', e.target.value)}
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
                                  value={editor.values.elevation}
                                  onChange={(e) => editor.change('elevation', e.target.value)}
                                  data-testid="inline-edit-elevation"
                                />
                              </div>
                              {crossesInversionThreshold(editor.values) && (
                                <div className="mt-1 text-xs text-amber-400" data-testid="inline-edit-elevation-warning">
                                  {parseMetres(editor.values.elevation) >= INVERSION_MIN_ELEVATION_M
                                    ? 'This makes the site eligible for inversion scoring (200 m or higher, overlooks water).'
                                    : 'This takes the site out of inversion scoring (below 200 m).'}
                                </div>
                              )}
                              {coordinatesChanged(editor.values) && (
                                <div className="mt-1 text-xs text-amber-400" data-testid="inline-edit-coords-hint">
                                  Coordinates changed: check elevation and valley floor
                                </div>
                              )}
                              <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs" data-testid="inline-edit-valley-floor">
                                <span className="text-plex-text-muted">Valley floor</span>
                                {editor.values.floorLat !== null ? (
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
                                      value={editor.values.floorMetres}
                                      onChange={(e) => editor.changeFloorHeight(e.target.value)}
                                      data-testid="inline-edit-valley-floor-height"
                                    />
                                    <span className="text-plex-text-muted">
                                      m at {editor.values.floorLat.toFixed(4)}, {editor.values.floorLng.toFixed(4)}
                                    </span>
                                  </>
                                ) : (
                                  <span className="text-plex-text-muted" data-testid="inline-edit-valley-floor-none">—</span>
                                )}
                                <button
                                  type="button"
                                  className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-muted hover:bg-plex-border hover:text-plex-text disabled:opacity-50"
                                  onClick={editor.suggestFloor}
                                  disabled={!coordsValid(editor.values.lat, editor.values.lon) || editor.values.floorBusy === true}
                                  data-testid="inline-edit-valley-floor-suggest"
                                >
                                  Suggest
                                </button>
                                <button
                                  type="button"
                                  className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-muted hover:bg-plex-border hover:text-plex-text disabled:opacity-50"
                                  onClick={editor.clearFloor}
                                  disabled={editor.values.floorLat === null}
                                  data-testid="inline-edit-valley-floor-clear"
                                >
                                  Clear
                                </button>
                                {editor.values.floorStatus && (
                                  <span className="text-plex-text-secondary" data-testid="inline-edit-valley-floor-status">
                                    {editor.values.floorStatus}
                                  </span>
                                )}
                              </div>
                              {editor.values.floorCandidates && editor.values.floorCandidates.length > 0 && (
                                <ul className="mt-1 flex flex-wrap gap-1 text-xs">
                                  {editor.values.floorCandidates.map((c, i) => (
                                    <li key={`${c.lat}-${c.lng}`}>
                                      <button
                                        type="button"
                                        className="px-2 py-0.5 rounded bg-plex-surface-light text-plex-text hover:bg-plex-border"
                                        onClick={() => editor.pickFloor(c)}
                                        data-testid={`inline-edit-valley-floor-candidate-${i}`}
                                      >
                                        {c.metres} m, {c.distanceKm.toFixed(1)} km {c.bearing}
                                      </button>
                                    </li>
                                  ))}
                                </ul>
                              )}
                              {editor.values.floorCell && (
                                <div className="mt-1 text-xs text-plex-text-muted" data-testid="inline-edit-valley-floor-cell">
                                  Model cell {editor.values.floorCell.cellLat.toFixed(3)}, {editor.values.floorCell.cellLng.toFixed(3)}{' '}
                                  ({editor.values.floorCell.cellDistanceKm.toFixed(1)} km from the floor)
                                </div>
                              )}
                              {editor.values.locationTypes.includes('SEASCAPE') && (
                                <div className="mt-1.5" data-testid="inline-edit-tide-point">
                                  <div className="flex flex-wrap items-center gap-2 text-xs">
                                    <span className="text-plex-text-muted">Tide point</span>
                                    <label htmlFor={`inline-edit-tide-lat-${loc.id}`} className="sr-only">
                                      Tide point latitude
                                    </label>
                                    <input
                                      id={`inline-edit-tide-lat-${loc.id}`}
                                      type="number"
                                      step="any"
                                      placeholder="Lat"
                                      className={inlineInputClass + ' w-28'}
                                      value={editor.values.tideLat}
                                      onChange={(e) => editor.change('tideLat', e.target.value)}
                                      data-testid="inline-edit-tide-lat"
                                    />
                                    <label htmlFor={`inline-edit-tide-lng-${loc.id}`} className="sr-only">
                                      Tide point longitude
                                    </label>
                                    <input
                                      id={`inline-edit-tide-lng-${loc.id}`}
                                      type="number"
                                      step="any"
                                      placeholder="Lng"
                                      className={inlineInputClass + ' w-28'}
                                      value={editor.values.tideLng}
                                      onChange={(e) => editor.change('tideLng', e.target.value)}
                                      data-testid="inline-edit-tide-lng"
                                    />
                                    <button
                                      type="button"
                                      className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-muted hover:bg-plex-border hover:text-plex-text disabled:opacity-50"
                                      onClick={editor.clearTidePoint}
                                      disabled={editor.values.tideLat === '' && editor.values.tideLng === ''}
                                      data-testid="inline-edit-tide-point-clear"
                                    >
                                      Clear
                                    </button>
                                  </div>
                                  <div className="mt-0.5 text-xs text-plex-text-muted" data-testid="inline-edit-tide-point-help">
                                    Where tide times are read from, when the pin&apos;s own sea area is unreliable
                                  </div>
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
                                  value={editor.values.aliases}
                                  onChange={(e) => editor.change('aliases', e.target.value)}
                                  disabled={!editor.values.aliasesLoaded}
                                  placeholder={!editor.values.aliasesLoaded
                                    ? (aliasesFailed ? 'Other names could not be loaded' : 'Loading other names...')
                                    : 'Other names, comma-separated (e.g. The Heugh)'}
                                  data-testid="inline-edit-aliases"
                                />
                              </div>
                            </td>
                          </tr>
                        )}
                        {isEditing && editor.error && (
                          <tr>
                            <td colSpan={10} className="py-1 text-xs text-red-400 pl-1" data-testid="inline-edit-error">
                              {editor.error}
                              {editor.aliasDetail && (
                                <span className="ml-1 text-plex-text-secondary" data-testid="inline-edit-alias-detail">
                                  ({editor.aliasDetail})
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

          {!form.manualEntry && (
            <>
              <div>
                <label htmlFor="place-name" className={labelClass}>Place name</label>
                <div className="flex gap-2">
                  <input
                    id="place-name"
                    type="text"
                    className="flex-1 bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold"
                    placeholder="e.g. Bamburgh, Northumberland"
                    value={form.placeName}
                    onChange={(e) => add.set('placeName', e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') add.geocode(); }}
                    data-testid="place-name-input"
                  />
                  <button
                    className="btn-secondary text-xs shrink-0"
                    onClick={add.geocode}
                    disabled={add.geocoding}
                    data-testid="geocode-btn"
                  >
                    {add.geocoding ? 'Looking up...' : 'Look up'}
                  </button>
                </div>
              </div>

              {form.geocodeError && (
                <div className="flex flex-col gap-1">
                  <p className="text-xs text-red-400">{form.geocodeError}</p>
                  <button
                    type="button"
                    className="text-xs text-plex-gold hover:text-plex-text self-start"
                    onClick={add.enterManually}
                    data-testid="manual-entry-btn"
                  >
                    Enter coordinates manually
                  </button>
                </div>
              )}

              {form.geocodeResult && (
                <div className="bg-plex-surface-light border border-plex-border rounded px-3 py-2 text-xs text-plex-text-secondary">
                  <p className="font-medium text-plex-text">
                    {form.geocodeResult.lat.toFixed(4)}, {form.geocodeResult.lon.toFixed(4)}
                  </p>
                  <p className="mt-0.5">{form.geocodeResult.displayName}</p>
                </div>
              )}

              {add.enriching && (
                <p className="text-xs text-plex-text-muted animate-pulse" data-testid="enriching-spinner">
                  Detecting elevation, bortle, grid cell…
                </p>
              )}

              {!add.enriching && form.enrichmentData && (
                <div className="border border-plex-border rounded px-3 py-2" data-testid="enrichment-panel">
                  <p className="text-xs font-medium text-plex-text-secondary mb-1.5">Auto-detected</p>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
                    <div className="flex justify-between">
                      <span className="text-plex-text-secondary">Elevation</span>
                      <span className="text-plex-text" data-testid="enrichment-elevation">
                        {form.enrichmentData.elevationMetres != null ? `${form.enrichmentData.elevationMetres}m` : '—'}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-plex-text-secondary">Grid cell</span>
                      <span className="text-plex-text" data-testid="enrichment-grid">
                        {form.enrichmentData.gridLat != null && form.enrichmentData.gridLng != null
                          ? `${form.enrichmentData.gridLat}, ${form.enrichmentData.gridLng}`
                          : '—'}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-plex-text-secondary">Bortle</span>
                      <span className="text-plex-text" data-testid="enrichment-bortle">
                        {form.enrichmentData.bortleClass != null ? form.enrichmentData.bortleClass : '—'}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-plex-text-secondary">SQM</span>
                      <span className="text-plex-text" data-testid="enrichment-sqm">
                        {form.enrichmentData.skyBrightnessSqm != null ? form.enrichmentData.skyBrightnessSqm : '—'}
                      </span>
                    </div>
                  </div>
                </div>
              )}

              {!add.enriching && form.enrichmentData && (
                <div className="border border-plex-border rounded px-3 py-2" data-testid="manual-toggles-panel">
                  <p className="text-xs font-medium text-plex-text-secondary mb-1.5">Manual</p>
                  <div className="flex flex-wrap gap-x-6 gap-y-1.5">
                    <label className="flex items-center gap-1.5 text-xs text-plex-text cursor-pointer">
                      <input
                        type="checkbox"
                        checked={form.overlooksWater}
                        onChange={(e) => add.set('overlooksWater', e.target.checked)}
                        data-testid="overlooks-water-checkbox"
                      />
                      Overlooks water
                    </label>
                    <label className="flex items-center gap-1.5 text-xs text-plex-text cursor-pointer">
                      <input
                        type="checkbox"
                        checked={form.coastalTidal}
                        onChange={(e) => add.set('coastalTidal', e.target.checked)}
                        data-testid="coastal-tidal-checkbox"
                      />
                      Coastal tidal
                    </label>
                    <label className="flex items-center gap-1.5 text-xs text-plex-text cursor-pointer">
                      <input
                        type="checkbox"
                        checked={form.mistProne}
                        onChange={(e) => add.set('mistProne', e.target.checked)}
                        data-testid="mist-prone-checkbox"
                      />
                      Mist forms here: valley floor, riverside, lake shore
                    </label>
                  </div>
                </div>
              )}
            </>
          )}

          {form.manualEntry && (
            <div className="flex flex-col gap-3">
              <div>
                <label htmlFor="manual-name" className={labelClass}>Name</label>
                <input
                  id="manual-name"
                  type="text"
                  className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold"
                  placeholder="e.g. Bamburgh Castle"
                  value={form.manualName}
                  onChange={(e) => add.set('manualName', e.target.value)}
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
                    value={form.manualLat}
                    onChange={(e) => add.set('manualLat', e.target.value)}
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
                    value={form.manualLon}
                    onChange={(e) => add.set('manualLon', e.target.value)}
                    data-testid="manual-lon-input"
                  />
                </div>
              </div>
              <button
                type="button"
                className="text-xs text-plex-gold hover:text-plex-text self-start"
                onClick={add.backToLookup}
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
                  selected={form.solarEventTypes}
                  onChange={(next) => add.set('solarEventTypes', next)}
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
                  selected={form.locationTypes}
                  onChange={add.changeTypes}
                />
              </div>
              <p className="text-xs text-plex-text-muted mt-0.5">
                {formatLocationType(form.locationTypes)}
              </p>
            </div>
            <div>
              <span id="add-tide-type-label" className={labelClass}>Tide Preference</span>
              <div id="add-tide-type-chips" role="group" aria-labelledby="add-tide-type-label"
                   className="py-1.5" data-testid="add-tide-type">
                <TideToggleChips
                  selected={form.tideTypes}
                  onChange={(next) => add.set('tideTypes', next)}
                  disabled={!form.locationTypes.includes('SEASCAPE')}
                />
              </div>
            </div>
            <div>
              <label htmlFor="add-region" className={labelClass}>Region</label>
              <select
                id="add-region"
                className={selectClass}
                value={form.regionId}
                onChange={(e) => add.changeRegion(e.target.value)}
                data-testid="add-region"
              >
                <option value="">— None —</option>
                {regions.filter((r) => r.enabled).map((r) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </select>
              <SubRegionSelect
                subRegions={subRegions}
                regionId={form.regionId}
                value={form.subRegionId}
                onChange={(v) => add.set('subRegionId', v)}
                canBeEmpty
                id="add-sub-region"
                className={`${selectClass} mt-2`}
                testId="add-sub-region"
              />
            </div>
          </div>

          {add.error && <p className="text-xs text-red-400">{add.error}</p>}

          <div className="flex flex-col gap-2">
            <div className="flex justify-between">
              <button className="btn-secondary text-sm" onClick={handleCancel}>
                Cancel
              </button>
              <button
                className="btn-primary text-sm"
                onClick={add.review}
                disabled={!add.canSubmit}
                data-testid="review-confirm-btn"
              >
                Review & Confirm
              </button>
            </div>
            {!form.manualEntry && !form.geocodeResult && (
              <p className="text-xs text-plex-text-muted text-right">Search for a place above, or switch to manual entry</p>
            )}
          </div>
        </div>
      )}

      {/* Confirm modal (add only) */}
      {add.confirm && (
        <Modal label="Confirm location" onClose={() => add.setConfirm(null)} data-testid="confirm-location-modal">
            <p className="text-sm font-semibold text-plex-text">
              Confirm New Location
            </p>

            <div className="text-sm space-y-2">
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Name</span>
                <span className="text-plex-text font-medium">{add.confirm.name}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Coordinates</span>
                <span className="text-plex-text">
                  {add.confirm.lat.toFixed(4)} N, {Math.abs(add.confirm.lon).toFixed(4)} {add.confirm.lon < 0 ? 'W' : 'E'}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Solar</span>
                <span className="text-plex-text">{formatSolarEventType(add.confirm.solarEventTypes)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Type</span>
                <span className="text-plex-text">
                  {formatLocationType(add.confirm.locationTypes)}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Tide</span>
                <span className="text-plex-text">
                  {formatTideType(add.confirm.tideTypes)}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Region</span>
                <span className="text-plex-text">
                  {add.confirm.regionId ? (regions.find((r) => r.id === add.confirm.regionId)?.name ?? '—') : '—'}
                </span>
              </div>
              <hr className="border-plex-border" />
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Elevation</span>
                <span className="text-plex-text">
                  {add.confirm.elevationMetres != null ? `${add.confirm.elevationMetres}m` : '—'}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Bortle</span>
                <span className="text-plex-text">{add.confirm.bortleClass ?? '—'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">SQM</span>
                <span className="text-plex-text">{add.confirm.skyBrightnessSqm ?? '—'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Grid cell</span>
                <span className="text-plex-text">
                  {add.confirm.gridLat != null && add.confirm.gridLng != null
                    ? `${add.confirm.gridLat}, ${add.confirm.gridLng}`
                    : '—'}
                </span>
              </div>
              <hr className="border-plex-border" />
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Overlooks water</span>
                <span className="text-plex-text">{add.confirm.overlooksWater ? 'Yes' : 'No'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Coastal tidal</span>
                <span className="text-plex-text">{add.confirm.coastalTidal ? 'Yes' : 'No'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-plex-text-secondary">Mist forms here</span>
                <span className="text-plex-text" data-testid="confirm-mist-prone">
                  {add.confirm.mistProne ? 'Yes' : 'No'}
                </span>
              </div>
            </div>

            {add.error && <p className="text-xs text-red-400">{add.error}</p>}

            <div className="flex justify-between">
              <button
                className="btn-secondary text-sm"
                onClick={() => add.setConfirm(null)}
                disabled={add.saving}
              >
                Back
              </button>
              <button
                className="btn-primary text-sm"
                onClick={handleConfirmSave}
                disabled={add.saving}
                data-testid="confirm-save-btn"
              >
                {add.saving ? 'Saving...' : 'Save'}
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
