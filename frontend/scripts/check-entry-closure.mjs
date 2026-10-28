#!/usr/bin/env node
// Run after `npm run build`. Reads dist/index.html and fails if the first-load closure (the entry
// script, its modulepreloads and its stylesheets) pulls in Leaflet, its CSS, or the lazy-only
// chart/geo/heat-field chunks. Prints each closure file with raw and gzip sizes.
import { readFileSync, existsSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

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
if (violations.length) {
  console.error(`First load must not include:\n  ${violations.join('\n  ')}`);
  process.exit(1);
}
console.log('entry closure OK');
