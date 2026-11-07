import { describe, it, expect } from 'vitest';
import {
  applyEditChange, buildEditValues, coordinatesChanged, coordsValid, crossesInversionThreshold, emptyAddForm,
  parseMetres, planAdd, planEditSave, storedDrivePoint, tidePointBaseline, typesOrDefault, withSavedBaselines,
} from '../utils/locationEdit.js';

const LOC = {
  id: 1, name: 'Alnmouth', lat: 55.39, lon: -1.613, solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['SEASCAPE'],
  tideType: ['HIGH'], region: { id: 4 }, elevationMetres: 12,
};
const CONTEXT = { subRegions: [], areasLoaded: true, aliasesByLocation: {} };

function form(overrides = {}) {
  return { ...buildEditValues(LOC, CONTEXT), ...overrides };
}

describe('parseMetres', () => {
  it('reads blank and absent input as no height, a number as whole metres, and text as NaN', () => {
    expect(parseMetres('')).toBeNull();
    expect(parseMetres(null)).toBeNull();
    expect(parseMetres(undefined)).toBeNull();
    expect(parseMetres('0')).toBe(0);
    expect(parseMetres('12.6')).toBe(13);
    expect(parseMetres(' -3 ')).toBe(-3);
    expect(parseMetres('abc')).toBeNaN();
    expect(parseMetres('Infinity')).toBeNaN();
  });
});

describe('typesOrDefault', () => {
  it('falls back to LANDSCAPE for a missing, empty or non-array set, and copies a real one', () => {
    expect(typesOrDefault(null)).toEqual(['LANDSCAPE']);
    expect(typesOrDefault(undefined)).toEqual(['LANDSCAPE']);
    expect(typesOrDefault('SEASCAPE')).toEqual(['LANDSCAPE']);
    expect(typesOrDefault([])).toEqual(['LANDSCAPE']);
    const stored = ['WOODLAND', 'SEASCAPE'];
    const copy = typesOrDefault(stored);
    expect(copy).toEqual(['WOODLAND', 'SEASCAPE']);
    expect(copy).not.toBe(stored);
  });
});

describe('tidePointBaseline', () => {
  it('holds a complete numeric pair as the baseline', () => {
    expect(tidePointBaseline({ tideLat: ' 55.5 ', tideLng: '-1.25' })).toEqual({ lat: 55.5, lng: -1.25 });
    expect(tidePointBaseline({ tideLat: '0', tideLng: '0' })).toEqual({ lat: 0, lng: 0 });
  });

  it('holds none when either half is blank, whitespace or not a number', () => {
    expect(tidePointBaseline({ tideLat: '', tideLng: '' })).toBeNull();
    expect(tidePointBaseline({ tideLat: '', tideLng: '5' })).toBeNull();
    expect(tidePointBaseline({ tideLat: '5', tideLng: '' })).toBeNull();
    expect(tidePointBaseline({ tideLat: '   ', tideLng: '5' })).toBeNull();
    expect(tidePointBaseline({ tideLat: '5', tideLng: '   ' })).toBeNull();
    expect(tidePointBaseline({ tideLat: undefined, tideLng: '5' })).toBeNull();
    expect(tidePointBaseline({ tideLat: 'x', tideLng: '5' })).toBeNull();
    expect(tidePointBaseline({ tideLat: '5', tideLng: 'x' })).toBeNull();
  });
});

describe('storedDrivePoint', () => {
  it('needs the name, latitude and longitude, each independently', () => {
    expect(storedDrivePoint({ drivePointName: 'Car park', drivePointLat: 0, drivePointLon: 0 }))
      .toEqual({ name: 'Car park', lat: 0, lon: 0 });
    expect(storedDrivePoint({ drivePointName: null, drivePointLat: 1, drivePointLon: 2 })).toBeNull();
    expect(storedDrivePoint({ drivePointName: 'P', drivePointLat: null, drivePointLon: 2 })).toBeNull();
    expect(storedDrivePoint({ drivePointName: 'P', drivePointLat: 1, drivePointLon: null })).toBeNull();
    expect(storedDrivePoint({ drivePointName: 'P' })).toBeNull();
    expect(storedDrivePoint({ drivePointLat: 1 })).toBeNull();
    expect(storedDrivePoint({ drivePointLon: 2 })).toBeNull();
  });
});

describe('coordsValid', () => {
  it('accepts the boundary values and refuses anything beyond or non-numeric', () => {
    expect(coordsValid('90', '180')).toBe(true);
    expect(coordsValid('-90', '-180')).toBe(true);
    expect(coordsValid('0', '0')).toBe(true);
    expect(coordsValid('90.1', '0')).toBe(false);
    expect(coordsValid('-90.1', '0')).toBe(false);
    expect(coordsValid('0', '180.1')).toBe(false);
    expect(coordsValid('0', '-180.1')).toBe(false);
    expect(coordsValid('x', '0')).toBe(false);
    expect(coordsValid('0', 'x')).toBe(false);
    expect(coordsValid('', '')).toBe(false);
  });
});

describe('crossesInversionThreshold', () => {
  const base = { overlooksWater: true, elevation: '250', elevationBaseline: 100 };

  it('is true only when an overlooks-water site moves across 200 m either way', () => {
    expect(crossesInversionThreshold(base)).toBe(true);
    expect(crossesInversionThreshold({ ...base, elevation: '150' })).toBe(false);
    expect(crossesInversionThreshold({ ...base, elevation: '300', elevationBaseline: 250 })).toBe(false);
    expect(crossesInversionThreshold({ ...base, elevation: '150', elevationBaseline: 250 })).toBe(true);
    expect(crossesInversionThreshold({ ...base, overlooksWater: false })).toBe(false);
  });

  it('treats exactly 200 m as above the threshold', () => {
    expect(crossesInversionThreshold({ ...base, elevation: '100', elevationBaseline: 200 })).toBe(true);
    expect(crossesInversionThreshold({ ...base, elevation: '200', elevationBaseline: 100 })).toBe(true);
    expect(crossesInversionThreshold({ ...base, elevation: '200', elevationBaseline: 200 })).toBe(false);
  });

  it('counts a site with no stored height as below the threshold', () => {
    expect(crossesInversionThreshold({ ...base, elevationBaseline: null })).toBe(true);
    expect(crossesInversionThreshold({ ...base, elevation: '50', elevationBaseline: null })).toBe(false);
    expect(crossesInversionThreshold({ ...base, elevation: '50', elevationBaseline: undefined })).toBe(false);
  });

  it('never crosses when the new height is blank or not a number', () => {
    expect(crossesInversionThreshold({ ...base, elevation: '', elevationBaseline: 300 })).toBe(false);
    expect(crossesInversionThreshold({ ...base, elevation: 'abc', elevationBaseline: 300 })).toBe(false);
    expect(crossesInversionThreshold({ ...base, elevation: 'abc', elevationBaseline: 100 })).toBe(false);
  });
});

describe('coordinatesChanged', () => {
  const base = { lat: '55.39', lon: '-1.613', latBaseline: 55.39, lonBaseline: -1.613 };

  it('is false when both match the stored pair', () => {
    expect(coordinatesChanged(base)).toBe(false);
  });

  it('is true when either coordinate alone differs', () => {
    expect(coordinatesChanged({ ...base, lat: '55.4' })).toBe(true);
    expect(coordinatesChanged({ ...base, lon: '-1.6' })).toBe(true);
  });

  it('is false while either coordinate is not yet a number, even if the other moved', () => {
    expect(coordinatesChanged({ ...base, lat: 'x', lon: '-1.0' })).toBe(false);
    expect(coordinatesChanged({ ...base, lat: '56', lon: '' })).toBe(false);
    expect(coordinatesChanged({ ...base, lat: '', lon: '' })).toBe(false);
  });
});

describe('buildEditValues edges', () => {
  it('defaults the solar events and tides when the location carries none', () => {
    const values = buildEditValues(
      { id: 2, name: 'N', lat: 1, lon: 2, locationType: ['LANDSCAPE'] },
      { subRegions: [], areasLoaded: false, aliasesByLocation: null },
    );

    expect(values.solarEventTypes).toEqual(['SUNRISE', 'SUNSET']);
    expect(values.tideTypes).toEqual([]);
    expect(values.aliasesLoaded).toBe(false);
    expect(values.subRegionsLoaded).toBe(false);
  });

  it('reports the other names as loaded once they have landed, even when empty', () => {
    expect(buildEditValues(LOC, CONTEXT).aliasesLoaded).toBe(true);
  });

  it.each([
    ['metres', { valleyFloorMetres: null, valleyFloorLat: 1, valleyFloorLng: 2 }],
    ['latitude', { valleyFloorMetres: 100, valleyFloorLat: null, valleyFloorLng: 2 }],
    ['longitude', { valleyFloorMetres: 100, valleyFloorLat: 1, valleyFloorLng: null }],
    ['metres and latitude', { valleyFloorMetres: null, valleyFloorLat: null, valleyFloorLng: 2 }],
    ['metres and longitude', { valleyFloorMetres: null, valleyFloorLat: 1, valleyFloorLng: null }],
    ['latitude and longitude', { valleyFloorMetres: 100, valleyFloorLat: null, valleyFloorLng: null }],
    ['everything', {}],
  ])('holds no valley floor when %s is missing', (_, floor) => {
    const values = buildEditValues({ ...LOC, ...floor }, CONTEXT);

    expect(values.floorMetres).toBe('');
    expect(values.floorLat).toBeNull();
    expect(values.floorLng).toBeNull();
    expect(values.floorBaseline).toBeNull();
  });

  it('holds a complete valley floor as its own baseline', () => {
    const values = buildEditValues(
      { ...LOC, valleyFloorMetres: 0, valleyFloorLat: 54.5, valleyFloorLng: -3.1 }, CONTEXT,
    );

    expect(values.floorMetres).toBe('0');
    expect(values.floorLat).toBe(54.5);
    expect(values.floorLng).toBe(-3.1);
    expect(values.floorBaseline).toEqual({ metres: 0, lat: 54.5, lng: -3.1 });
  });

  it.each([
    ['latitude', { tideLat: null, tideLng: -1 }],
    ['longitude', { tideLat: 55, tideLng: null }],
    ['both', {}],
  ])('holds no tide point when the %s is missing', (_, tide) => {
    const values = buildEditValues({ ...LOC, ...tide }, CONTEXT);

    expect(values.tideLat).toBe('');
    expect(values.tideLng).toBe('');
    expect(values.tideBaseline).toBeNull();
  });

  it('holds a complete tide point as its own baseline, a zero included', () => {
    const values = buildEditValues({ ...LOC, tideLat: 0, tideLng: -1.5 }, CONTEXT);

    expect(values.tideLat).toBe('0');
    expect(values.tideLng).toBe('-1.5');
    expect(values.tideBaseline).toEqual({ lat: 0, lng: -1.5 });
  });
});

describe('applyEditChange edges', () => {
  const subRegions = [{ id: 21, regionId: 4 }, { id: 31, regionId: 5 }];

  it('keeps a sub-region that is still in the new region even when another sub-region is not', () => {
    const prev = form({ regionId: '9', regionBaseline: '9', subRegionId: '21', subRegionBaseline: '40' });

    expect(applyEditChange(prev, 'regionId', '4', subRegions).subRegionId).toBe('21');
  });

  it('clears a sub-region that matches no sub-region of the new region', () => {
    const prev = form({ regionId: '9', regionBaseline: '9', subRegionId: '21', subRegionBaseline: '40' });

    expect(applyEditChange(prev, 'regionId', '5', subRegions).subRegionId).toBe('');
    expect(applyEditChange(prev, 'regionId', '5', []).subRegionId).toBe('');
  });

  it('forgets valley-floor candidates on a latitude or longitude edit and on nothing else', () => {
    const prev = form({ floorCandidates: [{ id: 1 }], floorStatus: 'Found 1' });

    for (const field of ['lat', 'lon']) {
      const next = applyEditChange(prev, field, '1', subRegions);
      expect(next.floorCandidates).toBeNull();
      expect(next.floorStatus).toBe('');
    }
    for (const [field, value] of [['name', 'X'], ['elevation', '5'], ['mistProne', true]]) {
      const next = applyEditChange(prev, field, value, subRegions);
      expect(next.floorCandidates).toEqual([{ id: 1 }]);
      expect(next.floorStatus).toBe('Found 1');
    }
  });

  it('seeds all tides when a form with no recorded types becomes coastal', () => {
    const prev = form({ locationTypes: undefined, tideTypes: [] });

    expect(applyEditChange(prev, 'locationTypes', ['SEASCAPE'], subRegions).tideTypes).toEqual(['HIGH', 'MID', 'LOW']);
  });
});

describe('planEditSave: the tide point', () => {
  it.each([
    ['both blank', { tideLat: '', tideLng: '' }],
    ['both whitespace', { tideLat: '  ', tideLng: '  ' }],
    ['both missing', { tideLat: undefined, tideLng: null }],
  ])('sends nothing for a blank pair (%s) when none is stored', (_, tide) => {
    const { payload } = planEditSave(form(tide));

    expect(payload).not.toHaveProperty('tideLat');
    expect(payload).not.toHaveProperty('clearTidePoint');
  });

  it('clears a stored point when both halves are blank or whitespace', () => {
    const tideBaseline = { lat: 55, lng: -1 };

    expect(planEditSave(form({ tideBaseline, tideLat: '', tideLng: '' })).payload.clearTidePoint).toBe(true);
    expect(planEditSave(form({ tideBaseline, tideLat: ' ', tideLng: ' ' })).payload.clearTidePoint).toBe(true);
  });

  it('refuses a point with only one half given, whichever half it is', () => {
    const message = 'A tide point needs both latitude and longitude, or clear it.';

    expect(planEditSave(form({ tideLat: '', tideLng: '5' }))).toEqual({ error: message, field: 'tidePoint' });
    expect(planEditSave(form({ tideLat: '5', tideLng: '' }))).toEqual({ error: message, field: 'tidePoint' });
    expect(planEditSave(form({ tideLat: '  ', tideLng: '5' }))).toEqual({ error: message, field: 'tidePoint' });
    expect(planEditSave(form({ tideLat: '5', tideLng: '  ' }))).toEqual({ error: message, field: 'tidePoint' });
  });

  it.each([
    ['91', '0'], ['-91', '0'], ['0', '181'], ['0', '-181'], ['x', '0'], ['0', 'x'],
  ])('refuses the out-of-range or non-numeric point %s, %s', (tideLat, tideLng) => {
    expect(planEditSave(form({ tideLat, tideLng }))).toEqual({
      error: 'Tide point latitude must be between -90 and 90 and longitude between -180 and 180.',
      field: 'tidePoint',
    });
  });

  it.each([['90', '180'], ['-90', '-180'], ['90', '-180'], ['-90', '180']])(
    'accepts the extreme point %s, %s',
    (tideLat, tideLng) => {
      const { payload } = planEditSave(form({ tideLat, tideLng }));

      expect(payload.tideLat).toBe(Number(tideLat));
      expect(payload.tideLng).toBe(Number(tideLng));
    },
  );

  it('sends the point when only one half differs from the stored point, and nothing when both match', () => {
    const tideBaseline = { lat: 55, lng: -1 };

    expect(planEditSave(form({ tideBaseline, tideLat: '56', tideLng: '-1' })).payload)
      .toMatchObject({ tideLat: 56, tideLng: -1 });
    expect(planEditSave(form({ tideBaseline, tideLat: '55', tideLng: '-2' })).payload)
      .toMatchObject({ tideLat: 55, tideLng: -2 });
    const same = planEditSave(form({ tideBaseline, tideLat: '55', tideLng: '-1' })).payload;
    expect(same).not.toHaveProperty('tideLat');
    expect(same).not.toHaveProperty('tideLng');
  });
});

describe('planEditSave: heights', () => {
  const heightError = 'Elevation must be a whole number between -50 and 1,500 m.';

  it.each([['-50', -50], ['1500', 1500], ['0', 0]])('accepts an elevation of %s', (text, metres) => {
    expect(planEditSave(form({ elevation: text })).payload.elevationMetres).toBe(metres);
  });

  it.each([['-51'], ['1501'], ['abc']])('refuses an elevation of %s', (text) => {
    expect(planEditSave(form({ elevation: text }))).toEqual({ error: heightError, field: 'elevation' });
  });

  it('sends no elevation when it matches the stored one', () => {
    expect(planEditSave(form({ elevation: '12' })).payload).not.toHaveProperty('elevationMetres');
  });

  it('lets a form with no stored elevation stay blank without sending one', () => {
    const { payload, error } = planEditSave(form({ elevation: '', elevationBaseline: null }));

    expect(error).toBeUndefined();
    expect(payload).not.toHaveProperty('elevationMetres');
  });

  it('ignores a valley floor with only one coordinate', () => {
    const onlyLat = planEditSave(form({ floorLat: 54.5, floorLng: null, floorMetres: '100' }));
    const onlyLng = planEditSave(form({ floorLat: null, floorLng: -3.1, floorMetres: '100' }));
    const none = planEditSave(form({ floorLat: null, floorLng: null, floorMetres: '' }));

    for (const plan of [onlyLat, onlyLng, none]) {
      expect(plan.error).toBeUndefined();
      expect(plan.payload).not.toHaveProperty('valleyFloorMetres');
      expect(plan.payload).not.toHaveProperty('valleyFloorLat');
      expect(plan.payload).not.toHaveProperty('valleyFloorLng');
    }
  });

  it.each([['-50', -50], ['1500', 1500]])('accepts a valley floor of %s m', (text, metres) => {
    const { payload } = planEditSave(form({ floorLat: 54.5, floorLng: -3.1, floorMetres: text }));

    expect(payload).toMatchObject({ valleyFloorMetres: metres, valleyFloorLat: 54.5, valleyFloorLng: -3.1 });
  });

  it.each([['-51'], ['1501'], ['abc'], ['']])('refuses a valley floor of "%s" m', (text) => {
    expect(planEditSave(form({ floorLat: 54.5, floorLng: -3.1, floorMetres: text }))).toEqual({
      error: 'Valley floor height must be a whole number between -50 and 1,500 m, or clear the floor.',
      field: 'floor',
    });
  });

  it('resends the floor when any one of its three parts differs from the stored floor, and otherwise not', () => {
    const floorBaseline = { metres: 100, lat: 54.5, lng: -3.1 };
    const stored = { floorBaseline, floorLat: 54.5, floorLng: -3.1, floorMetres: '100' };

    expect(planEditSave(form({ ...stored, floorMetres: '120' })).payload.valleyFloorMetres).toBe(120);
    expect(planEditSave(form({ ...stored, floorLat: 54.6 })).payload).toMatchObject({
      valleyFloorMetres: 100, valleyFloorLat: 54.6, valleyFloorLng: -3.1,
    });
    expect(planEditSave(form({ ...stored, floorLng: -3.2 })).payload).toMatchObject({
      valleyFloorMetres: 100, valleyFloorLat: 54.5, valleyFloorLng: -3.2,
    });
    const same = planEditSave(form(stored)).payload;
    expect(same).not.toHaveProperty('valleyFloorMetres');
    expect(same).not.toHaveProperty('valleyFloorLat');
    expect(same).not.toHaveProperty('valleyFloorLng');
  });

  it('asks the server to clear a stored floor, and sends nothing for clearing none', () => {
    const floorBaseline = { metres: 100, lat: 54.5, lng: -3.1 };
    const cleared = { floorCleared: true, floorLat: 54.5, floorLng: -3.1, floorMetres: '100' };

    expect(planEditSave(form({ ...cleared, floorBaseline })).payload.clearValleyFloor).toBe(true);
    expect(planEditSave(form({ ...cleared, floorBaseline: null })).payload).not.toHaveProperty('clearValleyFloor');
  });
});

describe('planEditSave: the coordinate and name edges', () => {
  it.each([['90', '180'], ['-90', '-180']])('accepts the extreme coordinates %s, %s', (lat, lon) => {
    const { payload } = planEditSave(form({ lat, lon }));

    expect(payload.lat).toBe(Number(lat));
    expect(payload.lon).toBe(Number(lon));
  });

  it.each([['-90.5', '0', 'lat'], ['90.5', '0', 'lat'], ['0', '180.5', 'lon'], ['0', '-180.5', 'lon'], ['0', 'x', 'lon']])(
    'refuses %s, %s on the %s field',
    (lat, lon, field) => {
      expect(planEditSave(form({ lat, lon })).field).toBe(field);
    },
  );

  it('sends the name trimmed', () => {
    expect(planEditSave(form({ name: '  Alnmouth  ' })).payload.name).toBe('Alnmouth');
  });
});

describe('withSavedBaselines edges', () => {
  const held = { floorCleared: false, floorLat: 54.5, floorLng: -3.1, floorMetres: '100' };

  it('keeps a held floor as the new baseline', () => {
    expect(withSavedBaselines(form(held)).floorBaseline).toEqual({ metres: 100, lat: 54.5, lng: -3.1 });
  });

  it('holds no floor baseline when the floor was cleared or either coordinate is missing', () => {
    expect(withSavedBaselines(form({ ...held, floorCleared: true })).floorBaseline).toBeNull();
    expect(withSavedBaselines(form({ ...held, floorLat: null })).floorBaseline).toBeNull();
    expect(withSavedBaselines(form({ ...held, floorLng: null })).floorBaseline).toBeNull();
    expect(withSavedBaselines(form({ ...held, floorLat: null, floorLng: null })).floorBaseline).toBeNull();
  });

  it('resets the floor-cleared flag', () => {
    expect(withSavedBaselines(form({ ...held, floorCleared: true })).floorCleared).toBe(false);
  });
});

describe('emptyAddForm', () => {
  it('starts on sunrise and sunset, a landscape, and no tides', () => {
    const empty = emptyAddForm();

    expect(empty.solarEventTypes).toEqual(['SUNRISE', 'SUNSET']);
    expect(empty.locationTypes).toEqual(['LANDSCAPE']);
    expect(empty.tideTypes).toEqual([]);
  });

  it('hands out fresh arrays each time', () => {
    const a = emptyAddForm();
    a.solarEventTypes.push('X');
    a.tideTypes.push('HIGH');

    expect(emptyAddForm().solarEventTypes).toEqual(['SUNRISE', 'SUNSET']);
    expect(emptyAddForm().tideTypes).toEqual([]);
  });
});

describe('planAdd: manual coordinate edges', () => {
  const manual = (overrides) => ({
    ...emptyAddForm(), manualEntry: true, manualName: 'Spot', manualLat: '1', manualLon: '2', ...overrides,
  });

  it.each([['90', '180'], ['-90', '-180']])('accepts the extreme coordinates %s, %s', (manualLat, manualLon) => {
    const { payload } = planAdd(manual({ manualLat, manualLon }));

    expect(payload.lat).toBe(Number(manualLat));
    expect(payload.lon).toBe(Number(manualLon));
  });

  it.each([['90.5', '0', 'lat'], ['-90.5', '0', 'lat'], ['x', '0', 'lat'], ['0', '180.5', 'lon'],
    ['0', '-180.5', 'lon'], ['0', 'x', 'lon']])('refuses %s, %s on the %s field', (manualLat, manualLon, field) => {
    expect(planAdd(manual({ manualLat, manualLon })).field).toBe(field);
  });
});
