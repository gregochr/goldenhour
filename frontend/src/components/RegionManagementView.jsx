import React, { useEffect, useOptimistic, useState, useTransition, useMemo } from 'react';
import {
  fetchRegions, addRegion, updateRegion, setRegionEnabled, setRegionBase,
} from '../api/regionApi.js';
import { fetchLocations } from '../api/forecastApi.js';
import Pagination from './Pagination.jsx';
import usePagination from '../hooks/usePagination.js';
import SortableHeader from './shared/SortableHeader.jsx';

/**
 * Region management view with list/add/edit modes and client-side pagination.
 *
 * <p>Follows the same pattern as UserManagementView but simpler — just name + enabled.
 */
export default function RegionManagementView() {
  const [regions, setRegions] = useState([]);
  const [locations, setLocations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [mode, setMode] = useState('list'); // list | add | edit
  const [editingRegion, setEditingRegion] = useState(null);

  // Form state
  const [formName, setFormName] = useState('');
  /**
   * The base town — the origin the Plan tab can plan from (heat-field plan §4.8).
   *
   * <p>Held as STRINGS, including the two coordinates, because an empty field and a zero are
   * different answers and a numeric state cannot hold both: `Number('')` is 0, which would drop a
   * base on the Gulf of Guinea the moment someone cleared a field. They are parsed once, on save.
   *
   * <p>Edit mode only. A region is created with a name and given a base afterwards, which keeps the
   * add form the one-field form it has always been — and a base is not something an admin has to
   * hand before a region can hold locations.
   */
  const [formBaseName, setFormBaseName] = useState('');
  const [formBaseLat, setFormBaseLat] = useState('');
  const [formBaseLon, setFormBaseLon] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  // Sort state
  const [sortKey, setSortKey] = useState('name');
  const [sortDir, setSortDir] = useState('asc');

  const [optimisticRegions, addOptimisticRegion] = useOptimistic(regions, (current, toggledId) =>
    current.map((r) => r.id === toggledId ? { ...r, enabled: !r.enabled } : r),
  );
  const [, startToggleTransition] = useTransition();

  const locationCountByRegion = useMemo(() => {
    const counts = {};
    locations.forEach((loc) => {
      if (loc.region?.id) {
        counts[loc.region.id] = (counts[loc.region.id] || 0) + 1;
      }
    });
    return counts;
  }, [locations]);

  const sortedRegions = useMemo(() => {
    const sorted = [...optimisticRegions];
    sorted.sort((a, b) => {
      let va, vb;
      if (sortKey === 'name') { va = a.name; vb = b.name; }
      else if (sortKey === 'status') { va = a.enabled ? 'Enabled' : 'Disabled'; vb = b.enabled ? 'Enabled' : 'Disabled'; }
      else if (sortKey === 'created') { va = a.createdAt || ''; vb = b.createdAt || ''; }
      else if (sortKey === 'locationCount') {
        va = locationCountByRegion[a.id] || 0;
        vb = locationCountByRegion[b.id] || 0;
        return sortDir === 'asc' ? va - vb : vb - va;
      } else { va = ''; vb = ''; }
      const cmp = String(va).localeCompare(String(vb), undefined, { sensitivity: 'base' });
      return sortDir === 'asc' ? cmp : -cmp;
    });
    return sorted;
  }, [optimisticRegions, sortKey, sortDir, locationCountByRegion]);

  const pagination = usePagination(sortedRegions);
  const pageRegions = pagination.pageItems;

  function handleSort(key) {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  async function refreshRegions() {
    try {
      const data = await fetchRegions();
      setRegions(data);
    } catch {
      // Keep existing list on failure
    }
  }

  useEffect(() => {
    Promise.all([fetchRegions(), fetchLocations()])
      .then(([regs, locs]) => { setRegions(regs); setLocations(locs); })
      .finally(() => setLoading(false));
  }, []);

  function handleStartAdd() {
    setMode('add');
    setFormName('');
    setFormBaseName('');
    setFormBaseLat('');
    setFormBaseLon('');
    setError('');
  }

  function handleStartEdit(region) {
    setMode('edit');
    setEditingRegion(region);
    setFormName(region.name);
    setFormBaseName(region.baseName ?? '');
    // `?? ''` rather than `|| ''`: a longitude of exactly 0 is Greenwich, and `||` would blank it.
    setFormBaseLat(region.baseLat == null ? '' : String(region.baseLat));
    setFormBaseLon(region.baseLon == null ? '' : String(region.baseLon));
    setError('');
  }

  function handleCancel() {
    setMode('list');
    setEditingRegion(null);
    setError('');
  }

  /**
   * The base form as the endpoint takes it, plus whether it differs from what is stored.
   *
   * <p>All three empty means "clear the base", which is a legitimate save. A partial base is
   * rejected here as well as on the backend, so the admin gets the message beside the fields rather
   * than as a 400 after a round trip.
   */
  function readBaseForm() {
    const name = formBaseName.trim();
    const latText = formBaseLat.trim();
    const lonText = formBaseLon.trim();
    if (!name && !latText && !lonText) {
      const changed = Boolean(editingRegion?.baseName)
        || editingRegion?.baseLat != null || editingRegion?.baseLon != null;
      return { changed, body: { baseName: null, baseLat: null, baseLon: null } };
    }
    const lat = Number(latText);
    const lon = Number(lonText);
    if (!name || latText === '' || lonText === ''
        || !Number.isFinite(lat) || !Number.isFinite(lon)) {
      return { invalid: true };
    }
    const changed = name !== (editingRegion?.baseName ?? '')
      || lat !== editingRegion?.baseLat || lon !== editingRegion?.baseLon;
    return { changed, body: { baseName: name, baseLat: lat, baseLon: lon } };
  }

  async function handleSave() {
    const trimmed = formName.trim();
    if (!trimmed) {
      setError('Region name is required.');
      return;
    }
    if (mode === 'edit' && readBaseForm().invalid) {
      setError('A base town needs a name, a latitude and a longitude — or leave all three blank.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      if (mode === 'add') {
        await addRegion({ name: trimmed });
      } else {
        await updateRegion(editingRegion.id, { name: trimmed });
        // A SECOND call, deliberately: the rename endpoint carries no base fields, so a rename can
        // never clear a base by omission. Sent after the rename and only when something changed,
        // because moving the base discards that region's whole shared drive-time matrix — a cost
        // worth paying when the town moves and not worth paying to re-save a name.
        const base = readBaseForm();
        if (base.changed) await setRegionBase(editingRegion.id, base.body);
      }
      await refreshRegions();
      handleCancel();
    } catch (err) {
      setError(err?.response?.data?.error ?? err.message ?? 'Failed to save region.');
      // ⚠️ Refresh even on a failure, and that is not tidiness. The save is TWO calls — the rename,
      // then the base — so the first can commit while the second 400s on a coordinate the backend
      // bounds-checks and this form does not. Without this the table still showed the OLD name for
      // a rename that had already landed, under an error message about the base, with no hint that
      // half the save had persisted. The form stays open on the failed field.
      await refreshRegions().catch(() => {});
    } finally {
      setSaving(false);
    }
  }

  function handleToggleEnabled(region) {
    startToggleTransition(async () => {
      addOptimisticRegion(region.id);
      try {
        await setRegionEnabled(region.id, !region.enabled);
        await refreshRegions();
      } catch (err) {
        console.error('Failed to toggle region enabled:', err);
      }
    });
  }

  const inputClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold';
  const COL_COUNT = 6;

  return (
    <div className="flex flex-col gap-4">

      {/* List mode */}
      {mode === 'list' && (
        <>
          <div className="flex items-center justify-between gap-4">
            <p className="text-sm font-semibold text-plex-text">Region Management</p>
            <button
              className="btn-secondary text-xs shrink-0"
              onClick={handleStartAdd}
              data-testid="add-region-btn"
            >
              + Add Region
            </button>
          </div>

          {loading && (
            <p className="text-sm text-plex-text-muted animate-pulse">Loading regions...</p>
          )}

          {!loading && regions.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full table-fixed text-sm text-left" data-testid="regions-table">
                <thead>
                  <tr className="text-xs text-plex-text-muted border-b border-plex-border">
                    <SortableHeader label="Name" sortKey="name" currentSortKey={sortKey} currentSortDir={sortDir} onSort={handleSort} spacer={false} />
                    <SortableHeader label="Created" sortKey="created" currentSortKey={sortKey} currentSortDir={sortDir} onSort={handleSort} spacer={false} />
                    <SortableHeader label="Status" sortKey="status" currentSortKey={sortKey} currentSortDir={sortDir} onSort={handleSort} spacer={false} />
                    <SortableHeader label="Location Count" sortKey="locationCount" currentSortKey={sortKey} currentSortDir={sortDir} onSort={handleSort} spacer={false} />
                    <th className="pb-1 font-medium text-xs text-plex-text-muted align-bottom">
                      <span className="whitespace-nowrap">Base</span>
                    </th>
                    <th className="pb-1 font-medium text-xs text-plex-text-muted align-bottom">
                      <span className="whitespace-nowrap">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {pageRegions.map((region) => (
                    <tr
                      key={region.id}
                      className={`border-b border-plex-surface last:border-0 ${!region.enabled ? 'opacity-50' : ''}`}
                    >
                      <td className="py-2 text-plex-text">{region.name}</td>
                      <td className="py-2 text-plex-text-muted text-xs">
                        {region.createdAt ? region.createdAt.slice(0, 10) : '—'}
                      </td>
                      <td className="py-2">
                        <button
                          onClick={() => handleToggleEnabled(region)}
                          className={`text-xs px-2 py-0.5 rounded cursor-pointer ${
                            region.enabled
                              ? 'bg-green-900/40 text-green-400 hover:bg-green-900/60'
                              : 'bg-red-900/40 text-red-400 hover:bg-red-900/60'
                          }`}
                          data-testid={`toggle-region-enabled-${region.id}`}
                        >
                          {region.enabled ? 'Enabled' : 'Disabled'}
                        </button>
                      </td>
                      <td className="py-2 text-plex-text-secondary text-xs" data-testid={`region-location-count-${region.id}`}>
                        {locationCountByRegion[region.id] || 0}
                      </td>
                      {/* An em dash, not "none": a region without a base is the ordinary state and
                          reads as a blank rather than as a fault. */}
                      <td className="py-2 text-plex-text-secondary text-xs" data-testid={`region-base-${region.id}`}>
                        {region.baseName || '—'}
                      </td>
                      <td className="py-2">
                        <button
                          className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text"
                          onClick={() => handleStartEdit(region)}
                          data-testid={`edit-region-${region.id}`}
                        >
                          Edit
                        </button>
                      </td>
                    </tr>
                  ))}
                  {pageRegions.length > 0 && pageRegions.length < pagination.pageSize && (
                    Array.from({ length: pagination.pageSize - pageRegions.length }, (_, i) => (
                      <tr key={`spacer-${i}`} aria-hidden="true">
                        <td colSpan={COL_COUNT} className="py-2 text-sm">&nbsp;</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
              <Pagination
                page={pagination.page}
                totalPages={pagination.totalPages}
                pageSize={pagination.pageSize}
                totalItems={sortedRegions.length}
                onNextPage={pagination.nextPage}
                onPrevPage={pagination.prevPage}
                onFirstPage={pagination.firstPage}
                onLastPage={pagination.lastPage}
                onSetPageSize={pagination.setPageSize}
              />
            </div>
          )}

          {!loading && regions.length === 0 && (
            <p className="text-sm text-plex-text-muted">No regions configured. Add one to get started.</p>
          )}
        </>
      )}

      {/* Add / Edit mode */}
      {(mode === 'add' || mode === 'edit') && (
        <div className="flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">
            {mode === 'add' ? 'Add New Region' : `Edit Region: ${editingRegion?.name}`}
          </p>

          <div>
            <label htmlFor="region-name" className="block text-xs text-plex-text-secondary mb-1">
              Region name
            </label>
            <input
              id="region-name"
              type="text"
              className={inputClass}
              placeholder="e.g. Northumberland"
              value={formName}
              onChange={(e) => setFormName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleSave(); }}
              data-testid="region-name-input"
            />
          </div>

          {mode === 'edit' && (
            <fieldset className="flex flex-col gap-2" data-testid="region-base-fields">
              <legend className="block text-xs text-plex-text-secondary mb-1">
                Base town — the origin the Plan tab can plan from
              </legend>
              <p className="text-xs text-plex-text-muted">
                The town a visitor would stay in, not the region&apos;s centre — a centroid is
                often offshore, and every drive time would then measure a journey nobody can make.
                Leave all three blank and the region cannot be an origin. Changing the coordinates
                discards this region&apos;s stored drive times; the nightly job recalculates them.
              </p>
              <input
                id="region-base-name"
                type="text"
                className={inputClass}
                placeholder="Base town, e.g. Keswick"
                aria-label="Base town name"
                value={formBaseName}
                onChange={(e) => setFormBaseName(e.target.value)}
                data-testid="region-base-name-input"
              />
              <div className="flex gap-2">
                <input
                  id="region-base-lat"
                  type="text"
                  inputMode="decimal"
                  className={inputClass}
                  placeholder="Latitude, e.g. 54.6013"
                  aria-label="Base latitude"
                  value={formBaseLat}
                  onChange={(e) => setFormBaseLat(e.target.value)}
                  data-testid="region-base-lat-input"
                />
                <input
                  id="region-base-lon"
                  type="text"
                  inputMode="decimal"
                  className={inputClass}
                  placeholder="Longitude, e.g. -3.1347"
                  aria-label="Base longitude"
                  value={formBaseLon}
                  onChange={(e) => setFormBaseLon(e.target.value)}
                  data-testid="region-base-lon-input"
                />
              </div>
            </fieldset>
          )}

          {error && <p className="text-xs text-red-400">{error}</p>}

          <div className="flex justify-between">
            <button className="btn-secondary text-sm" onClick={handleCancel}>
              Cancel
            </button>
            <button
              className="btn-primary text-sm"
              onClick={handleSave}
              disabled={saving || !formName.trim()}
              data-testid="save-region-btn"
            >
              {saving ? 'Saving...' : 'Save'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
