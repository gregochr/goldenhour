import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../api/forecastApi.js', () => ({
  addLocation: vi.fn(),
  geocodePlace: vi.fn(),
  enrichLocation: vi.fn(),
}));

import { addLocation, geocodePlace, enrichLocation } from '../api/forecastApi.js';
import useLocationAdd from '../hooks/useLocationAdd.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const ENRICHMENT = { bortleClass: 3, skyBrightnessSqm: 21.4, elevationMetres: 45, gridLat: 55.6, gridLng: -1.7 };

let refreshLocations;
let refreshAreas;

function setup() {
  return renderHook(() => useLocationAdd({ refreshLocations, refreshAreas }));
}

beforeEach(() => {
  vi.resetAllMocks();
  refreshLocations = vi.fn().mockResolvedValue(undefined);
  refreshAreas = vi.fn().mockResolvedValue(undefined);
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => vi.restoreAllMocks());

describe('useLocationAdd form state', () => {
  it('starts as an empty form that cannot be submitted', () => {
    const { result } = setup();
    expect(result.current.form.placeName).toBe('');
    expect(result.current.form.locationTypes).toEqual(['LANDSCAPE']);
    expect(result.current.canSubmit).toBe(false);
    expect(result.current.geocoding).toBe(false);
    expect(result.current.error).toBe('');
  });

  it('sets one field without touching the rest', () => {
    const { result } = setup();
    act(() => result.current.set('placeName', 'Bamburgh'));
    expect(result.current.form.placeName).toBe('Bamburgh');
    expect(result.current.form.locationTypes).toEqual(['LANDSCAPE']);
  });

  it('seeds every tide type when SEASCAPE is first added', () => {
    const { result } = setup();
    act(() => result.current.changeTypes(['LANDSCAPE', 'SEASCAPE']));
    expect(result.current.form.tideTypes).toEqual(['HIGH', 'MID', 'LOW']);
  });

  it('keeps a curated tide preference when another type is added to a coastal site', () => {
    const { result } = setup();
    act(() => result.current.changeTypes(['SEASCAPE']));
    act(() => result.current.set('tideTypes', ['LOW']));
    act(() => result.current.changeTypes(['SEASCAPE', 'WOODLAND']));
    expect(result.current.form.tideTypes).toEqual(['LOW']);
    expect(result.current.form.locationTypes).toEqual(['SEASCAPE', 'WOODLAND']);
  });

  it('clears the tide preference when the site stops being coastal', () => {
    const { result } = setup();
    act(() => result.current.changeTypes(['SEASCAPE']));
    act(() => result.current.changeTypes(['LANDSCAPE']));
    expect(result.current.form.tideTypes).toEqual([]);
  });

  it('keeps an inland site\'s empty tide set when types change between inland types', () => {
    const { result } = setup();
    act(() => result.current.changeTypes(['WOODLAND']));
    expect(result.current.form.tideTypes).toEqual([]);
  });

  it('clears the sub-region when the region changes', () => {
    const { result } = setup();
    act(() => result.current.set('subRegionId', '12'));
    act(() => result.current.changeRegion('3'));
    expect(result.current.form.regionId).toBe('3');
    expect(result.current.form.subRegionId).toBe('');
  });
});

describe('useLocationAdd.geocode', () => {
  it('asks for a place name when none is typed, and does not call the API', async () => {
    const { result } = setup();
    act(() => result.current.set('placeName', '   '));
    await act(async () => { await result.current.geocode(); });
    expect(geocodePlace).not.toHaveBeenCalled();
    expect(result.current.form.geocodeError).toBe('Please enter a place name.');
  });

  it('geocodes the trimmed name, then fills in enrichment in the background', async () => {
    const enrich = deferred();
    geocodePlace.mockResolvedValue({ lat: 55.6, lon: -1.7 });
    enrichLocation.mockReturnValue(enrich.promise);
    const { result } = setup();
    act(() => result.current.set('placeName', '  Bamburgh  '));

    await act(async () => { await result.current.geocode(); });

    expect(geocodePlace).toHaveBeenCalledWith('Bamburgh');
    expect(enrichLocation).toHaveBeenCalledWith(55.6, -1.7);
    expect(result.current.form.geocodeResult).toEqual({ lat: 55.6, lon: -1.7 });
    expect(result.current.geocoding).toBe(false);
    // Add waits for the detected values while the lookup is still enriching.
    expect(result.current.enriching).toBe(true);
    expect(result.current.canSubmit).toBe(false);

    await act(async () => { enrich.resolve(ENRICHMENT); await enrich.promise; });

    expect(result.current.form.enrichmentData).toEqual(ENRICHMENT);
    expect(result.current.enriching).toBe(false);
    expect(result.current.canSubmit).toBe(true);
  });

  it('falls back to an empty enrichment, and logs, when enrichment fails', async () => {
    geocodePlace.mockResolvedValue({ lat: 1, lon: 2 });
    enrichLocation.mockRejectedValue(new Error('enrich down'));
    const { result } = setup();
    act(() => result.current.set('placeName', 'Somewhere'));

    await act(async () => { await result.current.geocode(); });
    await act(async () => { await Promise.resolve(); });

    expect(console.error).toHaveBeenCalledWith('Enrichment failed:', expect.any(Error));
    expect(result.current.form.enrichmentData).toEqual({
      bortleClass: null, skyBrightnessSqm: null, elevationMetres: null, gridLat: null, gridLng: null,
    });
    expect(result.current.enriching).toBe(false);
    expect(result.current.canSubmit).toBe(true);
  });

  it('shows the geocoder\'s own message when the lookup fails', async () => {
    geocodePlace.mockRejectedValue(new Error('No match for that place'));
    const { result } = setup();
    act(() => result.current.set('placeName', 'Nowhere'));

    await act(async () => { await result.current.geocode(); });

    expect(result.current.form.geocodeError).toBe('No match for that place');
    expect(result.current.form.geocodeResult).toBeNull();
    expect(result.current.geocoding).toBe(false);
    expect(enrichLocation).not.toHaveBeenCalled();
  });

  it('uses a generic message when the failure has none', async () => {
    geocodePlace.mockRejectedValue({});
    const { result } = setup();
    act(() => result.current.set('placeName', 'Nowhere'));
    await act(async () => { await result.current.geocode(); });
    expect(result.current.form.geocodeError).toBe('Geocoding failed.');
  });

  it('drops the reply of a lookup that a reset has since made stale', async () => {
    const lookup = deferred();
    geocodePlace.mockReturnValue(lookup.promise);
    const { result } = setup();
    act(() => result.current.set('placeName', 'Bamburgh'));

    let pending;
    act(() => { pending = result.current.geocode(); });
    expect(result.current.geocoding).toBe(true);

    act(() => result.current.reset());
    await act(async () => { lookup.resolve({ lat: 1, lon: 2 }); await pending; });

    expect(result.current.form.geocodeResult).toBeNull();
    expect(enrichLocation).not.toHaveBeenCalled();
    expect(result.current.geocoding).toBe(false);
  });

  it('drops a stale failure too', async () => {
    const lookup = deferred();
    geocodePlace.mockReturnValue(lookup.promise);
    const { result } = setup();
    act(() => result.current.set('placeName', 'Bamburgh'));

    let pending;
    act(() => { pending = result.current.geocode(); });
    act(() => result.current.reset());
    await act(async () => { lookup.reject(new Error('late failure')); await pending; });

    expect(result.current.form.geocodeError).toBe('');
  });

  it('drops a stale enrichment reply and its failure when a newer lookup has started', async () => {
    const oldEnrich = deferred();
    geocodePlace.mockResolvedValueOnce({ lat: 1, lon: 1 }).mockResolvedValueOnce({ lat: 2, lon: 2 });
    const newEnrich = deferred();
    enrichLocation.mockReturnValueOnce(oldEnrich.promise).mockReturnValueOnce(newEnrich.promise);
    const { result } = setup();
    act(() => result.current.set('placeName', 'A'));
    await act(async () => { await result.current.geocode(); });
    await act(async () => { await result.current.geocode(); });

    await act(async () => { oldEnrich.reject(new Error('old failed')); await oldEnrich.promise.catch(() => {}); });
    expect(result.current.form.enrichmentData).toBeNull();
    expect(result.current.enriching).toBe(true);

    await act(async () => { newEnrich.resolve(ENRICHMENT); await newEnrich.promise; });
    expect(result.current.form.enrichmentData).toEqual(ENRICHMENT);
  });

  it('drops a stale successful enrichment reply', async () => {
    const oldEnrich = deferred();
    geocodePlace.mockResolvedValue({ lat: 1, lon: 1 });
    enrichLocation.mockReturnValue(oldEnrich.promise);
    const { result } = setup();
    act(() => result.current.set('placeName', 'A'));
    await act(async () => { await result.current.geocode(); });

    act(() => result.current.reset());
    await act(async () => { oldEnrich.resolve(ENRICHMENT); await oldEnrich.promise; });

    expect(result.current.form.enrichmentData).toBeNull();
    expect(result.current.enriching).toBe(false);
  });
});

describe('useLocationAdd manual entry', () => {
  it('carries the typed place name over as the manual name, and clears a geocode error', () => {
    const { result } = setup();
    act(() => {
      result.current.set('placeName', '  Hidden spot ');
      result.current.set('geocodeError', 'No match');
    });
    act(() => result.current.enterManually());
    expect(result.current.form.manualEntry).toBe(true);
    expect(result.current.form.manualName).toBe('Hidden spot');
    expect(result.current.form.geocodeError).toBe('');
  });

  it('can be submitted only with a name and both coordinates', () => {
    const { result } = setup();
    act(() => result.current.enterManually());
    expect(result.current.canSubmit).toBe(false);
    act(() => result.current.set('manualName', 'Spot'));
    act(() => result.current.set('manualLat', '55.1'));
    expect(result.current.canSubmit).toBe(false);
    act(() => result.current.set('manualLon', '-1.5'));
    expect(result.current.canSubmit).toBe(true);
    act(() => result.current.set('manualName', '   '));
    expect(result.current.canSubmit).toBe(false);
  });

  it('goes back to the lookup, clearing any message', async () => {
    const { result } = setup();
    act(() => result.current.enterManually());
    await act(async () => { await result.current.submit(); });
    expect(result.current.error).toBe('Name is required.');
    expect(result.current.errorField).toBe('name');

    act(() => result.current.backToLookup());

    expect(result.current.form.manualEntry).toBe(false);
    expect(result.current.error).toBe('');
    expect(result.current.errorField).toBeNull();
  });
});

describe('useLocationAdd.submit', () => {
  it('refuses with the validation message and field, and sends nothing', async () => {
    const { result } = setup();
    act(() => result.current.enterManually());
    act(() => {
      result.current.set('manualName', 'Spot');
      result.current.set('manualLat', '95');
      result.current.set('manualLon', '-1.5');
    });
    let outcome;
    await act(async () => { outcome = await result.current.submit(); });

    expect(outcome).toBeNull();
    expect(result.current.errorField).toBe('lat');
    expect(result.current.error).not.toBe('');
    expect(addLocation).not.toHaveBeenCalled();
  });

  it('requires a tide type for a seascape', async () => {
    const { result } = setup();
    act(() => result.current.changeTypes(['SEASCAPE']));
    act(() => result.current.set('tideTypes', []));
    let outcome;
    await act(async () => { outcome = await result.current.submit(); });
    expect(outcome).toBeNull();
    expect(result.current.errorField).toBe('tides');
  });

  it('does nothing, quietly, when there is no lookup result yet', async () => {
    const { result } = setup();
    let outcome;
    await act(async () => { outcome = await result.current.submit(); });
    expect(outcome).toBeNull();
    expect(result.current.error).toBe('');
    expect(addLocation).not.toHaveBeenCalled();
    expect(result.current.saving).toBe(false);
  });

  it('adds a looked-up place with its enrichment, then re-reads locations and areas, and returns the reply', async () => {
    geocodePlace.mockResolvedValue({ lat: 55.6, lon: -1.7 });
    enrichLocation.mockResolvedValue(ENRICHMENT);
    addLocation.mockResolvedValue({ id: 77 });
    const { result } = setup();
    act(() => result.current.set('placeName', 'Bamburgh'));
    await act(async () => { await result.current.geocode(); });
    await act(async () => { await Promise.resolve(); });
    act(() => result.current.set('regionId', '4'));

    let outcome;
    await act(async () => { outcome = await result.current.submit(); });

    expect(outcome).toEqual({ id: 77 });
    expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Bamburgh', lat: 55.6, lon: -1.7, regionId: 4, bortleClass: 3, elevationMetres: 45,
    }));
    expect(refreshLocations).toHaveBeenCalledTimes(1);
    expect(refreshAreas).toHaveBeenCalledTimes(1);
    expect(result.current.saving).toBe(false);
    expect(result.current.error).toBe('');
  });

  it('adds manual coordinates with null enrichment', async () => {
    addLocation.mockResolvedValue({ id: 5 });
    const { result } = setup();
    act(() => result.current.enterManually());
    act(() => {
      result.current.set('manualName', ' Spot ');
      result.current.set('manualLat', '55.1');
      result.current.set('manualLon', '-1.5');
    });
    await act(async () => { await result.current.submit(); });
    expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Spot', lat: 55.1, lon: -1.5, bortleClass: null, gridLat: null,
    }));
  });

  it('returns an empty object when the server replies with nothing', async () => {
    addLocation.mockResolvedValue(undefined);
    const { result } = setup();
    act(() => result.current.enterManually());
    act(() => {
      result.current.set('manualName', 'Spot');
      result.current.set('manualLat', '55.1');
      result.current.set('manualLon', '-1.5');
    });
    let outcome;
    await act(async () => { outcome = await result.current.submit(); });
    expect(outcome).toEqual({});
  });

  it('shows the server\'s refusal, falls back to the message, then to a generic line', async () => {
    const { result } = setup();
    act(() => result.current.enterManually());
    act(() => {
      result.current.set('manualName', 'Spot');
      result.current.set('manualLat', '55.1');
      result.current.set('manualLon', '-1.5');
    });

    addLocation.mockRejectedValueOnce({ response: { data: { error: 'Duplicate location' } }, message: 'x' });
    let outcome;
    await act(async () => { outcome = await result.current.submit(); });
    expect(outcome).toBeNull();
    expect(result.current.error).toBe('Duplicate location');
    expect(result.current.errorField).toBeNull();
    expect(result.current.saving).toBe(false);
    expect(refreshLocations).not.toHaveBeenCalled();

    addLocation.mockRejectedValueOnce(new Error('Network Error'));
    await act(async () => { await result.current.submit(); });
    expect(result.current.error).toBe('Network Error');

    addLocation.mockRejectedValueOnce({});
    await act(async () => { await result.current.submit(); });
    expect(result.current.error).toBe('Failed to save location.');
  });

  it('lets the page set its own message through setError', () => {
    const { result } = setup();
    act(() => result.current.setError('Something else'));
    expect(result.current.error).toBe('Something else');
  });
});

describe('useLocationAdd.reset', () => {
  it('returns the whole form to empty and clears every message', async () => {
    geocodePlace.mockRejectedValue(new Error('nope'));
    const { result } = setup();
    act(() => result.current.set('placeName', 'X'));
    await act(async () => { await result.current.geocode(); });
    act(() => result.current.changeTypes(['SEASCAPE']));
    act(() => result.current.setError('old'));

    act(() => result.current.reset());

    expect(result.current.form.placeName).toBe('');
    expect(result.current.form.geocodeError).toBe('');
    expect(result.current.form.locationTypes).toEqual(['LANDSCAPE']);
    expect(result.current.error).toBe('');
    expect(result.current.errorField).toBeNull();
    expect(result.current.geocoding).toBe(false);
    expect(result.current.enriching).toBe(false);
  });
});
