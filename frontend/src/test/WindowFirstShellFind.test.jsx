import React, { useCallback, useRef, useState } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor,
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
  NOW, readyResponse, settings, WHITBY,
} from './askFixtures.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

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
  getAlmanac: vi.fn(() => Promise.resolve({
    builtFor: '2026-10-05', bands: null, counts: null, conditions: [], entries: [],
  })),
  ALMANAC_DAYS: 90,
}));
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
import { getAskSettings, getReady } from '../api/askApi.js';

// The first test to open a lazy chunk pays for fetching it.
vi.setConfig({ testTimeout: 30000 });

let b;

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
const popup = () => screen.queryByTestId('window-sheet');
// jsdom applies no Tailwind, so BOTH GlanceLabel spans count: the tab's name is "At a GlanceGlance" here.
const tab = (name) => screen.getByRole('tab', { name: name === 'At a Glance' ? /^At a Glance/ : name });
const selectedTab = () => screen.getByRole('tab', { selected: true });

function Rig({ shellProps }) {
  const [activeTab, setTab] = useState('plan');
  const closer = useRef(null);
  const register = useCallback((fn) => { closer.current = fn; }, []);
  const closeShellDialogs = useCallback(() => closer.current?.(), []);
  return (
    <AskProvider>
      <GuideProvider closeShellDialogs={closeShellDialogs} activeTab={activeTab}>
        <WindowFirstShell
          onOpenSettings={vi.fn()}
          onSignOut={vi.fn()}
          onShowOnMap={vi.fn()}
          locations={[]}
          onTabChange={setTab}
          registerCloseDialogs={register}
          {...shellProps}
        />
      </GuideProvider>
    </AskProvider>
  );
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
  return { ...view, ctx };
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
  b = installBackBrowser();
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

  it('⚠️ opens over the window popup — the popup is one of the two layers Find may stand on', async () => {
    mount();
    await ready();
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
    await screen.findByTestId('window-sheet');
    await settle();

    await expectActs();
    await settle();
    expect(__inspectForTests().layers).toBe(2);
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
    fireEvent.click(screen.getByRole('button', { name: 'Guide for this tab' }));
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

  it('⚠️ is refused while the guide\'s modal is up (it opens as a modal over the window popup)', async () => {
    mount();
    await ready();
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
    await screen.findByTestId('window-sheet');
    fireEvent.click(screen.getByRole('button', { name: 'Guide for this tab' }));
    expect(await screen.findByTestId('guide-dialog')).toBeInTheDocument();
    await settle();

    expectRefused(chord());

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('guide-dialog')).toBeNull());
    await expectActs();
  });

  it('is live on Coming up and the Map, and refused on Operations (a pick there would mean nothing)', async () => {
    mount({ props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await ready();

    fireEvent.click(tab('Coming up'));
    await act(async () => { await Promise.resolve(); });
    await expectActs();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(find()).toBeNull());

    fireEvent.click(tab('Map'));
    await expectActs();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(find()).toBeNull());

    fireEvent.click(tab('Operations'));
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

describe('what a pick does on At a Glance (unchanged)', () => {
  it('a place opens its next-days sheet, a window its popup, a region plans from it', async () => {
    const ctx = findCtx();
    mount({ ctx });
    await ready();

    fireEvent.click(findButton());
    await type('whitby');
    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));
    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(selectedTab()).toHaveTextContent(/At a Glance/);
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(sheet()).toBeNull());

    fireEvent.click(findButton());
    await screen.findByTestId('plan-search-panel');
    fireEvent.click(screen.getAllByRole('option', { name: /Tonight/ })[0]);
    expect(await screen.findByTestId('window-sheet')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(popup()).toBeNull());

    fireEvent.click(findButton());
    await type('north york');
    fireEvent.click(screen.getByRole('option', { name: /North York Moors/ }));
    expect(ctx.setOrigin).toHaveBeenCalledWith(REGION);
  });
});

describe('what a pick does on Coming up (decision D6: the Plan tab, then the sheet)', () => {
  const toComingUp = async (ctx = findCtx()) => {
    mount({ ctx });
    await ready();
    fireEvent.click(tab('Coming up'));
    await act(async () => { await Promise.resolve(); });
    fireEvent.click(findButton());
    await screen.findByTestId('plan-search-panel');
    return ctx;
  };

  it('⚠️ a place takes the reader to the Plan tab and opens its sheet there, never over the feed', async () => {
    await toComingUp();
    await type('whitby');

    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(selectedTab()).toHaveTextContent(/At a Glance/);
    expect(find()).toBeNull();
  });

  it('closing that sheet lands focus on the Plan tab button the pick moved the reader to', async () => {
    await toComingUp();
    await type('whitby');
    fireEvent.click(screen.getByRole('option', { name: /Whitby/ }));
    await screen.findByTestId('location-sheet');
    await settle();

    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(sheet()).toBeNull());
    expect(document.activeElement).toBe(tab('At a Glance'));
  });

  it('a window takes the reader to the Plan tab and opens its popup', async () => {
    await toComingUp();

    fireEvent.click(screen.getAllByRole('option', { name: /Tonight/ })[0]);

    expect(await screen.findByTestId('window-sheet')).toBeInTheDocument();
    expect(selectedTab()).toHaveTextContent(/At a Glance/);
    // The same popup a Glance window pick opens (`openWindow(key)`); its close lands on the Plan tab button.
    await settle();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(popup()).toBeNull());
    expect(document.activeElement).toBe(tab('At a Glance'));
  });

  it('a region plans from it and leaves the reader on Coming up', async () => {
    const ctx = await toComingUp();
    await type('north york');

    fireEvent.click(screen.getByRole('option', { name: /North York Moors/ }));

    expect(ctx.setOrigin).toHaveBeenCalledWith(REGION);
    expect(selectedTab()).toHaveTextContent('Coming up');
  });
});

describe('what a pick does on the Map', () => {
  const toMap = async ({ ctx = findCtx(), props = {} } = {}) => {
    const onFindOnMap = vi.fn();
    const onOpenMapTab = vi.fn();
    mount({ ctx, props: { mapPane: MAP_PANE, onFindOnMap, onOpenMapTab, ...props } });
    await ready();
    fireEvent.click(tab('Map'));
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
    expect(selectedTab()).toHaveTextContent('Map');
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
      date: TODAY, targetType: 'SUNSET', inPlace: true,
    }));
    expect(selectedTab()).toHaveTextContent('Map');
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
    for (const name of ['At a Glance', 'Coming up', 'Map']) {
      fireEvent.click(tab(name));
      await act(async () => { await Promise.resolve(); });
      fireEvent.click(findButton());
      await screen.findByTestId('plan-search-panel');
      await settle();
      expect(__inspectForTests().layers).toBe(1);

      await back();

      await waitFor(() => expect(find()).toBeNull());
      expect(__inspectForTests().layers).toBe(0);
    }
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
