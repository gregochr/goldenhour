import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';
import { USERS } from './fixture.js';
import { startStubSite } from './deploy-smoke-stub.js';

/**
 * The production post-deploy smoke script, run against the seeded stack (docs/engineering/engineering-gaps-plan.md,
 * Phase 4, R18). It lives in the `deploy-smoke` project, which starts only after `smoke`, `roles-write` and `logout`
 * have finished with both servers still up, because logging in as `pro` and logging out bumps that account's
 * credentials version, which would cancel any browser session still using it.
 *
 * Why the script runs through an ASYNC spawn and not spawnSync: the negative cases point it at a stub site served
 * from THIS process, and a blocking call would stop that server answering (a deadlock).
 *
 * CI runs the backend with Turnstile unconfigured, so the login goes through the OK_VERIFIED path, not the
 * production fallback that admits a login with no Turnstile token (`TurnstileGateTest` covers the fallback).
 *
 * Exit codes: 0 pass, 1 release broken, 3 inconclusive or infrastructure, 4 smoke misconfigured.
 */
const SCRIPT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../../../scripts/post-deploy-smoke.sh',
);

/**
 * @param {Record<string, string>} env variables for the script, on top of a clean base
 * @returns {Promise<{code: number, out: string}>} exit code and combined output
 */
function runSmoke(env) {
  return new Promise((resolve, reject) => {
    const child = spawn('bash', [SCRIPT], {
      env: {
        PATH: process.env.PATH,
        HOME: process.env.HOME,
        SMOKE_RETRY_SECONDS: '2',
        SMOKE_RETRY_INTERVAL: '1',
        SMOKE_DEADLINE_SECONDS: '40',
        ...env,
      },
    });
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, out }));
  });
}

test.describe('post-deploy smoke script', () => {
  test.setTimeout(90_000);

  const pro = USERS.pro;

  test('passes against the seeded stack behind the production nginx config, with PRO ratings', async ({ baseURL }) => {
    const { code, out } = await runSmoke({
      APP_URL_BASE: baseURL,
      SMOKE_DEPLOY_USERNAME: pro.username,
      SMOKE_DEPLOY_PASSWORD: pro.password,
    });
    expect(code, out).toBe(0);
    expect(out).toContain('ok  shell:');
    expect(out).toContain('ok  login:');
    expect(out).toMatch(/ok {2}briefing: .*[1-9][0-9]* served rating\(s\)/);
    expect(out).toContain('ok  logout:');
    expect(out, 'the password is never printed').not.toContain(pro.password);
  });

  test('a wrong password is a misconfigured smoke (4), not a broken release', async ({ baseURL }) => {
    // An account that does not exist, so no real account's failed-sign-in counter moves (a repeated run would
    // otherwise lock it out); the script treats an unknown account and a wrong password identically (401).
    const { code, out } = await runSmoke({
      APP_URL_BASE: baseURL,
      SMOKE_DEPLOY_USERNAME: 'smoke-no-such-user',
      SMOKE_DEPLOY_PASSWORD: 'Not-The-Password-1!',
    });
    expect(code, out).toBe(4);
    expect(out).toContain('POST /api/auth/login -> 401');
    expect(out).toContain('[smoke misconfigured]');
    expect(out).not.toContain('Not-The-Password-1!');
  });

  test('an unset password skips the logged-in probes with a warning, unless login is required', async ({ baseURL }) => {
    const skipped = await runSmoke({ APP_URL_BASE: baseURL });
    expect(skipped.code, skipped.out).toBe(0);
    expect(skipped.out).toContain('logged-in probes SKIPPED');

    const required = await runSmoke({ APP_URL_BASE: baseURL, SMOKE_LOGIN_REQUIRED: 'true' });
    expect(required.code, required.out).toBe(4);
    expect(required.out).toContain('SMOKE_LOGIN_REQUIRED=true');
  });

  test('nothing listening is inconclusive (3), never a broken release', async () => {
    const { code, out } = await runSmoke({ APP_URL_BASE: 'http://127.0.0.1:9' });
    expect(code, out).toBe(3);
    expect(out).toContain('no response');
  });

  test.describe('against a stub site that breaks one thing', () => {
    let stub;
    test.afterEach(async () => {
      await stub?.close();
      stub = undefined;
    });

    const withStub = async (scenario) => {
      stub = await startStubSite(scenario);
      return stub;
    };
    const login = { SMOKE_DEPLOY_USERNAME: 'smoke-deploy', SMOKE_DEPLOY_PASSWORD: 'Stub-Password-1!' };

    test('the stub with nothing broken passes (so each failure below is its own doing)', async () => {
      const site = await withStub({});
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(0);
      expect(site.state.logouts, 'the smoke logs out').toBe(1);
    });

    test('a missing Content-Security-Policy is a broken release (1)', async () => {
      const site = await withStub({ noCsp: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(1);
      expect(out).toContain('no Content-Security-Policy header');
    });

    test('a 500 from health is a broken release (1)', async () => {
      const site = await withStub({ healthStatus: 500 });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(1);
      expect(out).toContain('/actuator/health/container -> 500');
    });

    test('a 204 briefing is a broken release (1)', async () => {
      const site = await withStub({ briefing204: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(1);
      expect(out).toContain('-> 204');
      expect(site.state.logouts, 'it still logs out').toBe(1);
    });

    test('a Cloudflare challenge is inconclusive (3), and ends the run at once', async () => {
      const site = await withStub({ challenge: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(3);
      expect(out).toContain('Cloudflare challenge');
      expect(out, 'nothing after the first probe ran').not.toContain('backend health');
      expect(site.state.loginAttempts, 'no login was attempted').toBe(0);
    });

    test('passwordChangeRequired is a misconfigured smoke (4) that names the cause', async () => {
      const site = await withStub({ login: 'passwordChangeRequired' });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(4);
      expect(out).toContain('passwordChangeRequired=true');
      expect(site.state.logouts, 'it still logs the flagged session out').toBe(1);
    });

    test('a login 429 is a misconfigured smoke (4) and is attempted exactly once', async () => {
      // SMOKE_RETRY_SECONDS is large on purpose: the proof that a refused login is never retried is that it is
      // not retried even when the retry window is open.
      const site = await withStub({ login: '429' });
      const { code, out } = await runSmoke({
        APP_URL_BASE: site.url, ...login, SMOKE_RETRY_SECONDS: '30',
      });
      expect(code, out).toBe(4);
      expect(site.state.loginAttempts).toBe(1);
    });
  });
});
