import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../api/forecastApi.js', () => ({
  updateLocation: vi.fn(),
  fetchValleyFloorCandidates: vi.fn(),
  fetchValleyFloorCell: vi.fn(),
}));
vi.mock('../api/areaApi.js', () => ({ setLocationAliases: vi.fn() }));

import { updateLocation, fetchValleyFloorCandidates, fetchValleyFloorCell } from '../api/forecastApi.js';
import { setLocationAliases } from '../api/areaApi.js';
import useLocationEditor from '../hooks/useLocationEditor.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const LOC = {
  id: 1,
  name: 'Bamburgh',
  lat: 55.6,
  lon: -1.7,
  locationType: ['LANDSCAPE'],
  solarEventType: ['SUNRISE', 'SUNSET'],
  tideType: [],
  region: { id: 2 },
};

const SUB_REGIONS = [
  { id: 30, kind: 'SUB_REGION', name: 'Coast', locationIds: [1] },
  { id: 31, kind: 'SUB_REGION', name: 'Hills', locationIds: [9] },
];

let props;
let setAliasesByLocation;

function setup(overrides = {}) {
  props = {
    areas: SUB_REGIONS,
    subRegions: SUB_REGIONS,
    aliasesByLocation: { 1: ['Castle', 'Bamburgh Castle'] },
    refreshLocations: vi.fn().mockResolvedValue(undefined),
    refreshAreas: vi.fn().mockResolvedValue(undefined),
    onLocationsChanged: vi.fn(),
    ...overrides,
  };
  setAliasesByLocation = vi.fn();
  props.setAliasesByLocation = setAliasesByLocation;
  return renderHook((p) => useLocationEditor(p), { initialProps: props });
}

beforeEach(() => {
  vi.resetAllMocks();
});

describe('opening, changing and closing the editor', () => {
  it('is closed to begin with', () => {
    const { result } = setup();
    expect(result.current.editingId).toBeNull();
    expect(result.current.values).toBeNull();
  });

  it('opens on a location with its current sub-region and other names', () => {
    const { result } = setup();
    act(() => result.current.start(LOC));
    expect(result.current.editingId).toBe(1);
    expect(result.current.values).toMatchObject({
      name: 'Bamburgh', regionId: '2', subRegionId: '30', aliases: 'Castle, Bamburgh Castle',
    });
  });

  it('changes a field through the edit rules and patches plain fields', () => {
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.change('name', 'Bamburgh Beach'));
    act(() => result.current.patch({ mistProne: true }));
    expect(result.current.values.name).toBe('Bamburgh Beach');
    expect(result.current.values.mistProne).toBe(true);
  });

  it('clears the tide point', () => {
    const { result } = setup();
    act(() => result.current.start({ ...LOC, locationType: ['SEASCAPE'], tideType: ['HIGH'], tideLat: 55.7, tideLng: -1.6 }));
    expect(result.current.values.tideLat).not.toBe('');
    act(() => result.current.clearTidePoint());
    expect(result.current.values.tideLat).toBe('');
    expect(result.current.values.tideLng).toBe('');
  });

  it('cancel closes the editor and wipes every message', async () => {
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.change('name', ''));
    await act(async () => { await result.current.save(); });
    expect(result.current.error).not.toBe('');

    act(() => result.current.cancel());

    expect(result.current.editingId).toBeNull();
    expect(result.current.values).toBeNull();
    expect(result.current.error).toBe('');
    expect(result.current.errorField).toBeNull();
    expect(result.current.aliasDetail).toBe('');
    expect(result.current.saving).toBe(false);
  });

  it('discard closes the editor the same way', () => {
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.discard());
    expect(result.current.editingId).toBeNull();
    expect(result.current.values).toBeNull();
  });
});

describe('late fill', () => {
  it('starts the sub-region field, and its baseline, when the areas land after the editor opened', () => {
    const { result, rerender } = setup({ areas: null, subRegions: [] });
    act(() => result.current.start(LOC));
    expect(result.current.values.subRegionsLoaded).toBe(false);
    expect(result.current.values.subRegionId).toBe('');

    rerender({ ...props, areas: SUB_REGIONS, subRegions: SUB_REGIONS });

    expect(result.current.values.subRegionsLoaded).toBe(true);
    expect(result.current.values.subRegionId).toBe('30');
    expect(result.current.values.subRegionBaseline).toBe('30');
  });

  it('leaves the sub-region empty when no sub-region lists the location', () => {
    const { result, rerender } = setup({ areas: null, subRegions: [] });
    act(() => result.current.start({ ...LOC, id: 77 }));
    rerender({ ...props, areas: SUB_REGIONS, subRegions: SUB_REGIONS });
    expect(result.current.values.subRegionsLoaded).toBe(true);
    expect(result.current.values.subRegionId).toBe('');
  });

  it('starts the other-names field, and its baseline, when the names land after the editor opened', () => {
    const { result, rerender } = setup({ aliasesByLocation: null });
    act(() => result.current.start(LOC));
    expect(result.current.values.aliasesLoaded).toBe(false);

    rerender({ ...props, aliasesByLocation: { 1: ['Castle'] } });

    expect(result.current.values.aliasesLoaded).toBe(true);
    expect(result.current.values.aliases).toBe('Castle');
    expect(result.current.values.aliasesBaseline).toBe('Castle');
  });

  it('reads a location with no names as an empty field once the names land', () => {
    const { result, rerender } = setup({ aliasesByLocation: null });
    act(() => result.current.start(LOC));
    rerender({ ...props, aliasesByLocation: {} });
    expect(result.current.values.aliases).toBe('');
    expect(result.current.values.aliasesLoaded).toBe(true);
  });
});

describe('valley-floor lookups', () => {
  const CAND = { metres: 120, lat: 55.5, lng: -1.8 };

  it('lists candidates for the typed coordinates', async () => {
    fetchValleyFloorCandidates.mockResolvedValue({ candidates: [CAND] });
    const { result } = setup();
    act(() => result.current.start(LOC));

    await act(async () => { await result.current.suggestFloor(); });

    expect(fetchValleyFloorCandidates).toHaveBeenCalledWith(55.6, -1.7);
    expect(result.current.values.floorCandidates).toEqual([CAND]);
    expect(result.current.values.floorBusy).toBe(false);
    expect(result.current.values.floorStatus).toBe('');
  });

  it('says so when there is no valley floor, including when the reply has no list', async () => {
    fetchValleyFloorCandidates.mockResolvedValueOnce({ candidates: [] }).mockResolvedValueOnce(undefined);
    const { result } = setup();
    act(() => result.current.start(LOC));

    await act(async () => { await result.current.suggestFloor(); });
    expect(result.current.values.floorStatus).toBe('No valley floor 30 m below within 5 km');

    await act(async () => { await result.current.suggestFloor(); });
    expect(result.current.values.floorCandidates).toEqual([]);
    expect(result.current.values.floorStatus).toBe('No valley floor 30 m below within 5 km');
  });

  it('shows the server\'s error, or a generic one, when the lookup fails', async () => {
    const { result } = setup();
    act(() => result.current.start(LOC));

    fetchValleyFloorCandidates.mockRejectedValueOnce({ response: { data: { error: 'Elevation service down' } } });
    await act(async () => { await result.current.suggestFloor(); });
    expect(result.current.values.floorStatus).toBe('Elevation service down');
    expect(result.current.values.floorBusy).toBe(false);

    fetchValleyFloorCandidates.mockRejectedValueOnce(new Error('x'));
    await act(async () => { await result.current.suggestFloor(); });
    expect(result.current.values.floorStatus).toBe('Could not look up valley floors; try again shortly.');
  });

  it('is busy while the lookup is in flight', async () => {
    const lookup = deferred();
    fetchValleyFloorCandidates.mockReturnValue(lookup.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));

    let pending;
    act(() => { pending = result.current.suggestFloor(); });
    expect(result.current.values.floorBusy).toBe(true);
    await act(async () => { lookup.resolve({ candidates: [] }); await pending; });
    expect(result.current.values.floorBusy).toBe(false);
  });

  it('drops a candidate reply that arrives after Cancel', async () => {
    const lookup = deferred();
    fetchValleyFloorCandidates.mockReturnValue(lookup.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));
    let pending;
    act(() => { pending = result.current.suggestFloor(); });

    act(() => result.current.cancel());
    await act(async () => { lookup.resolve({ candidates: [CAND] }); await pending; });

    expect(result.current.values).toBeNull();
  });

  it('does not resurrect a discarded draft when a reply or failure lands on it', async () => {
    const ok = deferred();
    fetchValleyFloorCandidates.mockReturnValueOnce(ok.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));
    let pending;
    act(() => { pending = result.current.suggestFloor(); });
    act(() => result.current.discard());
    await act(async () => { ok.resolve({ candidates: [CAND] }); await pending; });
    expect(result.current.values).toBeNull();

    const bad = deferred();
    fetchValleyFloorCandidates.mockReturnValueOnce(bad.promise);
    act(() => result.current.start(LOC));
    act(() => { pending = result.current.suggestFloor(); });
    act(() => result.current.discard());
    await act(async () => { bad.reject(new Error('x')); await pending; });
    expect(result.current.values).toBeNull();
  });

  it('drops a candidate failure that arrives after Cancel', async () => {
    const lookup = deferred();
    fetchValleyFloorCandidates.mockReturnValue(lookup.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));
    let pending;
    act(() => { pending = result.current.suggestFloor(); });
    act(() => result.current.cancel());
    await act(async () => { lookup.reject(new Error('x')); await pending; });
    expect(result.current.values).toBeNull();
  });

  it('picks a candidate: sets the height and point at once, then fills in the model cell', async () => {
    const cell = deferred();
    fetchValleyFloorCell.mockReturnValue(cell.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));

    let pending;
    act(() => { pending = result.current.pickFloor(CAND); });
    expect(result.current.values).toMatchObject({
      floorMetres: '120', floorLat: 55.5, floorLng: -1.8, floorCleared: false, floorCell: null, floorBusy: true,
    });
    expect(fetchValleyFloorCell).toHaveBeenCalledWith(55.5, -1.8, 120);

    await act(async () => { cell.resolve({ gridLat: 55.5, gridLng: -1.75 }); await pending; });
    expect(result.current.values.floorCell).toEqual({ gridLat: 55.5, gridLng: -1.75 });
    expect(result.current.values.floorBusy).toBe(false);
  });

  it('shows the server\'s error, or a generic one, when the cell lookup fails', async () => {
    const { result } = setup();
    act(() => result.current.start(LOC));

    fetchValleyFloorCell.mockRejectedValueOnce({ response: { data: { error: 'No cell here' } } });
    await act(async () => { await result.current.pickFloor(CAND); });
    expect(result.current.values.floorStatus).toBe('No cell here');
    expect(result.current.values.floorBusy).toBe(false);

    fetchValleyFloorCell.mockRejectedValueOnce(new Error('x'));
    await act(async () => { await result.current.pickFloor(CAND); });
    expect(result.current.values.floorStatus).toBe('Could not look up the model cell for that point.');
  });

  it('drops a cell reply or failure that a later action has superseded', async () => {
    const cell = deferred();
    fetchValleyFloorCell.mockReturnValueOnce(cell.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));
    let pending;
    act(() => { pending = result.current.pickFloor(CAND); });

    act(() => result.current.changeFloorHeight('150'));
    await act(async () => { cell.resolve({ gridLat: 1 }); await pending; });

    expect(result.current.values.floorMetres).toBe('150');
    expect(result.current.values.floorCell).toBeNull();
    expect(result.current.values.floorBusy).toBe(false);

    const failing = deferred();
    fetchValleyFloorCell.mockReturnValueOnce(failing.promise);
    act(() => { pending = result.current.pickFloor(CAND); });
    act(() => result.current.clearFloor());
    await act(async () => { failing.reject(new Error('late')); await pending; });
    expect(result.current.values.floorStatus).toBe('');
  });

  it('does not resurrect a discarded draft when a cell reply or failure lands on it', async () => {
    const ok = deferred();
    fetchValleyFloorCell.mockReturnValueOnce(ok.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));
    let pending;
    act(() => { pending = result.current.pickFloor(CAND); });
    act(() => result.current.discard());
    await act(async () => { ok.resolve({ gridLat: 1 }); await pending; });
    expect(result.current.values).toBeNull();

    const bad = deferred();
    fetchValleyFloorCell.mockReturnValueOnce(bad.promise);
    act(() => result.current.start(LOC));
    act(() => { pending = result.current.pickFloor(CAND); });
    act(() => result.current.discard());
    await act(async () => { bad.reject(new Error('x')); await pending; });
    expect(result.current.values).toBeNull();
  });

  it('clears the floor, marking it cleared', () => {
    const { result } = setup();
    act(() => result.current.start({ ...LOC, valleyFloorMetres: 90, valleyFloorLat: 55.5, valleyFloorLng: -1.8 }));
    expect(result.current.values.floorMetres).toBe('90');

    act(() => result.current.clearFloor());

    expect(result.current.values).toMatchObject({
      floorMetres: '', floorLat: null, floorLng: null, floorCleared: true, floorCell: null, floorBusy: false,
    });
  });
});

describe('save', () => {
  it('refuses an invalid form with the message and field, and calls nothing', async () => {
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.change('name', '  '));

    let ok;
    await act(async () => { ok = await result.current.save(); });

    expect(ok).toBe(false);
    expect(result.current.errorField).toBe('name');
    expect(result.current.error).not.toBe('');
    expect(updateLocation).not.toHaveBeenCalled();
    expect(result.current.saving).toBe(false);
  });

  it('saves, re-reads areas and locations, tells the page, and closes — without touching unedited names', async () => {
    updateLocation.mockResolvedValue({});
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.change('name', 'Bamburgh Beach'));

    let ok;
    await act(async () => { ok = await result.current.save(); });

    expect(ok).toBe(true);
    expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({ name: 'Bamburgh Beach', regionId: 2 }));
    expect(props.refreshAreas).toHaveBeenCalledTimes(1);
    expect(props.refreshLocations).toHaveBeenCalledTimes(1);
    expect(props.onLocationsChanged).toHaveBeenCalledTimes(1);
    expect(setLocationAliases).not.toHaveBeenCalled();
    expect(result.current.editingId).toBeNull();
    expect(result.current.saving).toBe(false);
  });

  it('sends a sub-region only when it moved off its baseline', async () => {
    updateLocation.mockResolvedValue({});
    const { result } = setup();
    act(() => result.current.start(LOC));
    await act(async () => { await result.current.save(); });
    expect(updateLocation.mock.calls[0][1]).not.toHaveProperty('subRegionId');

    act(() => result.current.start(LOC));
    act(() => result.current.change('subRegionId', '31'));
    await act(async () => { await result.current.save(); });
    expect(updateLocation.mock.calls[1][1]).toMatchObject({ subRegionId: 31 });
  });

  it('writes the other names, after the location, when the field was edited, and updates the map', async () => {
    updateLocation.mockResolvedValue({});
    setLocationAliases.mockResolvedValue(['Castle', 'The Fort']);
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.change('aliases', 'Castle,  The Fort'));

    await act(async () => { await result.current.save(); });

    expect(setLocationAliases).toHaveBeenCalledWith(1, ['Castle', 'The Fort']);
    const updater = setAliasesByLocation.mock.calls[0][0];
    expect(updater({ 5: ['x'] })).toEqual({ 5: ['x'], 1: ['Castle', 'The Fort'] });
    expect(result.current.editingId).toBeNull();
  });

  it('does not write names while they have not loaded, even if the field was touched', async () => {
    updateLocation.mockResolvedValue({});
    const { result } = setup({ aliasesByLocation: null });
    act(() => result.current.start(LOC));
    act(() => result.current.change('aliases', 'Something'));

    await act(async () => { await result.current.save(); });

    expect(setLocationAliases).not.toHaveBeenCalled();
  });

  it('keeps the editor open, with every baseline moved, when only the other names fail', async () => {
    updateLocation.mockResolvedValue({});
    setLocationAliases.mockRejectedValue({ response: { data: { error: 'Alias already used' } } });
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.change('aliases', 'New name'));
    act(() => result.current.change('regionId', '5'));

    let ok;
    await act(async () => { ok = await result.current.save(); });

    expect(ok).toBe(false);
    expect(result.current.error).toBe('Location saved; its other names were not');
    expect(result.current.errorField).toBe('aliases');
    expect(result.current.aliasDetail).toBe('Alias already used');
    expect(result.current.values.regionBaseline).toBe('5');
    expect(result.current.editingId).toBe(1);
    expect(props.refreshLocations).toHaveBeenCalledTimes(1);
    expect(props.onLocationsChanged).toHaveBeenCalledTimes(1);
    expect(result.current.saving).toBe(false);
  });

  it('shows no detail when the other-names failure carries none', async () => {
    updateLocation.mockResolvedValue({});
    setLocationAliases.mockRejectedValue(new Error('x'));
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.change('aliases', 'New name'));
    await act(async () => { await result.current.save(); });
    expect(result.current.aliasDetail).toBe('');
  });

  it('shows the server\'s refusal, then the message, then a generic line, and stays open', async () => {
    const { result } = setup();
    act(() => result.current.start(LOC));

    updateLocation.mockRejectedValueOnce({ response: { data: { error: 'Name already taken' } }, message: 'm' });
    let ok;
    await act(async () => { ok = await result.current.save(); });
    expect(ok).toBe(false);
    expect(result.current.error).toBe('Name already taken');
    expect(result.current.errorField).toBeNull();
    expect(result.current.editingId).toBe(1);
    expect(result.current.saving).toBe(false);
    expect(props.refreshAreas).not.toHaveBeenCalled();

    updateLocation.mockRejectedValueOnce(new Error('Network Error'));
    await act(async () => { await result.current.save(); });
    expect(result.current.error).toBe('Network Error');

    updateLocation.mockRejectedValueOnce({});
    await act(async () => { await result.current.save(); });
    expect(result.current.error).toBe('Failed to save location.');
  });

  it('is saving while the request is in flight', async () => {
    const request = deferred();
    updateLocation.mockReturnValue(request.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));

    let pending;
    act(() => { pending = result.current.save(); });
    expect(result.current.saving).toBe(true);

    await act(async () => { request.resolve({}); await pending; });
    expect(result.current.saving).toBe(false);
  });

  it('writes nothing to the form when Cancel came while the save was in flight', async () => {
    const request = deferred();
    updateLocation.mockReturnValue(request.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));
    let pending;
    let ok;
    act(() => { pending = result.current.save().then((v) => { ok = v; }); });

    act(() => result.current.cancel());
    await act(async () => { request.resolve({}); await pending; });

    expect(ok).toBe(false);
    expect(result.current.values).toBeNull();
    expect(result.current.editingId).toBeNull();
  });

  it('does not close a different row\'s editor when an earlier save finishes', async () => {
    const request = deferred();
    updateLocation.mockReturnValue(request.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));
    let pending;
    act(() => { pending = result.current.save(); });

    act(() => result.current.start({ ...LOC, id: 2, name: 'Craster' }));
    await act(async () => { request.resolve({}); await pending; });

    expect(result.current.editingId).toBe(2);
    expect(result.current.values.name).toBe('Craster');
  });

  it('does not show an old save\'s failure on a newer draft', async () => {
    const request = deferred();
    updateLocation.mockReturnValue(request.promise);
    const { result } = setup();
    act(() => result.current.start(LOC));
    let pending;
    act(() => { pending = result.current.save(); });

    act(() => result.current.start({ ...LOC, id: 2 }));
    await act(async () => { request.reject(new Error('old failure')); await pending; });

    expect(result.current.error).toBe('');
    expect(result.current.editingId).toBe(2);
  });

  it('leaves the newer draft alone when an old save\'s other-names write fails', async () => {
    const request = deferred();
    updateLocation.mockReturnValue(request.promise);
    setLocationAliases.mockRejectedValue(new Error('alias failed'));
    const { result } = setup();
    act(() => result.current.start(LOC));
    act(() => result.current.change('aliases', 'Changed'));
    let pending;
    act(() => { pending = result.current.save(); });

    act(() => result.current.start({ ...LOC, id: 2, name: 'Craster' }));
    await act(async () => { request.resolve({}); await pending; });

    expect(result.current.values.name).toBe('Craster');
    expect(result.current.error).toBe('');
    // The page is still told locations changed: the location itself was saved.
    expect(props.onLocationsChanged).toHaveBeenCalledTimes(1);
  });
});
