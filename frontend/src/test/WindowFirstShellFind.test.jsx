import { openMapView, pressMenuRow, registerMapDoor } from './shellMapDoor.js';
import React, { useCallback, useRef, useState } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import { AskProvider } from '../context/AskContext.jsx';
import { GuideProvider } from '../context/GuideContext.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { resetViewport, installViewport } from './askViewport.js';
import {
  MAP_PANE, OPERATIONS_PANE, matrixCtx, shellCtx, TODAY,
} from './askShellHarness.jsx';
import {
  NOW, readyResponse, SALTBURN, settings, WHITBY,
} from './askFixtures.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';
import { favouritesValue } from './favouritesKit.jsx';
import { FavouritesContext } from '../context/FavouritesContext.jsx';

/**
 * Find everywhere (find-and-favourites-plan.md F1): the masthead door and ⌘K / Ctrl K on every tab, and what a
 * pick does on each — through the REAL shell, with Ask and the guide mounted around it the way `App` mounts them.
 *
 * <p>⚠️ <b>How a refusal is asserted.</b> Two ways, never one, as `AskShellKey.test.jsx` does it:
 * {@code defaultPrevented} is the observable where the key's default IS the rule (a ⌘K a refusal leaves must still
 * reach the browser), and the absence of Find is asserted only next to a control — the same press, with the refusal
 * lifted, DOES open it. An absence alone is what a key that was never wired also produces.
 */
vi.mock('../api/almanacApi.js', () => ({
  ALMANAC_DAYS: 90,
  getAlmanac: vi.fn().mockResolvedValue({
    builtFor: '2026-10-05', bands: null, counts: null, conditions: [], entries: [],
  }),
}));
vi.mock('../api/askApi.js', async (original) => {
  const real = await original();
  return {
    ...real, getReady: vi.fn(), ask: vi.fn(), getAskSettings: vi.fn(),
  };
});
import { getAskSettings, getReady } from '../api/askApi.js';
vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));
import { fetchAreaDirectory, fetchAreaOutlook } from '../api/areasApi.js';

// The first test to open a lazy chunk pays for fetching it.
vi.setConfig({ testTimeout: 30000 });

let b;
/** A favourites value for the Rig to provide, or null for none. */
let favouritesCtx = null;

const settle = async () => {
  await act(async () => {
    for (let i = 0; i < 2; i += 1) {
      await new Promise((resolve) => { setTimeout(resolve, 5); });
      b.flush();
    }
  });
};
const back = async () => {
  b.userBack();
  await settle();
};

const REGION = {
  id: 7, name: 'North York Moors & Coast', baseName: 'Whitby', baseLat: 54.48, baseLon: -0.61,
};
const findButton = () => screen.getByTestId('window-first-search');
const find = () => screen.queryByTestId('plan-search-panel');
const input = () => screen.getByTestId('plan-search-input');
const sheet = () => screen.queryByTestId('location-sheet');
/** The screen is what is showing: the map view is closed or was never opened. */
const expectScreen = () => expect(screen.queryByTestId('map-screen')?.hasAttribute('hidden') ?? true).toBe(true);

/** The shell under the guide and Ask providers, with the screen it reports handed to the guide, as `App` does. */
function Rig({ shellProps }) {
  const closeRef = useRef(null);
  const [current, setCurrent] = useState('plan');
  const closeDialogs = useCallback(() => closeRef.current?.(), []);
  const register = useCallback((fn) => { closeRef.current = fn; }, []);
  const shell = (
    <WindowFirstShell registerOpenMap={registerMapDoor}
      {...{ locations: [], onShowOnMap: vi.fn(), onSignOut: vi.fn(), onOpenSettings: vi.fn() }}
      {...{ onScreenChange: setCurrent, registerCloseDialogs: register }}
      {...shellProps}
    />
  );
  const tree = (
    <GuideProvider closeShellDialogs={closeDialogs} activeScreen={current}>
      <AskProvider>{shell}</AskProvider>
    </GuideProvider>
  );
  // Your places (F4): a favourites value is provided only when a test asks for one, as `App` provides it for a Pro or
  // Lite reader; with none, the shell runs as it did before favourites.
  return favouritesCtx ? <FavouritesContext.Provider value={favouritesCtx}>{tree}</FavouritesContext.Provider> : tree;
}

/** The matrix context, plus the region records and the `setOrigin` a region pick reaches for. */
const findCtx = (extra = {}) => ({
  ...matrixCtx(), regions: [REGION], setOrigin: vi.fn(), origin: null, ...extra,
});

function mount({
  width = 1280, ctx = findCtx(), props = {}, askSettings = settings(),
} = {}) {
  installViewport(width);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx(ctx));
  if (askSettings !== false) getAskSettings.mockResolvedValue(askSettings);
  const view = render(<Rig shellProps={props} />);
  return { ...view, ctx, rerenderShell: (next) => view.rerender(<Rig shellProps={{ ...props, ...next }} />) };
}

/** Presses ⌘K (or Ctrl K, or anything else asked for) and returns the event, so a test can read `defaultPrevented`. */
function chord(init = { metaKey: true }, target = document) {
  const press = new KeyboardEvent('keydown', {
    key: 'k', bubbles: true, cancelable: true, ...init,
  });
  fireEvent(target, press);
  return press;
}

async function expectActs() {
  const press = chord();
  expect(press.defaultPrevented).toBe(true);
  expect(await screen.findByTestId('plan-search-panel')).toBeInTheDocument();
}

function expectRefused(press) {
  expect(press.defaultPrevented).toBe(false);
  expect(find()).toBeNull();
}

/** The Find button has rendered (the matrix is lazy), and the key handler's effect has run. */
async function ready() {
  await screen.findByTestId('wf-heat-strip');
  await act(async () => {});
}

async function type(text) {
  await screen.findByTestId('plan-search-panel');
  fireEvent.change(input(), { target: { value: text } });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  vi.resetAllMocks();
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  fetchAreaDirectory.mockResolvedValue([]);
  fetchAreaOutlook.mockResolvedValue([]);
  b = installBackBrowser();
  favouritesCtx = null;
});

afterEach(() => {
  cleanup();
  uninstallBackBrowser();
  vi.useRealTimers();
  vi.restoreAllMocks();
  document.querySelectorAll('[data-foreign-dialog]').forEach((node) => node.remove());
  document.querySelectorAll('[data-stray-field]').forEach((node) => node.remove());
  resetViewport();
});

describe('⌘K / Ctrl K — the guard matrix', () => {
  it('opens Find with ⌘K and with Ctrl K, and takes the key from the browser', async () => {
    mount();
    await ready();
    await expectActs();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(find()).toBeNull());

    const press = chord({ ctrlKey: true });
    expect(press.defaultPrevented).toBe(true);
    expect(await screen.findByTestId('plan-search-panel')).toBeInTheDocument();
  });

  it.each([
    ['a phone width, where Ask has no dock and so no `/`', { width: 390 }],
    ['a LITE reader, whose Ask is a locked door', { ctx: findCtx({ isPro: false, isLiteUser: true }) }],
    ['Ask down (its settings request failed)', { askSettings: false, down: true }],
    ['Ask off on the server', { askSettings: { ...settings(), enabled: false } }],
  ])('⚠️ is live at %s — it is not `askKeyRefused`', async (_label, options) => {
    const { down, ...rest } = options;
    if (down) getAskSettings.mockRejectedValue(new Error('network'));
    mount(rest);
    await ready();
    await expectActs();
  });

  it('closes on a second press, ahead of every refusal — focus is in Find\'s own field by then', async () => {
    mount();
    await ready();
    await expectActs();
    await settle();
    expect(document.activeElement).toBe(input());

    const press = chord({ metaKey: true }, input());

    expect(press.defaultPrevented).toBe(true);
    await waitFor(() => expect(find()).toBeNull());
  });

  it('⚠️ a second press does NOT close Find while the guide\'s modal is stacked above it', async () => {
    mount();
    await ready();
    await expectActs();
    await settle();
    pressMenuRow('masthead-menu-guide');
    // With Find standing the guide opens as a modal over it (the stacked protocol), which is this rule's case.
    expect(await screen.findByTestId('guide-dialog')).toBeInTheDocument();

    const press = chord({ metaKey: true });

    expect(press.defaultPrevented).toBe(false);
    expect(find()).not.toBeNull();
    expect(screen.queryByTestId('guide-dialog')).not.toBeNull();
  });

  it('⚠️ ignores an auto-repeating key: a held chord opens Find once and never toggles it', async () => {
    mount();
    await ready();
    expectRefused(chord({ metaKey: true, repeat: true }));

    await expectActs();
    const held = chord({ metaKey: true, repeat: true }, input());
    expect(held.defaultPrevented).toBe(false);
    expect(find()).not.toBeNull();
  });

  it('⚠️ is refused in a field the reader is typing in, and the browser keeps the key', async () => {
    mount();
    await ready();
    const stray = document.createElement('input');
    stray.setAttribute('data-stray-field', '');
    document.body.appendChild(stray);

    expectRefused(chord({ metaKey: true }, stray));

    stray.remove();
    await expectActs();
  });

  it('⚠️ is refused over a dialog this shell does not own — the settings modal, the postcode prompt', async () => {
    mount();
    await ready();
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('data-foreign-dialog', '');
    document.body.appendChild(foreign);

    expectRefused(chord());

    foreign.remove();
    await expectActs();
  });

  it.each([
    ['plain K', { }],
    ['Shift', { metaKey: true, shiftKey: true }],
    ['Alt', { ctrlKey: true, altKey: true }],
  ])('leaves %s to the browser', async (_label, init) => {
    mount();
    await ready();
    expectRefused(chord(init));
    await expectActs();
  });

  it('⚠️ is refused while the next-days sheet is up (two layers already), and the control opens it again', async () => {
    mount();
    await ready();
    await expectActs();
    await type('whitby');
    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));
    await screen.findByTestId('location-sheet');
    await settle();

    expectRefused(chord());
    expect(sheet()).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(sheet()).toBeNull());
    await expectActs();
  });

  it('is live on the screen and the Map, and refused on Operations (a pick there would mean nothing)', async () => {
    const { rerenderShell } = mount({ props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await ready();

    fireEvent.click(screen.getByTestId('further-ahead-jump'));
    await act(async () => { await Promise.resolve(); });
    await expectActs();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(find()).toBeNull());

    openMapView();
    await expectActs();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(find()).toBeNull());
    // The view is the base layer under Find: close it before moving to Operations (its screen is inert while it is up).
    fireEvent.click(screen.getByTestId('map-screen-close'));

    rerenderShell({ operationsOpen: true });
    expectRefused(chord());
    expect(screen.queryByTestId('window-first-search')).toBeNull();
  });
});

describe('the Find button', () => {
  it('is refused under the same two-layer rule: a sheet up, no third layer', async () => {
    mount();
    await ready();
    fireEvent.click(findButton());
    await type('whitby');
    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));
    await screen.findByTestId('location-sheet');
    await settle();
    const layers = __inspectForTests().layers;

    fireEvent.click(findButton());
    await settle();

    expect(find()).toBeNull();
    expect(__inspectForTests().layers).toBe(layers);
  });

  it('closes the Map\'s menus when it opens: the shell says so through onFindOpenChange', async () => {
    const onFindOpenChange = vi.fn();
    mount({ props: { mapPane: MAP_PANE, onFindOpenChange } });
    await ready();
    expect(onFindOpenChange).toHaveBeenLastCalledWith(false);

    fireEvent.click(findButton());
    await screen.findByTestId('plan-search-panel');
    expect(onFindOpenChange).toHaveBeenLastCalledWith(true);

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(find()).toBeNull());
    expect(onFindOpenChange).toHaveBeenLastCalledWith(false);
  });
});

describe('what a pick does on This week (unchanged)', () => {
  it('a place opens its next-days sheet, a region plans from it', async () => {
    const ctx = findCtx();
    mount({ ctx });
    await ready();

    fireEvent.click(findButton());
    await type('whitby');
    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));
    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expectScreen();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(sheet()).toBeNull());

    fireEvent.click(findButton());
    await type('north york');
    fireEvent.click(screen.getByRole('option', { name: /North York Moors/ }));
    expect(ctx.setOrigin).toHaveBeenCalledWith(REGION);
  });
});

describe('what a pick does with Further ahead on the screen (one-screen-plan.md O2: it is the same screen)', () => {
  const toComingUp = async (ctx = findCtx(), props = {}) => {
    mount({ ctx, props });
    await ready();
    // Coming up is a door onto Further ahead, the bottom section of the Plan pane: Find is offered there as everywhere.
    fireEvent.click(screen.getByTestId('further-ahead-jump'));
    await act(async () => { await Promise.resolve(); });
    fireEvent.click(findButton());
    await screen.findByTestId('plan-search-panel');
    return ctx;
  };

  it('⚠️ a place opens its sheet on the screen, and Find closes behind it', async () => {
    await toComingUp();
    await type('whitby');

    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expectScreen();
    expect(find()).toBeNull();
  });

  it('closing that sheet leaves focus on a real control, never on the body', async () => {
    await toComingUp();
    await type('whitby');
    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));
    await screen.findByTestId('location-sheet');
    await settle();

    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(sheet()).toBeNull());
    expect(document.activeElement).not.toBe(document.body);
  });

  it('a window opens the map view on it (the view\'s own Back layer is the way back)', async () => {
    const onOpenMapTab = vi.fn();
    await toComingUp(findCtx(), { mapPane: MAP_PANE, onOpenMapTab });

    fireEvent.click(screen.getAllByRole('option', { name: /Tonight/ })[0]);

    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    expect(onOpenMapTab.mock.calls[0][0]).toMatchObject({ kind: 'event', inPlace: false });
    expect(screen.getByTestId('map-screen')).not.toHaveAttribute('hidden');
  });

  it('a region plans from it and leaves the reader on the screen', async () => {
    const ctx = await toComingUp();
    await type('north york');

    fireEvent.click(screen.getByRole('option', { name: /North York Moors/ }));

    expect(ctx.setOrigin).toHaveBeenCalledWith(REGION);
    expectScreen();
  });
});

describe('what a pick does on the Map', () => {
  const toMap = async ({ ctx = findCtx(), props = {} } = {}) => {
    const onFindOnMap = vi.fn();
    const onOpenMapTab = vi.fn();
    mount({ ctx, props: { mapPane: MAP_PANE, onFindOnMap, onOpenMapTab, ...props } });
    await ready();
    openMapView();
    fireEvent.click(findButton());
    await screen.findByTestId('plan-search-panel');
    return { ctx, onFindOnMap, onOpenMapTab };
  };

  it('⚠️ a place goes to the Map\'s own channel — no sheet, no tab move, no origin', async () => {
    const { ctx, onFindOnMap } = await toMap();
    await type('whitby');

    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));

    expect(onFindOnMap).toHaveBeenCalledWith({ kind: 'location', id: WHITBY, name: 'Whitby' });
    expect(sheet()).toBeNull();
    expect(screen.getByTestId('map-screen')).not.toHaveAttribute('hidden');
    expect(ctx.setOrigin).not.toHaveBeenCalled();
    expect(find()).toBeNull();
  });

  it('⚠️ a region is a pan and a frame: the Map\'s channel, and never `setOrigin`', async () => {
    const { ctx, onFindOnMap } = await toMap();
    await type('north york');
    const row = screen.getByRole('option', { name: /North York Moors/ });
    // Worded for the map, and never refused for a reason that only an origin move has.
    expect(row).toHaveTextContent('Frame on the map');

    fireEvent.click(row);

    expect(onFindOnMap).toHaveBeenCalledWith({ kind: 'region', name: 'North York Moors & Coast' });
    expect(ctx.setOrigin).not.toHaveBeenCalled();
  });

  it('a region with no base town, or the one planned from, is still pickable on the Map', async () => {
    const baseless = { ...REGION, baseName: null, baseLat: null, baseLon: null };
    const { onFindOnMap } = await toMap({ ctx: findCtx({ regions: [baseless], origin: { id: 7, name: baseless.name, baseName: 'Whitby' } }) });
    await type('north york');

    fireEvent.click(screen.getByRole('option', { name: /North York Moors/ }));

    expect(onFindOnMap).toHaveBeenCalledWith({ kind: 'region', name: 'North York Moors & Coast' });
  });

  it('a window selects that window on the map, in place — the door\'s in-place form', async () => {
    const { onOpenMapTab } = await toMap({ props: { forecastDates: [TODAY] } });

    fireEvent.click(screen.getAllByRole('option', { name: /Tonight/ })[0]);

    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'event', date: TODAY, eventType: 'SUNSET', inPlace: true,
    }));
    expect(screen.getByTestId('map-screen')).not.toHaveAttribute('hidden');
  });

  it('⚠️ offers no window the map\'s control cannot reach (a date outside forecastDates)', async () => {
    await toMap({ props: { forecastDates: ['2026-12-25'] } });

    expect(screen.queryAllByRole('option', { name: /Tonight/ })).toHaveLength(0);
  });

  it('words a place for the map', async () => {
    await toMap();
    await type('whitby');
    expect(screen.getByRole('option', { name: /Whitby/ })).toHaveTextContent('Show on map');
  });
});

describe('Back and focus', () => {
  it('Back closes Find on every tab it is offered on', async () => {
    mount({ props: { mapPane: MAP_PANE } });
    await ready();
    for (const name of ['This week', 'Further ahead']) {
      if (name === 'Further ahead') fireEvent.click(screen.getByTestId('further-ahead-jump'));
      await act(async () => { await Promise.resolve(); });
      fireEvent.click(findButton());
      await screen.findByTestId('plan-search-panel');
      await settle();
      expect(__inspectForTests().layers).toBe(1);

      await back();

      await waitFor(() => expect(find()).toBeNull());
      expect(__inspectForTests().layers).toBe(0);
    }
    // In the map view Find is layer 2 over the view's own base layer: Back closes Find, then the view.
    openMapView();
    await settle();
    fireEvent.click(screen.getByTestId('map-screen-find'));
    await screen.findByTestId('plan-search-panel');
    await settle();
    expect(__inspectForTests().layers).toBe(2);
    await back();
    await waitFor(() => expect(find()).toBeNull());
    expect(__inspectForTests().layers).toBe(1);
    expect(screen.getByTestId('map-screen')).not.toHaveAttribute('hidden');
    await back();
    expect(__inspectForTests().layers).toBe(0);
    expect(screen.getByTestId('map-screen')).toHaveAttribute('hidden');
  });

  it('⚠️ a ⌘K open has no focused trigger, so closing returns focus to the Find button, not <body>', async () => {
    mount();
    await ready();
    expect(document.activeElement).toBe(document.body);
    await expectActs();

    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(find()).toBeNull());
    expect(document.activeElement).toBe(findButton());
  });

  it('⚠️ a sheet a pick opened returns focus to the Find button when it closes', async () => {
    mount();
    await ready();
    await expectActs();
    await type('whitby');
    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));
    await screen.findByTestId('location-sheet');
    await settle();

    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(sheet()).toBeNull());
    expect(document.activeElement).toBe(findButton());
  });
});

describe('areas in Find (F2)', () => {
  const SALTBURN_SPOT = {
    id: SALTBURN, name: 'Saltburn', lat: 54.58, lng: -0.97, regionName: 'North York Moors & Coast',
    rid: 'North York Moors & Coast', skySubject: true, bortleClass: 3, scores: [5],
  };
  const WALL = {
    id: 1, name: "Hadrian's Wall", kind: 'NAMED', aliases: ['Roman Wall'], regionId: null, regionName: null,
    locationIds: [WHITBY, SALTBURN],
  };
  const TYNEDALE = {
    id: 2, name: "Hadrian's Wall & Tynedale", kind: 'SUB_REGION', aliases: [], regionId: 3, regionName: 'North East',
    locationIds: [WHITBY],
  };
  const areaSheet = () => screen.queryByTestId('area-sheet');
  const areaCtx = (extra = {}) => {
    const base = findCtx();
    return {
      ...base,
      heatSpots: [...base.heatSpots, SALTBURN_SPOT],
      effectiveReachById: shellCtx().reachById,
      // Whitby is rated 4 for tonight; Saltburn has no row at all.
      scoreRows: [{
        locationId: WHITBY, locationName: 'Whitby', date: TODAY, targetType: 'SUNSET', rating: 4,
      }],
      ...extra,
    };
  };

  beforeEach(() => {
    fetchAreaDirectory.mockResolvedValue([WALL, TYNEDALE]);
    fetchAreaOutlook.mockResolvedValue([{
      areaId: 1,
      windows: [{
        date: TODAY, targetType: 'SUNSET', ratedMembers: 1, totalMembers: 2, gatedMembers: 1, topRating: 4, topLocationId: WHITBY,
      }],
    }]);
  });

  /** Opens Find on the tab already showing, waits for the directory, and types. */
  const findArea = async (query) => {
    fireEvent.click(findButton());
    await screen.findByTestId('plan-search-panel');
    await settle();
    fireEvent.change(input(), { target: { value: query } });
  };
  const pickWall = () => fireEvent.click(screen.getByRole('option', { name: /^Hadrian's Wall(?! &)/ }));
  const openWall = async (props = {}, ctx = areaCtx()) => {
    mount({ ctx, props });
    await ready();
    await findArea('hadrian');
    pickWall();
    await screen.findByTestId('area-sheet');
    await settle();
  };

  it('fetches nothing until Find first opens, then the directory and the outlook', async () => {
    mount({ ctx: areaCtx() });
    await ready();
    expect(fetchAreaDirectory).not.toHaveBeenCalled();

    fireEvent.click(findButton());
    await screen.findByTestId('plan-search-panel');

    expect(fetchAreaDirectory).toHaveBeenCalledTimes(1);
    expect(fetchAreaOutlook).toHaveBeenCalledTimes(1);
  });

  it('lists both areas under an Areas heading, never merged, and the row carries its figure and window', async () => {
    mount({ ctx: areaCtx() });
    await ready();
    await findArea('hadrian');

    const options = screen.getAllByRole('option');
    expect(options.map((o) => o.getAttribute('data-kind'))).toEqual(['area', 'area']);
    expect(options[0]).toHaveTextContent("Hadrian's Wall");
    expect(within(options[0]).getByTestId('plan-search-sub').textContent)
      .toBe('Named area·2 spots·1 rated·1 outside your picks');
    expect(options[0]).toHaveTextContent('4★');
    expect(options[0]).toHaveTextContent('Tonight Sunset');
    expect(within(options[1]).getByTestId('plan-search-sub').textContent).toBe('Sub-region·1 spot');
    expect(screen.getByRole('group', { name: 'Areas' })).toBeInTheDocument();
  });

  it('⚠️ finds an area by its alias, and by a one-letter slip', async () => {
    mount({ ctx: areaCtx() });
    await ready();
    await findArea('roman wall');
    expect(screen.getByRole('option', { name: /Hadrian's Wall/ })).toHaveTextContent('also Roman Wall');

    fireEvent.change(input(), { target: { value: 'hadrain' } });
    expect(screen.getAllByRole('option')).toHaveLength(2);
  });

  it('⚠️ a failed directory leaves Find working for places, windows and regions', async () => {
    fetchAreaDirectory.mockRejectedValue(new Error('network'));
    mount({ ctx: areaCtx() });
    await ready();
    await findArea('whitby');

    expect(screen.getByRole('option', { name: /Whitby/ })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Areas' })).toBeNull();
  });

  describe('on This week', () => {
    it('a pick opens the area sheet with its spots, closes Find, and a Escape returns focus to the Find button', async () => {
      await openWall();

      expect(find()).toBeNull();
      expect(screen.getByTestId('area-sheet-title')).toHaveTextContent("Hadrian's Wall");
      expect(screen.getByTestId('area-sheet-meta')).toHaveTextContent('Named area · 2 spots · 50 min to 1h 35min drive');
      // The rated spot first, with its own best window (the sheet stays open after Find has closed, so its ratings
      // must not depend on Find being open); then the unrated one.
      const spotRows = screen.getAllByTestId('area-sheet-spot');
      expect(spotRows.map((row) => row.textContent)).toEqual([
        expect.stringContaining('Whitby'), expect.stringContaining('Saltburn'),
      ]);
      expect(spotRows[0]).toHaveTextContent('4★ · Tonight Sunset');
      expect(spotRows[1]).toHaveTextContent('Not rated');
      expectScreen();

      fireEvent.keyDown(document, { key: 'Escape' });

      await waitFor(() => expect(areaSheet()).toBeNull());
      expect(document.activeElement).toBe(findButton());
    });

    it('⚠️ Back closes it, one layer per press', async () => {
      await openWall();
      expect(__inspectForTests().layers).toBe(1);

      await back();

      expect(areaSheet()).toBeNull();
      expect(__inspectForTests().layers).toBe(0);
    });

    it('⚠️ a spot opens that place\'s sheet over it, two deep: one Escape takes the top layer only', async () => {
      await openWall();

      fireEvent.click(screen.getAllByTestId('area-sheet-spot')[1]);
      await screen.findByTestId('location-sheet');
      await settle();

      expect(areaSheet()).toBeInTheDocument();
      expect(__inspectForTests().layers).toBe(2);
      // The lower layer is inert and no longer claims to be the modal.
      expect(areaSheet()).not.toHaveAttribute('aria-modal');
      expect(areaSheet()).toHaveAttribute('inert');

      fireEvent.keyDown(document, { key: 'Escape' });
      await waitFor(() => expect(sheet()).toBeNull());
      expect(areaSheet()).toBeInTheDocument();
      expect(areaSheet()).toHaveAttribute('aria-modal', 'true');

      fireEvent.keyDown(document, { key: 'Escape' });
      await waitFor(() => expect(areaSheet()).toBeNull());
    });

    it('⚠️ every route out of the Plan tab closes it', async () => {
      await openWall({ mapPane: MAP_PANE });

      fireEvent.click(screen.getByTestId('further-ahead-jump'));

      await waitFor(() => expect(areaSheet()).toBeNull());
      expect(__inspectForTests().layers).toBe(0);
    });

    it('⚠️ the place sheet\'s "Plan from <region>" takes the area sheet under it down too', async () => {
      const ctx = areaCtx();
      await openWall({}, ctx);
      fireEvent.click(screen.getAllByTestId('area-sheet-spot')[0]);
      await screen.findByTestId('location-sheet');
      await settle();

      fireEvent.click(screen.getByTestId('location-sheet-plan'));

      await waitFor(() => expect(areaSheet()).toBeNull());
      expect(sheet()).toBeNull();
      expect(ctx.setOrigin).toHaveBeenCalledWith(REGION);
      expect(document.querySelector('[aria-modal="true"]')).toBeNull();
    });

    it('⚠️ the place sheet\'s "Show on map" door takes the area sheet under it down too', async () => {
      const onOpenMapTab = vi.fn();
      await openWall({ mapPane: MAP_PANE, onOpenMapTab });
      fireEvent.click(screen.getAllByTestId('area-sheet-spot')[0]);
      await screen.findByTestId('location-sheet');
      await settle();

      fireEvent.click(screen.getByTestId('location-sheet-map'));

      expect(onOpenMapTab).toHaveBeenCalled();
      await waitFor(() => expect(areaSheet()).toBeNull());
      expect(sheet()).toBeNull();
    });

    it('refuses Find (and ⌘K) while it is up: no third layer', async () => {
      await openWall();

      expectRefused(chord());
      fireEvent.click(findButton());
      await settle();

      expect(find()).toBeNull();
    });

    it('the foot hands the area to the Map as a named-spot lens, on its best window', async () => {
      const onOpenMapTab = vi.fn();
      await openWall({ mapPane: MAP_PANE, onOpenMapTab });

      fireEvent.click(screen.getByTestId('area-sheet-map'));

      expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
        date: TODAY, targetType: 'SUNSET', region: null, locationName: null,
        focusNames: ['Whitby', 'Saltburn'],
      }));
      expect(areaSheet()).toBeNull();
    });

    it('with no map door the foot says so rather than offering a dead control', async () => {
      await openWall();

      expect(screen.queryByTestId('area-sheet-map')).toBeNull();
      expect(screen.getByTestId('area-sheet-nomap')).toBeInTheDocument();
    });

    it('⚠️ a Lite reader\'s places outside their picks are listed as outside them, never as unrated', async () => {
      await openWall({}, areaCtx({ allowedLocationIds: [WHITBY], isPro: false, isLiteUser: true }));

      const rows = screen.getAllByTestId('area-sheet-spot');
      const saltburn = rows.find((row) => row.textContent.includes('Saltburn'));
      expect(saltburn).toHaveTextContent('Outside your picks');
      expect(saltburn).not.toHaveTextContent('Not rated');
      expect(screen.getByTestId('area-sheet-note')).toHaveTextContent('1 is outside your picks');
      // Gated places sort after the places the reader may see.
      expect(rows[rows.length - 1]).toBe(saltburn);
    });
  });

  describe('on Coming up', () => {
    it('⚠️ takes the reader to the Plan tab and opens the sheet there, never over the feed', async () => {
      mount({ ctx: areaCtx() });
      await ready();
      fireEvent.click(screen.getByTestId('further-ahead-jump'));
      await act(async () => { await Promise.resolve(); });
      await findArea('hadrian');

      pickWall();

      expect(await screen.findByTestId('area-sheet')).toBeInTheDocument();
      expectScreen();
      expect(find()).toBeNull();
    });
  });

  describe('on the Map', () => {
    it('hands the area to the Map\'s own channel with its member ids, and opens no sheet', async () => {
      const onFindOnMap = vi.fn();
      mount({ ctx: areaCtx(), props: { mapPane: MAP_PANE, onFindOnMap } });
      await ready();
      openMapView();
      await findArea('hadrian');
      expect(screen.getAllByRole('option')[0]).toHaveTextContent('Show');

      pickWall();

      expect(onFindOnMap).toHaveBeenCalledWith({
        kind: 'area', id: 1, name: "Hadrian's Wall", ids: [WHITBY, SALTBURN],
      });
      expect(areaSheet()).toBeNull();
      expect(screen.getByTestId('map-screen')).not.toHaveAttribute('hidden');
    });
  });
});

describe('Your places through the shell (find-and-favourites-plan.md F4)', () => {
  const SALTBURN_SPOT = {
    id: SALTBURN, name: 'Saltburn', lat: 54.58, lng: -0.97, regionName: 'North York Moors & Coast',
    rid: 'North York Moors & Coast', skySubject: true, bortleClass: 3, scores: [5],
  };
  const WALL = {
    id: 1, name: "Hadrian's Wall", kind: 'NAMED', aliases: [], regionId: null, regionName: null, locationIds: [WHITBY],
  };
  const favCtx = () => {
    const base = findCtx();
    return {
      ...base,
      heatSpots: [...base.heatSpots, SALTBURN_SPOT],
      scoreRows: [{
        locationId: WHITBY, locationName: 'Whitby', date: TODAY, targetType: 'SUNSET', rating: 4,
      }],
    };
  };
  beforeEach(() => {
    fetchAreaDirectory.mockResolvedValue([WALL]);
    fetchAreaOutlook.mockResolvedValue([{
      areaId: 1,
      windows: [{
        date: TODAY, targetType: 'SUNSET', ratedMembers: 1, totalMembers: 1, gatedMembers: 0, topRating: 4, topLocationId: WHITBY,
      }],
    }]);
  });

  it('shows Your places at the top of This week for a Pro reader, with served figures', async () => {
    favouritesCtx = favouritesValue({
      places: [WHITBY], areas: [1], locationIds: [WHITBY], bookmarkedAreas: [WALL],
    });
    mount({ ctx: favCtx() });
    await ready();

    const section = await screen.findByTestId('your-places');
    expect(section).toHaveTextContent('Your places');
    expect(section.querySelector('[data-testid="bookmark-mark"]')).not.toBeNull();
    expect(screen.getByTestId('this-week')).toContainElement(section);
    // Above the forecast, not among the almanac's entries.
    expect(screen.getByTestId('further-ahead')).not.toContainElement(section);
    const chips = within(section).getAllByTestId('your-places-chip');
    expect(chips.map((r) => r.dataset.kind)).toEqual(['area', 'place']);
    expect(within(chips[1]).getByTestId('your-places-detail').textContent).toMatch(/4★/);
  });

  it('a chip with a window opens the map view on it, focused on the place', async () => {
    const onOpenMapTab = vi.fn();
    favouritesCtx = favouritesValue({ places: [WHITBY], locationIds: [WHITBY] });
    mount({ ctx: favCtx(), props: { mapPane: MAP_PANE, onOpenMapTab } });
    await ready();

    fireEvent.click(await screen.findByTestId('your-places-chip'));

    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    expect(onOpenMapTab.mock.calls[0][0]).toMatchObject({ date: TODAY, inPlace: false });
    expect(screen.getByTestId('map-screen')).not.toHaveAttribute('hidden');
  });

  it('an area chip with a window opens the map view on it too, with its spots as the lens', async () => {
    const onOpenMapTab = vi.fn();
    favouritesCtx = favouritesValue({ areas: [1], locationIds: [WHITBY], bookmarkedAreas: [WALL] });
    mount({ ctx: favCtx(), props: { mapPane: MAP_PANE, onOpenMapTab } });
    await ready();

    fireEvent.click(await screen.findByTestId('your-places-chip'));

    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    expect(onOpenMapTab.mock.calls[0][0]).toMatchObject({ date: TODAY, focusNames: ['Whitby'] });
  });

  it('a chip with no window worth a trip opens the place\'s sheet on the screen', async () => {
    favouritesCtx = favouritesValue({ places: [SALTBURN], locationIds: [SALTBURN] });
    mount({ ctx: favCtx() });
    await ready();

    fireEvent.click(await screen.findByTestId('your-places-chip'));

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expectScreen();
  });

  it('an area chip with no window opens the area sheet', async () => {
    fetchAreaOutlook.mockResolvedValue([{
      areaId: 1,
      windows: [{
        date: TODAY, targetType: 'SUNSET', ratedMembers: 0, totalMembers: 1, gatedMembers: 0, topRating: null, topLocationId: null,
      }],
    }]);
    favouritesCtx = favouritesValue({ areas: [1], locationIds: [WHITBY], bookmarkedAreas: [WALL] });
    mount({ ctx: favCtx() });
    await ready();

    fireEvent.click(await screen.findByTestId('your-places-chip'));

    expect(await screen.findByTestId('area-sheet')).toBeInTheDocument();
  });

  it('a Lite reader sees the Your places teaser and no Find group, and Find still opens', async () => {
    favouritesCtx = favouritesValue({ status: 'locked', places: [WHITBY] });
    mount({ ctx: favCtx() });
    await ready();
    expect(screen.getByTestId('your-places-teaser')).toHaveTextContent('Your places');
    expect(screen.getByTestId('this-week')).toContainElement(screen.getByTestId('your-places-teaser'));
    fireEvent.click(findButton());
    await screen.findByTestId('plan-search-panel');
    expect(screen.queryByRole('group', { name: 'Your places' })).toBeNull();
  });

  it('a Pro reader\'s Find rests on Your places, and a pick opens the place as any row does', async () => {
    favouritesCtx = favouritesValue({ places: [WHITBY], locationIds: [WHITBY] });
    mount({ ctx: favCtx() });
    await ready();
    fireEvent.click(findButton());
    const group = await screen.findByRole('group', { name: 'Your places' });
    fireEvent.click(within(group).getByRole('option', { name: /Whitby/ }));
    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('location-sheet-favourite')).toHaveAttribute('data-starred', 'true');
  });

  it('no provider: the screen has no Your places row and no teaser', async () => {
    mount({ ctx: favCtx() });
    await ready();
    expect(screen.queryByTestId('your-places')).toBeNull();
    expect(screen.queryByTestId('your-places-teaser')).toBeNull();
  });
});
