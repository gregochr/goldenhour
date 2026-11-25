import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, renderHook, screen,
} from '@testing-library/react';
import useBodyScrollLock from '../hooks/useBodyScrollLock.js';
import useDockEdge from '../hooks/useDockEdge.js';
import ScreenNote from '../components/ScreenNote.jsx';
import MapScreen from '../components/MapScreen.jsx';
import { useMapScreen } from '../context/MapScreenContext.jsx';
import { loadMapChunk, preloadMapChunk, PRELOAD_FALLBACK_MS } from '../utils/mapChunk.js';
import { routeWindowStatus } from '../utils/mapRoute.js';
import { markEscapeAnswered, escapeAnswered } from '../utils/mapEscape.js';
import {
  foreignModalOverPaneOf, MAP_PANE_SELECTOR, MAP_SCREEN_SELECTOR,
} from '../utils/mapForeignModal.js';

// The real pane drags Leaflet in; the chunk tests only need a module to resolve.
vi.mock('../components/WindowFirstMapPane.jsx', () => ({ default: () => null }));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  document.documentElement.style.overflow = '';
  document.body.style.paddingRight = '';
  document.body.innerHTML = '';
});

describe('useBodyScrollLock', () => {
  it('hides the root overflow while held, never touches the scroll position, and restores on release', () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    document.documentElement.style.overflow = 'scroll';
    const { rerender } = renderHook(({ locked }) => useBodyScrollLock(locked), { initialProps: { locked: false } });
    expect(document.documentElement.style.overflow).toBe('scroll');
    rerender({ locked: true });
    expect(document.documentElement.style.overflow).toBe('hidden');
    rerender({ locked: false });
    expect(document.documentElement.style.overflow).toBe('scroll');
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it('hands the scrollbar width back as body padding, and takes it away again', () => {
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(985);
    window.innerWidth = 1000;
    document.body.style.paddingRight = '4px';
    const { unmount } = renderHook(() => useBodyScrollLock(true));
    expect(document.body.style.paddingRight).toBe('19px');
    unmount();
    expect(document.body.style.paddingRight).toBe('4px');
  });

  it('is reference counted: two holders, the last to go restores', () => {
    document.documentElement.style.overflow = 'auto';
    const first = renderHook(() => useBodyScrollLock(true));
    const second = renderHook(() => useBodyScrollLock(true));
    first.unmount();
    expect(document.documentElement.style.overflow).toBe('hidden');
    second.unmount();
    expect(document.documentElement.style.overflow).toBe('auto');
  });
});

describe('ScreenNote', () => {
  it('says its text in a status region and dismisses through its button', () => {
    const onDismiss = vi.fn();
    render(<ScreenNote text="Not forecast yet" onDismiss={onDismiss} />);
    expect(screen.getByRole('status')).toHaveTextContent('Not forecast yet');
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss this note' }));
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });
});

describe('preloadMapChunk', () => {
  it('loads the pane\'s module through the one loader `lazy()` also uses', async () => {
    const module = await loadMapChunk();
    expect(typeof module.default).toBe('function');
  });

  it('starts on an idle callback (and loads the chunk), and can be cancelled', async () => {
    const win = {
      requestIdleCallback: vi.fn((callback) => { callback(); return 7; }), cancelIdleCallback: vi.fn(),
    };
    const cancel = preloadMapChunk(win);
    expect(win.requestIdleCallback).toHaveBeenCalledTimes(1);
    cancel();
    expect(win.cancelIdleCallback).toHaveBeenCalledWith(7);
  });

  it('cancels quietly when the window has no cancelIdleCallback', () => {
    const win = { requestIdleCallback: vi.fn(() => 3) };
    expect(() => preloadMapChunk(win)()).not.toThrow();
  });

  it('falls back to a timer where there is no idle callback, and cancels it', () => {
    const win = { setTimeout: vi.fn((callback) => { callback(); return 9; }), clearTimeout: vi.fn() };
    const cancel = preloadMapChunk(win);
    expect(win.setTimeout).toHaveBeenCalledWith(expect.any(Function), PRELOAD_FALLBACK_MS);
    cancel();
    expect(win.clearTimeout).toHaveBeenCalledWith(9);
  });

  it('is a no-op with no window', () => {
    expect(preloadMapChunk(undefined)).toBeTypeOf('function');
    vi.stubGlobal('window', undefined);
    try {
      expect(preloadMapChunk()()).toBeUndefined();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('reads the real window by default', () => {
    window.requestIdleCallback = vi.fn(() => 1);
    try {
      preloadMapChunk();
      expect(window.requestIdleCallback).toHaveBeenCalledTimes(1);
    } finally {
      delete window.requestIdleCallback;
    }
  });
});

describe('routeWindowStatus', () => {
  const facts = { todayStr: '2026-10-05', forecastDates: ['2026-10-05', '2026-10-06'], windowKeys: new Set(['2026-10-05:SUNSET']) };

  it('a date before today has passed', () => {
    expect(routeWindowStatus({ date: '2026-10-04', eventType: 'SUNRISE' }, facts)).toBe('past');
  });

  it('a date the forecast does not cover is not forecast yet', () => {
    expect(routeWindowStatus({ date: '2026-10-09', eventType: 'SUNSET' }, facts)).toBe('unforecast');
  });

  it('today\'s window with no card has passed, with one it is fine', () => {
    expect(routeWindowStatus({ date: '2026-10-05', eventType: 'SUNRISE' }, facts)).toBe('past');
    expect(routeWindowStatus({ date: '2026-10-05', eventType: 'SUNSET' }, facts)).toBe('ok');
  });

  it('a future covered date is fine', () => {
    expect(routeWindowStatus({ date: '2026-10-06', eventType: 'SUNRISE' }, facts)).toBe('ok');
  });

  it('never invents a complaint from missing knowledge', () => {
    expect(routeWindowStatus({ date: '2026-10-09', eventType: 'SUNSET' }, { todayStr: '2026-10-05' })).toBe('ok');
    expect(routeWindowStatus({ date: '2026-10-05', eventType: 'SUNRISE' }, { todayStr: '2026-10-05', forecastDates: ['2026-10-05'] })).toBe('ok');
    expect(routeWindowStatus({ date: null, eventType: null }, facts)).toBe('ok');
    expect(routeWindowStatus(null, facts)).toBe('ok');
  });

  it('judges a night only by the calendar', () => {
    expect(routeWindowStatus({ date: '2026-10-09', eventType: 'ASTRO' }, facts)).toBe('ok');
    expect(routeWindowStatus({ date: '2026-10-01', eventType: 'AURORA' }, facts)).toBe('past');
  });
});

describe('markEscapeAnswered', () => {
  it('remembers a native event and nothing else', () => {
    const event = new KeyboardEvent('keydown', { key: 'Escape' });
    expect(escapeAnswered(event)).toBe(false);
    markEscapeAnswered(event);
    expect(escapeAnswered(event)).toBe(true);
    expect(escapeAnswered(new KeyboardEvent('keydown'))).toBe(false);
    markEscapeAnswered(null);
    markEscapeAnswered('x');
    expect(escapeAnswered(null)).toBe(false);
    expect(escapeAnswered('x')).toBe(false);
  });
});

describe('foreignModalOverPaneOf from the map view\'s header', () => {
  it('finds the pane through the view\'s root, so a node in the header is not told "no pane" (stand down for anything)', () => {
    document.body.innerHTML = `
      <div data-map-screen>
        <div id="head"><button id="in-head"></button></div>
        <div class="wf-map-tab" id="pane"><div role="dialog" aria-modal="true" id="inline"></div></div>
      </div>`;
    const head = document.getElementById('in-head');
    expect(MAP_SCREEN_SELECTOR).toBe('[data-map-screen]');
    expect(MAP_PANE_SELECTOR).toBe('.wf-map-tab');
    // A dialog INSIDE the pane is its own business: not foreign, even from the header.
    expect(foreignModalOverPaneOf(head)).toBe(false);
    const outside = document.createElement('div');
    outside.setAttribute('role', 'dialog');
    outside.setAttribute('aria-modal', 'true');
    document.body.appendChild(outside);
    expect(foreignModalOverPaneOf(head)).toBe(true);
  });

  it('still stands down for any modal when the node has neither a pane nor a view above it', () => {
    document.body.innerHTML = '<button id="lone"></button><div role="dialog" aria-modal="true"></div>';
    expect(foreignModalOverPaneOf(document.getElementById('lone'))).toBe(true);
  });
});

describe('useDockEdge', () => {
  let observed;
  beforeEach(() => {
    observed = [];
    window.ResizeObserver = class {
      constructor(callback) { this.callback = callback; observed.push(this); }

      observe() {}

      disconnect() {}
    };
  });

  function Host({ active }) {
    const ref = React.useRef(null);
    const edge = useDockEdge(ref, active);
    return (
      <div ref={ref}>
        <aside className="wf-ask-dock" data-testid="dock" />
        <output data-testid="edge">{edge}</output>
      </div>
    );
  }

  it('is the dock\'s distance from the right edge while active, and zero when not', async () => {
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280);
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
      return this.classList?.contains('wf-ask-dock')
        ? { left: 900, width: 380, right: 1280, top: 0, bottom: 0, height: 0 }
        : { left: 0, width: 0, right: 0, top: 0, bottom: 0, height: 0 };
    });
    const { rerender } = render(<Host active={false} />);
    expect(screen.getByTestId('edge')).toHaveTextContent('0');
    await act(async () => { rerender(<Host active />); });
    expect(screen.getByTestId('edge')).toHaveTextContent('380');
    await act(async () => { rerender(<Host active={false} />); });
    expect(screen.getByTestId('edge')).toHaveTextContent('0');
  });

  it('measures again on a window resize and on a dock resize, and reads zero when the dock has no width', async () => {
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280);
    let left = 900;
    let width = 380;
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
      return this.classList?.contains('wf-ask-dock')
        ? { left, width, right: left + width, top: 0, bottom: 0, height: 0 }
        : { left: 0, width: 0, right: 0, top: 0, bottom: 0, height: 0 };
    });
    await act(async () => { render(<Host active />); });
    expect(screen.getByTestId('edge')).toHaveTextContent('380');
    left = 800;
    await act(async () => { window.dispatchEvent(new Event('resize')); });
    expect(screen.getByTestId('edge')).toHaveTextContent('480');
    width = 0;
    await act(async () => { observed[0].callback(); });
    expect(screen.getByTestId('edge')).toHaveTextContent('0');
  });

  it('reads zero when no dock is in the tree', async () => {
    function Bare() {
      const ref = React.useRef(null);
      const edge = useDockEdge(ref, true);
      return <div ref={ref}><output data-testid="edge">{edge}</output></div>;
    }
    await act(async () => { render(<Bare />); });
    expect(screen.getByTestId('edge')).toHaveTextContent('0');
  });

  it('works with no ResizeObserver at all', async () => {
    delete window.ResizeObserver;
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1000);
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
      return this.classList?.contains('wf-ask-dock')
        ? { left: 700, width: 300, right: 1000, top: 0, bottom: 0, height: 0 }
        : { left: 0, width: 0, right: 0, top: 0, bottom: 0, height: 0 };
    });
    await act(async () => { render(<Host active />); });
    expect(screen.getByTestId('edge')).toHaveTextContent('300');
  });
});

describe('MapScreen on its own', () => {
  function Child() {
    const screenCtx = useMapScreen();
    return <span data-testid="child">{screenCtx ? 'in view' : 'standalone'}</span>;
  }

  const base = () => ({
    onClose: vi.fn(),
    onScrimPress: vi.fn(),
    onRoute: vi.fn(),
    titleRef: React.createRef(),
  });

  it('shows the default title and offers its context to the pane', () => {
    render(<MapScreen open {...base()}><Child /></MapScreen>);
    expect(screen.getByTestId('map-screen-title')).toHaveTextContent('Map');
    expect(screen.getByTestId('child')).toHaveTextContent('in view');
    expect(screen.getByTestId('map-screen-facts')).toBeEmptyDOMElement();
  });

  it('draws no light clause for a header published without one, or with an empty one', () => {
    function Publisher({ light }) {
      const screenCtx = useMapScreen();
      React.useEffect(() => { screenCtx.publishHeader({ title: 'Fri sunrise', light }); }, [screenCtx, light]);
      return null;
    }
    const { rerender } = render(<MapScreen open {...base()}><Publisher light={undefined} /></MapScreen>);
    expect(screen.queryByTestId('map-screen-light-golden')).toBeNull();
    rerender(<MapScreen open {...base()}><Publisher light={null} /></MapScreen>);
    expect(screen.queryByTestId('map-screen-light-blue')).toBeNull();
    rerender(<MapScreen open {...base()}><Publisher light={{ place: null, windows: [{ label: 'golden', range: '05:00–05:40' }] }} /></MapScreen>);
    expect(screen.getByTestId('map-screen-light-golden')).toHaveTextContent('golden 05:00–05:40');
    // No place, no "at" clause.
    expect(screen.getByTestId('map-screen-light-golden').querySelector('.sr-only')).toBeNull();
    expect(screen.queryByTestId('map-screen-light-blue')).toBeNull();
  });

  it('sets the dock inset as a custom property only when there is one', () => {
    const { rerender } = render(<MapScreen open {...base()} right={380}><Child /></MapScreen>);
    expect(screen.getByTestId('map-screen').style.getPropertyValue('--wf-map-right')).toBe('380px');
    rerender(<MapScreen open {...base()}><Child /></MapScreen>);
    expect(screen.getByTestId('map-screen').style.getPropertyValue('--wf-map-right')).toBe('');
  });

  it('is hidden when closed and does not lock the scroll', () => {
    render(<MapScreen open={false} {...base()}><Child /></MapScreen>);
    expect(screen.getByTestId('map-screen').hasAttribute('hidden')).toBe(true);
    expect(document.documentElement.style.overflow).toBe('');
  });

  it('draws the note only when it has one', () => {
    const props = base();
    const { rerender } = render(<MapScreen open {...props}><Child /></MapScreen>);
    expect(screen.queryByTestId('screen-note')).toBeNull();
    rerender(<MapScreen open {...props} note="Not forecast yet" onDismissNote={vi.fn()}><Child /></MapScreen>);
    expect(screen.getByTestId('screen-note')).toBeInTheDocument();
  });

  it('a standalone child sees no context', () => {
    render(<Child />);
    expect(screen.getByTestId('child')).toHaveTextContent('standalone');
  });
});
