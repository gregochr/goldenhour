import React, { StrictMode, useRef } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, act } from '@testing-library/react';
import useBackLayer from '../hooks/useBackLayer.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests, __resetForTests } from '../utils/dialogHistory.js';

/**
 * `useBackLayer({ activationGated })` (back-closes-dialogs-plan.md, PR 3): a first-run prompt registers its
 * Back guard only once the page has a live user activation, so Chromium never marks the entry skippable.
 * `navigator.userActivation` is stubbed globally (what the hook reads); the fake browser's own `win` always
 * reports an active page, so the engine's own push is never the thing under test.
 */

let b;

function setActivation(value) {
  if (value === undefined) {
    delete globalThis.navigator.userActivation;
  } else {
    Object.defineProperty(globalThis.navigator, 'userActivation', {
      value: { isActive: value }, configurable: true, writable: true,
    });
  }
}

function Probe({ gated = true, withRef = true, open = true, mode = 'block' }) {
  const ref = useRef(null);
  useBackLayer({ open, mode, activationGated: gated, ref: withRef ? ref : undefined });
  return <div ref={ref} data-testid="prompt"><button type="button" data-testid="inside">in</button></div>;
}

beforeEach(() => {
  b = installBackBrowser();
});

afterEach(() => {
  setActivation(undefined);
  uninstallBackBrowser();
  vi.restoreAllMocks();
});

describe('useBackLayer activationGated', () => {
  it('registers at once when the page is already active', async () => {
    setActivation(true);
    render(<Probe />);
    await b.settle();

    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(__inspectForTests().layers).toBe(1);
  });

  it('with no activation, pushes nothing at open and nothing on a pointerdown', async () => {
    setActivation(false);
    const view = render(<Probe />);
    await b.settle();
    expect(b.history.pushState).not.toHaveBeenCalled();

    fireEvent.pointerDown(view.getByTestId('inside'));
    await b.settle();

    expect(b.history.pushState).not.toHaveBeenCalled();
    expect(__inspectForTests().layers).toBe(0);
  });

  it('registers on the first click inside once activation is live, and absorbs Back', async () => {
    setActivation(false);
    const view = render(<Probe />);
    await b.settle();

    setActivation(true);
    fireEvent.click(view.getByTestId('inside'));
    await b.settle();

    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    await b.back();
    // A block layer absorbs: pushed again, still one layer at depth 1.
    expect(__inspectForTests().layers).toBe(1);
    expect(__inspectForTests().committedDepth).toBe(1);
  });

  it('registers on a keyup as well', async () => {
    setActivation(false);
    const view = render(<Probe />);
    setActivation(true);
    fireEvent.keyUp(view.getByTestId('inside'), { key: 'Tab' });
    await b.settle();

    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('keeps waiting when a gesture arrives while the page is still not active', async () => {
    setActivation(false);
    const view = render(<Probe />);

    fireEvent.click(view.getByTestId('inside'));
    await b.settle();
    expect(b.history.pushState).not.toHaveBeenCalled();

    setActivation(true);
    fireEvent.click(view.getByTestId('inside'));
    await b.settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('listens on the document when given no ref, and a second gesture does not push again', async () => {
    setActivation(false);
    render(<Probe withRef={false} />);
    setActivation(true);

    fireEvent.click(document.body);
    fireEvent.click(document.body);
    await b.settle();

    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(__inspectForTests().layers).toBe(1);
  });

  it('with no userActivation API on either side, registers on the gesture but makes no history entry', async () => {
    setActivation(undefined);
    // The engine reads its own window's navigator: remove the API there too.
    delete b.win.navigator.userActivation;
    const view = render(<Probe />);
    await b.settle();
    expect(__inspectForTests().layers).toBe(0);

    fireEvent.click(view.getByTestId('inside'));
    await b.settle();

    // Registered (a layer exists), but the engine pushes only on a live activation: no guard, Back as before.
    expect(__inspectForTests().layers).toBe(1);
    expect(b.history.pushState).not.toHaveBeenCalled();
    expect(__inspectForTests().committedDepth).toBe(0);
  });

  it('listens on the element it is given, not the document: a click outside it does not register', async () => {
    setActivation(false);
    const view = render(<Probe />);
    setActivation(true);

    fireEvent.click(document.body);
    fireEvent.keyUp(document.body, { key: 'Tab' });
    await b.settle();
    expect(b.history.pushState).not.toHaveBeenCalled();
    expect(__inspectForTests().layers).toBe(0);

    fireEvent.click(view.getByTestId('inside'));
    await b.settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('closing before any activation makes zero history calls and leaves no listener', async () => {
    setActivation(false);
    const view = render(<Probe />);
    const node = view.getByTestId('prompt');

    view.unmount();
    setActivation(true);
    fireEvent.click(node);
    fireEvent.keyUp(node, { key: 'Tab' });
    await b.settle();

    expect(b.history.pushState).not.toHaveBeenCalled();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(__inspectForTests().layers).toBe(0);
  });

  it('closing after registration releases the layer and drops the entry', async () => {
    setActivation(true);
    const view = render(<Probe />);
    await b.settle();
    expect(__inspectForTests().committedDepth).toBe(1);

    view.unmount();
    await b.settle();

    expect(__inspectForTests().layers).toBe(0);
    expect(__inspectForTests().committedDepth).toBe(0);
  });

  it('nets one push under StrictMode, both when active at open and when a gesture arrives later', async () => {
    setActivation(true);
    const first = render(<StrictMode><Probe /></StrictMode>);
    await b.settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    first.unmount();
    await b.settle();

    b.history.pushState.mockClear();
    setActivation(false);
    const second = render(<StrictMode><Probe /></StrictMode>);
    setActivation(true);
    fireEvent.click(second.getByTestId('inside'));
    await b.settle();

    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(__inspectForTests().layers).toBe(1);
  });

  it('is unchanged without the flag: registers at open whatever the activation', async () => {
    setActivation(false);
    render(<Probe gated={false} />);
    await b.settle();

    expect(__inspectForTests().layers).toBe(1);
  });

  it('is a no-op under the off switch: zero history calls, before and after a gesture', async () => {
    __resetForTests({ history: b.history, win: b.win, enabled: false });
    setActivation(false);
    const view = render(<Probe />);
    setActivation(true);
    fireEvent.click(view.getByTestId('inside'));
    await act(async () => { await Promise.resolve(); });

    expect(b.history.pushState).not.toHaveBeenCalled();
    expect(b.history.replaceState).not.toHaveBeenCalled();
    expect(b.history.go).not.toHaveBeenCalled();
  });
});
