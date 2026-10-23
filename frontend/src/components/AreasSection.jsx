import React, { useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import {
  fetchAreas, createArea, updateArea, setAreaLocations, deleteArea,
} from '../api/areaApi.js';
import { parseAliases, formatAliases, errorText } from '../utils/aliasText.js';

const inputClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-2 py-1 text-xs text-plex-text placeholder-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold';

/**
 * The "Areas" section of the location management view (admin only): the named areas Ask understands, such as
 * "Hadrian's Wall". Each area has a name, other names for it and a set of member locations.
 *
 * <p>Names and members are saved by two separate requests with two separate buttons, because the server keeps
 * them apart: the membership endpoint is the only writer of an area's locations, so renaming can never touch
 * the members. Every error shown is the server's own text, which names a clash.
 *
 * @param {object} props
 * @param {Array<{id: number, name: string, enabled?: boolean}>} props.locations every location, for the picker
 */
export default function AreasSection({ locations }) {
  const [areas, setAreas] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);
  const [busy, setBusy] = useState(false);

  // The name/alias form (create and rename share it) and the member picker.
  const [nameText, setNameText] = useState('');
  const [aliasText, setAliasText] = useState('');
  const [members, setMembers] = useState(() => new Set());
  const [memberFilter, setMemberFilter] = useState('');

  useEffect(() => {
    let cancelled = false;
    fetchAreas()
      .then((data) => { if (!cancelled) setAreas(data); })
      .catch(() => { if (!cancelled) setLoadError('Could not load areas.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const sortedLocations = useMemo(
    () => [...locations].sort((a, b) => a.name.localeCompare(b.name)),
    [locations],
  );
  const shownLocations = useMemo(() => {
    const needle = memberFilter.trim().toLowerCase();
    return needle ? sortedLocations.filter((l) => l.name.toLowerCase().includes(needle)) : sortedLocations;
  }, [sortedLocations, memberFilter]);

  function replaceArea(saved) {
    setAreas((prev) => prev.map((a) => (a.id === saved.id ? saved : a)));
  }

  function closeForms() {
    setCreating(false);
    setEditingId(null);
    setConfirmDeleteId(null);
    setError('');
    setMemberFilter('');
  }

  function startCreate() {
    closeForms();
    setCreating(true);
    setNameText('');
    setAliasText('');
  }

  function startEdit(area) {
    closeForms();
    setEditingId(area.id);
    setNameText(area.name);
    setAliasText(formatAliases(area.aliases));
    setMembers(new Set(area.locationIds));
  }

  async function handleCreate() {
    setBusy(true);
    setError('');
    try {
      const saved = await createArea({ name: nameText, aliases: parseAliases(aliasText) });
      setAreas((prev) => [...prev, saved]);
      setCreating(false);
    } catch (err) {
      setError(errorText(err, 'Failed to create the area.'));
    } finally {
      setBusy(false);
    }
  }

  async function handleSaveNames() {
    setBusy(true);
    setError('');
    try {
      replaceArea(await updateArea(editingId, { name: nameText, aliases: parseAliases(aliasText) }));
    } catch (err) {
      setError(errorText(err, 'Failed to save the names.'));
    } finally {
      setBusy(false);
    }
  }

  async function handleSaveMembers() {
    setBusy(true);
    setError('');
    try {
      replaceArea(await setAreaLocations(editingId, [...members]));
    } catch (err) {
      setError(errorText(err, 'Failed to save the members.'));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(id) {
    setBusy(true);
    setError('');
    try {
      await deleteArea(id);
      setAreas((prev) => prev.filter((a) => a.id !== id));
      setConfirmDeleteId(null);
      if (editingId === id) setEditingId(null);
    } catch (err) {
      setError(errorText(err, 'Failed to delete the area.'));
    } finally {
      setBusy(false);
    }
  }

  function toggleMember(id) {
    setMembers((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function renderNameFields(prefix) {
    return (
      <div className="flex flex-col gap-2">
        <div>
          <label htmlFor={`${prefix}-name`} className="block text-xs text-plex-text-secondary mb-1">Area name</label>
          <input
            id={`${prefix}-name`}
            type="text"
            className={inputClass}
            value={nameText}
            onChange={(e) => setNameText(e.target.value)}
            placeholder="e.g. Hadrian's Wall"
            data-testid="area-name-input"
          />
        </div>
        <div>
          <label htmlFor={`${prefix}-aliases`} className="block text-xs text-plex-text-secondary mb-1">
            Also known as (comma-separated, each at least two words)
          </label>
          <input
            id={`${prefix}-aliases`}
            type="text"
            className={inputClass}
            value={aliasText}
            onChange={(e) => setAliasText(e.target.value)}
            placeholder="e.g. Roman Wall, Hadrian Wall"
            data-testid="area-aliases-input"
          />
        </div>
      </div>
    );
  }

  return (
    <section className="flex flex-col gap-3 border-t border-plex-border pt-4" data-testid="areas-section">
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-plex-text">Areas</p>
          <p className="text-xs text-plex-text-muted">
            Named groups of places, such as a landmark, that Ask can answer about.
          </p>
        </div>
        <button
          type="button"
          className="btn-secondary text-xs shrink-0"
          onClick={startCreate}
          data-testid="add-area-btn"
        >
          + Add Area
        </button>
      </div>

      {loading && <p className="text-sm text-plex-text-muted animate-pulse">Loading areas...</p>}
      {loadError && <p className="text-xs text-red-400" data-testid="areas-load-error">{loadError}</p>}

      {creating && (
        <div className="border border-plex-border rounded p-3 flex flex-col gap-2" data-testid="area-create-form">
          {renderNameFields('area-new')}
          {error && <p className="text-xs text-red-400" data-testid="area-error">{error}</p>}
          <div className="flex gap-2 justify-end">
            <button type="button" className="btn-secondary text-xs" onClick={closeForms} disabled={busy}>
              Cancel
            </button>
            <button
              type="button"
              className="btn-primary text-xs"
              onClick={handleCreate}
              disabled={busy}
              data-testid="area-create-btn"
            >
              {busy ? 'Saving...' : 'Create'}
            </button>
          </div>
        </div>
      )}

      {!loading && !loadError && areas.length === 0 && !creating && (
        <p className="text-xs text-plex-text-muted" data-testid="areas-empty">No areas yet.</p>
      )}

      <ul className="flex flex-col gap-2">
        {areas.map((area) => {
          const editing = editingId === area.id;
          return (
            <li
              key={area.id}
              className="border border-plex-border rounded p-3 flex flex-col gap-2"
              data-testid={`area-row-${area.id}`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-sm text-plex-text" data-testid={`area-name-${area.id}`}>{area.name}</p>
                  <p className="text-xs text-plex-text-secondary" data-testid={`area-aliases-${area.id}`}>
                    {area.aliases.length > 0 ? `Also: ${formatAliases(area.aliases)}` : 'No other names'}
                  </p>
                  <p className="text-xs text-plex-text-muted" data-testid={`area-count-${area.id}`}>
                    {area.locationIds.length} {area.locationIds.length === 1 ? 'place' : 'places'}
                  </p>
                </div>
                <div className="flex gap-2 shrink-0">
                  <button
                    type="button"
                    className="btn-secondary text-xs"
                    onClick={() => (editing ? closeForms() : startEdit(area))}
                    data-testid={`area-edit-${area.id}`}
                  >
                    {editing ? 'Close' : 'Edit'}
                  </button>
                  <button
                    type="button"
                    className="btn-secondary text-xs"
                    onClick={() => { closeForms(); setConfirmDeleteId(area.id); }}
                    data-testid={`area-delete-${area.id}`}
                  >
                    Delete
                  </button>
                </div>
              </div>

              {confirmDeleteId === area.id && (
                <div className="flex items-center gap-2 text-xs" data-testid={`area-delete-confirm-${area.id}`}>
                  <span className="text-plex-text">
                    Delete {area.name}? Its places stay; only the area goes.
                  </span>
                  <button type="button" className="btn-secondary text-xs" onClick={() => setConfirmDeleteId(null)}>
                    Keep
                  </button>
                  <button
                    type="button"
                    className="btn-primary text-xs"
                    onClick={() => handleDelete(area.id)}
                    disabled={busy}
                    data-testid={`area-delete-yes-${area.id}`}
                  >
                    Delete area
                  </button>
                </div>
              )}

              {editing && (
                <div className="flex flex-col gap-3" data-testid={`area-editor-${area.id}`}>
                  {renderNameFields(`area-${area.id}`)}
                  <div className="flex justify-end">
                    <button
                      type="button"
                      className="btn-primary text-xs"
                      onClick={handleSaveNames}
                      disabled={busy}
                      data-testid="area-save-names"
                    >
                      Save names
                    </button>
                  </div>

                  <div className="flex flex-col gap-1">
                    <label htmlFor={`area-${area.id}-filter`} className="block text-xs text-plex-text-secondary">
                      Places in this area ({members.size} selected)
                    </label>
                    <input
                      id={`area-${area.id}-filter`}
                      type="text"
                      className={inputClass}
                      value={memberFilter}
                      onChange={(e) => setMemberFilter(e.target.value)}
                      placeholder="Filter places by name"
                      data-testid="area-member-filter"
                    />
                    <ul className="max-h-48 overflow-y-auto border border-plex-border rounded p-1" data-testid="area-member-list">
                      {shownLocations.map((loc) => (
                        <li key={loc.id}>
                          <label className="flex items-center gap-2 text-xs text-plex-text cursor-pointer py-0.5">
                            <input
                              type="checkbox"
                              checked={members.has(loc.id)}
                              onChange={() => toggleMember(loc.id)}
                              data-testid={`area-member-${loc.id}`}
                            />
                            <span>{loc.name}{loc.enabled === false ? ' (disabled)' : ''}</span>
                          </label>
                        </li>
                      ))}
                      {shownLocations.length === 0 && (
                        <li className="text-xs text-plex-text-muted p-1">No places match.</li>
                      )}
                    </ul>
                  </div>
                  <div className="flex justify-end">
                    <button
                      type="button"
                      className="btn-primary text-xs"
                      onClick={handleSaveMembers}
                      disabled={busy}
                      data-testid="area-save-members"
                    >
                      Save members
                    </button>
                  </div>
                  {error && <p className="text-xs text-red-400" data-testid="area-error">{error}</p>}
                </div>
              )}
            </li>
          );
        })}
      </ul>
      {error && !creating && editingId === null && (
        <p className="text-xs text-red-400" data-testid="area-error">{error}</p>
      )}
    </section>
  );
}

AreasSection.propTypes = {
  locations: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.number.isRequired,
    name: PropTypes.string.isRequired,
    enabled: PropTypes.bool,
  })).isRequired,
};
