import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describeLogRedaction } from './nginxLogRedaction.js';

/**
 * Pins the parts of the repo-root `nginx.conf` the login/registration series depends on (plan §2 #8
 * and §1 #8, R6).
 *
 * <p>The syntax of the file is CI's `nginx -t` step; this file pins what a syntactically valid file
 * can still get wrong, and which nothing else would notice:
 *
 * <ul>
 *   <li>`navigator.geolocation` rejects in the document while its `Permissions-Policy` says
 *       `geolocation=()` — "Use my location" is dead with no error anywhere. The directive must read
 *       `geolocation=(self)` on EVERY response that sets it.
 *   <li>nginx's `add_header` inheritance: a location that sets ANY header inherits none, so the AASA
 *       block must repeat the whole security set, and must state its own type and cache policy.
 * </ul>
 *
 * <p>Comments are stripped before anything is matched, so prose quoting a directive (the file's own
 * notes do) can neither satisfy nor fail an assertion.
 */
const conf = readFileSync(resolve(process.cwd(), '../nginx.conf'), 'utf8');
const code = conf
  .split('\n')
  .map((line) => line.replace(/^\s*#.*$/, ''))
  .join('\n');

/** The body of `location <selector> { ... }`; the blocks in this file contain no nested braces. */
const block = (selector) => {
  const start = code.indexOf(`location ${selector} {`);
  expect(start, `location ${selector} must exist in nginx.conf`).toBeGreaterThan(-1);
  const open = code.indexOf('{', start);
  const close = code.indexOf('}', open);
  return code.slice(open + 1, close);
};

const directives = (body) =>
  body
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean);

describeLogRedaction('nginx.conf', code, block('/assets/'));

const AASA = '= /.well-known/apple-app-site-association';
const PERMISSIONS =
  'add_header Permissions-Policy "geolocation=(self), camera=(), microphone=(), payment=(), usb=()" always;';

const SECURITY_HEADERS = [
  'add_header X-Content-Type-Options "nosniff" always;',
  'add_header X-Frame-Options "DENY" always;',
  'add_header Referrer-Policy "strict-origin-when-cross-origin" always;',
  PERMISSIONS,
  'add_header Cross-Origin-Opener-Policy "same-origin" always;',
  'add_header Content-Security-Policy $csp always;',
];

describe('nginx.conf permissions policy', () => {
  it('allows geolocation for the page itself, in every Permissions-Policy line', () => {
    const lines = directives(code).filter((l) => l.startsWith('add_header Permissions-Policy'));
    expect(lines).toHaveLength(3);
    for (const line of lines) {
      expect(line).toBe(PERMISSIONS);
    }
  });

  it('never leaves geolocation disabled or open to every origin anywhere', () => {
    expect(code).not.toContain('geolocation=()');
    expect(code).not.toMatch(/geolocation=\(\s*\*/);
    expect(code).not.toContain('geolocation=*');
  });

  it('keeps the other powerful features switched off', () => {
    for (const feature of ['camera=()', 'microphone=()', 'payment=()', 'usb=()']) {
      expect(code.split(feature)).toHaveLength(4);
    }
  });

  it.each(['/assets/', '/', AASA])('sends the policy from location %s', (selector) => {
    expect(directives(block(selector))).toContain(PERMISSIONS);
  });
});

describe('nginx.conf apple-app-site-association location', () => {
  it('is an exact-match location, so no prefix block (and not the SPA fallback) can answer it', () => {
    expect(code).toContain(`location ${AASA} {`);
    // The only location naming .well-known is the exact one (a `^~` or plain prefix twin is refused).
    expect(code.match(/^\s*location\s[^{]*\.well-known[^{]*\{/gm)).toHaveLength(1);
    expect(code).not.toMatch(/location\s+~/);
  });

  it('serves the file as application/json with revalidation, and nothing else of its own', () => {
    expect(directives(block(AASA))).toEqual([
      'default_type application/json;',
      'add_header Cache-Control "no-cache";',
      ...SECURITY_HEADERS,
    ]);
  });

  it('repeats the full security set the root block repeats, because it inherits none', () => {
    const aasa = directives(block(AASA));
    const root = directives(block('/'));
    for (const header of SECURITY_HEADERS) {
      expect(root).toContain(header);
      expect(aasa).toContain(header);
    }
  });

  it('does not rewrite to index.html, which would answer Apple with HTML', () => {
    expect(block(AASA)).not.toContain('try_files');
    expect(block(AASA)).not.toContain('index.html');
  });

  it('keeps the SPA fallback on the root location, untouched', () => {
    expect(directives(block('/'))).toContain('try_files $uri $uri/ /index.html;');
  });
});
