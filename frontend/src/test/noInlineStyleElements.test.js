import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

/**
 * Guards against an injected `<style>` element in app code.
 *
 * <p>The enforced Content-Security-Policy (repo-root `nginx.conf`) is `style-src 'self'` with no
 * `'unsafe-inline'`, so a `<style>` element created at runtime is silently blocked in production:
 * the page looks fine in dev and in tests and loses its styling only behind nginx. Put rules in a
 * stylesheet that Vite bundles (`index.css`, or a co-located `.css` the component imports).
 * `style={{...}}` props and `el.style.x = ...` are CSSOM and are not affected.
 */
const SRC = resolve(process.cwd(), 'src');

const walk = (dir) =>
  readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) return name === 'test' ? [] : walk(full);
    return /\.(js|jsx)$/.test(name) && !/\.test\.(js|jsx)$/.test(name) ? [full] : [];
  });

const FORBIDDEN = [/<style[\s>]/, /createElement\(\s*['"`]style['"`]/];

describe('app source', () => {
  it("never injects a <style> element, which style-src 'self' would block", () => {
    const offenders = walk(SRC)
      .filter((file) => {
        const text = readFileSync(file, 'utf8');
        return FORBIDDEN.some((re) => re.test(text));
      })
      .map((file) => relative(SRC, file));
    expect(offenders, `inject no <style> element (CSP style-src 'self'): ${offenders.join(', ')}`).toEqual([]);
  });
});
