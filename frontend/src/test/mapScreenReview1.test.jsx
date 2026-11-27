import React, { useEffect } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, renderHook, screen,
} from '@testing-library/react';
import MapScreen from '../components/MapScreen.jsx';
import useDockEdge from '../hooks/useDockEdge.js';
import useBackLayer from '../hooks/useBackLayer.js';
import { useMapScreen } from '../context/MapScreenContext.jsx';
import { __inspectForTests, __resetForTests } from '../utils/dialogHistory.js';
import { escapeAnswered } from '../utils/mapEscape.js';

/**
 * The map view's review round 1 (`docs/engineering/one-screen-plan.md` D12): the arrow keys step the window from the
 * header, Escape with focus on <body> still closes the view, the dock edge is measured in the commit that needs it, and
 * the activation-gated layer registers on a keydown too.
 */
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  document.documentElement.style.overflow = '';
  document.body.innerHTML = '';
});

/** A pane stand-in: publishes a stepper and an Escape ladder through the view's context, as `MapView` does. */
function Pane({ step = vi.fn(), ladder = () => false }) {
  const screenCtx = useMapScreen();
  const publishStep = screenCtx?.publishStep;
  const publishEscape = screenCtx?.publishEscape;
  useEffect(() => { publishStep?.(step); }, [publishStep, step]);
  useEffect(() => { publishEscape?.(ladder); }, [publishEscape, ladder]);
  return (
    <div className="wf-map-tab" data-testid="pane">
      <div className="leaflet-container" tabIndex={-1} data-testid="leaflet" />
    </div>
  );
}

function mountView({
  open = true, onClose = vi.fn(), find = null, step = vi.fn(), ladder = () => false,
} = {}) {
  const titleRef = React.createRef();
  const utils = render(
    <MapScreen
      open={open}
      onClose={onClose}
      onScrimPress={vi.fn()}
      onRoute={vi.fn()}
      titleRef={titleRef}
      find={find}
    >
      <Pane step={step} ladder={ladder} />
    </MapScreen>,
  );
  return { ...utils, onClose, step, titleRef };
}

describe('the arrow keys on the header step the window', () => {
  it('→ steps forward and ← steps back from the title, the close button and the Find control', () => {
    const step = vi.fn();
    mountView({ step, find: <button type="button" data-testid="find-stub">Find</button> });
    ['map-screen-title', 'map-screen-close', 'find-stub'].forEach((id) => {
      step.mockClear();
      fireEvent.keyDown(screen.getByTestId(id), { key: 'ArrowRight' });
      expect(step).toHaveBeenLastCalledWith(1);
      fireEvent.keyDown(screen.getByTestId(id), { key: 'ArrowLeft' });
      expect(step).toHaveBeenLastCalledWith(-1);
    });
  });

  it('prevents the default, so the key does not also scroll', () => {
    mountView();
    const event = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true });
    screen.getByTestId('map-screen-title').dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
  });

  it('leaves a text field, a listbox, a menu and the window control to their own arrow keys', () => {
    const step = vi.fn();
    mountView({
      step,
      find: (
        <>
          <input data-testid="field" />
          <div role="listbox" tabIndex={0} data-testid="listbox"><span role="option" aria-selected="false" data-testid="option" tabIndex={0}>a</span></div>
          <div role="menu" tabIndex={0} data-testid="menu" />
          <div data-testid="wf-win-control"><button type="button" data-testid="in-win">w</button></div>
        </>
      ),
    });
    ['field', 'listbox', 'option', 'menu', 'in-win'].forEach((id) => {
      fireEvent.keyDown(screen.getByTestId(id), { key: 'ArrowRight' });
    });
    expect(step).not.toHaveBeenCalled();
  });

  it('is not heard from the map itself, and ignores a modified arrow', () => {
    const step = vi.fn();
    mountView({ step });
    fireEvent.keyDown(screen.getByTestId('leaflet'), { key: 'ArrowRight' });
    fireEvent.keyDown(screen.getByTestId('map-screen-title'), { key: 'ArrowRight', altKey: true });
    fireEvent.keyDown(screen.getByTestId('map-screen-title'), { key: 'ArrowRight', shiftKey: true });
    fireEvent.keyDown(screen.getByTestId('map-screen-title'), { key: 'ArrowRight', ctrlKey: true });
    fireEvent.keyDown(screen.getByTestId('map-screen-title'), { key: 'ArrowRight', metaKey: true });
    fireEvent.keyDown(screen.getByTestId('map-screen-title'), { key: 'ArrowUp' });
    expect(step).not.toHaveBeenCalled();
  });

  it('does nothing, and does not swallow the key, when the pane has published no stepper', () => {
    render(
      <MapScreen open onClose={vi.fn()} onScrimPress={vi.fn()} onRoute={vi.fn()} titleRef={React.createRef()} />,
    );
    const event = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true });
    screen.getByTestId('map-screen-title').dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
  });
});

describe('Escape with focus on <body>', () => {
  const pressOnBody = () => {
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    document.body.dispatchEvent(event);
    return event;
  };

  it('closes the view while it is open', () => {
    const { onClose } = mountView();
    const event = pressOnBody();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);
  });

  it('closes an inner layer first, and the view only when nothing inside answers', () => {
    const ladder = vi.fn().mockReturnValueOnce(true).mockReturnValue(false);
    const { onClose } = mountView({ ladder });
    pressOnBody();
    expect(onClose).not.toHaveBeenCalled();
    pressOnBody();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('stands down for a dialog over the pane (a sheet, Find, Ask)', () => {
    const { onClose } = mountView();
    const sheet = document.createElement('div');
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-modal', 'true');
    document.body.appendChild(sheet);
    pressOnBody();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('stands down for a press another handler already answered', () => {
    const { onClose } = mountView();
    const answered = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    answered.preventDefault();
    document.body.dispatchEvent(answered);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('is not the view\'s business when the press came from an element outside it', () => {
    const { onClose } = mountView();
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    fireEvent.keyDown(outside, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('does nothing while the view is closed, and stops listening when it closes', () => {
    const { onClose, rerender, titleRef } = mountView({ open: false });
    pressOnBody();
    expect(onClose).not.toHaveBeenCalled();
    rerender(
      <MapScreen open onClose={onClose} onScrimPress={vi.fn()} onRoute={vi.fn()} titleRef={titleRef}>
        <Pane />
      </MapScreen>,
    );
    pressOnBody();
    expect(onClose).toHaveBeenCalledTimes(1);
    rerender(
      <MapScreen open={false} onClose={onClose} onScrimPress={vi.fn()} onRoute={vi.fn()} titleRef={titleRef}>
        <Pane />
      </MapScreen>,
    );
    pressOnBody();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('a press the pane answered (marked) is left to it', () => {
    const { onClose } = mountView();
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    expect(escapeAnswered(event)).toBe(false);
    document.body.dispatchEvent(event);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('useDockEdge on a reopen', () => {
  beforeEach(() => {
    window.ResizeObserver = class {
      observe() {}

      disconnect() {}
    };
  });

  it('never renders a previous dock\'s edge: the first render of a reopen reads zero when the dock is gone', () => {
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280);
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
      return this.classList?.contains('wf-ask-dock')
        ? { left: 900, width: 380, right: 1280, top: 0, bottom: 0, height: 0 }
        : { left: 0, width: 0, right: 0, top: 0, bottom: 0, height: 0 };
    });
    const root = document.createElement('div');
    const dock = document.createElement('aside');
    dock.className = 'wf-ask-dock';
    root.appendChild(dock);
    document.body.appendChild(root);
    const rootRef = { current: root };
    const seen = [];
    const { rerender } = renderHook(({ active }) => {
      const edge = useDockEdge(rootRef, active);
      seen.push([active, edge]);
      return edge;
    }, { initialProps: { active: true } });
    expect(seen.at(-1)).toEqual([true, 380]);

    // Closed, the dock goes away, then the view reopens: no render of the reopen may carry the old 380.
    rerender({ active: false });
    dock.remove();
    seen.length = 0;
    rerender({ active: true });

    expect(seen.length).toBeGreaterThan(0);
    seen.forEach(([, edge]) => expect(edge).toBe(0));
  });

  it('measures in the commit that activates it, so the reopen paints the dock\'s own edge at once', () => {
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280);
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
      return this.classList?.contains('wf-ask-dock')
        ? { left: 800, width: 480, right: 1280, top: 0, bottom: 0, height: 0 }
        : { left: 0, width: 0, right: 0, top: 0, bottom: 0, height: 0 };
    });
    const root = document.createElement('div');
    const dock = document.createElement('aside');
    dock.className = 'wf-ask-dock';
    root.appendChild(dock);
    document.body.appendChild(root);
    const rootRef = { current: root };
    const { result } = renderHook(() => useDockEdge(rootRef, true));
    // No await, no timer: renderHook has flushed layout effects only.
    expect(result.current).toBe(480);
  });
});

describe('an activation-gated layer registers on a keydown as well as a click or keyup', () => {
  function Gated({ onBack = vi.fn() }) {
    useBackLayer({ open: true, onBack, activationGated: true });
    return null;
  }

  beforeEach(() => {
    __resetForTests();
    Object.defineProperty(globalThis.navigator, 'userActivation', { configurable: true, value: { isActive: false } });
  });

  afterEach(() => {
    delete globalThis.navigator.userActivation;
    __resetForTests();
  });

  it('a keyboard first gesture (⌘K) registers the base layer BEFORE the layer that key opens', () => {
    render(<Gated />);
    expect(__inspectForTests().layers).toBe(0);
    // The activation is live for the keydown, which is captured before any handler that opens Find.
    Object.defineProperty(globalThis.navigator, 'userActivation', { configurable: true, value: { isActive: true } });
    act(() => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true, bubbles: true })); });
    expect(__inspectForTests().layers).toBe(1);
  });

  it('registers once, whichever gesture comes first', () => {
    render(<Gated />);
    Object.defineProperty(globalThis.navigator, 'userActivation', { configurable: true, value: { isActive: true } });
    act(() => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true })); });
    act(() => { document.dispatchEvent(new KeyboardEvent('keyup', { key: 'a', bubbles: true })); });
    act(() => { document.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
    expect(__inspectForTests().layers).toBe(1);
  });

  it('a keydown with no live activation registers nothing', () => {
    render(<Gated />);
    act(() => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Shift', bubbles: true })); });
    expect(__inspectForTests().layers).toBe(0);
  });
});
