import { beforeEach, afterEach } from 'vitest';

/**
 * The recording 2d-context stub shared by the heat-field kernel tests (jsdom has no 2d context and no
 * global polyfill goes into setup.js). {@link installCanvasStub} registers the beforeEach/afterEach
 * hooks that patch {@code HTMLCanvasElement.prototype.getContext}, and returns a state object whose
 * {@code contexts} (a WeakMap canvas -> context) and {@code allContexts} (every context made, in
 * order) are fresh for each test.
 */

export const PATH_METHODS = ['moveTo', 'lineTo', 'closePath', 'arc'];

const RECORDED = ['putImageData', 'clearRect', 'fillRect', 'save', 'restore', 'drawImage', 'beginPath',
  'fill', 'stroke', 'clip', 'setTransform', 'translate', ...PATH_METHODS];

/**
 * Builds one recording context.
 *
 * @param {(ctx: object) => object} snapshot the context state each recorded call carries besides its
 *   name and args (for example {@code (c) => ({ fillStyle: c.fillStyle })})
 */
export function makeContext(snapshot = () => ({})) {
  const calls = [];
  const record = (name) => (...args) => {
    calls.push({ name, args, ...snapshot(ctx) });
  };
  const ctx = {
    calls,
    filter: 'none',
    globalAlpha: 1,
    globalCompositeOperation: 'source-over',
    imageSmoothingEnabled: false,
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 0,
    dash: null,
    createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4), width: w, height: h }),
  };
  ctx.setLineDash = (d) => { ctx.dash = d; calls.push({ name: 'setLineDash', args: [d] }); };
  RECORDED.forEach((name) => {
    ctx[name] = record(name);
  });
  return ctx;
}

/**
 * Installs the getContext patch for every test in the calling file.
 *
 * @param {(ctx: object) => object} snapshot see {@link makeContext}
 * @returns {{contexts: WeakMap, allContexts: object[]}} live state, reassigned before each test
 */
export function installCanvasStub(snapshot) {
  const state = { contexts: new WeakMap(), allContexts: [] };
  let originalGetContext;

  beforeEach(() => {
    state.contexts = new WeakMap();
    state.allContexts = [];
    originalGetContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function getContextStub() {
      if (!state.contexts.has(this)) {
        const ctx = makeContext(snapshot);
        ctx.canvas = this;
        state.contexts.set(this, ctx);
        state.allContexts.push(ctx);
      }
      return state.contexts.get(this);
    };
  });

  afterEach(() => {
    HTMLCanvasElement.prototype.getContext = originalGetContext;
  });

  return state;
}
