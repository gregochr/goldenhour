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
  const frameMap = bodyAfter(code, 'map $uri $x_frame_options');
  const ancestorsMap = bodyAfter(code, 'map $uri $csp_frame_ancestors');

  it('gives the guide the same security headers as every other page, plus its own cache rule', () => {
    expect(guide).toContain('include /etc/nginx/security-headers.conf;');
    expect(guide).toMatch(/add_header\s+Cache-Control\s+"no-cache"\s+always;/);
  });

  it('sends no X-Frame-Options on guide documents, which would refuse the app as well', () => {
    expect(frameMap).toMatch(/default\s+"SAMEORIGIN";/);
    expect(frameMap).toMatch(/"~\^\/guide\/"\s+"";/);
    expect(guide).not.toMatch(/X-Frame-Options/i);
  });

  it('lets only the app frame the guide, and only the site itself frame anything else', () => {
    expect(ancestorsMap).toMatch(/default\s+"'self'";/);
    expect(ancestorsMap).toMatch(/"~\^\/guide\/"\s+"https:\/\/app\.photocast\.online";/);
  });

  it('keeps a mistyped guide URL inside /guide/ with its own 404 page', () => {
    expect(guide).toMatch(/^\s*error_page\s+404\s+\/guide\/index\.html;/m);
    expect(guide).toMatch(/try_files\s+\$uri\s+\$uri\/\s+=404;/);
  });

  it('redirects /guide to /guide/ with a relative Location', () => {
    expect(redirect).toMatch(/^\s*return\s+301\s+\/guide\/;/m);
    expect(redirect).toMatch(/^\s*absolute_redirect\s+off;/m);
  });
});

describe('landing security headers', () => {
  const headers = readCode('../landing/security-headers.conf');
  const root = bodyAfter(code, 'location / ');
  const assets = bodyAfter(code, ASSETS);
  const guide = bodyAfter(code, 'location /guide/');
  const csp = headers.match(/add_header\s+Content-Security-Policy\s+"([^"]+)"\s+always;/)?.[1] ?? '';
  const directives = Object.fromEntries(
    csp
      .split(';')
      .map((d) => d.trim())
      .filter(Boolean)
      .map((d) => [d.split(' ')[0], d.split(' ').slice(1).join(' ')]),
  );
  const INCLUDE = 'include /etc/nginx/security-headers.conf;';

  it.each([
    ['root', root],
    ['asset', assets],
    ['guide', guide],
  ])('includes the shared header file in the %s location, which sets its own add_header', (_name, body) => {
    expect(body).toContain(INCLUDE);
  });

  it('includes the shared header file at server level too', () => {
    expect(code).toMatch(/^ {8}include \/etc\/nginx\/security-headers\.conf;/m);
  });

  it('sets the full header set, including a Content-Security-Policy, in the shared file', () => {
    expect(headers).toContain('add_header X-Frame-Options $x_frame_options always;');
    expect(headers).toContain('add_header X-Content-Type-Options "nosniff" always;');
    expect(headers).toContain('add_header Referrer-Policy "strict-origin-when-cross-origin" always;');
    expect(headers).toContain(
      'add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;',
    );
    expect(headers).toContain('add_header Cross-Origin-Opener-Policy "same-origin" always;');
    expect(csp).not.toBe('');
  });

  it('has no X-XSS-Protection anywhere', () => {
    expect(headers).not.toMatch(/X-XSS-Protection/i);
    expect(code).not.toMatch(/X-XSS-Protection/i);
  });

  it('sets no header inline in a location except Cache-Control, which would shadow the shared set', () => {
    for (const body of [root, assets, guide]) {
      expect(body).not.toMatch(/add_header\s+(?!Cache-Control)/);
    }
  });

  it('commits no Strict-Transport-Security, which is a long-lived decision for the owner', () => {
    expect(headers + code).not.toMatch(/Strict-Transport-Security/i);
  });

  it('allows scripts only from the site itself, and no plugins', () => {
    expect(directives['default-src']).toBe("'self'");
    expect(directives['script-src']).toBe("'self'");
    expect(directives['object-src']).toBe("'none'");
    expect(directives['connect-src']).toBe("'self'");
    expect(csp).not.toContain('unsafe-eval');
  });

  it('allows styles from the site, inline and Google Fonts only; fonts from gstatic only', () => {
    expect(directives['style-src']).toBe("'self' 'unsafe-inline' https://fonts.googleapis.com");
    expect(directives['font-src']).toBe('https://fonts.gstatic.com');
    expect(directives['img-src']).toBe("'self' data:");
  });

  it('pins the base, form and upgrade directives', () => {
    expect(directives['base-uri']).toBe("'self'");
    expect(directives['form-action']).toBe("'self'");
    expect(directives).toHaveProperty('upgrade-insecure-requests');
  });

  it('takes frame-ancestors from the mapped variable, so /guide/ is the only place the app is let in', () => {
    expect(directives['frame-ancestors']).toBe('$csp_frame_ancestors');
    expect(csp).not.toContain('app.photocast.online');
  });
});
