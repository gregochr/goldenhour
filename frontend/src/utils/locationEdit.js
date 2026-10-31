/**
 * Pure logic of the admin location editor (Manage → Places → Locations): the option lists, the edit form's starting
 * values, the field-change rules, the validation and the save payloads. Lifted out of {@code LocationManagementView}
 * (data-tabs-redesign-plan.md §2, P6) so the view and its hooks share one copy; behaviour is unchanged.
 */
import { LOCATION_TYPE_META, DISPLAY_TYPES } from './locationTypes.js';
import { formatAliases } from './aliasText.js';

/** Assignable types, derived so a new backend constant needs no edit here. */
export const LOCATION_TYPES = DISPLAY_TYPES.map((value) => ({ value, ...LOCATION_TYPE_META[value] }));

/** The tide states a seascape can be shot at. */
export const TIDE_TYPES = [
  { value: 'HIGH', label: 'H', fullLabel: 'High' },
  { value: 'MID', label: 'M', fullLabel: 'Mid' },
  { value: 'LOW', label: 'L', fullLabel: 'Low' },
];

/** All three tide states, the preselection when a location becomes a seascape. */
export const ALL_TIDE_TYPES = ['HIGH', 'MID', 'LOW'];

/** The default solar events of a new location. */
export const DEFAULT_SOLAR_EVENTS = ['SUNRISE', 'SUNSET'];

export const MIN_HEIGHT_M = -50;
export const MAX_HEIGHT_M = 1500;
/** Inversion scoring reads a site's own height from here up (InversionScoreCalculator.MIN_ELEVATION_METRES). */
export const INVERSION_MIN_ELEVATION_M = 200;

/**
 * Normalises a location's type set for editing, falling back to LANDSCAPE.
 *
 * <p>Deliberately keeps every value. The predecessor of this helper returned {@code set[0]},
 * which silently discarded the rest — and because the set arrives from a Java HashSet, which
 * element survived was not even stable between requests.
 *
 * @param {Array<string>} types - The stored type values.
 * @returns {Array<string>} A copy of the types, or ['LANDSCAPE'] when absent.
 */
export function typesOrDefault(types) {
  if (!Array.isArray(types) || types.length === 0) return ['LANDSCAPE'];
  return [...types];
}

/** Parses a whole-metre input: '' is null, a non-number is NaN, anything else is rounded. */
export function parseMetres(value) {
  if (value === '' || value == null) return null;
  const n = Number(value);
  return Number.isFinite(n) ? Math.round(n) : NaN;
}

/** The tide point a form holds as the new server baseline after a save, or null when it holds none. */
export function tidePointBaseline(values) {
  const latText = String(values.tideLat ?? '').trim();
  const lngText = String(values.tideLng ?? '').trim();
  const lat = Number(latText);
  const lng = Number(lngText);
  const blank = latText === '' || lngText === '';
  return blank || !Number.isFinite(lat) || !Number.isFinite(lng) ? null : { lat, lng };
}

/** True when the edited lat/lon text is a valid coordinate pair. */
export function coordsValid(lat, lon) {
  const a = parseFloat(lat);
  const b = parseFloat(lon);
  return !isNaN(a) && a >= -90 && a <= 90 && !isNaN(b) && b >= -180 && b <= 180;
}

/** True when an elevation edit moves an overlooks-water site across the 200 m inversion threshold. */
export function crossesInversionThreshold(values) {
  if (!values.overlooksWater) return false;
  const next = parseMetres(values.elevation);
  if (next === null || Number.isNaN(next)) return false;
  const was = (values.elevationBaseline ?? -Infinity) >= INVERSION_MIN_ELEVATION_M;
  return was !== (next >= INVERSION_MIN_ELEVATION_M);
}

/** True when the edited lat/lon differ from the stored coordinates. */
export function coordinatesChanged(values) {
  const lat = parseFloat(values.lat);
  const lon = parseFloat(values.lon);
  if (isNaN(lat) || isNaN(lon)) return false;
  return lat !== values.latBaseline || lon !== values.lonBaseline;
}

/** The sub-region id (as a string) a location belongs to among the given SUB_REGION areas, or ''. */
export function currentSubRegionId(subRegions, locationId) {
  const current = subRegions.find((a) => a.locationIds.includes(locationId));
  return current ? String(current.id) : '';
}

/**
 * The edit form's starting values for a location. The baselines are what the server holds; a save sends a height,
 * tide-point or sub-region field only when it differs from them.
 *
 * @param {object} loc the location as the API returns it
 * @param {object} context what was loaded alongside it
 * @param {Array<object>} context.subRegions the SUB_REGION areas read so far
 * @param {boolean} context.areasLoaded whether the areas have landed
 * @param {Object<number, string[]>|null} context.aliasesByLocation other names per location id, null until loaded
 * @returns {object} the form values
 */
export function buildEditValues(loc, { subRegions, areasLoaded, aliasesByLocation }) {
  const hasStoredFloor = loc.valleyFloorMetres != null && loc.valleyFloorLat != null && loc.valleyFloorLng != null;
  const hasStoredTidePoint = loc.tideLat != null && loc.tideLng != null;
  return {
    name: loc.name,
    lat: String(loc.lat),
    lon: String(loc.lon),
    solarEventTypes: Array.isArray(loc.solarEventType) ? [...loc.solarEventType] : [...DEFAULT_SOLAR_EVENTS],
    locationTypes: typesOrDefault(loc.locationType),
    tideTypes: Array.isArray(loc.tideType) ? [...loc.tideType] : [],
    regionId: loc.region?.id ? String(loc.region.id) : '',
    regionBaseline: loc.region?.id ? String(loc.region.id) : '',
    subRegionId: currentSubRegionId(subRegions, loc.id),
    subRegionBaseline: currentSubRegionId(subRegions, loc.id),
    subRegionsLoaded: areasLoaded,
    mistProne: loc.mistProne === true,
    locId: loc.id,
    aliases: formatAliases(aliasesByLocation?.[loc.id]),
    aliasesBaseline: formatAliases(aliasesByLocation?.[loc.id]),
    aliasesLoaded: aliasesByLocation != null,
    elevation: loc.elevationMetres != null ? String(loc.elevationMetres) : '',
    elevationBaseline: loc.elevationMetres ?? null,
    overlooksWater: loc.overlooksWater === true,
    latBaseline: loc.lat,
    lonBaseline: loc.lon,
    floorMetres: hasStoredFloor ? String(loc.valleyFloorMetres) : '',
    floorLat: hasStoredFloor ? loc.valleyFloorLat : null,
    floorLng: hasStoredFloor ? loc.valleyFloorLng : null,
    floorBaseline: hasStoredFloor
      ? { metres: loc.valleyFloorMetres, lat: loc.valleyFloorLat, lng: loc.valleyFloorLng }
      : null,
    floorCleared: false,
    // Tide point: where WorldTides is asked about instead of the pin. Held as text; baseline is what the
    // server holds, so a save sends the tide keys only when they differ.
    tideLat: hasStoredTidePoint ? String(loc.tideLat) : '',
    tideLng: hasStoredTidePoint ? String(loc.tideLng) : '',
    tideBaseline: hasStoredTidePoint ? { lat: loc.tideLat, lng: loc.tideLng } : null,
    floorCandidates: null,
    floorCell: null,
    floorStatus: '',
    floorBusy: false,
  };
}

/**
 * The form after one field changed, with the field-change rules applied.
 *
 * @param {object} prev the form values before
 * @param {string} field the field that changed
 * @param {*} value its new value
 * @param {Array<object>} subRegions the SUB_REGION areas read so far
 * @returns {object} the form values after
 */
export function applyEditChange(prev, field, value, subRegions) {
  const next = { ...prev, [field]: value };
  if (field === 'regionId') {
    // A sub-region belongs to one region: keep the pick only while it is still in the chosen region, and
    // put the location's own back when the region goes back to what it was.
    const stillInRegion = subRegions.some(
      (a) => String(a.id) === prev.subRegionId && String(a.regionId) === value,
    );
    if (value === prev.regionBaseline) next.subRegionId = prev.subRegionBaseline;
    else if (!stillInRegion) next.subRegionId = '';
  }
  if (field === 'lat' || field === 'lon') {
    // Candidates were fetched for the old coordinates: they must not be pickable for the new ones.
    next.floorCandidates = null;
    next.floorStatus = '';
  }
  if (field === 'locationTypes') {
    const wasCoastal = (prev.locationTypes || []).includes('SEASCAPE');
    const isCoastal = value.includes('SEASCAPE');
    // Only re-seed on an actual coastal transition. Rewriting on every change would wipe a
    // curated tide preference the moment an unrelated chip (say WOODLAND) was added to a
    // coastal site.
    if (isCoastal && !wasCoastal) next.tideTypes = [...ALL_TIDE_TYPES];
    else if (!isCoastal) next.tideTypes = [];
  }
  return next;
}

/**
 * Tide-point fields of the save payload: present only when they differ from what the server holds.
 * Both blank clears a stored point; one blank is an error.
 *
 * @param {object} values the form values
 * @returns {{error: string}|{fields: object}}
 */
export function tidePointPayload(values) {
  // The pair is hidden unless the location is coastal: a hidden half-blanked point must not block a save.
  if (!values.locationTypes.includes('SEASCAPE')) return { fields: {} };
  const latText = String(values.tideLat ?? '').trim();
  const lngText = String(values.tideLng ?? '').trim();
  const baseline = values.tideBaseline;
  if (latText === '' && lngText === '') {
    return { fields: baseline ? { clearTidePoint: true } : {} };
  }
  if (latText === '' || lngText === '') {
    return { error: 'A tide point needs both latitude and longitude, or clear it.' };
  }
  const lat = Number(latText);
  const lng = Number(lngText);
  if (!Number.isFinite(lat) || lat < -90 || lat > 90 || !Number.isFinite(lng) || lng < -180 || lng > 180) {
    return { error: 'Tide point latitude must be between -90 and 90 and longitude between -180 and 180.' };
  }
  if (baseline && lat === baseline.lat && lng === baseline.lng) return { fields: {} };
  return { fields: { tideLat: lat, tideLng: lng } };
}

/**
 * Height fields of the save payload, each present only when it differs from what the server holds.
 *
 * @param {object} values the form values
 * @returns {{error: string}|{fields: object}} an error for a value that cannot be sent, otherwise the fields
 */
export function heightPayload(values) {
  const fields = {};
  const elevation = parseMetres(values.elevation);
  if (elevation === null && values.elevationBaseline !== null) {
    return { error: "Elevation can't be cleared; enter a value" };
  }
  if (elevation !== null) {
    if (Number.isNaN(elevation) || elevation < MIN_HEIGHT_M || elevation > MAX_HEIGHT_M) {
      return { error: 'Elevation must be a whole number between -50 and 1,500 m.' };
    }
    if (elevation !== values.elevationBaseline) fields.elevationMetres = elevation;
  }
  const baseline = values.floorBaseline;
  if (values.floorCleared) {
    if (baseline) fields.clearValleyFloor = true;
  } else if (values.floorLat !== null && values.floorLng !== null) {
    const metres = parseMetres(values.floorMetres);
    if (metres === null || Number.isNaN(metres) || metres < MIN_HEIGHT_M || metres > MAX_HEIGHT_M) {
      return { error: 'Valley floor height must be a whole number between -50 and 1,500 m, or clear the floor.' };
    }
    if (!baseline || metres !== baseline.metres
      || values.floorLat !== baseline.lat || values.floorLng !== baseline.lng) {
      fields.valleyFloorMetres = metres;
      fields.valleyFloorLat = values.floorLat;
      fields.valleyFloorLng = values.floorLng;
    }
  }
  return { fields };
}

/** The coordinate error messages, in the order the editor checks them. Keyed so a field can show its own. */
export const EDIT_ERROR_FIELDS = {
  name: 'Name cannot be blank.',
  lat: 'Latitude must be between -90 and 90.',
  lon: 'Longitude must be between -180 and 180.',
  tides: 'Coastal locations require at least one tide preference (High, Mid, or Low).',
  types: 'A location must have at least one type.',
};

/**
 * Validates the edit form, in the order the editor has always checked it, and builds the save payload.
 *
 * @param {object} values the form values
 * @returns {{error: string, field: string|null}|{payload: object}} the first problem (with the field it belongs
 *   to, or null for one that is not a single field), otherwise the {@code PUT /api/locations/{id}} body
 */
export function planEditSave(values) {
  if (!values.name.trim()) return { error: EDIT_ERROR_FIELDS.name, field: 'name' };
  const parsedLat = parseFloat(values.lat);
  const parsedLon = parseFloat(values.lon);
  if (isNaN(parsedLat) || parsedLat < -90 || parsedLat > 90) return { error: EDIT_ERROR_FIELDS.lat, field: 'lat' };
  if (isNaN(parsedLon) || parsedLon < -180 || parsedLon > 180) return { error: EDIT_ERROR_FIELDS.lon, field: 'lon' };
  const coastal = values.locationTypes.includes('SEASCAPE');
  if (coastal && (!values.tideTypes || values.tideTypes.length === 0)) {
    return { error: EDIT_ERROR_FIELDS.tides, field: 'tides' };
  }
  if (values.locationTypes.length === 0) return { error: EDIT_ERROR_FIELDS.types, field: 'types' };
  const heights = heightPayload(values);
  if (heights.error) return { error: heights.error, field: heights.error.startsWith('Valley') ? 'floor' : 'elevation' };
  const tidePoint = tidePointPayload(values);
  if (tidePoint.error) return { error: tidePoint.error, field: 'tidePoint' };
  const subRegionChange = values.subRegionId && values.subRegionId !== values.subRegionBaseline
    ? { subRegionId: Number(values.subRegionId) }
    : {};
  return {
    payload: {
      name: values.name.trim(),
      lat: parsedLat,
      lon: parsedLon,
      solarEventTypes: values.solarEventTypes,
      locationTypes: values.locationTypes,
      tideTypes: coastal ? values.tideTypes : [],
      regionId: values.regionId ? Number(values.regionId) : null,
      mistProne: values.mistProne === true,
      ...subRegionChange,
      ...heights.fields,
      ...tidePoint.fields,
    },
  };
}

/**
 * The form after a save whose other-names write failed: the location itself is saved, so what the form holds for
 * region, sub-region, heights, tide point and coordinates is now the stored truth (a second save must resend none
 * of it).
 *
 * @param {object} prev the form values
 * @returns {object} the form values with every baseline moved to what they hold
 */
export function withSavedBaselines(prev) {
  const floorSet = !prev.floorCleared && prev.floorLat !== null && prev.floorLng !== null;
  return {
    ...prev,
    regionBaseline: prev.regionId,
    subRegionBaseline: prev.subRegionId,
    elevationBaseline: parseMetres(prev.elevation),
    floorBaseline: floorSet
      ? { metres: parseMetres(prev.floorMetres), lat: prev.floorLat, lng: prev.floorLng }
      : null,
    floorCleared: false,
    tideBaseline: tidePointBaseline(prev),
    latBaseline: parseFloat(prev.lat),
    lonBaseline: parseFloat(prev.lon),
  };
}

/** The shape of the add form's starting state. */
export function emptyAddForm() {
  return {
    placeName: '',
    geocodeResult: null,
    geocodeError: '',
    manualEntry: false,
    manualName: '',
    manualLat: '',
    manualLon: '',
    solarEventTypes: [...DEFAULT_SOLAR_EVENTS],
    locationTypes: ['LANDSCAPE'],
    tideTypes: [],
    regionId: '',
    subRegionId: '',
    enrichmentData: null,
    overlooksWater: false,
    coastalTidal: false,
    mistProne: false,
  };
}

/** What enrichment reads as when the lookup failed: every field absent. */
export const EMPTY_ENRICHMENT = {
  bortleClass: null, skyBrightnessSqm: null, elevationMetres: null, gridLat: null, gridLng: null,
};

/**
 * Validates the add form and builds the request body of {@code POST /api/locations}.
 *
 * <p>A looked-up place carries what enrichment found (Bortle, sky brightness, elevation, grid cell); manual
 * coordinates carry none of it (the fields are sent null). The three checkboxes (overlooks water, coastal tidal,
 * mist-prone) apply either way.
 *
 * @param {object} form the add form (see {@link emptyAddForm})
 * @returns {{error: string}|{pending: true}|{payload: object}} an error, nothing to add yet (no lookup result), or
 *   the body to send
 */
export function planAdd(form) {
  const coastal = form.locationTypes.includes('SEASCAPE');
  if (coastal && (!form.tideTypes || form.tideTypes.length === 0)) {
    return { error: EDIT_ERROR_FIELDS.tides };
  }
  const common = {
    solarEventTypes: form.solarEventTypes,
    locationTypes: form.locationTypes,
    tideTypes: coastal ? form.tideTypes : [],
    regionId: form.regionId ? Number(form.regionId) : null,
    overlooksWater: form.overlooksWater,
    coastalTidal: form.coastalTidal,
    mistProne: form.mistProne === true,
    ...(form.subRegionId ? { subRegionId: Number(form.subRegionId) } : {}),
  };
  if (form.manualEntry) {
    const name = form.manualName.trim();
    const lat = parseFloat(form.manualLat);
    const lon = parseFloat(form.manualLon);
    if (!name) return { error: 'Name is required.' };
    if (isNaN(lat) || lat < -90 || lat > 90) return { error: EDIT_ERROR_FIELDS.lat };
    if (isNaN(lon) || lon < -180 || lon > 180) return { error: EDIT_ERROR_FIELDS.lon };
    return {
      payload: {
        name, lat, lon, ...common,
        bortleClass: null, skyBrightnessSqm: null, elevationMetres: null, gridLat: null, gridLng: null,
      },
    };
  }
  if (!form.geocodeResult) return { pending: true };
  const enrichment = form.enrichmentData;
  return {
    payload: {
      name: form.placeName.trim(),
      lat: form.geocodeResult.lat,
      lon: form.geocodeResult.lon,
      ...common,
      bortleClass: enrichment?.bortleClass ?? null,
      skyBrightnessSqm: enrichment?.skyBrightnessSqm ?? null,
      elevationMetres: enrichment?.elevationMetres ?? null,
      gridLat: enrichment?.gridLat ?? null,
      gridLng: enrichment?.gridLng ?? null,
    },
  };
}

/**
 * Toggles one value in a multi-select where the last selected value is sticky (a location with no type routes to no
 * evaluation lane, with no solar event nothing is shot, with no tide a seascape is rejected).
 *
 * @param {Array<string>} selected the current values
 * @param {string} value the value pressed
 * @returns {Array<string>|null} the new values, or null when the press is refused (it would empty the set)
 */
export function toggleSticky(selected, value) {
  const isSelected = selected.includes(value);
  if (isSelected && selected.length <= 1) return null;
  return isSelected ? selected.filter((v) => v !== value) : [...selected, value];
}
