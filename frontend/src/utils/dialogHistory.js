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

/** More stacked layers than this is a bug in the caller: the map view plus two (a sheet or Find, and one over it). */
export const MAX_LAYER_DEPTH = 3;

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
let shrinkTimer = null;
/** Bumped by every test reset so a timer from an earlier test cannot touch the next one's stack. */
let epoch = 0;
let reconcileScheduled = false;
let scrollManual = false;
let booted = false;
let listenedWindow = null;
/** Resolvers of {@link whenSettled} promises, drained by {@link syncSettled} once nothing is outstanding. */
let settledWaiters = [];
/**
 * True when an entry-less layer's address was cleared while entries of ours stood above the base entry, so the base
 * entry (which carried the address) still shows it; rewritten bare once the history next lands at depth 0.
 */
let baseAddressStale = false;

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

/**
 * On every arrival at depth 0 with an empty stack and nothing outstanding, heals `'manual'` whatever our
 * own flag says: an entry we never touched (a hash entry pushed while a layer was open inherits it) can
 * carry it too.
 */
function resetScrollIfIdle() {
  if (stack.length || committedDepth !== 0 || inFlight || shrinkTimer) return;
  try {
    const h = getHistory();
    if ('scrollRestoration' in h && h.scrollRestoration === 'manual') h.scrollRestoration = 'auto';
  } catch {
    // jsdom and exotic hosts have no such property.
  }
  scrollManual = false;
}

/** The Navigation API's entry index, where the browser has one (used only to tell landings apart). */
function navIndex() {
  const index = getWindow()?.navigation?.currentEntry?.index;
  return Number.isInteger(index) ? index : null;
}

/** Publishes `committedDepth` on `<html>` once nothing is outstanding. */
function syncSettled() {
  if (!enabled || !booted) return;
  const busy = reconcileScheduled || inFlight || shrinkTimer;
  try {
    getWindow().document.documentElement.setAttribute(SETTLED_ATTRIBUTE, busy ? BUSY_VALUE : String(committedDepth));
  } catch {
    // No document: nothing to publish to.
  }
  if (!busy && settledWaiters.length) {
    const waiting = settledWaiters;
    settledWaiters = [];
    waiting.forEach((resolve) => resolve());
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

/** The address the stack wants shown: the topmost layer's `url` that has one, or undefined. */
function effectiveUrl() {
  for (let i = stack.length - 1; i >= 0; i -= 1) {
    if (stack[i].url) return stack[i].url;
  }
  return undefined;
}

/** The hash the browser shows now ('' when none or unreadable). */
function currentHash() {
  return getWindow()?.location?.hash ?? '';
}

/**
 * Whether the engine may write the address: only over an empty hash or its own map hash, so a route someone else
 * owns (`#manage/...`, a token) is never clobbered.
 */
function addressIsOurs() {
  const hash = currentHash();
  return hash === '' || hash.startsWith('#map');
}

/**
 * Writes the stack's address onto the CURRENT entry (copying its state) when it differs. Skipped while a
 * traversal is outstanding (the location is in transit) and over a foreign hash.
 */
function syncUrl() {
  if (!enabled || inFlight || shrinkTimer) return;
  const want = effectiveUrl();
  if (!want) {
    // No layer wants an address, yet our own map hash is still showing: a layer with an address gave its entry to one
    // without (a same-depth swap), or the base entry kept the address of an entry-less layer that closed above it.
    const lingering = currentHash().startsWith('#map') && addressIsOurs();
    if (lingering && (stack.length > 0 || (baseAddressStale && committedDepth === 0))) writeBareAddress();
    if (committedDepth === 0) baseAddressStale = false;
    return;
  }
  baseAddressStale = false;
  if (currentHash() === want || !addressIsOurs()) return;
  try {
    const h = getHistory();
    h.replaceState(h.state, '', want);
  } catch {
    // The address is a nicety; the layers still work without it.
  }
}

/** Replaces the current entry's address with the bare path, copying its state. */
function writeBareAddress() {
  try {
    const loc = getWindow().location;
    const h = getHistory();
    h.replaceState(h.state, '', `${loc.pathname}${loc.search}`);
  } catch {
    // The address is a nicety; the layers still work without it.
  }
}

/** Pushes one entry for the next ordinal. Returns false (and pushes nothing) when the browser refuses. */
function pushEntry() {
  try {
    const h = getHistory();
    if (!scrollManual && 'scrollRestoration' in h) {
      h.scrollRestoration = 'manual';
      scrollManual = true;
    }
    const state = { ...stateObject(), [MARKER_KEY]: { session: session(), depth: committedDepth + 1 } };
    const url = effectiveUrl();
    if (url && addressIsOurs()) h.pushState(state, '', url);
    else h.pushState(state, '');
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
  const index = navIndex();
  inFlight = {
    expected,
    expectedIndex: index === null ? null : index + delta,
    rearmed: false,
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

/**
 * Brings the history in line with the stack. Safe to call at any time; defers while a `go` is out.
 * A shrink is deferred by one task and re-checked (`shrinkNow`), so a user's Back that was already
 * queued behind a close lands first and the `go` is then not issued at all.
 */
function reconcile(shrinkNow = false) {
  if (!enabled) return;
  if (inFlight || shrinkTimer) {
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
    if (shrinkNow) {
      shrink(diff);
    } else {
      shrinkTimer = setTimeout(() => {
        shrinkTimer = null;
        reconcile(true);
      }, 0);
    }
  } else {
    pending.forEach((layer) => {
      layer.placed = true;
      layer.hasEntry = hasActivation() && pushEntry();
    });
  }
  resetScrollIfIdle();
  syncUrl();
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
  const born = epoch;
  setTimeout(() => {
    if (born !== epoch) return;
    closed.forEach((layer) => {
      if (layer.released || stack.includes(layer)) return;
      layer.hasEntry = false;
      layer.placed = true;
      stack.push(layer);
      stack.sort((a, b) => a.seq - b.seq);
      warnIfTooDeep();
    });
    syncSettled();
  }, 0);
}

/**
 * Back (or a long-press jump) landed at `arrived`, below where the stack thinks it is: close layers
 * from the top, stopping at a blocking one, and push entries back for whoever is left.
 */
function closeDownTo(arrived) {
  let toClose = Math.max(0, entriedCount() - arrived);
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
  syncUrl();
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
    const landedHere = inFlight.expectedIndex === null || navIndex() === inFlight.expectedIndex;
    if (arrived === inFlight.expected && landedHere) {
      clearFlight();
      committedDepth = arrived;
      reconcile();
      // A traversal landed on an older entry of ours: its address may be stale (the window stepped meanwhile).
      syncUrl();
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
  const position = readDepth();
  if (position === flight.from && !flight.rearmed) {
    // A slow device is not a broken one: wait once more, and a late landing is still taken as ours.
    flight.rearmed = true;
    flight.timer = setTimeout(onTraversalTimeout, TRAVERSAL_TIMEOUT_MS);
    return;
  }
  inFlight = null;
  if (position === flight.from) {
    // The traversal never moved us, twice: stop trusting the history altogether.
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

function warnIfTooDeep() {
  if (import.meta.env.DEV && stack.length > MAX_LAYER_DEPTH) {
    console.warn(`[dialogHistory] ${stack.length} dialogs are stacked; the app allows at most ${MAX_LAYER_DEPTH}.`);
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
export function registerBackLayer({ onBack, mode = 'close', url = undefined } = {}) {
  if (!enabled) return INERT_LAYER;
  bootDialogHistory();
  const layer = { seq: (seq += 1), onBack, mode, url, hasEntry: false, placed: false, released: false };
  stack.push(layer);
  warnIfTooDeep();
  scheduleReconcile();
  return {
    update(next = {}) {
      if (next.onBack !== undefined) layer.onBack = next.onBack;
      if (next.mode !== undefined) layer.mode = next.mode;
      if (next.url !== undefined && next.url !== layer.url) {
        layer.url = next.url;
        // A placed layer shows its new address at once; one still waiting to push carries it into the push.
        if (layer.placed) syncUrl();
      }
    },
    release() {
      if (layer.released) return;
      layer.released = true;
      const at = stack.indexOf(layer);
      if (at >= 0) {
        stack.splice(at, 1);
        clearOwnAddress(layer);
        scheduleReconcile();
      }
    },
  };
}

/**
 * A closing layer that wrote an address but owns no entry (the load-time open, a refused push) puts the bare path
 * back, copying the state, unless another layer still owns an address or the hash is no longer the one it wrote.
 */
function clearOwnAddress(layer) {
  if (layer.hasEntry || !layer.url || effectiveUrl() || currentHash() !== layer.url) return;
  // Entries of ours stand above the base entry: the address was written on the base, which this cannot reach, so it
  // is rewritten when a traversal next lands there (`syncUrl`).
  if (committedDepth > 0) baseAddressStale = true;
  writeBareAddress();
}

/**
 * Resolves once no traversal, deferred shrink or reconcile is outstanding, i.e. the history has caught up with
 * every layer that closed. A caller about to write a hash of its own (Operations' `#manage`, plan D4/D9) awaits
 * this so its entry is never taken for the engine's. Resolves immediately when the engine is off or idle.
 *
 * @returns {Promise<void>}
 */
export function whenSettled() {
  return new Promise((resolve) => {
    const busy = enabled && (reconcileScheduled || inFlight || shrinkTimer);
    if (!busy) {
      resolve();
      return;
    }
    settledWaiters.push(resolve);
  });
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
  epoch += 1;
  const waiting = settledWaiters;
  settledWaiters = [];
  waiting.forEach((resolve) => resolve());
  clearFlight();
  clearTimeout(shrinkTimer);
  shrinkTimer = null;
  seam = { history, win };
  enabled = on;
  sessionId = null;
  stack = [];
  seq = 0;
  committedDepth = 0;
  baseAddressStale = false;
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

/** Removes the module's `popstate` listener (hot module replacement; also lets a test prove it). */
export function disposeDialogHistory() {
  listenedWindow?.removeEventListener('popstate', onPop);
  listenedWindow = null;
}

// No HMR dispose hook: GitHub CodeQL's JS extractor fails to parse `import.meta.hot` calls (code-scanning alerts
// 287 and 288). An HMR reload of this module leaves an inert old listener in development, which is harmless.
listen(getWindow());
