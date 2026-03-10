import React, { useEffect, useOptimistic, useState, useTransition, useMemo } from 'react';
import PropTypes from 'prop-types';
import { fetchRegions, addRegion, updateRegion, setRegionEnabled } from '../api/regionApi.js';
import { fetchLocations } from '../api/forecastApi.js';
import Pagination from './Pagination.jsx';
import usePagination from '../hooks/usePagination.js';

/**
 * Sortable header cell for the regions table.
 *
 * @param {object} props
 * @param {string} props.label - Column header label.
 * @param {string} props.sortKey - Key used for sorting.
 * @param {string} props.currentSortKey - Currently active sort key.
 * @param {'asc'|'desc'} props.currentSortDir - Current sort direction.
 * @param {function} props.onSort - Called with the sort key when clicked.
 */
function SortableHeader({ label, sortKey, currentSortKey, currentSortDir, onSort }) {
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
    </th>
  );
}

SortableHeader.propTypes = {
  label: PropTypes.string.isRequired,
  sortKey: PropTypes.string.isRequired,
  currentSortKey: PropTypes.string.isRequired,
  currentSortDir: PropTypes.string.isRequired,
  onSort: PropTypes.func.isRequired,
};

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
    setError('');
  }

  function handleStartEdit(region) {
    setMode('edit');
    setEditingRegion(region);
    setFormName(region.name);
    setError('');
  }

  function handleCancel() {
    setMode('list');
    setEditingRegion(null);
    setError('');
  }

  async function handleSave() {
    const trimmed = formName.trim();
    if (!trimmed) {
      setError('Region name is required.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      if (mode === 'add') {
        await addRegion({ name: trimmed });
      } else {
        await updateRegion(editingRegion.id, { name: trimmed });
      }
      await refreshRegions();
      handleCancel();
    } catch (err) {
      setError(err?.response?.data?.error ?? err.message ?? 'Failed to save region.');
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
  const COL_COUNT = 5;

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
                    <SortableHeader label="Name" sortKey="name" currentSortKey={sortKey} currentSortDir={sortDir} onSort={handleSort} />
                    <SortableHeader label="Created" sortKey="created" currentSortKey={sortKey} currentSortDir={sortDir} onSort={handleSort} />
                    <SortableHeader label="Status" sortKey="status" currentSortKey={sortKey} currentSortDir={sortDir} onSort={handleSort} />
                    <SortableHeader label="Location Count" sortKey="locationCount" currentSortKey={sortKey} currentSortDir={sortDir} onSort={handleSort} />
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
