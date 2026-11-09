// P4a measurement. Run from frontend/: node ../scripts/measurements/p4a-map-frames/measure.mjs
// Needs temporary p4a:* performance marks in MapHeatLayer/MapLabels (see instrumentation.diff).
import { chromium } from '../../../frontend/node_modules/playwright/index.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const APP = process.env.APP || 'http://localhost:5190';
const API = process.env.API || 'http://localhost:8083';
const OUT = process.env.OUT || new URL('.', import.meta.url).pathname;
const RUNS = Number(process.env.RUNS || 2);
const GESTURE_MS = Number(process.env.GESTURE_MS || 10000);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

const login = await (await fetch(`${API}/api/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'golden2026' }),
})).json();

const stats = (a) => {
  if (!a.length) return { n: 0 };
  const s = [...a].sort((x, y) => x - y);
  const q = (p) => s[Math.min(s.length - 1, Math.floor(p * s.length))];
  return { n: s.length, median: +q(0.5).toFixed(2), p90: +q(0.9).toFixed(2), p99: +q(0.99).toFixed(2), max: +s[s.length - 1].toFixed(2) };
};

async function setup(browser, vp) {
  const ctx = await browser.newContext({
    viewport: { width: vp.w, height: vp.h }, isMobile: vp.mobile, hasTouch: vp.mobile,
    deviceScaleFactor: vp.mobile ? 3 : 2,
  });
  await ctx.addInitScript((d) => {
    localStorage.setItem('goldenhour_token', d.accessToken);
    localStorage.setItem('goldenhour_refresh', d.refreshToken);
    localStorage.setItem('goldenhour_role', d.role);
    localStorage.setItem('goldenhour_must_change', 'false');
    localStorage.setItem('goldenhour_username', d.username);
  }, login);
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  await page.goto(APP, { waitUntil: 'load', timeout: 60000 });
  const tab = page.getByRole('tab', { name: /^Map$/ }).or(page.getByRole('button', { name: /^Map$/ })).first();
  await tab.waitFor({ state: 'visible', timeout: 30000 });
  await tab.click();
  await page.waitForSelector('.leaflet-container canvas', { state: 'attached', timeout: 30000 })
    .catch(async (e) => { await page.screenshot({ path: `${OUT}/setup-fail-${vp.name}.png` }); throw e; });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  await sleep(4000);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  return { ctx, page, cdp };
}

async function startCapture(page) {
  await page.evaluate(() => {
    performance.clearMarks(); performance.clearMeasures();
    window.__p4a = { ...(window.__p4a || {}), zmin: undefined, zmax: undefined };
    window.__frames = []; window.__run = true;
    const loop = (t) => { window.__frames.push(t); if (window.__run) requestAnimationFrame(loop); };
    requestAnimationFrame(loop);
  });
}
async function stopCapture(page) {
  return page.evaluate(() => {
    window.__run = false;
    const f = window.__frames;
    const deltas = f.slice(1).map((t, i) => t - f[i]);
    const m = (n) => performance.getEntriesByName(n).map((e) => ({ s: e.startTime, d: e.duration }));
    const dt = m('p4a:drawTiles'), lp = m('p4a:labels.paint'), ll = m('p4a:labels.layout');
    const bucket = new Map();
    for (const e of [...lp, ...ll]) {
      let lo = 0, hi = f.length - 1, idx = 0;
      while (lo <= hi) { const mid = (lo + hi) >> 1; if (f[mid] <= e.s) { idx = mid; lo = mid + 1; } else hi = mid - 1; }
      bucket.set(idx, (bucket.get(idx) || 0) + e.d);
    }
    const p = window.__p4a || {};
    return {
      drawTiles: dt.map((e) => e.d), paint: lp.map((e) => e.d), layout: ll.map((e) => e.d),
      combined: [...bucket.values()], frames: deltas,
      points: p.points, labels: p.labels, zmin: p.zmin, zmax: p.zmax,
    };
  });
}

async function dragDesktop(page, vp) {
  const cx = vp.w / 2, cy = vp.h / 2 + 40, amp = 250;
  const end = Date.now() + GESTURE_MS;
  await page.mouse.move(cx - amp, cy); await page.mouse.down();
  let dir = 1, x = cx - amp;
  while (Date.now() < end) {
    x += dir * 6;
    if (x > cx + amp || x < cx - amp) dir = -dir;
    await page.mouse.move(x, cy + Math.sin(x / 40) * 40);
    await sleep(8);
  }
  await page.mouse.up();
}
async function zoomDesktop(page, vp) {
  const cx = vp.w / 2, cy = vp.h / 2 + 40;
  await page.mouse.move(cx, cy);
  const end = Date.now() + GESTURE_MS; let dir = -1, n = 0;
  while (Date.now() < end) {
    await page.mouse.wheel(0, dir * 120); n++;
    await sleep(60);
    if (n % 12 === 0) dir = -dir;
  }
}
async function dragPhone(cdp, vp) {
  const end = Date.now() + GESTURE_MS; let dir = 1, n = 0;
  while (Date.now() < end) {
    await cdp.send('Input.synthesizeScrollGesture', {
      x: vp.w / 2, y: vp.h / 2, xDistance: dir * 220, yDistance: dir * 80, speed: 400,
      gestureSourceType: 'touch', preventFling: true,
    });
    dir = -dir; n++;
  }
  return n;
}
async function pinchPhone(cdp, vp) {
  const end = Date.now() + GESTURE_MS; let out = false, n = 0, err = null;
  while (Date.now() < end) {
    try {
      await cdp.send('Input.synthesizePinchGesture', {
        x: vp.w / 2, y: vp.h / 2, scaleFactor: out ? 0.5 : 2, relativeSpeed: 300, gestureSourceType: 'touch',
      });
    } catch (e) { err = String(e); break; }
    out = !out; n++;
  }
  return { n, err };
}

const browser = await chromium.launch({ channel: 'chrome' });
const results = [];
const viewports = [
  { name: '390x844', w: 390, h: 844, mobile: true },
  { name: '1440x900', w: 1440, h: 900, mobile: false },
];
for (const vp of viewports) {
  for (const gesture of ['drag', 'zoom']) {
    for (let run = 1; run <= RUNS; run++) {
      const { ctx, page, cdp } = await setup(browser, vp);
      await page.screenshot({ path: `${OUT}/${vp.name}-${gesture}-${run}-before.png` });
      await startCapture(page);
      let how, extra = {};
      if (vp.mobile) {
        if (gesture === 'drag') { extra.swipes = await dragPhone(cdp, vp); how = 'CDP Input.synthesizeScrollGesture touch swipes back and forth'; }
        else { const r = await pinchPhone(cdp, vp); extra = r; how = 'CDP Input.synthesizePinchGesture touch pinch in/out (scale 2 / 0.5)'; }
      } else if (gesture === 'drag') { await dragDesktop(page, vp); how = 'mouse down + 6px steps every ~8ms, 500px sweep, sine y'; }
      else { await zoomDesktop(page, vp); how = 'mouse.wheel deltaY +-120 every 60ms, 12 ticks per direction'; }
      const d = await stopCapture(page);
      await page.screenshot({ path: `${OUT}/${vp.name}-${gesture}-${run}-after.png` });
      results.push({
        viewport: vp.name, gesture, run, how, ...extra, points: d.points, labels: d.labels, zmin: d.zmin, zmax: d.zmax,
        longFrames50: d.frames.filter((x) => x > 50).length, frameCount: d.frames.length,
        drawTiles: stats(d.drawTiles), labelsPaint: stats(d.paint), labelsLayout: stats(d.layout),
        labelsCombined: stats(d.combined), rafDelta: stats(d.frames),
      });
      console.log(JSON.stringify(results.at(-1)));
      await ctx.close();
    }
  }
}
await browser.close();
writeFileSync(`${OUT}/results.json`, JSON.stringify(results, null, 2));
const f = (s) => (s.n ? `${s.n} / ${s.median} / ${s.p90} / ${s.p99}` : '0');
let md = '| viewport | gesture | run | drawTiles n/med/p90/p99 | labels.paint | labels.layout | paint+layout per frame | rAF delta | frames>50ms | pts | chips | zoom range |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n';
for (const r of results) md += `| ${r.viewport} | ${r.gesture} | ${r.run} | ${f(r.drawTiles)} | ${f(r.labelsPaint)} | ${f(r.labelsLayout)} | ${f(r.labelsCombined)} | ${f(r.rafDelta)} | ${r.longFrames50}/${r.frameCount} | ${r.points} | ${r.labels} | ${r.zmin}-${r.zmax} |\n`;
writeFileSync(`${OUT}/results.md`, md);
console.log(md);
