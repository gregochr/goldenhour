import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** An nginx config with whole-line comments removed, so prose quoting a directive cannot satisfy a pin. */
export const readCode = (relativePath) =>
  readFileSync(resolve(process.cwd(), relativePath), 'utf8')
    .split('\n')
    .map((line) => line.replace(/^\s*#.*$/, ''))
    .join('\n');

/** The body of the first `{ ... }` after `marker` (the blocks pinned here contain no nested braces). */
export const bodyAfter = (code, marker) => {
  const start = code.indexOf(marker);
  expect(start, `${marker} must exist`).toBeGreaterThan(-1);
  const open = code.indexOf('{', start);
  return code.slice(open + 1, code.indexOf('}', open));
};

/**
 * Pins that the access log never writes a query string (one-time link tokens, stream tokens); the
 * plan is `docs/engineering/token-log-redaction-plan.md` step 1. CI's canary run proves the behaviour;
 * these pins catch a regression at unit-test time.
 */
export const describeLogRedaction = (label, code, assetsBody) => {
  const start = code.indexOf('log_format redacted');
  const format = start < 0 ? '' : code.slice(start, code.indexOf(';', start));

  describe(`${label} access-log redaction`, () => {
    it('writes the access log in the redacted format, not the default', () => {
      expect(code).toMatch(/^\s*access_log\s+\/var\/log\/nginx\/access\.log\s+redacted;/m);
      expect(code.match(/^\s*access_log\s/gm)).toHaveLength(1);
    });

    it('defines a redacted format that logs only the sanitised path and referer', () => {
      expect(format).toContain('log_format redacted');
      expect(format).toContain('$log_path');
      expect(format).toContain('$log_referer');
    });

    it.each([
      ['the bare $request variable', /\$request(?![A-Za-z0-9_])/],
      ['$request_uri', /\$request_uri/],
      ['$args', /\$args(?![A-Za-z0-9_])/],
      ['$query_string', /\$query_string/],
      ['$http_referer', /\$http_referer/],
    ])('keeps %s out of the redacted format', (_name, pattern) => {
      expect(format).not.toMatch(pattern);
    });

    it('maps the request URI to its path, stopping at the query', () => {
      expect(bodyAfter(code, 'map $request_uri $log_path')).toMatch(/"~\^\(\?<\w+>\[\^\?\]\*\)"\s+\$\w+;/);
    });

    it('maps the referer to its URL without query or fragment', () => {
      expect(bodyAfter(code, 'map $http_referer $log_referer')).toMatch(/"~\^\(\?<\w+>\[\^\?#\]\*\)"\s+\$\w+;/);
    });

    it('uses distinct capture names in the two maps', () => {
      const p = bodyAfter(code, 'map $request_uri $log_path').match(/\(\?<(\w+)>/)[1];
      const r = bodyAfter(code, 'map $http_referer $log_referer').match(/\(\?<(\w+)>/)[1];
      expect(p).not.toBe(r);
    });

    it('does not log a missing static asset to the error log, which would record the Referer', () => {
      expect(assetsBody).toMatch(/^\s*log_not_found\s+off;/m);
    });
  });
};
