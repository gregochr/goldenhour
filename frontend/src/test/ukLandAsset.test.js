import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { feature } from 'topojson-client';
import vendored from '../assets/uk-land-50m.json';

/**
 * The committed coastline is the one artefact in the heat feature that nobody will ever diff.
 * `scripts/generate-uk-land.mjs` produced it once and will essentially never run again, so nothing
 * else in the repo can tell you whether the blob and the script that claims to produce it still
 * agree — or whether a hand-edit, a `prettier --write src`, or a `world-atlas` bump has moved the
 * coastline out from under six thumbnails and a full-width map.
 *
 * <p>This is that check, and it is deliberately a comparison against the UPSTREAM package rather
 * than against a stored hash: a hash pins the bytes but tells you nothing about whether they are
 * still the right geometry, and it has to be updated by the same hand that would break it.
 */
const require = createRequire(import.meta.url);
const UPSTREAM = JSON.parse(readFileSync(require.resolve('world-atlas/countries-50m.json'), 'utf8'));

/** ISO 3166-1 numeric for the United Kingdom. */
const UK_ID = '826';

describe('uk-land-50m.json — the vendored coastline', () => {
  const decoded = feature(vendored, vendored.objects.countries);
  const upstreamUk = feature(UPSTREAM, UPSTREAM.objects.countries).features.find(
    (f) => String(f.id) === UK_ID,
  );

  it('decodes to exactly the geometry world-atlas ships for id 826', () => {
    // The generator keeps the upstream `transform` untouched and reuses the arc integers verbatim,
    // precisely so this equality holds — the vendored outline is bit-identical to the one the CDN
    // would have served, which is what the design was drawn against. If this fails, either the
    // asset drifted or the generator stopped being a faithful subset; both are the same bug.
    expect(decoded.features).toHaveLength(1);
    expect(JSON.stringify(decoded.features[0].geometry)).toBe(
      JSON.stringify(upstreamUk.geometry),
    );
    expect(decoded.features[0].id).toBe(UK_ID);
    expect(decoded.features[0].properties).toEqual(upstreamUk.properties);
  });

  it('carries only the arcs the UK references, reindexed and in range', () => {
    // 24 of the upstream's 1,959. The saving is the whole point of pruning; an out-of-range index
    // would decode as garbage coastline rather than throwing.
    expect(vendored.arcs).toHaveLength(24);
    expect(UPSTREAM.arcs.length).toBeGreaterThan(1900);
    const refs = JSON.stringify(vendored.objects.countries.geometries[0].arcs)
      .match(/-?\d+/g)
      .map(Number);
    refs.forEach((i) => {
      const forward = i < 0 ? ~i : i;
      expect(forward).toBeGreaterThanOrEqual(0);
      expect(forward).toBeLessThan(vendored.arcs.length);
    });
  });

  it('keeps the upstream transform, which is what makes the arcs reusable verbatim', () => {
    expect(vendored.transform).toEqual(UPSTREAM.transform);
  });

  it('states a bbox that matches the geometry it actually carries', () => {
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
    decoded.features.forEach((f) => visit(f.geometry.coordinates));
    expect(vendored.bbox).toEqual(box);
  });

  it('spans the whole United Kingdom and excludes the Republic of Ireland', () => {
    // Southernmost Scilly to northernmost Shetland, Western Isles to the Norfolk coast. A filter
    // that silently caught the wrong id, or dropped the offshore isles, moves these numbers.
    const [west, south, east, north] = vendored.bbox;
    expect(south).toBeCloseTo(50.02, 1);
    expect(north).toBeCloseTo(60.83, 1);
    expect(west).toBeCloseTo(-8.15, 1);
    expect(east).toBeCloseTo(1.75, 1);
    // Great Britain, Northern Ireland and the isles — one MultiPolygon, many rings.
    expect(decoded.features[0].geometry.type).toBe('MultiPolygon');
    expect(decoded.features[0].geometry.coordinates.length).toBeGreaterThan(20);
  });

  it('stays minified — a formatter run would nearly double it and ping-pong the diff', () => {
    // `npm run format` is `prettier --write src`, and the asset lives under src/. .prettierignore
    // holds it out; if that ever lapses the file reflows to ~1,080 lines and the next generator
    // run flips it straight back.
    const raw = readFileSync(require.resolve('../assets/uk-land-50m.json'), 'utf8');
    expect(raw.split('\n')).toHaveLength(2); // one line of JSON plus the trailing newline
    expect(raw.length).toBeLessThan(12000);
  });
});
