import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  registerBackLayer, whenSettled, __resetForTests, __inspectForTests,
} from '../utils/dialogHistory.js';
import { createFakeBrowser, tick } from './fakeBrowserHistory.js';

let b;

function install(options) {
  b = createFakeBrowser(options);
  __resetForTests({ history: b.history, win: b.win });
  return b;
}

async function open(options = {}) {
  const onBack = vi.fn();
  const layer = registerBackLayer({ onBack, ...options });
  await tick();
  return { layer, onBack };
}

beforeEach(() => install());
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  __resetForTests();
});

describe('a layer url, written by the engine', () => {
  it('pushes its entry with the url and copies the marker onto the state', async () => {
    await open({ url: '#map/2026-11-24/sunrise' });
    const [state, , url] = b.history.pushState.mock.calls[0];
    expect(url).toBe('#map/2026-11-24/sunrise');
    expect(state.phLayer.depth).toBe(1);
    expect(b.win.location.hash).toBe('#map/2026-11-24/sunrise');
  });

  it('pushes with no url argument at all when no layer has one', async () => {
    await open();
    expect(b.history.pushState.mock.calls[0]).toHaveLength(2);
  });

  it('writes update({url}) onto the current entry, keeping its state (a window step)', async () => {
    const { layer } = await open({ url: '#map/2026-11-24/sunrise' });
    layer.update({ url: '#map/2026-11-24/sunset' });
    expect(b.win.location.hash).toBe('#map/2026-11-24/sunset');
    const [state, , url] = b.history.replaceState.mock.calls.at(-1);
    expect(url).toBe('#map/2026-11-24/sunset');
    expect(state).toBe(b.history.state);
    expect(state.phLayer.depth).toBe(1);
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('lets a sheet over the layer inherit its address, and keeps it right while the window steps', async () => {
    const view = await open({ url: '#map/2026-11-24/sunrise' });
    await open();
    expect(b.history.pushState.mock.calls[1][2]).toBe('#map/2026-11-24/sunrise');
    view.layer.update({ url: '#map/2026-11-25/sunrise' });
    // Written onto the sheet's entry (the current one), not pushed.
    expect(b.win.location.hash).toBe('#map/2026-11-25/sunrise');
    expect(b.history.pushState).toHaveBeenCalledTimes(2);
  });

  it('rewrites a stale address when a traversal lands back on the layer (Back from the sheet)', async () => {
    const view = await open({ url: '#map/2026-11-24/sunrise' });
    const sheet = await open();
    view.layer.update({ url: '#map/2026-11-25/sunrise' });
    b.userBack();
    b.flush();
    expect(sheet.onBack).toHaveBeenCalledTimes(1);
    expect(view.onBack).not.toHaveBeenCalled();
    // The browser restored the view's OLD entry address; the engine puts the live one back.
    expect(b.index).toBe(1);
    expect(b.win.location.hash).toBe('#map/2026-11-25/sunrise');
    expect(b.history.state.phLayer.depth).toBe(1);
  });

  it('takes over an entry in a same-commit swap and writes the new layer url', async () => {
    const first = await open({ url: '#map/2026-11-24/sunrise' });
    first.layer.release();
    registerBackLayer({ onBack: vi.fn(), url: '#map/2026-11-24/sunset' });
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(b.win.location.hash).toBe('#map/2026-11-24/sunset');
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 1, committedDepth: 1 });
  });

  it('returns to the bare path when the entried layer closes (the engine traverses back)', async () => {
    const { layer } = await open({ url: '#map/2026-11-24/sunrise' });
    layer.release();
    await tick();
    b.flush();
    expect(b.index).toBe(0);
    expect(b.win.location.hash).toBe('');
  });

  it('writes an entry-less layer url, and replaces back to the bare path when it closes', async () => {
    b.setActivation(false);
    const { layer } = await open({ url: '#map/2026-11-24/sunrise' });
    expect(b.history.pushState).not.toHaveBeenCalled();
    layer.update({ url: '#map/2026-11-24/sunset' });
    expect(b.win.location.hash).toBe('#map/2026-11-24/sunset');
    const stateBefore = b.history.state;
    layer.release();
    expect(b.win.location.hash).toBe('');
    const [state, , url] = b.history.replaceState.mock.calls.at(-1);
    expect(url).toBe('/');
    expect(state).toBe(stateBefore);
    expect(b.history.go).not.toHaveBeenCalled();
  });

  it('leaves a hash someone else wrote alone when an entry-less layer closes', async () => {
    b.setActivation(false);
    const { layer } = await open({ url: '#map/2026-11-24/sunrise' });
    b.history.replaceState(null, '', '#manage/runs/start');
    b.history.replaceState.mockClear();
    layer.release();
    expect(b.history.replaceState).not.toHaveBeenCalled();
    expect(b.win.location.hash).toBe('#manage/runs/start');
  });

  it('pushes its own entry over a stale Operations address, and Back restores that address', async () => {
    // The reported bug: Operations left `#manage/...` standing, so a view opened from This week showed it.
    b.history.replaceState(null, '', '#manage/runs?view=cost');
    const { layer } = await open({ url: '#map/2026-11-27/sunrise' });
    expect(b.win.location.hash).toBe('#map/2026-11-27/sunrise');
    expect(b.index).toBe(1);
    // Later route changes replace the view's own address as ever.
    layer.update({ url: '#map/2026-11-27/sunset' });
    expect(b.win.location.hash).toBe('#map/2026-11-27/sunset');
    layer.release();
    await tick();
    b.flush();
    expect(b.index).toBe(0);
    expect(b.win.location.hash).toBe('#manage/runs?view=cost');
  });

  it('an entry-less layer (no activation) still never overwrites an Operations address', async () => {
    b.setActivation(false);
    b.history.replaceState(null, '', '#manage/runs?view=cost');
    await open({ url: '#map/2026-11-27/sunrise' });
    expect(b.win.location.hash).toBe('#manage/runs?view=cost');
  });

  it('never overwrites a foreign hash when the url changes', async () => {
    b.setActivation(false);
    const { layer } = await open({ url: '#map/2026-11-24/sunrise' });
    b.history.replaceState(null, '', '#manage/runs/start');
    b.history.replaceState.mockClear();
    layer.update({ url: '#map/2026-11-25/sunrise' });
    expect(b.history.replaceState).not.toHaveBeenCalled();
  });

  it('survives a replaceState that throws', async () => {
    const { layer } = await open({ url: '#map/2026-11-24/sunrise' });
    b.history.replaceState.mockImplementation(() => { throw new Error('SecurityError'); });
    expect(() => layer.update({ url: '#map/2026-11-24/sunset' })).not.toThrow();
  });

  it('does not rewrite when the url is unchanged', async () => {
    const { layer } = await open({ url: '#map/2026-11-24/sunrise' });
    b.history.replaceState.mockClear();
    layer.update({ url: '#map/2026-11-24/sunrise' });
    expect(b.history.replaceState).not.toHaveBeenCalled();
  });
});

describe('whenSettled', () => {
  it('resolves at once when nothing is outstanding', async () => {
    await expect(whenSettled()).resolves.toBeUndefined();
  });

  it('waits for a closing layer traversal to land', async () => {
    const { layer } = await open({ url: '#map/2026-11-24/sunrise' });
    layer.release();
    let done = false;
    const waiting = whenSettled().then(() => { done = true; });
    await tick();
    expect(done).toBe(false);
    b.flush();
    await waiting;
    expect(done).toBe(true);
    expect(b.index).toBe(0);
  });

  it('resolves immediately with the engine off', async () => {
    __resetForTests({ history: b.history, win: b.win, enabled: false });
    await expect(whenSettled()).resolves.toBeUndefined();
  });

  it('releases a waiter on a test reset', async () => {
    const { layer } = await open();
    layer.release();
    const waiting = whenSettled();
    __resetForTests({ history: b.history, win: b.win });
    await expect(waiting).resolves.toBeUndefined();
  });
});

describe('an address nobody wants any more is taken down (review round 1)', () => {
  it('a swap from the map layer to a layer with no address rewrites the bare path, copying the state', async () => {
    const view = await open({ url: '#map/2026-11-24/sunrise' });
    view.layer.release();
    registerBackLayer({ onBack: vi.fn() });
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(b.win.location.hash).toBe('');
    const [state, , url] = b.history.replaceState.mock.calls.at(-1);
    expect(url).toBe('/');
    expect(state).toBe(b.history.state);
    expect(state.phLayer.depth).toBe(1);
  });

  it('...but never a hash someone else owns', async () => {
    const view = await open({ url: '#map/2026-11-24/sunrise' });
    b.history.replaceState(b.history.state, '', '#manage/runs/start');
    b.history.replaceState.mockClear();
    view.layer.release();
    registerBackLayer({ onBack: vi.fn() });
    await tick();
    expect(b.win.location.hash).toBe('#manage/runs/start');
    expect(b.history.replaceState).not.toHaveBeenCalled();
  });

  it('a layer with no address opened OVER the map leaves the map\'s address standing', async () => {
    await open({ url: '#map/2026-11-24/sunrise' });
    await open();
    expect(b.win.location.hash).toBe('#map/2026-11-24/sunrise');
  });

  it('an entry-less map layer that closes under an entried layer rewrites the base entry when Back lands there', async () => {
    b.setActivation(false);
    const view = await open({ url: '#map/2026-11-24/sunrise' });
    expect(b.win.location.hash).toBe('#map/2026-11-24/sunrise');
    b.setActivation(true);
    const sheet = await open();
    expect(b.index).toBe(1);
    view.layer.release();
    await tick();
    // The top entry is cleared at once; the BASE entry (which the map's address was written on) is not reachable yet.
    expect(b.win.location.hash).toBe('');

    sheet.layer.release();
    await tick();
    b.flush();

    expect(b.index).toBe(0);
    expect(b.win.location.hash).toBe('');
  });

  it('forgets a pending base rewrite once a layer wants an address again', async () => {
    b.setActivation(false);
    const view = await open({ url: '#map/2026-11-24/sunrise' });
    b.setActivation(true);
    const sheet = await open();
    view.layer.release();
    await tick();
    await open({ url: '#map/2026-11-26/sunset' });
    sheet.layer.release();
    await tick();
    b.flush();
    expect(b.win.location.hash).toBe('#map/2026-11-26/sunset');
  });
});

describe('a load-time deep link is not the engine\'s until a layer adopts it (review round 2)', () => {
  it('a layer with no address that registers before the first gesture leaves the loaded #map hash alone', async () => {
    b.history.replaceState(b.history.state, '', '#map/2026-11-24/sunrise/5');
    b.history.replaceState.mockClear();
    b.setActivation(false);
    // The gated map layer has not registered yet; something url-less (a sheet, Find) does.
    registerBackLayer({ onBack: vi.fn() });
    await tick();
    expect(b.win.location.hash).toBe('#map/2026-11-24/sunrise/5');
    expect(b.history.replaceState).not.toHaveBeenCalled();
  });

  it('then the gated layer registers with the same address, owns it, and closing it clears it', async () => {
    b.history.replaceState(b.history.state, '', '#map/2026-11-24/sunrise/5');
    b.setActivation(false);
    registerBackLayer({ onBack: vi.fn() });
    await tick();
    const view = registerBackLayer({ onBack: vi.fn(), url: '#map/2026-11-24/sunrise/5' });
    await tick();
    expect(b.win.location.hash).toBe('#map/2026-11-24/sunrise/5');
    view.release();
    await tick();
    // The url-less layer remains and the address nobody wants now is taken down.
    expect(b.win.location.hash).toBe('');
  });

  it('an address the engine itself wrote is still taken down in the swap', async () => {
    const view = await open({ url: '#map/2026-11-24/sunrise' });
    view.layer.release();
    registerBackLayer({ onBack: vi.fn() });
    await tick();
    expect(b.win.location.hash).toBe('');
  });
});
