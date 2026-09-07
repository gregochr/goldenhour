/**
 * Shared inputs for the label-obstacle measurement (docs/engineering/map-landing-plan.md §4b.1).
 *
 * Everything a run depends on is DECLARED HERE rather than described in prose, because the residual
 * this measurement closed (R5) existed precisely because an earlier one could not be re-run.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO = resolve(HERE, '../../..');
export const FRONTEND = resolve(REPO, 'frontend');

/**
 * The roster, derived from `scripts/dev-seed-locations.sh` — its 21 real anchors plus the
 * deterministic 189-point `--dense` scatter, reproducing that script's own offset arithmetic
 * (`dlat = (n % 3 - 1) * 0.075`, `dlon = (int(n / 3) - 1) * 0.130`, n = 1..9 per anchor).
 *
 * ⚠️ Derived rather than committed as a blob, so it tracks the seed script. If that script's
 * anchors change, these numbers change with it — which is correct, and is why a run should quote
 * the roster size it actually loaded.
 */
export function loadRoster() {
  const sh = readFileSync(resolve(REPO, 'scripts/dev-seed-locations.sh'), 'utf8');
  const after = sh.split("read -r -d '' ROWS <<'EOF'")[1];
  if (after === undefined) throw new Error('could not find the ROWS heredoc in dev-seed-locations.sh');
  // ⚠️ Drop the remainder of the OPENER line first. It ends `|| true`, which otherwise splits on
  // `|` into a phantom 22nd anchor named " " — and 9 phantom scatter points with it. The roster
  // came out 220 across 5 regions instead of 210 across 4 before this line existed.
  const block = after.slice(after.indexOf('\n') + 1).split('\nEOF')[0];
  const anchors = block.trim().split('\n').map((line) => {
    const [name, lat, lon, region, type, bortle] = line.split('|');
    return {
      name, lat: Number(lat), lon: Number(lon), region, type, bortle: Number(bortle),
    };
  });
  const out = [...anchors];
  let i = 0;
  for (const a of anchors) {
    for (let n = 1; n <= 9; n += 1) {
      i += 1;
      const dlat = ((n % 3) - 1) * 0.075;
      const dlon = (Math.trunc(n / 3) - 1) * 0.130;
      out.push({
        name: `Scatter ${i}`,
        lat: Number((a.lat + dlat).toFixed(4)),
        lon: Number((a.lon + dlon).toFixed(4)),
        region: a.region,
        type: 'LANDSCAPE',
        bortle: a.bortle,
      });
    }
  }
  return out;
}

/**
 * A location's rating, tide alignment and drive time — DETERMINISTIC, from an FNV-1a hash of the
 * name. These steer `chipCandidates`' greedy ordering and nothing else; both arms of every
 * comparison see byte-identical values, so a difference can only be the obstacle's.
 */
export function hash(s) {
  let x = 2166136261;
  for (let i = 0; i < s.length; i += 1) { x ^= s.charCodeAt(i); x = Math.imul(x, 16777619); }
  return (x >>> 0) / 2 ** 32;
}

export function spotsFrom(roster) {
  return roster.map((r) => ({
    name: r.name,
    lat: r.lat,
    lng: r.lon,
    rid: r.region,
    rating: 1 + Math.floor(hash(`${r.name}|rating`) * 5),
    onTheLight: hash(`${r.name}|tide`) < 0.18,
    driveMinutes: Math.round(20 + hash(`${r.name}|drive`) * 160),
  }));
}

/** The four frames measured. */
export const VIEWPORTS = [
  { name: 'desktop-1280', width: 1280, height: 800 },
  { name: 'laptop-1024', width: 1024, height: 720 },
  { name: 'tablet-820', width: 820, height: 1100 },
  { name: 'phone-390', width: 390, height: 844 },
];

/** The seven zooms swept, spanning the region-label cutoff (11.2) and the chip budget's range. */
export const ZOOMS = [8.6, 9.2, 10.0, 10.6, 11.2, 12.0, 13.0];

/** The five camera centres: the roster centroid, then four pans in degrees. */
export const CENTRE_OFFSETS = [
  { name: 'centroid', dLat: 0, dLon: 0 },
  { name: 'north', dLat: 0.35, dLon: 0 },
  { name: 'south', dLat: -0.35, dLon: 0 },
  { name: 'east', dLat: 0, dLon: 0.7 },
  { name: 'west', dLat: 0, dLon: -0.7 },
];

export function centresFor(spots) {
  const lat = spots.reduce((a, s) => a + s.lat, 0) / spots.length;
  const lon = spots.reduce((a, s) => a + s.lng, 0) / spots.length;
  return CENTRE_OFFSETS.map((c) => ({ name: c.name, lat: lat + c.dLat, lon: lon + c.dLon }));
}

// ── Leaflet's own projection (CRS.EPSG3857), so a point here is the point
//    `map.latLngToContainerPoint` would return. ───────────────────────────────────────────────────
const R = 6378137;
const D = Math.PI / 180;
const TA = 0.5 / (Math.PI * R);
const TC = -0.5 / (Math.PI * R);

export function latLngToPoint(lat, lon, zoom) {
  const clamped = Math.max(Math.min(85.0511287798, lat), -85.0511287798);
  const sin = Math.sin(clamped * D);
  const scale = 256 * 2 ** zoom;
  return {
    x: scale * (TA * (R * lon * D) + 0.5),
    y: scale * (TC * ((R * Math.log((1 + sin) / (1 - sin))) / 2) + 0.5),
  };
}

/** `(lat, lon) => [x, y]` in frame px, for a map centred at `centre` at `zoom` in a `w`×`h` frame. */
export function projector(centre, zoom, w, h) {
  const c = latLngToPoint(centre.lat, centre.lon, zoom);
  return (lat, lon) => {
    const p = latLngToPoint(lat, lon, zoom);
    return [p.x - c.x + w / 2, p.y - c.y + h / 2];
  };
}

/**
 * The chrome that is on screen in every ordinary map state, and is therefore held constant in both
 * arms of every comparison — a null "no collateral drops" result is only worth having under
 * competition.
 *
 * ⚠️ These are MEASURED (`measure.mjs` reads their real rects), not modelled. The first cut of this
 * measurement invented a `224.45 x 36` box for `wf-map-chrome-tr` from a width recorded in
 * `index.css` plus a guessed height. The real cluster is **158 x 146** on desktop — narrower and
 * four times taller — and on a phone it is not there at all: it becomes the bottom bar. A review
 * lens showed the panels' collateral count flipping at a plausible taller value, so that guess was
 * load-bearing, which is exactly why nothing here is guessed any more.
 */
export const ALWAYS_ON = [
  'wf-map-chrome-tr', 'wf-map-chrome-bl', 'wf-map-counts-footer', 'photocast-scored-legend',
];

/**
 * How much vertical space the app's own shell takes above the map frame (masthead + tab strip).
 *
 * ⚠️ A BAND, not a figure, and deliberately so. The harness does not mount the masthead, so the
 * frame it measures is the whole viewport — which is optimistic: more room for labels, more spots
 * in view. Rather than invent a number (the mistake `ALWAYS_ON` records), the sweep runs at both
 * ends and reports whether the conclusions differ. 175 = CLAUDE.md's own 128px masthead figure plus
 * `.wf-tabs`' `12px + 4px` padding around a 12.5px/8px-padded tab row.
 */
export const SHELL_CHROME_BAND = [0, 175];
