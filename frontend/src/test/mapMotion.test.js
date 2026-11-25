import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import {
  ARRIVING_CLASS, CALLBACK_TIMEOUT_MS, CARD_CLASS, CLEANUP_TIMEOUT_MS, CLOSING_CLASS, FRAME_CLASS, canMorphClose, canMorphOpen,
  closeWithMotion, openWithMotion, thumbnailOf,
} from '../utils/mapMotion.js';

/**
 * The card-to-map morph (`docs/engineering/screen-spec-plan.md` section 6), against a stubbed
 * `document.startViewTransition`: when it runs, when it does not, and that its classes always come off.
 */
let card;
let thumb;
let hit;
let frame;
let finish;

/** A stub transition: the callback runs on a microtask (as the browser runs it, a frame later), `finished` is held. */
function installStub({ rejectFinished = false, throwOnStart = false } = {}) {
  document.startViewTransition = vi.fn((callback) => {
    if (throwOnStart) throw new Error('InvalidStateError');
    let release;
    const finished = new Promise((resolve, reject) => {
      release = () => (rejectFinished ? reject(new Error('skipped')) : resolve());
    });
    finish = release;
    const updateCallbackDone = Promise.resolve().then(callback);
    return { finished, updateCallbackDone };
  });
}

const settle = () => new Promise((resolve) => { setTimeout(resolve, 0); });

/** A thumbnail box that is on screen. */
const onScreen = () => ({
  width: 100, height: 60, top: 10, left: 10, bottom: 70, right: 110,
});

beforeEach(() => {
  document.body.innerHTML = `
    <div data-testid="wf-heat-card" id="card"><span class="wf-hc-cv" id="thumb"></span>
      <button class="wf-hc-hit" id="hit">open</button></div>
    <div data-map-screen="" id="frame"></div>`;
  card = document.getElementById('card');
  thumb = document.getElementById('thumb');
  hit = document.getElementById('hit');
  frame = document.getElementById('frame');
  thumb.getBoundingClientRect = onScreen;
  installStub();
});

afterEach(() => {
  delete document.startViewTransition;
  document.documentElement.className = '';
  vi.useRealTimers();
});

describe('thumbnailOf', () => {
  it('finds the thumbnail from the card, its button or itself, and nothing else', () => {
    expect(thumbnailOf(thumb)).toBe(thumb);
    expect(thumbnailOf(hit)).toBe(thumb);
    expect(thumbnailOf(card)).toBe(thumb);
    expect(thumbnailOf(frame)).toBeNull();
    expect(thumbnailOf(null)).toBeNull();
    expect(thumbnailOf(document.createElement('button'))).toBeNull();
  });

  it('is null for a card with no thumbnail (an away card) and for a detached source', () => {
    thumb.remove();
    expect(thumbnailOf(hit)).toBeNull();
    const loose = document.createElement('span');
    loose.className = 'wf-hc-cv';
    expect(thumbnailOf(loose)).toBeNull();
  });
});

describe('openWithMotion', () => {
  it('runs the whole update inside the transition, names the card then the frame, and cleans up when it finishes', async () => {
    const update = vi.fn(() => { expect(thumb.classList.contains(CARD_CLASS)).toBe(true); });
    const onDone = vi.fn();
    expect(openWithMotion({ source: thumb, update, onDone })).toBe(true);
    expect(update).not.toHaveBeenCalled();
    expect(thumb.classList.contains(CARD_CLASS)).toBe(true);
    await settle();
    expect(update).toHaveBeenCalledTimes(1);
    // One name at a time: the card has given it up and the frame holds it, with the pane hidden.
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
    expect(frame.classList.contains(FRAME_CLASS)).toBe(true);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(true);
    finish();
    await settle();
    expect(frame.classList.contains(FRAME_CLASS)).toBe(false);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('runs plainly, once and at once, when the browser has no startViewTransition', () => {
    delete document.startViewTransition;
    const update = vi.fn();
    expect(openWithMotion({ source: thumb, update })).toBe(false);
    expect(update).toHaveBeenCalledTimes(1);
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
  });

  it.each([
    ['in place', { inPlace: true }],
    ['a load-time open', { loadTime: true }],
    ['stepping', { stepping: true }],
    ['no thumbnail', { source: null }],
  ])('runs plainly for %s', (_name, extra) => {
    const update = vi.fn();
    expect(openWithMotion({ source: thumb, update, ...extra })).toBe(false);
    expect(update).toHaveBeenCalledTimes(1);
    expect(document.startViewTransition).not.toHaveBeenCalled();
  });

  it('runs plainly for a disconnected source', () => {
    const update = vi.fn();
    thumb.remove();
    expect(openWithMotion({ source: thumb, update })).toBe(false);
    expect(update).toHaveBeenCalledTimes(1);
  });

  it('still morphs under reduced motion: the stylesheet turns the morph into a 110 ms fade, not this module', async () => {
    window.matchMedia = vi.fn().mockReturnValue({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() });
    const update = vi.fn();
    expect(openWithMotion({ source: thumb, update })).toBe(true);
    await settle();
    expect(update).toHaveBeenCalledTimes(1);
    finish();
    await settle();
    delete window.matchMedia;
  });

  it('cleans up when the transition is skipped (finished rejects)', async () => {
    installStub({ rejectFinished: true });
    const update = vi.fn();
    openWithMotion({ source: thumb, update });
    await settle();
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(true);
    finish();
    await settle();
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
    expect(frame.classList.contains(FRAME_CLASS)).toBe(false);
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
  });

  it('cleans up when the update itself throws (updateCallbackDone rejects)', async () => {
    const update = vi.fn(() => { throw new Error('boom'); });
    openWithMotion({ source: thumb, update });
    await settle();
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
    finish();
    await settle();
  });

  it('cleans up by the timeout when the transition never finishes, so the map cannot stay invisible', async () => {
    vi.useFakeTimers();
    const update = vi.fn();
    openWithMotion({ source: thumb, update });
    await vi.advanceTimersByTimeAsync(0);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(true);
    await vi.advanceTimersByTimeAsync(CLEANUP_TIMEOUT_MS + 1);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
    expect(frame.classList.contains(FRAME_CLASS)).toBe(false);
    finish();
  });

  it('a transition that settles before its update ran is cleaned up after the update, never before it', async () => {
    document.startViewTransition = vi.fn((callback) => ({
      finished: Promise.reject(new Error('skipped')),
      updateCallbackDone: new Promise((resolve) => { setTimeout(() => resolve(callback()), 5); }),
    }));
    const update = vi.fn();
    openWithMotion({ source: thumb, update });
    await new Promise((resolve) => { setTimeout(resolve, 30); });
    expect(update).toHaveBeenCalledTimes(1);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
    expect(frame.classList.contains(FRAME_CLASS)).toBe(false);
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
  });

  it('gives up on a callback that never comes, and takes the card name off', async () => {
    vi.useFakeTimers();
    document.startViewTransition = vi.fn(() => ({ finished: new Promise(() => {}) }));
    openWithMotion({ source: thumb, update: vi.fn() });
    expect(thumb.classList.contains(CARD_CLASS)).toBe(true);
    await vi.advanceTimersByTimeAsync(CALLBACK_TIMEOUT_MS + 1);
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
  });

  it('runs the update plainly, once, when startViewTransition throws', () => {
    installStub({ throwOnStart: true });
    const update = vi.fn();
    expect(openWithMotion({ source: thumb, update })).toBe(false);
    expect(update).toHaveBeenCalledTimes(1);
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
  });

  it('a second press while one is pending runs plainly, so a double press is one morph', async () => {
    const first = vi.fn();
    const second = vi.fn();
    expect(openWithMotion({ source: thumb, update: first })).toBe(true);
    expect(openWithMotion({ source: thumb, update: second })).toBe(false);
    expect(second).toHaveBeenCalledTimes(1);
    await settle();
    expect(first).toHaveBeenCalledTimes(1);
    expect(document.startViewTransition).toHaveBeenCalledTimes(1);
    finish();
    await settle();
    // The guard is released: the next press morphs again.
    expect(canMorphOpen({ source: thumb })).toBe(true);
  });

  it('tolerates a missing frame (nothing to name) and a transition with no updateCallbackDone', async () => {
    frame.remove();
    document.startViewTransition = vi.fn((callback) => {
      Promise.resolve().then(callback);
      return { finished: Promise.resolve() };
    });
    const update = vi.fn();
    openWithMotion({ source: thumb, update });
    await settle();
    expect(update).toHaveBeenCalledTimes(1);
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
  });

  it('runs the update a frame later, through the browser, never inside the press', async () => {
    const order = [];
    const update = vi.fn(() => { order.push('update'); });
    openWithMotion({ source: thumb, update });
    order.push('returned');
    await settle();
    expect(order).toEqual(['returned', 'update']);
    finish();
    await settle();
  });
});

describe('closeWithMotion', () => {
  let raf;
  beforeEach(() => {
    raf = vi.fn((callback) => { setTimeout(callback, 0); return 1; });
    vi.stubGlobal('requestAnimationFrame', raf);
  });
  afterEach(() => { vi.unstubAllGlobals(); });

  it('hides the pane, waits a frame, names the frame, then closes inside the transition and names the card', async () => {
    const update = vi.fn();
    expect(closeWithMotion({ source: thumb, frame, update })).toBe(true);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(true);
    expect(frame.classList.contains(FRAME_CLASS)).toBe(false);
    expect(update).not.toHaveBeenCalled();
    await settle();
    expect(document.documentElement.classList.contains(CLOSING_CLASS)).toBe(true);
    await settle();
    expect(update).toHaveBeenCalledTimes(1);
    expect(frame.classList.contains(FRAME_CLASS)).toBe(false);
    expect(thumb.classList.contains(CARD_CLASS)).toBe(true);
    finish();
    await settle();
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
    expect(document.documentElement.classList.contains(CLOSING_CLASS)).toBe(false);
  });

  it.each([
    ['no startViewTransition', () => { delete document.startViewTransition; }],
    ['a disconnected card', () => { thumb.remove(); }],
    ['a card off screen', () => {
      thumb.getBoundingClientRect = () => ({
        width: 100, height: 60, top: 5000, left: 0, bottom: 5060, right: 100,
      });
    }],
    ['a card with no box', () => {
      thumb.getBoundingClientRect = () => ({
        width: 0, height: 0, top: 0, left: 0, bottom: 0, right: 0,
      });
    }],
    ['a hidden view', () => { frame.hidden = true; }],
  ])('closes plainly, at once, for %s', (_name, arrange) => {
    arrange();
    const update = vi.fn();
    expect(closeWithMotion({ source: thumb, frame, update })).toBe(false);
    expect(update).toHaveBeenCalledTimes(1);
    expect(raf).not.toHaveBeenCalled();
  });

  it('closes plainly when the card is gone by the time the frame comes', async () => {
    const update = vi.fn();
    closeWithMotion({ source: thumb, frame, update });
    thumb.remove();
    await settle();
    expect(update).toHaveBeenCalledTimes(1);
    expect(document.startViewTransition).not.toHaveBeenCalled();
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
  });

  it('a close pressed while the open morph is still running closes at once and ends the morph (never swallowed)', async () => {
    let release;
    const skip = vi.fn(() => release());
    document.startViewTransition = vi.fn((callback) => {
      const finished = new Promise((resolve) => { release = resolve; });
      return { finished, updateCallbackDone: Promise.resolve().then(callback), skipTransition: skip };
    });
    openWithMotion({ source: thumb, update: vi.fn() });
    await settle();
    const close = vi.fn();
    expect(closeWithMotion({ source: thumb, frame, update: close })).toBe(false);
    expect(skip).toHaveBeenCalledTimes(1);
    expect(close).toHaveBeenCalledTimes(1);
    await settle();
    // The skipped open cleaned up, so nothing is left running.
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
    expect(canMorphOpen({ source: thumb })).toBe(true);
  });

  it('a second press while one is waiting is swallowed: one close', async () => {
    const update = vi.fn();
    closeWithMotion({ source: thumb, frame, update });
    expect(closeWithMotion({ source: thumb, frame, update })).toBe(true);
    await settle();
    await settle();
    expect(update).toHaveBeenCalledTimes(1);
    finish();
    await settle();
  });

  it('closes by the timeout when no frame ever comes (a hidden tab)', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1));
    const update = vi.fn();
    closeWithMotion({ source: thumb, frame, update });
    await vi.advanceTimersByTimeAsync(CLEANUP_TIMEOUT_MS + 1);
    expect(update).toHaveBeenCalledTimes(1);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
  });

  it('closes plainly, once, when startViewTransition throws', async () => {
    installStub({ throwOnStart: true });
    const update = vi.fn();
    closeWithMotion({ source: thumb, frame, update });
    await settle();
    expect(update).toHaveBeenCalledTimes(1);
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
    expect(document.documentElement.classList.contains(CLOSING_CLASS)).toBe(false);
  });

  it('cleans up when the close is skipped or its update throws', async () => {
    installStub({ rejectFinished: true });
    const update = vi.fn(() => { throw new Error('boom'); });
    closeWithMotion({ source: thumb, frame, update });
    await settle();
    await settle();
    finish();
    await settle();
    expect(frame.classList.contains(ARRIVING_CLASS)).toBe(false);
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
  });

  it('a close whose card disappears inside the callback names nothing', async () => {
    const update = vi.fn(() => { thumb.remove(); });
    closeWithMotion({ source: thumb, frame, update });
    await settle();
    await settle();
    expect(thumb.classList.contains(CARD_CLASS)).toBe(false);
    finish();
    await settle();
  });
});

describe('canMorphClose / canMorphOpen', () => {
  it('answer false with no source or frame', () => {
    expect(canMorphOpen({ source: null })).toBe(false);
    expect(canMorphClose({ source: null, frame })).toBe(false);
    expect(canMorphClose({ source: thumb, frame: null })).toBe(false);
    expect(canMorphClose({ source: thumb, frame })).toBe(true);
  });
});
