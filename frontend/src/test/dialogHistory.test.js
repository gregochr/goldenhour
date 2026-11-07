import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  registerBackLayer, bootDialogHistory, disposeDialogHistory, __resetForTests, __inspectForTests,
  SETTLED_ATTRIBUTE, BUSY_VALUE, TRAVERSAL_TIMEOUT_MS, MAX_LAYER_DEPTH,
} from '../utils/dialogHistory.js';
import { createFakeBrowser, tick } from './fakeBrowserHistory.js';

let b;

function install(options) {
  b = createFakeBrowser(options);
  __resetForTests({ history: b.history, win: b.win });
  return b;
}

/** Registers a layer and lets its reconcile run. */
async function open(mode = 'close', onBack = vi.fn()) {
  const layer = registerBackLayer({ onBack, mode });
  await tick();
  return { layer, onBack };
}

const settledValue = () => document.documentElement.getAttribute(SETTLED_ATTRIBUTE);

beforeEach(() => {
  install();
  document.documentElement.removeAttribute(SETTLED_ATTRIBUTE);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  __resetForTests();
  document.documentElement.removeAttribute(SETTLED_ATTRIBUTE);
});

describe('pushing', () => {
  it('pushes one marked entry for a layer opened under user activation', async () => {
    await open();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    const [state, url] = b.history.pushState.mock.calls[0];
    expect(state.phLayer).toEqual({ session: __inspectForTests().session, depth: 1 });
    expect(url).toBe('');
    expect(__inspectForTests()).toMatchObject({ committedDepth: 1, layers: 1, entried: 1 });
  });

  it('keeps unrelated history state when it pushes', async () => {
    b.history.replaceState({ keep: 'me' }, '');
    await open();
    expect(b.history.state.keep).toBe('me');
  });

  it('numbers a second entry 2 (the ordinal, not the stack index)', async () => {
    await open();
    await open();
    expect(b.history.pushState.mock.calls[1][0].phLayer.depth).toBe(2);
  });

  it('makes the session id from crypto.getRandomValues (randomUUID is not needed)', async () => {
    const spy = vi.spyOn(globalThis.crypto, 'getRandomValues');
    __resetForTests({ history: b.history, win: b.win });
    await open();
    expect(spy).toHaveBeenCalled();
    expect(__inspectForTests().session).toMatch(/^[0-9a-f]{16}$/);
  });

  it('registers entry-less, with no history call, when there is no user activation', async () => {
    b.setActivation(false);
    await open();
    expect(b.history.pushState).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 0, committedDepth: 0 });
  });

  it('registers entry-less when the browser has no userActivation API at all', async () => {
    delete b.win.navigator.userActivation;
    await open();
    expect(b.history.pushState).not.toHaveBeenCalled();
  });

  it('registers entry-less when pushState throws', async () => {
    b.history.pushState.mockImplementation(() => { throw new Error('SecurityError'); });
    const { layer } = await open();
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 0, committedDepth: 0 });
    layer.release();
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
  });

  it('spreads only a plain-object state when it pushes', async () => {
    b.history.replaceState('a string state', '');
    await open();
    expect(Object.keys(b.history.state)).toEqual(['phLayer']);
  });

  it('nets one push for StrictMode-style register, release, register in one commit', async () => {
    const first = registerBackLayer({ onBack: vi.fn() });
    first.release();
    registerBackLayer({ onBack: vi.fn() });
    await tick();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 1, committedDepth: 1 });
  });

  it('is a same-depth swap with no go(0) and no second push when one layer replaces another', async () => {
    const a = await open();
    a.layer.release();
    const next = registerBackLayer({ onBack: vi.fn() });
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 1, committedDepth: 1 });
    next.release();
  });
});

describe('the user presses Back', () => {
  it('closes the layer with exactly one traversal and no go of our own', async () => {
    const { onBack } = await open();
    b.userBack();
    b.flush();
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(b.history.go).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0 });
    expect(b.index).toBe(0);
  });

  it('closes only the top of two layers per press', async () => {
    const one = await open();
    const two = await open();
    b.userBack();
    b.flush();
    expect(two.onBack).toHaveBeenCalledTimes(1);
    expect(one.onBack).not.toHaveBeenCalled();
    b.userBack();
    b.flush();
    expect(one.onBack).toHaveBeenCalledTimes(1);
    expect(b.history.go).not.toHaveBeenCalled();
  });

  it('handles a double Back (both queued before either lands)', async () => {
    const one = await open();
    const two = await open();
    b.userBack();
    b.userBack();
    b.flush();
    expect(one.onBack).toHaveBeenCalledTimes(1);
    expect(two.onBack).toHaveBeenCalledTimes(1);
    expect(b.index).toBe(0);
    expect(b.history.go).not.toHaveBeenCalled();
  });

  it('closes both layers on a two-step jump', async () => {
    const one = await open();
    const two = await open();
    b.userGo(-2);
    b.flush();
    expect(one.onBack).toHaveBeenCalledTimes(1);
    expect(two.onBack).toHaveBeenCalledTimes(1);
  });

  it('pops the store before calling onBack (a re-entrant release finds nothing to traverse)', async () => {
    const layers = {};
    layers.a = registerBackLayer({ onBack: () => layers.a.release() });
    await tick();
    b.userBack();
    b.flush();
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
  });

  it('survives an onBack that throws, closing the rest', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const one = await open();
    const bad = await open('close', vi.fn(() => { throw new Error('boom'); }));
    b.userGo(-2);
    b.flush();
    expect(bad.onBack).toHaveBeenCalled();
    expect(one.onBack).toHaveBeenCalled();
    expect(error).toHaveBeenCalledTimes(1);
  });

  it('does nothing for a hash push arrival with no layers open', async () => {
    b.hashPush('#x');
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0 });
    expect(b.history.go).not.toHaveBeenCalled();
  });

  it('closes the open layer when a hash push navigates away (the arrival is depth 0)', async () => {
    const { onBack } = await open();
    b.hashPush('#x');
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(__inspectForTests().committedDepth).toBe(0);
  });

  it('treats an arrival on a stale entry from another page load as depth 0', async () => {
    const { onBack } = await open();
    b.history.replaceState({ phLayer: { session: 'someone-else', depth: 1 } }, '');
    b.userGo(0);
    b.flush();
    expect(onBack).toHaveBeenCalledTimes(1);
  });
});

describe('mixed entried and entry-less stacks', () => {
  it('closes an entry-less layer above an entried one together with it', async () => {
    const one = await open();
    b.setActivation(false);
    const two = await open();
    expect(__inspectForTests()).toMatchObject({ layers: 2, entried: 1, committedDepth: 1 });
    b.userBack();
    b.flush();
    expect(two.onBack).toHaveBeenCalledTimes(1);
    expect(one.onBack).toHaveBeenCalledTimes(1);
    expect(__inspectForTests().layers).toBe(0);
  });

  it('closes only the entried top layer and leaves an entry-less layer beneath', async () => {
    b.setActivation(false);
    const one = await open();
    b.setActivation(true);
    const two = await open();
    b.userBack();
    b.flush();
    expect(two.onBack).toHaveBeenCalledTimes(1);
    expect(one.onBack).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 0, committedDepth: 0 });
  });
});

describe('closing with the dialog itself (x, Escape, a tab switch)', () => {
  it('makes one go(-1) and ends at depth 0', async () => {
    const { layer } = await open();
    layer.release();
    await tick();
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(__inspectForTests().inFlight).toBe(true);
    expect(settledValue()).toBe(BUSY_VALUE);
    b.flush();
    expect(__inspectForTests()).toMatchObject({ inFlight: false, committedDepth: 0 });
    expect(settledValue()).toBe('0');
    expect(b.index).toBe(0);
  });

  it('coalesces two layers closed in one commit into a single go(-2)', async () => {
    const one = await open();
    const two = await open();
    one.layer.release();
    two.layer.release();
    await tick();
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.go).toHaveBeenCalledWith(-2);
    b.flush();
    expect(b.index).toBe(0);
  });

  it('answers Escape held down over two layers with one traversal per layer, in order', async () => {
    const one = await open();
    const two = await open();
    two.layer.release();
    await tick();
    one.layer.release();
    await tick();
    expect(b.history.go.mock.calls).toEqual([[-1]]);
    b.step();
    await tick();
    expect(b.history.go.mock.calls).toEqual([[-1], [-1]]);
    b.flush();
    expect(b.index).toBe(0);
    expect(__inspectForTests()).toMatchObject({ committedDepth: 0, layers: 0, inFlight: false });
  });

  it('defers a push while a traversal is in flight and pushes after it lands', async () => {
    const { layer } = await open();
    layer.release();
    await tick();
    await open();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    b.flush();
    await tick();
    expect(b.history.pushState).toHaveBeenCalledTimes(2);
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 1, committedDepth: 1 });
  });

  it('does not traverse when the top entry is not ours (app pushState after the layer opened)', async () => {
    const { layer } = await open();
    b.appPush(null);
    layer.release();
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ committedDepth: 0, inFlight: false });
  });

  it('strips a foreign marker from the top entry instead of navigating', async () => {
    const { layer } = await open();
    b.appPush({ phLayer: { session: 'other', depth: 1 }, keep: true });
    b.history.replaceState.mockClear();
    layer.release();
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(b.history.replaceState).toHaveBeenCalledWith({ keep: true }, '');
  });

  it('survives a replaceState that throws while stripping', async () => {
    const { layer } = await open();
    b.appPush({ phLayer: { session: 'other', depth: 1 } });
    b.history.replaceState.mockImplementation(() => { throw new Error('nope'); });
    layer.release();
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
  });

  it('degrades to entry-less when go throws', async () => {
    const one = await open();
    b.history.go.mockImplementation(() => { throw new Error('nope'); });
    one.layer.release();
    await tick();
    expect(__inspectForTests()).toMatchObject({ committedDepth: 0, inFlight: false });
    const next = await open();
    next.layer.release();
    await tick();
    expect(__inspectForTests().inFlight).toBe(false);
  });

  it('ignores a release called twice', async () => {
    const { layer } = await open();
    layer.release();
    layer.release();
    await tick();
    expect(b.history.go).toHaveBeenCalledTimes(1);
  });

  it('makes no traversal for an entry-less layer closing', async () => {
    b.setActivation(false);
    const { layer } = await open();
    layer.release();
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
  });

  it('ends consistent, on the base entry, after a hash push between the close and its arrival', async () => {
    b = createFakeBrowser({ foreignBelow: true });
    __resetForTests({ history: b.history, win: b.win });
    const { layer } = await open();
    layer.release();
    await tick();
    b.hashPush('#y');
    b.flush();
    await tick();
    b.flush();
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0, inFlight: false });
    // The queued go(-1) ran from the hash entry onto our stale entry, which was bounced to the base.
    // The hash navigation is therefore reverted: an ACCEPTED, rare case (a hash write racing a close).
    // What matters is that the reader stays in the app (index 1; index 0 is the foreign entry).
    expect(b.index).toBe(1);
    expect(b.entries[3].hash).toBe('#y');
    expect(b.history.go.mock.calls).toEqual([[-1], [-1]]);
  });

  it('does not take a depth-0 arrival at a different entry index as its own landing (Navigation API)', async () => {
    b = createFakeBrowser({ navigation: true });
    __resetForTests({ history: b.history, win: b.win });
    const { layer } = await open();
    layer.release();
    await tick();
    b.hashPush('#y');
    expect(__inspectForTests().inFlight).toBe(true);
  });
});

describe('a user Back racing a close of our own', () => {
  const microtasks = async () => { await Promise.resolve(); await Promise.resolve(); };

  it('never leaves the app when the Back lands before the deferred go (foreign entry below the base)', async () => {
    b = createFakeBrowser({ foreignBelow: true });
    __resetForTests({ history: b.history, win: b.win });
    const { layer } = await open();
    expect(b.index).toBe(2);
    layer.release(); // x, Escape or a data close...
    await microtasks();
    b.userBack(); // ...with the reader's Back already queued
    b.step();
    expect(b.index).toBe(1);
    await tick(); // the deferred shrink re-reads the depth and finds nothing to do
    b.flush();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(b.index).toBe(1);
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0, inFlight: false });
  });

  it('a Back that lands after our go was issued is the reader’s second step (the residual window)', async () => {
    b = createFakeBrowser({ foreignBelow: true });
    __resetForTests({ history: b.history, win: b.win });
    const { layer } = await open();
    layer.release();
    await tick();
    expect(b.history.go).toHaveBeenCalledTimes(1);
    b.userBack();
    b.flush();
    // Documented in the plan's Risks: once our go is out, a queued Back is indistinguishable from it.
    expect(b.index).toBe(0);
  });

  it('closes one layer’s worth when a Back follows a close that is already traversing', async () => {
    const a = await open();
    const second = await open();
    second.layer.release();
    await tick();
    b.userBack();
    b.flush();
    expect(a.onBack).toHaveBeenCalledTimes(1);
    expect(second.onBack).not.toHaveBeenCalled();
    expect(b.index).toBe(0);
  });

  it('leaves the layer beneath open when the Back lands before the deferred go', async () => {
    const a = await open();
    const second = await open();
    second.layer.release();
    await microtasks();
    b.userBack();
    b.step();
    await tick();
    expect(a.onBack).not.toHaveBeenCalled();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 1, committedDepth: 1 });
  });
});

describe('Forward', () => {
  async function backedOutOfTwo() {
    await open();
    await open();
    b.userGo(-2);
    b.flush();
    b.history.go.mockClear();
  }

  it('bounces a single Forward with one go and reopens nothing', async () => {
    const { onBack } = await open();
    b.userBack();
    b.flush();
    onBack.mockClear();
    b.userForward();
    b.flush();
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(b.index).toBe(0);
    expect(onBack).not.toHaveBeenCalled();
    expect(__inspectForTests().committedDepth).toBe(0);
  });

  it('never steps past the base entry on a fast double Forward (one go in flight, then the timeout re-reads)', async () => {
    vi.useFakeTimers();
    install();
    await backedOutOfTwo();
    b.userForward();
    b.userForward();
    b.flush();
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.index).toBe(1);
    expect(__inspectForTests().inFlight).toBe(true);
    vi.advanceTimersByTime(TRAVERSAL_TIMEOUT_MS);
    expect(b.history.go).toHaveBeenCalledTimes(2);
    b.flush();
    expect(b.index).toBe(0);
    expect(__inspectForTests()).toMatchObject({ inFlight: false, committedDepth: 0 });
  });

  it('closes a layer whose Back arrives while our own traversal is out, then re-reads at the timeout', async () => {
    vi.useFakeTimers();
    install();
    const one = await open();
    const two = await open();
    b.userGo(-2);
    two.layer.release();
    await tick();
    expect(b.history.go).toHaveBeenCalledWith(-1);
    b.flush();
    expect(one.onBack).toHaveBeenCalledTimes(1);
    expect(__inspectForTests().committedDepth).toBe(0);
    vi.advanceTimersByTime(TRAVERSAL_TIMEOUT_MS);
    expect(__inspectForTests()).toMatchObject({ inFlight: false, committedDepth: 0 });
  });
});

describe('the traversal timeout', () => {
  it('degrades to entry-less without retrying when a go never moves the browser', async () => {
    vi.useFakeTimers();
    install({ dropGo: true });
    const { layer } = await open();
    layer.release();
    await tick();
    expect(__inspectForTests().inFlight).toBe(true);
    vi.advanceTimersByTime(TRAVERSAL_TIMEOUT_MS);
    // First expiry: a slow device is not a broken one. Wait once more.
    expect(__inspectForTests()).toMatchObject({ inFlight: true, committedDepth: 1 });
    vi.advanceTimersByTime(TRAVERSAL_TIMEOUT_MS);
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(__inspectForTests()).toMatchObject({ inFlight: false, committedDepth: 0 });
    expect(b.history.state?.phLayer).toBeUndefined();
  });

  it('takes a landing that arrives after the first expiry as ours (no degrade, no bounce)', async () => {
    vi.useFakeTimers();
    install();
    const { layer } = await open();
    layer.release();
    await tick();
    vi.advanceTimersByTime(TRAVERSAL_TIMEOUT_MS);
    expect(__inspectForTests().inFlight).toBe(true);
    b.flush();
    expect(__inspectForTests()).toMatchObject({ inFlight: false, committedDepth: 0 });
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.replaceState).not.toHaveBeenCalled();
    expect(b.index).toBe(0);
  });

  it('clears the timer when the traversal lands', async () => {
    vi.useFakeTimers();
    install();
    const { layer } = await open();
    layer.release();
    await tick();
    b.flush();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('blocking layers', () => {
  it('absorbs Back on a blocking layer: nothing closes and the entry is pushed again', async () => {
    const { onBack } = await open('block');
    b.userBack();
    b.flush();
    expect(onBack).not.toHaveBeenCalled();
    expect(b.history.pushState).toHaveBeenCalledTimes(2);
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });
    b.userBack();
    b.flush();
    expect(onBack).not.toHaveBeenCalled();
    expect(__inspectForTests().committedDepth).toBe(1);
  });

  it('absorbs Back on a blocking layer over an included one, closing neither', async () => {
    const included = await open();
    const blocking = await open('block');
    b.userBack();
    b.flush();
    expect(blocking.onBack).not.toHaveBeenCalled();
    expect(included.onBack).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ layers: 2, committedDepth: 2 });
  });

  it('closes the top layer of a two-step jump and stops at a blocking one beneath it', async () => {
    const blocking = await open('block');
    const top = await open();
    b.userGo(-2);
    b.flush();
    expect(top.onBack).toHaveBeenCalledTimes(1);
    expect(blocking.onBack).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 1, committedDepth: 1 });
  });

  it('degrades when the re-push throws', async () => {
    await open('block');
    b.history.pushState.mockImplementation(() => { throw new Error('rate limited'); });
    b.userBack();
    b.flush();
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 0, committedDepth: 0 });
  });

  it('updates a layer from block to close in place, with no history call', async () => {
    const { layer, onBack } = await open('block');
    b.history.pushState.mockClear();
    layer.update({ mode: 'close' });
    layer.update({});
    b.userBack();
    b.flush();
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(b.history.pushState).not.toHaveBeenCalled();
  });

  it('updates onBack in place', async () => {
    const { layer, onBack } = await open();
    const later = vi.fn();
    layer.update({ onBack: later });
    b.userBack();
    b.flush();
    expect(later).toHaveBeenCalledTimes(1);
    expect(onBack).not.toHaveBeenCalled();
  });

  it('treats a cancel layer exactly like a close layer', async () => {
    const { onBack } = await open('cancel');
    b.userBack();
    b.flush();
    expect(onBack).toHaveBeenCalledTimes(1);
  });
});

describe('a layer that stays mounted after onBack', () => {
  it('is adopted back as entry-less, in its old place', async () => {
    vi.useFakeTimers();
    install();
    const one = await open();
    const stubborn = await open();
    b.userBack();
    b.flush();
    expect(stubborn.onBack).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(0);
    expect(__inspectForTests()).toMatchObject({ layers: 2, entried: 1, committedDepth: 1 });
    // The re-adopted layer is entry-less, so releasing it traverses nothing.
    stubborn.layer.release();
    await tick();
    expect(b.history.go).not.toHaveBeenCalled();
    one.layer.release();
  });

  it('is not adopted back when it unmounted in the meantime', async () => {
    vi.useFakeTimers();
    install();
    const { layer } = await open();
    b.userBack();
    b.flush();
    layer.release();
    vi.advanceTimersByTime(0);
    expect(__inspectForTests().layers).toBe(0);
  });

  it('is not adopted twice when it was already put back by the app', async () => {
    vi.useFakeTimers();
    install();
    const { layer } = await open();
    b.userBack();
    b.flush();
    vi.advanceTimersByTime(0);
    vi.advanceTimersByTime(0);
    expect(__inspectForTests().layers).toBe(1);
    layer.release();
  });
});

describe('scrollRestoration', () => {
  it('is manual just before the first push and auto again after the last layer closes', async () => {
    const { layer } = await open();
    expect(b.log).toEqual(['scroll:manual', 'push']);
    layer.release();
    await tick();
    b.flush();
    expect(b.log).toEqual(['scroll:manual', 'push', 'scroll:auto']);
    expect(b.history.scrollRestoration).toBe('auto');
  });

  it('is set once for several layers and stays manual while any is open', async () => {
    const one = await open();
    const two = await open();
    expect(b.log.filter((e) => e === 'scroll:manual')).toHaveLength(1);
    two.layer.release();
    await tick();
    b.flush();
    expect(b.history.scrollRestoration).toBe('manual');
    one.layer.release();
    await tick();
    b.flush();
    expect(b.history.scrollRestoration).toBe('auto');
  });

  it('is auto again after a Back that closes the last layer', async () => {
    await open();
    b.userBack();
    b.flush();
    expect(b.history.scrollRestoration).toBe('auto');
    expect(__inspectForTests().scrollManual).toBe(false);
  });

  it('is never touched by an entry-less layer', async () => {
    b.setActivation(false);
    await open();
    expect(b.log).toEqual([]);
  });

  it('is healed to auto at boot when a previous load left it manual', async () => {
    install({ scrollInitial: 'manual' });
    b.setActivation(false);
    await open();
    expect(b.history.scrollRestoration).toBe('auto');
  });

  it('is skipped where the property does not exist (jsdom)', async () => {
    install({ scroll: false });
    const { layer } = await open();
    layer.release();
    await tick();
    b.flush();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(__inspectForTests().scrollManual).toBe(false);
  });

  it('is reset by a depth-0 arrival that no layer is waiting on, if it was left manual', async () => {
    await open();
    expect(__inspectForTests().scrollManual).toBe(true);
    b.hashPush('#x');
    expect(b.history.scrollRestoration).toBe('auto');
  });

  it('heals a manual hash entry: a hash push closes the layer, then Back returns to a manual base', async () => {
    await open();
    b.hashPush('#x');
    expect(b.entries[2].scroll).toBe('auto');
    expect(b.entries[0].scroll).toBe('manual');
    b.userBack();
    b.flush();
    expect(b.index).toBe(0);
    expect(b.entries[0].scroll).toBe('auto');
  });

  it('heals a manual entry on any depth-0 arrival even with nothing registered', () => {
    install({ scrollInitial: 'manual' });
    b.hashPush('#x');
    expect(b.history.scrollRestoration).toBe('auto');
  });

  it('heals a manual entry at boot (a reload onto it) without any registration', () => {
    install({ scrollInitial: 'manual' });
    bootDialogHistory();
    expect(b.history.scrollRestoration).toBe('auto');
  });

  it('survives a history that throws on the property', async () => {
    const { layer } = await open();
    Object.defineProperty(b.history, 'scrollRestoration', {
      get: () => { throw new Error('nope'); },
      set: () => { throw new Error('nope'); },
    });
    layer.release();
    await tick();
    b.flush();
    expect(__inspectForTests().inFlight).toBe(false);
  });
});

describe('boot', () => {
  it('strips a marker left by an earlier page load', async () => {
    b.history.replaceState({ phLayer: { session: 'old', depth: 2 }, keep: 1 }, '');
    b.history.replaceState.mockClear();
    b.setActivation(false);
    await open();
    expect(b.history.replaceState).toHaveBeenCalledWith({ keep: 1 }, '');
  });

  it('makes no history write when there is nothing to strip', async () => {
    b.setActivation(false);
    await open();
    expect(b.history.replaceState).not.toHaveBeenCalled();
  });

  it('is idempotent and callable on its own', () => {
    bootDialogHistory();
    bootDialogHistory();
    expect(b.history.replaceState).not.toHaveBeenCalled();
  });

  it('tolerates a history that throws on state', () => {
    const throwing = createFakeBrowser();
    Object.defineProperty(throwing.history, 'state', { get: () => { throw new Error('nope'); } });
    __resetForTests({ history: throwing.history, win: throwing.win });
    expect(() => bootDialogHistory()).not.toThrow();
  });

  it('tolerates a history that throws on scrollRestoration', () => {
    const throwing = createFakeBrowser();
    Object.defineProperty(throwing.history, 'scrollRestoration', { get: () => { throw new Error('nope'); } });
    __resetForTests({ history: throwing.history, win: throwing.win });
    expect(() => bootDialogHistory()).not.toThrow();
  });
});

describe('the settled signal', () => {
  it('shows busy while a reconcile is queued and the depth once it settles', async () => {
    registerBackLayer({ onBack: vi.fn() });
    expect(settledValue()).toBe(BUSY_VALUE);
    await tick();
    expect(settledValue()).toBe('1');
  });

  it('survives a window with no document', async () => {
    delete b.win.document;
    await open();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });
});

describe('the off switch', () => {
  it('makes zero history calls and writes nothing', async () => {
    const off = createFakeBrowser();
    __resetForTests({ history: off.history, win: off.win, enabled: false });
    const layer = registerBackLayer({ onBack: vi.fn(), mode: 'block' });
    await tick();
    layer.update({ mode: 'close' });
    off.dispatch();
    off.userBack();
    off.flush();
    layer.release();
    await tick();
    bootDialogHistory();
    expect(off.history.pushState).not.toHaveBeenCalled();
    expect(off.history.replaceState).not.toHaveBeenCalled();
    expect(off.history.go).not.toHaveBeenCalled();
    expect(off.history.back).not.toHaveBeenCalled();
    expect(off.history.forward).not.toHaveBeenCalled();
    expect(off.log).toEqual([]);
    expect(document.documentElement.hasAttribute(SETTLED_ATTRIBUTE)).toBe(false);
  });
});

describe('the development warning', () => {
  it('warns past the two-dialog invariant in development', async () => {
    vi.stubEnv('DEV', true);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    for (let i = 0; i < MAX_LAYER_DEPTH; i += 1) registerBackLayer({ onBack: vi.fn() });
    expect(warn).not.toHaveBeenCalled();
    registerBackLayer({ onBack: vi.fn() });
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it('stays quiet outside development', async () => {
    vi.stubEnv('DEV', false);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    for (let i = 0; i < MAX_LAYER_DEPTH + 1; i += 1) registerBackLayer({ onBack: vi.fn() });
    expect(warn).not.toHaveBeenCalled();
  });
});

describe('teardown', () => {
  it('disposeDialogHistory removes the popstate listener (hot module replacement)', () => {
    expect(b.listenerCount).toBe(1);
    disposeDialogHistory();
    expect(b.listenerCount).toBe(0);
    disposeDialogHistory();
  });

  it('ignores an adopt timer left over from a previous test', async () => {
    vi.useFakeTimers();
    install();
    await open();
    b.userBack();
    b.flush();
    install();
    vi.advanceTimersByTime(0);
    expect(__inspectForTests().layers).toBe(0);
  });

  it('counts a re-adopted layer towards the development depth warning', async () => {
    vi.useFakeTimers();
    install();
    vi.stubEnv('DEV', true);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    registerBackLayer({ onBack: vi.fn() });
    registerBackLayer({ onBack: vi.fn() });
    await tick();
    b.userBack();
    b.flush();
    registerBackLayer({ onBack: vi.fn() });
    expect(warn).not.toHaveBeenCalled();
    vi.advanceTimersByTime(0);
    expect(warn).toHaveBeenCalledTimes(1);
  });
});

describe('the test seam', () => {
  it('moves the popstate listener to the injected window and back', () => {
    expect(b.listenerCount).toBe(1);
    const other = createFakeBrowser();
    __resetForTests({ history: other.history, win: other.win });
    expect(b.listenerCount).toBe(0);
    expect(other.listenerCount).toBe(1);
  });

  it('falls back to the real window and history when nothing is injected', async () => {
    __resetForTests();
    expect(() => registerBackLayer({ onBack: vi.fn() }).release()).not.toThrow();
  });
});
