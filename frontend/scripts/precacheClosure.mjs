// Pure helpers shared by vite.config.js (which chunks the service worker precaches) and
// scripts/check-entry-closure.mjs (which proves the generated sw.js precached them). Kept free of
// Vite and Workbox so a unit test can drive them with a fake file reader.
import { posix } from 'node:path';

/** Precache-style path of the lazy sign-in chunk: not in the entry closure, but needed offline. */
export const AUTH_PAGES_RE = /^assets\/AuthPages-[^/]*\.js$/;

/**
 * Every `/assets/*` href that `index.html` loads before first paint: script, modulepreload and
 * stylesheet tags (and `preload` links when `includePreload` is set).
 *
 * @param {string} html the text of dist/index.html
 * @returns {Map<string, string>} href as written (leading slash) to its kind
 */
export function htmlAssetRefs(html, { includePreload = false } = {}) {
  const kinds = includePreload ? ['modulepreload', 'stylesheet', 'preload'] : ['modulepreload', 'stylesheet'];
  const refs = new Map();
  for (const [tag] of html.matchAll(/<(?:script|link)\b[^>]*>/gi)) {
    const isScript = tag.toLowerCase().startsWith('<script');
    const rel = /\brel="([^"]*)"/i.exec(tag)?.[1] ?? '';
    const href = /\b(?:href|src)="([^"]*)"/i.exec(tag)?.[1];
    if (!href || !href.includes('/assets/')) continue;
    if (isScript || kinds.includes(rel)) refs.set(href, isScript ? 'script' : rel);
  }
  return refs;
}

/**
 * The STATIC relative imports of one built chunk (`from"./x.js"`, `import"./x.js"`), resolved
 * against the chunk's own directory. Dynamic `import("./x.js")` is deliberately not matched: a
 * lazy chunk is fetched on demand and runtime-cached, not part of the closure.
 *
 * @param {string} url precache-style path of the importing chunk (`assets/a.js`)
 * @param {string} source the chunk's text
 * @returns {string[]} precache-style paths
 */
export function staticImports(url, source) {
  const found = [];
  for (const m of source.matchAll(/(?:\bfrom|\bimport)\s*["'](\.{1,2}\/[^"']+\.js)["']/g)) {
    found.push(posix.normalize(posix.join(posix.dirname(url), m[1])));
  }
  return found;
}

/**
 * Expands seed chunk paths with everything they statically import, transitively.
 *
 * @param {Iterable<string>} seeds precache-style `.js` paths
 * @param {(url: string) => string|null} readChunk returns a chunk's text, or null when absent
 * @returns {Set<string>} the seeds plus their whole static import closure
 */
export function expandStaticImports(seeds, readChunk) {
  const closure = new Set();
  const queue = [...seeds];
  while (queue.length) {
    const url = queue.pop();
    if (closure.has(url)) continue;
    closure.add(url);
    if (!url.endsWith('.js')) continue;
    const source = readChunk(url);
    if (source == null) continue;
    queue.push(...staticImports(url, source));
  }
  return closure;
}

/**
 * Everything the precache must hold for a cold start and an offline sign-in: the entry closure
 * from index.html, every AuthPages chunk, and the whole static import closure of both.
 *
 * @param {string} html text of dist/index.html
 * @param {string[]} builtAssets precache-style paths of every built file (to find AuthPages)
 * @param {(url: string) => string|null} readChunk chunk reader
 * @returns {Set<string>} precache-style paths
 */
export function requiredPrecache(html, builtAssets, readChunk) {
  const seeds = [...htmlAssetRefs(html).keys()].map((href) => href.replace(/^\//, ''));
  seeds.push(...builtAssets.filter((url) => AUTH_PAGES_RE.test(url)));
  return expandStaticImports(seeds, readChunk);
}

/**
 * The Workbox manifest filter: non-JS entries (css, html, fonts) pass; a JS entry passes only when
 * it is in the required closure.
 *
 * @param {Array<{url: string}>} entries Workbox manifest entries
 * @param {Set<string>} required result of {@link requiredPrecache}
 * @returns {{manifest: Array<{url: string}>, warnings: string[]}}
 */
export function filterManifest(entries, required) {
  const manifest = entries.filter((entry) => !entry.url.endsWith('.js') || required.has(entry.url));
  const have = new Set(manifest.map((entry) => entry.url));
  const warnings = [...required]
    .filter((url) => !have.has(url))
    .map((url) => `precache: required file not precached: ${url}`);
  return { manifest, warnings };
}
