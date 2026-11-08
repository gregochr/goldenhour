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
 * The cases against the seeded stack need the PRODUCTION nginx config (nginx mode, `E2E_WEB=nginx`, which CI sets):
 * `vite preview` sends no Content-Security-Policy and the shell probe would rightly fail. They skip elsewhere (a
 * laptop without Docker); the stub-site cases run in both modes.
 *
 * CI runs the backend with Turnstile unconfigured, so the login goes through the OK_VERIFIED path, not the
 * production fallback that admits a login with no Turnstile token (`TurnstileGateTest` covers the fallback).
 *
 * Exit codes: 0 pass, 1 release broken, 3 inconclusive or infrastructure, 4 smoke misconfigured; 1 outranks 4
 * outranks 3 when probes disagree.
 */
const SCRIPT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../../../scripts/post-deploy-smoke.sh',
);
const NEEDS_NGINX = 'needs the production nginx.conf (E2E_WEB=nginx): vite preview sends no CSP';

let activeChild;

/**
 * @param {Record<string, string>} env variables for the script, on top of a clean base
 * @param {string[]} [args] arguments for the script
 * @returns {Promise<{code: number, out: string}>} exit code and combined output
 */
function runSmoke(env, args = []) {
  return new Promise((resolve, reject) => {
    const child = spawn('bash', [SCRIPT, ...args], {
      env: {
        PATH: process.env.PATH,
        HOME: process.env.HOME,
        SMOKE_RETRY_SECONDS: '2',
        SMOKE_RETRY_INTERVAL: '1',
        SMOKE_DEADLINE_SECONDS: '40',
        ...env,
      },
    });
    activeChild = child;
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, out }));
  });
}

test.describe('post-deploy smoke script', () => {
  test.setTimeout(90_000);

  // A test that times out leaves the awaited promise dangling and the script running; kill it so it cannot
  // outlive the test and talk to a stub (or the real backend) that has gone.
  test.afterEach(() => {
    if (activeChild && activeChild.exitCode === null && !activeChild.killed) activeChild.kill('SIGKILL');
    activeChild = undefined;
  });

  const pro = USERS.pro;

  test.describe('against the seeded stack', () => {
    test.skip(process.env.E2E_WEB !== 'nginx', NEEDS_NGINX);

    test('passes behind the production nginx config, with PRO ratings', async ({ baseURL }) => {
      const { code, out } = await runSmoke({
        APP_URL_BASE: baseURL,
        SMOKE_DEPLOY_USERNAME: pro.username,
        SMOKE_DEPLOY_PASSWORD: pro.password,
        // The seeded password has a '#'; this script is run directly here, with no SSH forwarding to mangle it.
        SMOKE_PASSWORD_CHARSET_CHECK: 'off',
      });
      expect(code, out).toBe(0);
      expect(out).toContain('ok  shell:');
      expect(out).toContain('ok  login:');
      expect(out).toMatch(/ok {2}briefing: .*[1-9][0-9]* slot\(s\), [1-9][0-9]* served rating\(s\)/);
      expect(out).toContain('ok  logout:');
      expect(out, 'the password is never printed').not.toContain(pro.password);
    });

    test('a wrong password is a misconfigured smoke (4), not a broken release', async ({ baseURL }) => {
      // An account that does not exist, so no real account's failed-sign-in counter moves (a repeated run would
      // otherwise lock it out); the script treats an unknown account and a wrong password identically (401).
      const { code, out } = await runSmoke({
        APP_URL_BASE: baseURL,
        SMOKE_DEPLOY_USERNAME: 'smoke-no-such-user',
        SMOKE_DEPLOY_PASSWORD: 'Not-The-Password-1',
      });
      expect(code, out).toBe(4);
      expect(out).toContain('POST /api/auth/login -> 401');
      expect(out).toContain('[smoke misconfigured]');
      expect(out).not.toContain('Not-The-Password-1');
    });

    test('an unset password skips the logged-in probes with a warning, unless login is required', async ({ baseURL }) => {
      const skipped = await runSmoke({ APP_URL_BASE: baseURL });
      expect(skipped.code, skipped.out).toBe(0);
      expect(skipped.out).toContain('logged-in probes SKIPPED');

      const required = await runSmoke({ APP_URL_BASE: baseURL, SMOKE_LOGIN_REQUIRED: 'true' });
      expect(required.code, required.out).toBe(4);
      expect(required.out).toContain('SMOKE_LOGIN_REQUIRED=true');
    });
  });

  test('a password with characters the SSH forwarding can mangle is refused (4) and never printed', async ({ baseURL }) => {
    const bad = "pa'ss$w0rd";
    const { code, out } = await runSmoke({
      APP_URL_BASE: baseURL, SMOKE_DEPLOY_USERNAME: pro.username, SMOKE_DEPLOY_PASSWORD: bad,
    });
    expect(code, out).toBe(4);
    expect(out).toContain('SMOKE_DEPLOY_PASSWORD contains characters the SSH forwarding can mangle');
    expect(out).not.toContain(bad);
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
    const login = { SMOKE_DEPLOY_USERNAME: 'smoke-deploy', SMOKE_DEPLOY_PASSWORD: 'Stub-Password-1' };

    test('the stub with nothing broken passes (so each failure below is its own doing)', async () => {
      const site = await withStub({});
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(0);
      expect(site.state.logouts, 'the smoke logs out').toBe(1);
    });

    test('every rating null (a quiet day) is a warning, not a failure', async () => {
      const site = await withStub({ nullRatings: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(0);
      expect(out).toContain('warn briefing: the briefing carries no served rating');
      expect(out).toContain('0 served rating(s)');
    });

    test('a slot that is only in unregioned[] still counts as a served slot', async () => {
      const site = await withStub({ onlyUnregioned: true, nullRatings: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(0);
    });

    test('a briefing with no days is a broken release (1)', async () => {
      const site = await withStub({ noDays: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(1);
      expect(out).toContain('no days');
    });

    test('a gated marker for a PRO reader is a broken release (1)', async () => {
      const site = await withStub({ gated: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(1);
      expect(out).toContain('gated marker');
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
      expect(out).toContain('Cloudflare edge response');
      expect(out, 'nothing after the first probe ran').not.toContain('backend health');
      expect(site.state.loginAttempts, 'no login was attempted').toBe(0);
    });

    test('a WAF 403 block page (no cf-mitigated header) is the edge (3), not a broken release', async () => {
      const site = await withStub({ edge: 'waf403' });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(3);
      expect(out).toContain('Cloudflare edge response');
    });

    test('a Cloudflare 1015 rate-limit 429 is the edge (3)', async () => {
      const site = await withStub({ edge: 'cf1015' });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(3);
    });

    test('a cf-error-details header is the edge (3)', async () => {
      const site = await withStub({ edge: 'errorDetails' });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(3);
    });

    test('a Cloudflare 1015 on the LOGIN is the edge (3), not a misconfigured smoke (4)', async () => {
      const site = await withStub({ loginEdge: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(3);
      expect(site.state.loginAttempts).toBe(1);
    });

    test('a truncated body (curl exit 18) is transport (3), not a broken release', async () => {
      const site = await withStub({ truncate: true });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(3);
      expect(out).toContain('curl exit 18');
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

    test('a login that 500s again and again is a broken release (1), tried at most 3 times', async () => {
      const site = await withStub({ loginSeq: [500, 500, 500, 500, 500, 429] });
      const { code, out } = await runSmoke({
        APP_URL_BASE: site.url, ...login, SMOKE_RETRY_SECONDS: '30', SMOKE_RETRY_INTERVAL: '1',
      });
      expect(code, out).toBe(1);
      expect(site.state.loginAttempts).toBeLessThanOrEqual(3);
    });

    test('a login 500 followed by a 429 is the release breaking login (1), not a misconfiguration (4)', async () => {
      const site = await withStub({ loginSeq: [500, 429] });
      const { code, out } = await runSmoke({
        APP_URL_BASE: site.url, ...login, SMOKE_RETRY_SECONDS: '30', SMOKE_RETRY_INTERVAL: '1',
      });
      expect(code, out).toBe(1);
      expect(out).toContain('after an earlier 5xx');
    });

    test('rollback mode, previous image without the container group, aggregate health DOWN: inconclusive (3)', async () => {
      const site = await withStub({ legacyHealth: 'down' });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url }, ['--rollback-mode']);
      expect(code, out).toBe(3);
      expect(out).toContain('GET /actuator/health -> 503');
      expect(out).toContain('inconclusive');
    });

    test('rollback mode, aggregate health UP on the previous image: passes', async () => {
      const site = await withStub({ legacyHealth: 'up' });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url }, ['--rollback-mode']);
      expect(code, out).toBe(0);
      expect(out).toContain('GET /actuator/health -> 200 UP');
    });

    test('the same 404 on /container outside rollback mode is a broken release (1)', async () => {
      const site = await withStub({ legacyHealth: 'up' });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url });
      expect(code, out).toBe(1);
    });

    test('a misconfiguration (4) and an independent break (1) together are 1', async () => {
      const site = await withStub({ healthStatus: 500, login: '429' });
      const { code, out } = await runSmoke({ APP_URL_BASE: site.url, ...login });
      expect(code, out).toBe(1);
      expect(out).toContain('[smoke misconfigured]');
      expect(out).toContain('[release broken]');
    });
  });
});
