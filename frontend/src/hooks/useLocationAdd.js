import { useState } from 'react';
import { addLocation, geocodePlace, enrichLocation } from '../api/forecastApi.js';
import {
  ALL_TIDE_TYPES, EMPTY_ENRICHMENT, addPayload, emptyAddForm, planAdd,
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
  const [confirm, setConfirm] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  /** Sets one form field. */
  function set(field, value) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  /** Resets the whole form (opening Add). */
  function reset() {
    setForm(emptyAddForm());
    setGeocoding(false);
    setEnriching(false);
    setConfirm(null);
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
    setGeocoding(true);
    setForm((prev) => ({ ...prev, geocodeError: '', geocodeResult: null, enrichmentData: null }));
    try {
      const result = await geocodePlace(trimmed);
      set('geocodeResult', result);
      // Fire enrichment in background — don't block geocode result
      setEnriching(true);
      enrichLocation(result.lat, result.lon)
        .then((data) => set('enrichmentData', data))
        .catch((err) => {
          console.error('Enrichment failed:', err);
          set('enrichmentData', { ...EMPTY_ENRICHMENT });
        })
        .finally(() => setEnriching(false));
    } catch (err) {
      set('geocodeError', err.message || 'Geocoding failed.');
    } finally {
      setGeocoding(false);
    }
  }

  /** Switches to manual coordinates, carrying the typed place name over as the name. */
  function enterManually() {
    setForm((prev) => ({ ...prev, manualEntry: true, manualName: prev.placeName.trim(), geocodeError: '' }));
  }

  function backToLookup() {
    set('manualEntry', false);
    setError('');
  }

  /** Whether there is enough to add: a lookup result, or a name with both manual coordinates. */
  const canSubmit = form.manualEntry
    ? Boolean(form.manualName.trim() && form.manualLat && form.manualLon)
    : Boolean(form.geocodeResult);

  /** Validates, and holds what is to be confirmed. */
  function review() {
    const plan = planAdd(form);
    if (plan.error) {
      setError(plan.error);
      return;
    }
    if (!plan.confirm) return;
    setError('');
    setConfirm(plan.confirm);
  }

  /** Saves what {@link review} held. Resolves true when it was added. */
  async function confirmSave() {
    setSaving(true);
    setError('');
    try {
      await addLocation(addPayload(confirm));
      await refreshLocations();
      await refreshAreas();
      return true;
    } catch (err) {
      setError(err?.response?.data?.error ?? err.message ?? 'Failed to save location.');
      return false;
    } finally {
      setSaving(false);
    }
  }

  return {
    form, set, reset, geocoding, enriching, confirm, setConfirm, saving, error, setError,
    changeTypes, changeRegion, geocode, enterManually, backToLookup, canSubmit, review, confirmSave,
  };
}
