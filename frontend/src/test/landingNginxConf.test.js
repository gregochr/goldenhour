import { describe, it, expect } from 'vitest';
import { bodyAfter, describeLogRedaction, readCode } from './nginxLogRedaction.js';

/** Pins `landing/nginx.conf` (the public site's image). Syntax is CI's `nginx -t`; this is what that cannot see. */
const code = readCode('../landing/nginx.conf');
const ASSETS = 'location ~* \\.(jpg|jpeg|png|gif|svg|ico|css|js|webp)$';

describeLogRedaction('landing/nginx.conf', code, bodyAfter(code, ASSETS));

describe('landing/nginx.conf static-asset location', () => {
  it('still caches fingerprinted assets', () => {
    expect(bodyAfter(code, ASSETS)).toContain('expires 30d;');
  });
});

describe('landing/nginx.conf has no API proxy', () => {
  it('serves static files only, so no stream token can arrive', () => {
    expect(code).not.toContain('proxy_pass');
  });
});

describe('landing/nginx.conf guide locations', () => {
  const guide = bodyAfter(code, 'location /guide/');
  const redirect = bodyAfter(code, 'location = /guide');
  const root = bodyAfter(code, 'location / ');
  const headers = readCode('../landing/security-headers.conf');

  it('sends the app-only frame-ancestors CSP on every guide response', () => {
    expect(guide).toMatch(
      /^\s*add_header\s+Content-Security-Policy\s+"frame-ancestors https:\/\/app\.photocast\.online"\s+always;/m,
    );
  });

  it('sends no X-Frame-Options on guide documents, which would refuse the app as well', () => {
    expect(guide).not.toMatch(/X-Frame-Options/i);
    expect(guide).not.toContain('security-headers.conf');
  });

  it('repeats nosniff and Referrer-Policy, since a location with add_header inherits none', () => {
    expect(guide).toMatch(/add_header\s+X-Content-Type-Options\s+"nosniff"\s+always;/);
    expect(guide).toMatch(/add_header\s+Referrer-Policy\s+"strict-origin-when-cross-origin"\s+always;/);
    expect(guide).toMatch(/add_header\s+Cache-Control\s+"no-cache"\s+always;/);
  });

  it('keeps a mistyped guide URL inside /guide/ with its own 404 page', () => {
    expect(guide).toMatch(/^\s*error_page\s+404\s+\/guide\/index\.html;/m);
    expect(guide).toMatch(/try_files\s+\$uri\s+\$uri\/\s+=404;/);
  });

  it('redirects /guide to /guide/ with a relative Location', () => {
    expect(redirect).toMatch(/^\s*return\s+301\s+\/guide\/;/m);
    expect(redirect).toMatch(/^\s*absolute_redirect\s+off;/m);
  });

  it('leaves every other page SAMEORIGIN through security-headers.conf', () => {
    expect(root).toContain('include /etc/nginx/security-headers.conf;');
    expect(headers).toContain('add_header X-Frame-Options "SAMEORIGIN" always;');
  });
});
