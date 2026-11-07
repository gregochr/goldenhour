/**
 * The dialog-history engine: lets the browser or phone Back button close the top dialog.
 *
 * <p>Plan: `docs/engineering/back-closes-dialogs-plan.md` (the "Refuter amendments" section wins over
 * "Review amendments", which wins over the original design). Nothing in the app registers a layer
 * yet (PR 1 ships the engine only); `Modal` and `BottomSheet` take an opt-in `back` prop and
 * {@link registerBackLayer} / `hooks/useBackLayer` are the only entry points.
 *
 * <h2>Model</h2>
 *
 * <p>A module singleton, not a React context, because Settings and the prompts render in `App`, a
 * sibling of the shell. It holds an ordered stack of layers `{onBack, mode, hasEntry}`. A layer that
 * has an <em>entry</em> owns one browser history entry whose state carries the marker
 * `{session, depth}`; `depth` is the entry ORDINAL (how many of our entries lie up to and including
 * it), not a stack index. The URL and hash are never touched. `committedDepth` is the ordinal of the
 * entry the browser should be sitting on whenever the store is idle.
 *
 * <h2>Rules, by plan item</h2>
 * <ul>
 *   <li>The depth is read from the entry the browser actually lands on ({@link readDepth}); a
 *       foreign-session or unmarked entry reads as 0.</li>
 *   <li>Growth pushes only under user activation; otherwise the layer is registered ENTRY-LESS, so
 *       Back behaves as it does today and the base entry is never marked skippable.</li>
 *   <li>Shrinking is one `go(-n)` and only when the top entry is ours at the expected depth;
 *       otherwise the marker is stripped and nothing navigates.</li>
 *   <li>At most one `go` is in flight, with an expected landing depth. Closes and pushes wait for it;
 *       the timeout only re-reads, it never pushes blind.</li>
 *   <li>Back closes the top entried layers (and any entry-less layers above them) down to the
 *       arrived depth, popping the store synchronously BEFORE calling each `onBack`. A blocking layer
 *       stops the close and the entries are pushed again. Forward past our entries is bounced.</li>
 *   <li>A popped layer that is still mounted afterwards is adopted back as entry-less.</li>
 *   <li>Every history call is in try/catch and degrades to entry-less.</li>
 *   <li>`history.scrollRestoration` is `'manual'` from just before the first push and `'auto'` again
 *       on any arrival at depth 0 with an empty stack, and at boot.</li>
 * </ul>
 *
 * <p>Import-time side effects: the single `popstate` listener, nothing else. {@link bootDialogHistory}
 * (strip a stale marker, heal `scrollRestoration`) runs lazily on the first registration.
 */

/**
 * The off switch. `false` turns every registration into a no-op (zero history calls); rollback is a
 * one-line PR.
 */
export const BACK_CLOSES_DIALOGS_ENABLED = true;

/** The `data-back-depth` attribute on `<html>`: the settled signal for end-to-end tests. */
export const SETTLED_ATTRIBUTE = 'data-back-depth';

/** The value of {@link SETTLED_ATTRIBUTE} while a traversal or reconcile is outstanding. */
export const BUSY_VALUE = 'busy';

/** More stacked layers than this is a bug in the caller (the two-dialog invariant, plus one slack). */
export const MAX_LAYER_DEPTH = 2;

/** How long an issued `go` may stay unanswered before the store re-reads the history. */
export const TRAVERSAL_TIMEOUT_MS = 1000;

const MARKER_KEY = 'phLayer';

/** Everything a test may replace; real globals are read lazily so nothing runs at import time. */
let seam = { history: null, win: null };
let enabled = BACK_CLOSES_DIALOGS_ENABLED;
let sessionId = null;
let stack = [];
let seq = 0;
let committedDepth = 0;
let inFlight = null;
let reconcileScheduled = false;
let scrollManual = false;
let booted = false;
let listenedWindow = null;

const getWindow = () => seam.win || (typeof window === 'undefined' ? null : window);
const getHistory = () => seam.history || getWindow()?.history;

/** A random per-page-load id. `getRandomValues`, because `randomUUID` needs a secure context. */
function makeSessionId() {
  const bytes = new Uint8Array(8);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

const session = () => {
  if (sessionId === null) sessionId = makeSessionId();
  return sessionId;
};

/** The depth of the entry the browser is on: ours (this session) or 0. */
function readDepth() {
  try {
    const marker = getHistory().state?.[MARKER_KEY];
    if (marker && marker.session === session() && Number.isInteger(marker.depth) && marker.depth > 0) {
      return marker.depth;
    }
  } catch {
    // A hostile or missing history reads as "not ours".
  }
  return 0;
}

/** The current state as a plain object (anything else is not ours to spread). */
function stateObject() {
  const s = getHistory().state;
  return s && typeof s === 'object' && !Array.isArray(s) ? s : {};
}

/** Removes our marker from the current entry, if there is one. Never navigates. */
function stripMarker() {
  try {
    const state = getHistory().state;
    if (state && typeof state === 'object' && state[MARKER_KEY]) {
      const rest = { ...state };
      delete rest[MARKER_KEY];
      getHistory().replaceState(rest, '');
    }
  } catch {
    // Nothing more can be done; the layers stay entry-less.
  }
}

const entriedCount = () => stack.filter((l) => l.hasEntry).length;

function setScroll(value) {
  try {
    const h = getHistory();
    if ('scrollRestoration' in h) h.scrollRestoration = value;
  } catch {
    // jsdom and exotic hosts have no such property.
  }
}

function resetScrollIfIdle() {
  if (scrollManual && !stack.length && committedDepth === 0 && !inFlight) {
    setScroll('auto');
    scrollManual = false;
  }
}

/** Publishes `committedDepth` on `<html>` once nothing is outstanding. */
function syncSettled() {
  if (!enabled || !booted) return;
  try {
    const busy = reconcileScheduled || inFlight;
    getWindow().document.documentElement.setAttribute(SETTLED_ATTRIBUTE, busy ? BUSY_VALUE : String(committedDepth));
  } catch {
    // No document: nothing to publish to.
  }
}

/** Makes every layer entry-less and forgets our position (the degraded, history-free mode). */
function degradeAll() {
  stack.forEach((l) => { l.hasEntry = false; l.placed = true; });
  committedDepth = 0;
}

function hasActivation() {
  return getWindow()?.navigator?.userActivation?.isActive === true;
}

/** Pushes one entry for the next ordinal. Returns false (and pushes nothing) when the browser refuses. */
function pushEntry() {
  try {
    const h = getHistory();
    if (!scrollManual && 'scrollRestoration' in h) {
      h.scrollRestoration = 'manual';
      scrollManual = true;
    }
    h.pushState({ ...stateObject(), [MARKER_KEY]: { session: session(), depth: committedDepth + 1 } }, '');
    committedDepth += 1;
    return true;
  } catch {
    return false;
  }
}

/** Issues the single in-flight `go`. */
function issueGo(delta, expected) {
  try {
    getHistory().go(delta);
  } catch {
    return false;
  }
  inFlight = {
    expected,
    from: committedDepth,
    timer: setTimeout(onTraversalTimeout, TRAVERSAL_TIMEOUT_MS),
  };
  return true;
}

function clearFlight() {
  if (inFlight) clearTimeout(inFlight.timer);
  inFlight = null;
}

/** Shrink: `diff` more entries are ours than the stack owns. */
function shrink(diff) {
  if (committedDepth === 0 || readDepth() !== committedDepth) {
    // The top entry is not ours at the expected depth (a hash push got in between, a reload...).
    stripMarker();
    committedDepth -= diff;
    return;
  }
  if (!issueGo(-diff, committedDepth - diff)) {
    stripMarker();
    degradeAll();
  }
}

/** Brings the history in line with the stack. Safe to call at any time; defers while a `go` is out. */
function reconcile() {
  if (!enabled) return;
  if (inFlight) {
    syncSettled();
    return;
  }
  const pending = stack.filter((l) => !l.placed);
  let diff = committedDepth - entriedCount();
  // A same-depth swap (A closes, B opens in one commit): B takes A's entry. No `go(0)`, no push.
  while (diff > 0 && pending.length) {
    const taker = pending.shift();
    taker.hasEntry = true;
    taker.placed = true;
    diff -= 1;
  }
  if (diff > 0) {
    shrink(diff);
  } else {
    pending.forEach((layer) => {
      layer.placed = true;
      layer.hasEntry = hasActivation() && pushEntry();
    });
  }
  resetScrollIfIdle();
  syncSettled();
}

function scheduleReconcile() {
  if (reconcileScheduled) return;
  reconcileScheduled = true;
  syncSettled();
  queueMicrotask(() => {
    reconcileScheduled = false;
    reconcile();
  });
}

/** Re-adopts popped layers that are still mounted after their `onBack`, as entry-less. */
function adoptRefused(closed) {
  setTimeout(() => {
    closed.forEach((layer) => {
      if (layer.released || stack.includes(layer)) return;
      layer.hasEntry = false;
      layer.placed = true;
      stack.push(layer);
      stack.sort((a, b) => a.seq - b.seq);
    });
    syncSettled();
  }, 0);
}

/**
 * Back (or a long-press jump) landed at `arrived`, below where the stack thinks it is: close layers
 * from the top, stopping at a blocking one, and push entries back for whoever is left.
 */
function closeDownTo(arrived) {
  let toClose = committedDepth - arrived;
  const closed = [];
  while (toClose > 0 && stack.length && stack[stack.length - 1].mode !== 'block') {
    const layer = stack.pop();
    if (layer.hasEntry) toClose -= 1;
    closed.push(layer);
  }
  const blocked = toClose > 0 && stack.length > 0;
  committedDepth = arrived;
  if (blocked) {
    const want = entriedCount();
    while (committedDepth < want) {
      if (!pushEntry()) {
        degradeAll();
        break;
      }
    }
  }
  closed.forEach((layer) => {
    try {
      layer.onBack?.();
    } catch (error) {
      console.error('[dialogHistory] onBack threw', error);
    }
  });
  if (closed.length) adoptRefused(closed);
}

/** Classifies an arrival nobody on our side asked for. `canGo` is false while a traversal is out. */
function handleArrival(arrived, canGo) {
  if (arrived > committedDepth) {
    // Forward onto a stale entry of ours: bounce, closing nothing.
    if (canGo) issueGo(committedDepth - arrived, committedDepth);
  } else if (arrived < committedDepth) {
    closeDownTo(arrived);
  }
  resetScrollIfIdle();
  syncSettled();
}

function onPop() {
  if (!enabled) return;
  const arrived = readDepth();
  if (!inFlight && !stack.length && committedDepth === 0 && arrived === 0) {
    resetScrollIfIdle();
    return;
  }
  if (inFlight) {
    if (arrived === inFlight.expected) {
      clearFlight();
      committedDepth = arrived;
      reconcile();
      return;
    }
    handleArrival(arrived, false);
    return;
  }
  handleArrival(arrived, true);
  scheduleReconcile();
}

function onTraversalTimeout() {
  const flight = inFlight;
  inFlight = null;
  const position = readDepth();
  if (position === flight.from) {
    // The traversal never moved us: stop trusting the history altogether.
    stripMarker();
    degradeAll();
  } else {
    handleArrival(position, true);
  }
  reconcile();
}

/** Strips a marker left by an earlier page load and heals `scrollRestoration`. Idempotent. */
export function bootDialogHistory() {
  if (!enabled || booted) return;
  booted = true;
  stripMarker();
  try {
    const h = getHistory();
    if ('scrollRestoration' in h && h.scrollRestoration === 'manual') h.scrollRestoration = 'auto';
  } catch {
    // Not a browser.
  }
}

const INERT_LAYER = Object.freeze({ update() {}, release() {} });

/**
 * Registers a dialog layer. Returns `{update, release}`; `update({onBack, mode})` changes the layer
 * in place (its place in the stack and its entry are kept), `release()` unregisters it (idempotent).
 *
 * @param {{onBack: Function, mode?: 'close'|'cancel'|'block'}} options `block` absorbs Back.
 * @returns {{update: Function, release: Function}}
 */
export function registerBackLayer({ onBack, mode = 'close' } = {}) {
  if (!enabled) return INERT_LAYER;
  bootDialogHistory();
  const layer = { seq: (seq += 1), onBack, mode, hasEntry: false, placed: false, released: false };
  stack.push(layer);
  if (import.meta.env.DEV && stack.length > MAX_LAYER_DEPTH) {
    console.warn(`[dialogHistory] ${stack.length} dialogs are stacked; the app allows at most ${MAX_LAYER_DEPTH}.`);
  }
  scheduleReconcile();
  return {
    update(next = {}) {
      if (next.onBack !== undefined) layer.onBack = next.onBack;
      if (next.mode !== undefined) layer.mode = next.mode;
    },
    release() {
      if (layer.released) return;
      layer.released = true;
      const at = stack.indexOf(layer);
      if (at >= 0) {
        stack.splice(at, 1);
        scheduleReconcile();
      }
    },
  };
}

function listen(win) {
  if (listenedWindow === win) return;
  listenedWindow?.removeEventListener('popstate', onPop);
  win?.addEventListener('popstate', onPop);
  listenedWindow = win;
}

/**
 * Test seam: clears all state and installs a fake `history` / `win` (omit for the real ones).
 *
 * @param {{history?: object, win?: object, enabled?: boolean}} [options]
 */
export function __resetForTests({ history = null, win = null, enabled: on = BACK_CLOSES_DIALOGS_ENABLED } = {}) {
  clearFlight();
  seam = { history, win };
  enabled = on;
  sessionId = null;
  stack = [];
  seq = 0;
  committedDepth = 0;
  reconcileScheduled = false;
  scrollManual = false;
  booted = false;
  listen(getWindow());
}

/** Test visibility into the store; not for product code. */
export function __inspectForTests() {
  return {
    committedDepth,
    layers: stack.length,
    entried: entriedCount(),
    inFlight: Boolean(inFlight),
    scrollManual,
    session: sessionId,
  };
}

listen(getWindow());
