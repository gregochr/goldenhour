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
