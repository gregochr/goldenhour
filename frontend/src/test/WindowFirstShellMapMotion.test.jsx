import React, { useEffect } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen,
} from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import { AskProvider } from '../context/AskContext.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { useMapScreen } from '../context/MapScreenContext.jsx';
import { installViewport, resetViewport } from './askViewport.js';
import { matrixCtx, shellCtx, TODAY } from './askShellHarness.jsx';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';
import { openMapView, registerMapDoor } from './shellMapDoor.js';
import { ARRIVING_CLASS, CARD_CLASS, FRAME_CLASS } from '../utils/mapMotion.js';

/**
 * The card opens into the map (`docs/engineering/screen-spec-plan.md` section 6), through the real shell with a
 * stubbed `document.startViewTransition`: a card thumbnail morphs, every other door and every other close is plain,
 * the Back entry still exists after an animated open (the update runs a frame later, inside the transition), and focus
 * is never on `<body>`.
 */
vi.mock('../api/askApi.js', async (original) => {
  const real = await original();
  return {
    ...real, getReady: vi.fn(), ask: vi.fn(), getAskSettings: vi.fn(),
  };
});
import { getAskSettings, getReady } from '../api/askApi.js';
import { readyResponse, settings } from './askFixtures.js';
vi.mock('../api/almanacApi.js', () => ({
  ALMANAC_DAYS: 90,
  getAlmanac: vi.fn().mockResolvedValue({
    builtFor: '2026-10-05', bands: null, counts: null, conditions: [], entries: [],
  }),
}));
vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));
import { fetchAreaDirectory, fetchAreaOutlook } from '../api/areasApi.js';

vi.setConfig({ testTimeout: 30000 });

/** The pane, reduced to what the view needs of it. */
function StubPane() {
  const mapScreen = useMapScreen();
  useEffect(() => {
    mapScreen?.publishHeader({ title: 'Tonight Sunset', verdictLabel: 'Worth it', bestRating: 5 });
    mapScreen?.publishRoute({ date: TODAY, eventType: 'SUNSET', locationId: 5 });
  }, [mapScreen]);
  return <div className="wf-map-tab" data-testid="map-pane-stub" tabIndex={-1}>pane</div>;
}

let browser;
let finishTransition;
const view = () => screen.getByTestId('map-screen');
const viewIsOpen = () => !view().hasAttribute('hidden');
const thumb = () => screen.getByTestId('thumb');
const hit = () => screen.getByTestId('card-hit');
const layers = () => __inspectForTests().layers;
const settle = () => act(async () => { await new Promise((resolve) => { setTimeout(resolve, 0); }); });

/** A browser with View Transitions: the callback runs a frame (a macrotask) later, `finished` waits for the test. */
function installTransitions() {
  document.startViewTransition = vi.fn((callback) => {
    let release;
    const finished = new Promise((resolve) => { release = resolve; });
    finishTransition = release;
    const updateCallbackDone = new Promise((resolve) => {
      setTimeout(() => { resolve(callback()); }, 0);
    });
    return { finished, updateCallbackDone };
  });
}

function mount() {
  installViewport(1280);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx({ ...matrixCtx() }));
  const onOpenMapTab = vi.fn();
  const base = {
    onOpenSettings: vi.fn(),
    onSignOut: vi.fn(),
    locations: [{ id: 11, name: 'Whitby' }],
    mapPane: <StubPane />,
    onOpenMapTab,
    onFindOnMap: vi.fn(),
    forecastDates: [TODAY],
    registerOpenMap: registerMapDoor,
  };
  render(
    <>
      <div data-testid="wf-heat-card">
        <span data-testid="thumb" className="wf-hc-cv" />
        <button
          type="button"
          className="wf-hc-hit"
          data-testid="card-hit"
          onClick={(event) => openMapView({
            window: { date: TODAY, eventType: 'SUNSET' }, from: event.currentTarget,
          })}
        >
          card
        </button>
      </div>
      <button type="button" data-testid="plain-door" onClick={(event) => openMapView({ from: event.currentTarget })}>
        plain
      </button>
      <AskProvider>
        <WindowFirstShell {...base} />
      </AskProvider>
    </>,
  );
  thumb().getBoundingClientRect = () => ({
    width: 100, height: 60, top: 10, left: 10, bottom: 70, right: 110,
  });
  return { onOpenMapTab };
}

beforeEach(() => {
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  fetchAreaDirectory.mockResolvedValue([]);
  fetchAreaOutlook.mockResolvedValue({ outlook: [] });
  browser = installBackBrowser();
  installTransitions();
  vi.stubGlobal('requestAnimationFrame', (callback) => setTimeout(callback, 0));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  resetViewport();
  uninstallBackBrowser();
  delete document.startViewTransition;
  document.documentElement.className = '';
  document.documentElement.style.overflow = '';
});

/** Presses the card and lets the transition's update run (a frame later); the transition itself is left running. */
async function openFromCard() {
  hit().focus();
  await act(async () => { fireEvent.click(hit()); });
  await settle();
  await browser.settle();
}

describe('opening from a card', () => {
  it('runs the whole open inside the transition, names the card and then the view, and cleans up when it finishes', async () => {
    mount();
    hit().focus();
    await act(async () => { fireEvent.click(hit()); });
    // Pending: the press has started a transition and the old state is still the screen.
    expect(document.startViewTransition).toHaveBeenCalledTimes(1);
    expect(thumb()).toHaveClass(CARD_CLASS);
    expect(screen.queryByTestId('map-screen')).toBeNull();

    await settle();
    // The update has run: the view is open, with the frame's name and the pane hidden; the card gave its name up.
    expect(viewIsOpen()).toBe(true);
    expect(thumb()).not.toHaveClass(CARD_CLASS);
    expect(view()).toHaveClass(FRAME_CLASS);
    expect(view()).toHaveClass(ARRIVING_CLASS);
    // Focus is on the title (outside the pane), never body.
    expect(document.activeElement).toBe(screen.getByTestId('map-screen-title'));

    await act(async () => { finishTransition(); });
    await settle();
    expect(view()).not.toHaveClass(FRAME_CLASS);
    expect(view()).not.toHaveClass(ARRIVING_CLASS);
    expect(document.activeElement).not.toBe(document.body);
  });

  it('still pushes the Back entry, so Back closes the view, plainly (no transition for Back)', async () => {
    mount();
    await openFromCard();
    await act(async () => { finishTransition(); });
    await settle();
    expect(layers()).toBe(1);
    expect(document.startViewTransition).toHaveBeenCalledTimes(1);

    await browser.back();

    expect(viewIsOpen()).toBe(false);
    expect(layers()).toBe(0);
    expect(document.startViewTransition).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(hit());
  });

  it('a double press is one door: the second finds the view open and re-targets it', async () => {
    const { onOpenMapTab } = mount();
    await act(async () => {
      fireEvent.click(hit());
      fireEvent.click(hit());
    });
    await settle();
    await browser.settle();
    expect(viewIsOpen()).toBe(true);
    expect(layers()).toBe(1);
    expect(document.startViewTransition).toHaveBeenCalledTimes(1);
    // Two bodies ran, the first opened the view and the second found it open: it was handed to the map as in place.
    expect(onOpenMapTab.mock.calls.map(([door]) => door.inPlace)).toEqual([false, true]);
    await act(async () => { finishTransition(); });
    await settle();
  });

  it('a door that is not a card thumbnail opens plainly, with no transition', async () => {
    mount();
    await act(async () => { fireEvent.click(screen.getByTestId('plain-door')); });
    await browser.settle();
    expect(document.startViewTransition).not.toHaveBeenCalled();
    expect(viewIsOpen()).toBe(true);
    expect(view()).not.toHaveClass(FRAME_CLASS);
    expect(view()).not.toHaveClass(ARRIVING_CLASS);
  });

  it('opens plainly, at once, in a browser without view transitions', async () => {
    mount();
    delete document.startViewTransition;
    await act(async () => { fireEvent.click(hit()); });
    await browser.settle();
    expect(viewIsOpen()).toBe(true);
    expect(layers()).toBe(1);
  });

  it('a skipped transition still ends with the pane drawn: finished rejecting cleans up', async () => {
    mount();
    document.startViewTransition = vi.fn((callback) => {
      const updateCallbackDone = new Promise((resolve) => { setTimeout(() => resolve(callback()), 0); });
      return { finished: Promise.reject(new Error('skipped')), updateCallbackDone };
    });
    await act(async () => { fireEvent.click(hit()); });
    await settle();
    await settle();
    expect(viewIsOpen()).toBe(true);
    expect(view()).not.toHaveClass(ARRIVING_CLASS);
    expect(view()).not.toHaveClass(FRAME_CLASS);
  });

  it('puts focus on the title when the pane was hidden and nothing else holds it', async () => {
    mount();
    await openFromCard();
    // Something moved focus off the page while the pane was hidden (a focus into the pane that could not land).
    await act(async () => { document.activeElement?.blur(); });
    expect(document.activeElement).toBe(document.body);
    await act(async () => { finishTransition(); });
    await settle();
    expect(document.activeElement).toBe(screen.getByTestId('map-screen-title'));
  });
});

describe('closing while the open morph is still running', () => {
  it('Escape closes the view at once instead of being swallowed, and focus returns to the card', async () => {
    mount();
    await openFromCard();
    // The transition has not finished: the open morph is still running.
    expect(view()).toHaveClass(ARRIVING_CLASS);
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-title'), { key: 'Escape' }); });
    await settle();
    expect(viewIsOpen()).toBe(false);
    expect(document.activeElement).toBe(hit());
    await act(async () => { finishTransition(); });
    await settle();
    expect(layers()).toBe(0);
  });
});

describe('closing', () => {
  async function openSettled() {
    await openFromCard();
    await act(async () => { finishTransition(); });
    await settle();
    document.startViewTransition.mockClear();
  }

  it('the close button morphs back into the card, and focus returns to the card', async () => {
    mount();
    await openSettled();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    // Waiting a frame: the pane is hidden, nothing has closed yet.
    expect(viewIsOpen()).toBe(true);
    expect(view()).toHaveClass(ARRIVING_CLASS);
    await settle();
    await settle();
    expect(document.startViewTransition).toHaveBeenCalledTimes(1);
    expect(viewIsOpen()).toBe(false);
    expect(thumb()).toHaveClass(CARD_CLASS);
    expect(document.activeElement).toBe(hit());
    await act(async () => { finishTransition(); });
    await settle();
    expect(thumb()).not.toHaveClass(CARD_CLASS);
    expect(view()).not.toHaveClass(ARRIVING_CLASS);
    expect(document.activeElement).toBe(hit());
    expect(layers()).toBe(0);
  });

  it('Escape morphs back too', async () => {
    mount();
    await openSettled();
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-close'), { key: 'Escape' }); });
    await settle();
    await settle();
    expect(document.startViewTransition).toHaveBeenCalledTimes(1);
    expect(viewIsOpen()).toBe(false);
    expect(document.activeElement).toBe(hit());
    await act(async () => { finishTransition(); });
    await settle();
  });

  it('a press on the scrim closes plainly, with no transition', async () => {
    mount();
    await openSettled();
    await act(async () => {
      fireEvent.pointerDown(screen.getByTestId('map-screen-scrim'));
      fireEvent.click(screen.getByTestId('map-screen-scrim'));
    });
    await browser.settle();
    expect(document.startViewTransition).not.toHaveBeenCalled();
    expect(viewIsOpen()).toBe(false);
  });

  it('the close button closes plainly when the card has scrolled out of view', async () => {
    mount();
    await openSettled();
    thumb().getBoundingClientRect = () => ({
      width: 100, height: 60, top: 5000, left: 10, bottom: 5060, right: 110,
    });
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();
    expect(document.startViewTransition).not.toHaveBeenCalled();
    expect(viewIsOpen()).toBe(false);
  });

  it('a view opened by a plain door closes plainly from the close button', async () => {
    mount();
    await act(async () => { fireEvent.click(screen.getByTestId('plain-door')); });
    await browser.settle();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();
    expect(document.startViewTransition).not.toHaveBeenCalled();
    expect(viewIsOpen()).toBe(false);
    expect(document.activeElement).toBe(screen.getByTestId('plain-door'));
  });
});
