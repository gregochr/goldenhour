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
 * @param {(bump?: boolean) => Promise<void>} options.refreshAreas re-reads the areas
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

  /** Sets one form field. */
  function set(field, value) {
    setForm((prev) => ({ ...prev, [field]: value }));
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
    setForm((prev) => ({ ...prev, geocodeError: '', geocodeResult: null, enrichmentData: null }));
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
    setForm((prev) => ({ ...prev, manualEntry: true, manualName: prev.placeName.trim(), geocodeError: '' }));
  }

  function backToLookup() {
    set('manualEntry', false);
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
      setErrorField(null);
      return null;
    } finally {
      setSaving(false);
    }
  }

  return {
    form, set, reset, geocoding, enriching, saving, error, errorField, setError,
    changeTypes, changeRegion, geocode, enterManually, backToLookup, canSubmit, submit,
  };
}
