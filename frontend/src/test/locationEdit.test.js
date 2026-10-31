import { describe, it, expect } from 'vitest';
import {
  applyEditChange, buildEditValues, emptyAddForm, planAdd, planEditSave, toggleSticky, withSavedBaselines,
} from '../utils/locationEdit.js';

const LOC = {
  id: 1, name: 'Alnmouth', lat: 55.39, lon: -1.613, solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['SEASCAPE'],
  tideType: ['HIGH'], region: { id: 4 }, elevationMetres: 12,
};
const CONTEXT = { subRegions: [], areasLoaded: true, aliasesByLocation: {} };

function form(overrides = {}) {
  return { ...buildEditValues(LOC, CONTEXT), ...overrides };
}

describe('toggleSticky', () => {
  it('adds and removes, and refuses to empty the set', () => {
    expect(toggleSticky(['A'], 'B')).toEqual(['A', 'B']);
    expect(toggleSticky(['A', 'B'], 'A')).toEqual(['B']);
    expect(toggleSticky(['A'], 'A')).toBeNull();
  });
});

describe('planEditSave', () => {
  it('builds the payload with only the keys that changed', () => {
    const plan = planEditSave(form());

    expect(plan.payload).toEqual({
      name: 'Alnmouth', lat: 55.39, lon: -1.613, solarEventTypes: ['SUNRISE', 'SUNSET'], locationTypes: ['SEASCAPE'],
      tideTypes: ['HIGH'], regionId: 4, mistProne: false,
    });
  });

  it.each([
    [{ name: '  ' }, 'name', 'Name cannot be blank.'],
    [{ lat: '91' }, 'lat', 'Latitude must be between -90 and 90.'],
    [{ lat: 'x' }, 'lat', 'Latitude must be between -90 and 90.'],
    [{ lon: '-181' }, 'lon', 'Longitude must be between -180 and 180.'],
    [{ tideTypes: [] }, 'tides', 'Coastal locations require at least one tide preference (High, Mid, or Low).'],
    [{ locationTypes: [] }, 'types', 'A location must have at least one type.'],
    [{ elevation: '9999' }, 'elevation', 'Elevation must be a whole number between -50 and 1,500 m.'],
    [{ elevation: '' }, 'elevation', "Elevation can't be cleared; enter a value"],
    [{ floorLat: 54.5, floorLng: -3.1, floorMetres: '' }, 'floor',
      'Valley floor height must be a whole number between -50 and 1,500 m, or clear the floor.'],
    [{ tideLat: '55.0', tideLng: '' }, 'tidePoint', 'A tide point needs both latitude and longitude, or clear it.'],
    [{ tideLat: '95', tideLng: '1' }, 'tidePoint',
      'Tide point latitude must be between -90 and 90 and longitude between -180 and 180.'],
  ])('refuses %j with the message for %s', (overrides, field, message) => {
    expect(planEditSave(form(overrides))).toEqual({ error: message, field });
  });

  it('reports the first problem in the order the editor has always checked', () => {
    expect(planEditSave(form({ name: '', lat: '95', tideTypes: [] })).field).toBe('name');
    expect(planEditSave(form({ lat: '95', tideTypes: [] })).field).toBe('lat');
    expect(planEditSave(form({ tideTypes: [], elevation: '9999' })).field).toBe('tides');
    expect(planEditSave(form({ elevation: '9999', tideLat: '1', tideLng: '' })).field).toBe('elevation');
  });

  it('ignores a half-blanked tide point when the location is not a seascape', () => {
    const plan = planEditSave(form({ locationTypes: ['LANDSCAPE'], tideTypes: [], tideLat: '55', tideLng: '' }));

    expect(plan.error).toBeUndefined();
    expect(plan.payload).not.toHaveProperty('tideLat');
    expect(plan.payload.tideTypes).toEqual([]);
  });

  it('sends a sub-region only when it moved off its baseline', () => {
    expect(planEditSave(form({ subRegionId: '21', subRegionBaseline: '21' })).payload).not.toHaveProperty('subRegionId');
    expect(planEditSave(form({ subRegionId: '22', subRegionBaseline: '21' })).payload.subRegionId).toBe(22);
    expect(planEditSave(form({ subRegionId: '', subRegionBaseline: '21' })).payload).not.toHaveProperty('subRegionId');
  });
});

describe('applyEditChange', () => {
  const subRegions = [{ id: 21, regionId: 4 }, { id: 31, regionId: 5 }];
  const base = form({ regionId: '4', regionBaseline: '4', subRegionId: '21', subRegionBaseline: '21' });

  it('drops a sub-region that is not in the new region, and restores it when the region goes back', () => {
    const moved = applyEditChange(base, 'regionId', '5', subRegions);
    expect(moved.subRegionId).toBe('');

    const back = applyEditChange(moved, 'regionId', '4', subRegions);
    expect(back.subRegionId).toBe('21');
  });

  it('seeds all three tides on becoming coastal, clears them on leaving, and leaves them alone otherwise', () => {
    const land = form({ locationTypes: ['LANDSCAPE'], tideTypes: [] });
    expect(applyEditChange(land, 'locationTypes', ['LANDSCAPE', 'SEASCAPE'], []).tideTypes).toEqual(['HIGH', 'MID', 'LOW']);
    expect(applyEditChange(form(), 'locationTypes', ['SEASCAPE', 'WOODLAND'], []).tideTypes).toEqual(['HIGH']);
    expect(applyEditChange(form(), 'locationTypes', ['WOODLAND'], []).tideTypes).toEqual([]);
  });

  it('forgets valley-floor candidates when the coordinates change', () => {
    const withCandidates = form({ floorCandidates: [{ lat: 1, lng: 2 }], floorStatus: 'x' });
    const next = applyEditChange(withCandidates, 'lat', '55', []);

    expect(next.floorCandidates).toBeNull();
    expect(next.floorStatus).toBe('');
  });
});

describe('withSavedBaselines', () => {
  it('moves every baseline to what the form holds, so a second save resends nothing', () => {
    const edited = form({
      regionId: '5', subRegionId: '31', elevation: '40', lat: '55.5', lon: '-1.7', tideLat: '55.01', tideLng: '-1.41',
      floorLat: 54.5, floorLng: -3.1, floorMetres: '60',
    });
    const saved = withSavedBaselines(edited);

    expect(saved).toMatchObject({
      regionBaseline: '5', subRegionBaseline: '31', elevationBaseline: 40, latBaseline: 55.5, lonBaseline: -1.7,
      tideBaseline: { lat: 55.01, lng: -1.41 }, floorBaseline: { metres: 60, lat: 54.5, lng: -3.1 }, floorCleared: false,
    });
    const second = planEditSave(saved).payload;
    expect(second).not.toHaveProperty('elevationMetres');
    expect(second).not.toHaveProperty('tideLat');
    expect(second).not.toHaveProperty('valleyFloorMetres');
    expect(second).not.toHaveProperty('subRegionId');
  });
});

describe('planAdd', () => {
  const looked = {
    ...emptyAddForm(), placeName: ' Latrigg ', geocodeResult: { lat: 54.6, lon: -3.1, displayName: 'Latrigg, UK' },
    enrichmentData: { bortleClass: 3, skyBrightnessSqm: 21.7, elevationMetres: 368, gridLat: 54.61, gridLng: -3.12 },
  };

  it('has nothing to add before a lookup or manual coordinates', () => {
    expect(planAdd(emptyAddForm())).toEqual({ pending: true });
  });

  it('builds a looked-up place with what was detected', () => {
    expect(planAdd({ ...looked, mistProne: true, regionId: '4', subRegionId: '21' }).payload).toEqual({
      name: 'Latrigg', lat: 54.6, lon: -3.1, solarEventTypes: ['SUNRISE', 'SUNSET'], locationTypes: ['LANDSCAPE'],
      tideTypes: [], regionId: 4, subRegionId: 21, overlooksWater: false, coastalTidal: false, mistProne: true,
      bortleClass: 3, skyBrightnessSqm: 21.7, elevationMetres: 368, gridLat: 54.61, gridLng: -3.12,
    });
  });

  it('sends null for what enrichment did not find, and omits a sub-region that was not picked', () => {
    const payload = planAdd({ ...looked, enrichmentData: null }).payload;

    expect(payload).toMatchObject({ bortleClass: null, elevationMetres: null, gridLat: null });
    expect(payload).not.toHaveProperty('subRegionId');
  });

  it('builds manual coordinates with the checkboxes and no detected values, and validates them', () => {
    const manual = { ...emptyAddForm(), manualEntry: true, manualName: ' Keld ', manualLat: '54.4', manualLon: '-2.1', coastalTidal: true };

    expect(planAdd(manual).payload).toMatchObject({
      name: 'Keld', lat: 54.4, lon: -2.1, coastalTidal: true, overlooksWater: false, bortleClass: null,
    });
    expect(planAdd({ ...manual, manualName: ' ' })).toEqual({ error: 'Name is required.', field: 'name' });
    expect(planAdd({ ...manual, manualLat: '91' })).toEqual({ error: 'Latitude must be between -90 and 90.', field: 'lat' });
    expect(planAdd({ ...manual, manualLon: '' })).toEqual({ error: 'Longitude must be between -180 and 180.', field: 'lon' });
  });

  it('refuses a seascape with no tide preference, and drops tides for a non-seascape', () => {
    expect(planAdd({ ...looked, locationTypes: ['SEASCAPE'], tideTypes: [] })).toEqual({
      error: 'Coastal locations require at least one tide preference (High, Mid, or Low).', field: 'tides',
    });
    expect(planAdd({ ...looked, locationTypes: ['LANDSCAPE'], tideTypes: ['HIGH'] }).payload.tideTypes).toEqual([]);
  });
});
