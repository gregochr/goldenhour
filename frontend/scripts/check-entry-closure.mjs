#!/usr/bin/env node
// Run after `npm run build`. Reads dist/index.html and dist/sw.js and fails if the first-load closure
// (the entry script, its modulepreloads and its stylesheets) pulls in Leaflet, its CSS, or the
// lazy-only chart/geo/heat-field chunks, or if any closure file (or AuthPages) is missing from the
// service worker's precache list. Prints each closure file with raw and gzip sizes.
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { AUTH_PAGES_RE, requiredPrecache } from './precacheClosure.mjs';

const dist = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const htmlPath = join(dist, 'index.html');
if (!existsSync(htmlPath)) {
  console.error('dist/index.html not found: run `npm run build` first.');
  process.exit(2);
}
const html = readFileSync(htmlPath, 'utf8');

const refs = new Map(); // href -> kind
for (const [tag] of html.matchAll(/<(?:script|link)\b[^>]*>/gi)) {
  const isScript = tag.toLowerCase().startsWith('<script');
  const rel = /\brel="([^"]*)"/i.exec(tag)?.[1] ?? '';
  const href = /\b(?:href|src)="([^"]*)"/i.exec(tag)?.[1];
  if (!href || !href.includes('/assets/')) continue;
  if (isScript || ['modulepreload', 'stylesheet', 'preload'].includes(rel)) {
    refs.set(href, isScript ? 'script' : rel);
  }
}

const forbidden = [
  [/leaflet/i, 'leaflet'],
  [/\/(?:recharts|geo|heatField)[-.]/i, 'recharts/geo/heatField'],
];
let rawTotal = 0;
let gzTotal = 0;
const violations = [];
for (const [href, kind] of refs) {
  const file = join(dist, href.replace(/^\//, ''));
  const buf = existsSync(file) ? readFileSync(file) : Buffer.alloc(0);
  const gz = gzipSync(buf).length;
  rawTotal += buf.length;
  gzTotal += gz;
  console.log(
    `${kind.padEnd(12)} ${href}  ${(buf.length / 1024).toFixed(2)} KB raw  ${(gz / 1024).toFixed(2)} KB gzip`,
  );
  for (const [re, label] of forbidden) {
    if (re.test(href)) violations.push(`${kind} ${href} (${label})`);
  }
}
console.log(`entry closure total: ${(rawTotal / 1024).toFixed(2)} KB raw, ${(gzTotal / 1024).toFixed(2)} KB gzip`);
// The service worker's precache list must hold every file the first load needs, plus AuthPages
// (offline sign-in). dist/sw.js is generated after index.html, so a closure file missing here would
// be fetched from the network on every cold start.
const swPath = join(dist, 'sw.js');
if (!existsSync(swPath)) {
  console.error('dist/sw.js not found: run `npm run build` first.');
  process.exit(2);
}
const sw = readFileSync(swPath, 'utf8');
const precached = new Set([...sw.matchAll(/url:\s*"([^"]+)"/g)].map((m) => m[1]));
// The WHOLE static import closure of the entry and of AuthPages, not just the tags in index.html:
// AuthPages is lazy but imports shared chunks (PasswordInput ...), and an offline reload to sign-in
// needs every one of them.
const built = readdirSync(join(dist, 'assets')).map((name) => `assets/${name}`);
if (!built.some((url) => AUTH_PAGES_RE.test(url))) {
  violations.push('assets/AuthPages-*.js (not built)');
}
const required = requiredPrecache(html, built, (url) => {
  const file = join(dist, url);
  return existsSync(file) ? readFileSync(file, 'utf8') : null;
});
for (const url of required) {
  if (!precached.has(url)) violations.push(`/${url} (in the entry or AuthPages static closure, not in the sw.js precache)`);
}
console.log(`precache entries: ${precached.size}`);

if (violations.length) {
  console.error(`Entry closure check failed:\n  ${violations.join('\n  ')}`);
  process.exit(1);
}
console.log('entry closure OK');
