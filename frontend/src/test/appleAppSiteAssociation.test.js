import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Pins the committed `apple-app-site-association` file (login/registration plan §2 #8, R6).
 *
 * <p>Nothing in the app reads this file: Apple's CDN fetches it from the production origin to decide
 * which links the native app may claim. So a typo ships silently — the file is still valid JSON, the
 * app still works, and Universal Links just never open. Each assertion below is a way that has to
 * stay true for the claim to work, and the placeholders are pinned so a half-edit (one of the two
 * replaced) cannot land unnoticed.
 */
const AASA_PATH = resolve(process.cwd(), 'public/.well-known/apple-app-site-association');
const PLACEHOLDER = 'TEAMID.BUNDLEID';

const raw = () => readFileSync(AASA_PATH, 'utf8');
const parsed = () => JSON.parse(raw());

describe('apple-app-site-association', () => {
  it('lives at the extensionless path Vite copies into dist/.well-known', () => {
    expect(existsSync(AASA_PATH)).toBe(true);
  });

  it('is plain JSON with no BOM', () => {
    expect(raw().charCodeAt(0)).not.toBe(0xfeff);
    expect(() => JSON.parse(raw())).not.toThrow();
  });

  it('claims exactly the email-link paths (fragment form, older query form, sub-paths), in order, and nothing else', () => {
    const { applinks } = parsed();
    expect(applinks.details).toHaveLength(1);
    expect(applinks.details[0].components).toEqual([
      { '/': '/verify', '#': 'token=*' },
      { '/': '/verify', '?': { token: '*' } },
      { '/': '/verify/*' },
      { '/': '/reset', '#': 'token=*' },
      { '/': '/reset', '?': { token: '*' } },
      { '/': '/reset/*' },
    ]);
  });

  it('never claims a broad wildcard that would capture the whole app', () => {
    const paths = parsed().applinks.details[0].components.map((c) => c['/']);
    for (const path of paths) {
      expect(path).toMatch(/^\/(verify|reset)(\/\*)?$/);
    }
    expect(paths).not.toContain('*');
    expect(paths).not.toContain('/*');
  });

  it('names the TEAMID.BUNDLEID placeholder for both the link claim and shared credentials', () => {
    const doc = parsed();
    expect(doc.applinks.details[0].appIDs).toEqual([PLACEHOLDER]);
    expect(doc.webcredentials).toEqual({ apps: [PLACEHOLDER] });
  });

  it('carries the placeholder exactly twice, so replacing one and forgetting the other is caught', () => {
    expect(raw().split(PLACEHOLDER)).toHaveLength(3);
  });

  it('has no other top-level section', () => {
    expect(Object.keys(parsed()).sort()).toEqual(['applinks', 'webcredentials']);
  });
});
