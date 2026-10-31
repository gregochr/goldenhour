import { useCallback, useRef, useState } from 'react';
import { updateLocation, fetchValleyFloorCandidates, fetchValleyFloorCell } from '../api/forecastApi.js';
import { setLocationAliases } from '../api/areaApi.js';
import { parseAliases, formatAliases } from '../utils/aliasText.js';
import {
  applyEditChange, buildEditValues, planEditSave, withSavedBaselines,
} from '../utils/locationEdit.js';

/**
 * The state and logic of editing one location: the form values and their baselines, the field-change rules, the
 * valley-floor lookups (guarded by a race token), the save sequence and its partial-failure message.
 *
 * <p>The save sequence (kept exactly as the inline editor had it): {@code updateLocation} with only the changed
 * height, tide-point and sub-region keys, then {@code refreshAreas()}, then {@code setLocationAliases} only if the
 * field moved off the text it started with. If that last write fails, the location is saved: every baseline moves to
 * what the form holds (a second save resends nothing) and the message says only the other names were not.
 *
 * <p>A form opened before the areas or the other names landed starts that part when it does ("late fill"): it was
 * disabled, so nothing the admin typed can be lost, and the baseline is set then, never read as "cleared".
 *
 * @param {object} options
 * @param {Array<object>|null} options.areas every area, null until read
 * @param {Array<object>} options.subRegions the SUB_REGION areas
 * @param {Object|null} options.aliasesByLocation other names per location id, null until read
 * @param {Function} options.setAliasesByLocation sets that map
 * @param {() => Promise<void>} options.refreshLocations re-reads the locations
 * @param {(bump?: boolean) => Promise<void>} options.refreshAreas re-reads the areas
 * @param {() => void} options.onLocationsChanged called after a save (and after an other-names failure)
 */
export default function useLocationEditor({
  areas, subRegions, aliasesByLocation, setAliasesByLocation, refreshLocations, refreshAreas, onLocationsChanged,
}) {
  const [editingId, setEditingId] = useState(null);
  const [values, setValues] = useState(null);
  const [error, setError] = useState('');
  const [errorField, setErrorField] = useState(null);
  const [saving, setSaving] = useState(false);
  const [aliasDetail, setAliasDetail] = useState('');
  // Identifies the latest valley-floor request of the open editor. Bumped on open, Cancel, Clear and every
  // new request, so a reply that no longer belongs to the editor on screen is dropped.
  const floorToken = useRef(0);

  // Late fill, as React's adjust-state-while-rendering pattern: an editor opened before the areas or the names
  // landed starts that field now (and its baseline).
  if (values && !values.subRegionsLoaded && areas !== null) {
    const current = subRegions.find((a) => a.locationIds.includes(values.locId));
    const id = current ? String(current.id) : '';
    setValues({ ...values, subRegionId: id, subRegionBaseline: id, subRegionsLoaded: true });
  } else if (values && !values.aliasesLoaded && aliasesByLocation != null) {
    const text = formatAliases(aliasesByLocation[values.locId]);
    setValues({ ...values, aliases: text, aliasesBaseline: text, aliasesLoaded: true });
  }

  const start = useCallback((loc) => {
    floorToken.current += 1;
    setEditingId(loc.id);
    setValues(buildEditValues(loc, { subRegions, areasLoaded: areas !== null, aliasesByLocation }));
    setError('');
    setErrorField(null);
    setAliasDetail('');
  }, [subRegions, areas, aliasesByLocation]);

  /**
   * Drops the draft without touching the race token: safe while rendering (the view calls it when the selection
   * moves, which is state adjusted during render). A reply still in flight finds no draft and is dropped, and the
   * next {@link start} bumps the token anyway.
   */
  const discard = useCallback(() => {
    setEditingId(null);
    setValues(null);
    setError('');
    setErrorField(null);
    setAliasDetail('');
  }, []);

  const cancel = useCallback(() => {
    floorToken.current += 1;
    discard();
  }, [discard]);

  const change = useCallback((field, value) => {
    setValues((prev) => applyEditChange(prev, field, value, subRegions));
  }, [subRegions]);

  /** Sets fields with no change rule (the light and tide toggles). */
  const patch = useCallback((fields) => {
    setValues((prev) => ({ ...prev, ...fields }));
  }, []);

  async function suggestFloor() {
    const token = ++floorToken.current;
    const lat = parseFloat(values.lat);
    const lon = parseFloat(values.lon);
    setValues((prev) => ({ ...prev, floorBusy: true, floorStatus: '' }));
    try {
      const result = await fetchValleyFloorCandidates(lat, lon);
      if (token !== floorToken.current) return;
      const list = result?.candidates ?? [];
      setValues((prev) => (prev ? {
        ...prev,
        floorBusy: false,
        floorCandidates: list,
        floorStatus: list.length === 0 ? 'No valley floor 30 m below within 5 km' : '',
      } : prev));
    } catch (err) {
      if (token !== floorToken.current) return;
      setValues((prev) => (prev ? {
        ...prev,
        floorBusy: false,
        floorStatus: err?.response?.data?.error ?? 'Could not look up valley floors; try again shortly.',
      } : prev));
    }
  }

  async function pickFloor(candidate) {
    const token = ++floorToken.current;
    setValues((prev) => ({
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
      setValues((prev) => (prev ? { ...prev, floorBusy: false, floorCell: cell } : prev));
    } catch (err) {
      if (token !== floorToken.current) return;
      setValues((prev) => (prev ? {
        ...prev,
        floorBusy: false,
        floorStatus: err?.response?.data?.error ?? 'Could not look up the model cell for that point.',
      } : prev));
    }
  }

  function changeFloorHeight(value) {
    // Drops a cell reply still in flight for the previous height.
    floorToken.current += 1;
    setValues((prev) => ({ ...prev, floorMetres: value, floorCell: null, floorBusy: false }));
  }

  function clearFloor() {
    floorToken.current += 1;
    setValues((prev) => ({
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

  function clearTidePoint() {
    setValues((prev) => ({ ...prev, tideLat: '', tideLng: '' }));
  }

  /**
   * Validates and saves. Resolves true when the edit is fully saved (the editor has closed), false otherwise
   * (a validation message, a refusal, or the other-names failure, which leaves the editor open).
   */
  async function save() {
    const plan = planEditSave(values);
    if (plan.error) {
      setError(plan.error);
      setErrorField(plan.field);
      return false;
    }
    setSaving(true);
    setError('');
    setErrorField(null);
    setAliasDetail('');
    try {
      await updateLocation(editingId, plan.payload);
      // The region and sub-region may both have moved memberships: read them again.
      await refreshAreas();
      // Other names are a separate write, AFTER the location's own save succeeds, and only when the field
      // was edited: an unedited field must never overwrite names it may not have seen.
      // The comparison is against the text the field STARTED with (recorded when the editor opened, or when
      // the names landed), never the live map: an editor opened before the load must not read as "cleared".
      if (values.aliasesLoaded && values.aliases !== values.aliasesBaseline) {
        try {
          const savedAliases = await setLocationAliases(editingId, parseAliases(values.aliases));
          setAliasesByLocation((prev) => ({ ...prev, [editingId]: savedAliases }));
        } catch (aliasErr) {
          await refreshLocations();
          onLocationsChanged();
          setValues((prev) => (prev ? withSavedBaselines(prev) : prev));
          setError('Location saved; its other names were not');
          setErrorField('aliases');
          setAliasDetail(aliasErr?.response?.data?.error ?? '');
          return false;
        }
      }
      await refreshLocations();
      cancel();
      onLocationsChanged();
      return true;
    } catch (err) {
      setError(err?.response?.data?.error ?? err.message ?? 'Failed to save location.');
      setErrorField(null);
      return false;
    } finally {
      setSaving(false);
    }
  }

  return {
    editingId, values, error, errorField, aliasDetail, saving,
    start, cancel, discard, change, patch, suggestFloor, pickFloor, changeFloorHeight, clearFloor, clearTidePoint, save,
  };
}
