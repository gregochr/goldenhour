import { describe, it, expect } from 'vitest';
import {
  expandStaticImports, filterManifest, htmlAssetRefs, requiredPrecache, staticImports,
} from '../../scripts/precacheClosure.mjs';

const HTML = `<html><head>
<script type="module" crossorigin src="/assets/index-A.js"></script>
<link rel="modulepreload" crossorigin href="/assets/react-R.js">
<link rel="stylesheet" crossorigin href="/assets/index-C.css">
<link rel="icon" href="/favicon.svg">
</head></html>`;

const CHUNKS = {
  'assets/index-A.js': 'import{a}from"./react-R.js";import"./side-S.js";const l=()=>import("./Lazy-L.js");',
  'assets/react-R.js': 'export const a=1;',
  'assets/side-S.js': 'export{};',
  'assets/Lazy-L.js': 'import{p}from"./PasswordInput-P.js";',
  'assets/AuthPages-X.js': "import{p as P}from'./PasswordInput-P.js';import{z}from\"./deep-D.js\";",
  'assets/PasswordInput-P.js': 'export const p=1;',
  'assets/deep-D.js': 'import{a}from"./react-R.js";',
};
const read = (url) => CHUNKS[url] ?? null;

describe('precacheClosure', () => {
  it('reads script, modulepreload and stylesheet refs from index.html only', () => {
    expect([...htmlAssetRefs(HTML).keys()]).toEqual([
      '/assets/index-A.js', '/assets/react-R.js', '/assets/index-C.css',
    ]);
  });

  it('finds static imports in both quote styles and ignores dynamic import()', () => {
    expect(staticImports('assets/index-A.js', CHUNKS['assets/index-A.js']))
      .toEqual(['assets/react-R.js', 'assets/side-S.js']);
    expect(staticImports('assets/AuthPages-X.js', CHUNKS['assets/AuthPages-X.js']))
      .toEqual(['assets/PasswordInput-P.js', 'assets/deep-D.js']);
  });

  it('expands transitively, once each, and tolerates a cycle or a missing file', () => {
    const cyc = { 'assets/a.js': 'import"./b.js";', 'assets/b.js': 'import"./a.js";import"./gone.js";' };
    expect([...expandStaticImports(['assets/a.js'], (u) => cyc[u] ?? null)].sort())
      .toEqual(['assets/a.js', 'assets/b.js', 'assets/gone.js']);
  });

  it('requires the entry closure plus AuthPages and everything AuthPages statically imports', () => {
    const built = Object.keys(CHUNKS);
    const required = requiredPrecache(HTML, built, read);
    expect(required).toContain('assets/PasswordInput-P.js'); // AuthPages' shared chunk
    expect(required).toContain('assets/deep-D.js');
    expect(required).toContain('assets/side-S.js');
    expect(required).toContain('assets/index-C.css');
    expect(required).not.toContain('assets/Lazy-L.js'); // dynamic: runtime-cached, not precached
  });

  it('filters JS to the required set, passes other files, and warns about a missing required file', () => {
    const required = new Set(['assets/index-A.js', 'assets/needed-N.js']);
    const entries = [
      { url: 'assets/index-A.js' }, { url: 'assets/Lazy-L.js' }, { url: 'assets/font.woff2' },
      { url: 'index.html' },
    ];
    const { manifest, warnings } = filterManifest(entries, required);
    expect(manifest.map((e) => e.url)).toEqual(['assets/index-A.js', 'assets/font.woff2', 'index.html']);
    expect(warnings).toEqual(['precache: required file not precached: assets/needed-N.js']);
  });
});
