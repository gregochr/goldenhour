import { useRef, useState } from 'react';
import { addLocation, geocodePlace, enrichLocation } from '../api/forecastApi.js';
import {
  ALL_TIDE_TYPES, EMPTY_ENRICHMENT, emptyAddForm, planAdd,
} from '../utils/locationEdit.js';

/**
 * The state and logic of adding a location: the place lookup (geocode, then enrichment in the background), manual
 * coordinates, the type, light, tide and region choices, validation, and the save.
 *
 * @param {object} options
 * @param {() => Promise<void>} options.refreshLocations re-reads the locations
 * @param {() => Promise<void>} options.refreshAreas re-reads the areas
 */
export default function useLocationAdd({ refreshLocations, refreshAreas }) {
  const [form, setForm] = useState(emptyAddForm);
  const [geocoding, setGeocoding] = useState(false);
  const [enriching, setEnriching] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  // The form field a validation message belongs to ('name', 'lat', 'lon', 'tides'), or null for a server refusal.
  const [errorField, setErrorField] = useState(null);
  // Identifies the latest lookup: a geocode or enrichment reply for an older one (or for a form since reset) is dropped.
  const lookupToken = useRef(0);

  /** The fields a drive point belongs to: it was picked for one pin, so a different pin drops it. */
  const NO_DRIVE_POINT = { drivePoint: null, drivePointRequired: false };

  /** Drops a drive-point refusal's message with the point it was about. */
  function clearDrivePointError() {
    if (errorField === 'drivePoint') {
      setError('');
      setErrorField(null);
    }
  }

  /** Sets one form field. Typing a manual coordinate moves the pin, which drops the drive point picked for the old one. */
  function set(field, value) {
    const movesPin = field === 'manualLat' || field === 'manualLon';
    setForm((prev) => ({ ...prev, [field]: value, ...(movesPin ? NO_DRIVE_POINT : {}) }));
    if (movesPin) clearDrivePointError();
  }

  /** Picks (or, with null, clears) the drive point. Picking one ends "required": the refusal it answered is spent. */
  function setDrivePoint(point) {
    setForm((prev) => ({ ...prev, drivePoint: point, drivePointRequired: point ? false : prev.drivePointRequired }));
    if (point) clearDrivePointError();
  }

  /** Resets the whole form (opening Add). */
  function reset() {
    lookupToken.current += 1;
    setErrorField(null);
    setForm(emptyAddForm());
    setGeocoding(false);
    setEnriching(false);
    setError('');
  }

  // Seed tide preferences on an actual coastal transition, not on every type change — adding
  // WOODLAND to a coastal site must not reset a curated tide preference.
  function changeTypes(next) {
    setForm((prev) => {
      const wasCoastal = prev.locationTypes.includes('SEASCAPE');
      const isCoastal = next.includes('SEASCAPE');
      let tideTypes = prev.tideTypes;
      if (isCoastal && !wasCoastal) tideTypes = [...ALL_TIDE_TYPES];
      else if (!isCoastal) tideTypes = [];
      return { ...prev, locationTypes: next, tideTypes };
    });
  }

  function changeRegion(regionId) {
    setForm((prev) => ({ ...prev, regionId, subRegionId: '' }));
  }

  async function geocode() {
    const trimmed = form.placeName.trim();
    if (!trimmed) {
      set('geocodeError', 'Please enter a place name.');
      return;
    }
    const token = ++lookupToken.current;
    setGeocoding(true);
    setEnriching(false);
    setForm((prev) => ({
      ...prev, geocodeError: '', geocodeResult: null, enrichmentData: null, ...NO_DRIVE_POINT,
    }));
    clearDrivePointError();
    try {
      const result = await geocodePlace(trimmed);
      if (token !== lookupToken.current) return;
      set('geocodeResult', result);
      // Fire enrichment in background — don't block geocode result
      setEnriching(true);
      enrichLocation(result.lat, result.lon)
        .then((data) => { if (token === lookupToken.current) set('enrichmentData', data); })
        .catch((err) => {
          console.error('Enrichment failed:', err);
          if (token === lookupToken.current) set('enrichmentData', { ...EMPTY_ENRICHMENT });
        })
        .finally(() => { if (token === lookupToken.current) setEnriching(false); });
    } catch (err) {
      if (token === lookupToken.current) set('geocodeError', err.message || 'Geocoding failed.');
    } finally {
      if (token === lookupToken.current) setGeocoding(false);
    }
  }

  /** Switches to manual coordinates, carrying the typed place name over as the name. */
  function enterManually() {
    setForm((prev) => ({
      ...prev, manualEntry: true, manualName: prev.placeName.trim(), geocodeError: '', ...NO_DRIVE_POINT,
    }));
    clearDrivePointError();
  }

  function backToLookup() {
    setForm((prev) => ({ ...prev, manualEntry: false, ...NO_DRIVE_POINT }));
    setError('');
    setErrorField(null);
  }

  /** Whether there is enough to add: a lookup result, or a name with both manual coordinates. */
  // A lookup's detected values are still on their way while it is enriching, so Add waits for them.
  const canSubmit = form.manualEntry
    ? Boolean(form.manualName.trim() && form.manualLat && form.manualLon)
    : Boolean(form.geocodeResult) && !enriching;

  /**
   * Validates and adds the location, then re-reads the locations and the areas. Resolves with the server's reply
   * (which carries the new id), or null when nothing was added (a message is in {@code error}).
   */
  async function submit() {
    const plan = planAdd(form);
    if (plan.error) {
      setError(plan.error);
      setErrorField(plan.field ?? null);
      return null;
    }
    if (!plan.payload) return null;
    setSaving(true);
    setError('');
    setErrorField(null);
    try {
      const created = await addLocation(plan.payload);
      await refreshLocations();
      await refreshAreas();
      return created ?? {};
    } catch (err) {
      setError(err?.response?.data?.error ?? err.message ?? 'Failed to save location.');
      // 422 NO_ROAD_ROUTE: no road reaches the new place. The sentence belongs to the drive-point field, which
      // becomes required (reach-scoped-card-plan R1c).
      const noRoute = err?.response?.status === 422 && err.response.data?.code === 'NO_ROAD_ROUTE';
      setErrorField(noRoute ? 'drivePoint' : null);
      if (noRoute) set('drivePointRequired', true);
      return null;
    } finally {
      setSaving(false);
    }
  }

  return {
    form, set, setDrivePoint, reset, geocoding, enriching, saving, error, errorField, setError,
    changeTypes, changeRegion, geocode, enterManually, backToLookup, canSubmit, submit,
  };
}
