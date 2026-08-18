#!/usr/bin/env node
/**
 * Generates the vendored UK land geometry used by the heat field's geo host
 * (`src/utils/heatField.js` → `load()` → `drawGeo`).
 *
 * WHY THIS EXISTS AT ALL — the design prototype fetched `world-atlas@2.0.2/countries-50m.json`
 * from a public package CDN at runtime, via `d3.json`. That is an `XMLHttpRequest`/`fetch`, so it
 * is governed by the CSP's `connect-src`, which `nginx.conf` sets to `'self'` plus the postcode
 * geocoder alone — the fetch simply cannot succeed in production, and adding an origin to the CSP
 * to buy one static file is the wrong trade. (The CSP does name a CDN elsewhere: `img-src` allows
 * `basemaps.cartocdn.com` for the Leaflet tiles. That is a different directive and a different
 * question — it does not make a geometry fetch possible.) The geometry is therefore generated
 * once, here, and committed as a build asset. Nothing about the coastline is fetched at runtime.
 *
 * PROVENANCE AND LICENCE
 *   Source data : `world-atlas@2.0.2`, file `countries-50m.json` (npm package, ISC licence,
 *                 © 2013–2019 Michael Bostock). Installed here as a devDependency so this script
 *                 runs offline and reproducibly after a plain `npm ci`.
 *   Upstream of that : Natural Earth 1:50m Cultural Vectors — public domain, no permission or
 *                 credit required (credit given anyway).
 *   Selection   : the single `MultiPolygon` geometry with ISO 3166-1 numeric id `826`
 *                 (United Kingdom). Great Britain, Northern Ireland and the offshore isles;
 *                 the Republic of Ireland is deliberately not included, and the shared land
 *                 border arc is retained because the UK ring references it.
 *
 * WHAT IT PRODUCES — a TopoJSON topology, not GeoJSON, and with the upstream `transform` left
 * exactly as found. Two reasons, and both matter:
 *   1. Arcs stay delta-encoded quantized integers, which is roughly a fifth of the bytes the
 *      equivalent GeoJSON float coordinates would cost.
 *   2. Because the transform is untouched, the arc integers are reused verbatim — the vendored
 *      coastline is bit-identical to the one the prototype drew from the CDN. Re-quantizing to
 *      the UK bbox would give a finer outline than the design was drawn against, which is a
 *      change nobody asked for dressed up as an improvement.
 * Only the *arcs actually referenced by the UK* are kept, and their indices are rewritten to
 * match the pruned array (the upstream arc table is global — 1,959 arcs for 241 countries).
 *
 * RUN IT
 *   cd frontend && node scripts/generate-uk-land.mjs [path/to/countries-50m.json]
 * Re-run only to take an upstream geometry update; the output is committed and the app never
 * regenerates it.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { feature } from 'topojson-client';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_SOURCE = resolve(HERE, '../node_modules/world-atlas/countries-50m.json');
const OUTPUT = resolve(HERE, '../src/assets/uk-land-50m.json');

/** ISO 3166-1 numeric country code for the United Kingdom. */
const UK_ID = '826';

/**
 * Rewrites one arc index against a keep-list, recording first use.
 * TopoJSON encodes a reversed arc as the one's-complement `~i`, so the sign has to survive
 * the remap — dropping it silently reverses a ring's winding, which reads as the whole globe.
 */
function remapper(keep) {
  return function remap(index) {
    const forward = index < 0 ? ~index : index;
    if (!keep.has(forward)) keep.set(forward, keep.size);
    const mapped = keep.get(forward);
    return index < 0 ? ~mapped : mapped;
  };
}

/** Recursively remaps a geometry's nested arc-index structure (MultiPolygon → [poly][ring][arc]). */
function remapArcs(arcs, remap) {
  return Array.isArray(arcs[0]) ? arcs.map((a) => remapArcs(a, remap)) : arcs.map(remap);
}

/** [minLng, minLat, maxLng, maxLat] over a decoded GeoJSON FeatureCollection's positions. */
function bboxOf(collection) {
  const box = [Infinity, Infinity, -Infinity, -Infinity];
  const visit = (node) => {
    if (typeof node[0] === 'number') {
      box[0] = Math.min(box[0], node[0]);
      box[1] = Math.min(box[1], node[1]);
      box[2] = Math.max(box[2], node[0]);
      box[3] = Math.max(box[3], node[1]);
      return;
    }
    node.forEach(visit);
  };
  collection.features.forEach((f) => visit(f.geometry.coordinates));
  return box;
}

const sourcePath = process.argv[2] ? resolve(process.argv[2]) : DEFAULT_SOURCE;
let world;
try {
  world = JSON.parse(readFileSync(sourcePath, 'utf8'));
} catch (err) {
  console.error(`Could not read source topology at ${sourcePath}\n  ${err.message}`);
  console.error('Expected world-atlas@2.0.2 (a devDependency) — run `npm ci` first, or pass a path.');
  process.exit(1);
}

const geometries = world.objects?.countries?.geometries ?? [];
const uk = geometries.filter((g) => String(g.id) === UK_ID);
if (uk.length !== 1) {
  console.error(`Expected exactly one geometry with id ${UK_ID}, found ${uk.length}.`);
  process.exit(1);
}

const keep = new Map();
const remap = remapper(keep);
const geometry = {
  type: uk[0].type,
  id: uk[0].id,
  properties: uk[0].properties,
  arcs: remapArcs(uk[0].arcs, remap),
};

const topology = {
  type: 'Topology',
  // Untouched: the arc integers below are encoded against this exact transform.
  transform: world.transform,
  objects: { countries: { type: 'GeometryCollection', geometries: [geometry] } },
  arcs: [...keep.keys()].map((original) => world.arcs[original]),
};
topology.bbox = bboxOf(feature(topology, topology.objects.countries));

writeFileSync(OUTPUT, `${JSON.stringify(topology)}\n`);

const sourceKb = Math.round(readFileSync(sourcePath).length / 1024);
const outKb = Math.round(readFileSync(OUTPUT).length / 1024);
console.log(`world-atlas countries-50m: ${sourceKb} KB, ${world.arcs.length} arcs`);
console.log(`uk-land-50m.json:          ${outKb} KB, ${topology.arcs.length} arcs`);
console.log(`bbox: [${topology.bbox.map((n) => n.toFixed(3)).join(', ')}]`);
console.log(`written: ${OUTPUT}`);
