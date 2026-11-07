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
import { MAP_PANE, matrixCtx, shellCtx } from './askShellHarness.jsx';
import {
  NOW, readyResponse, settings, WHITBY,
} from './askFixtures.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

/**
 * Back on the Plan shell's own dialogs (back-closes-dialogs-plan.md, PR 2b): the window popup, the all-spots sheet,
 * the four-day location sheet, the pick dialog and Plan search. Each registers from the shell's DERIVED open state,
 * outside `Suspense`, so what is asserted is the store and the dialogs, never a history count.
 *
 * <p>The Plan dates are pinned with a Date-only fake clock, so the store's deferred shrink (a real `setTimeout`) is
 * awaited with a real macrotask rather than `tick`'s fake-timer branch.
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

/** Lets the engine's microtask, its one-task deferral and any queued traversal all run. */
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

const popup = () => screen.queryByTestId('window-sheet');
const spotSheet = () => screen.queryByTestId('window-spot-sheet');
const locationSheet = () => screen.queryByTestId('location-sheet');
const search = () => screen.queryByTestId('plan-search-panel');
const layers = () => __inspectForTests().layers;
const tab = (name) => screen.getByRole('tab', { name });

/** Mounts the shell the way App does, with a guide provider so the guide dialog can sit over the popup. */
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

function mount({ width = 1280, ctx = matrixCtx(), props = {} } = {}) {
  installViewport(width);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx(ctx));
  getAskSettings.mockResolvedValue(settings());
  const view = render(<Rig shellProps={props} />);
  return {
    ...view,
    rerenderShell: (next = {}, nextCtx = ctx) => {
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx(nextCtx));
      view.rerender(<Rig shellProps={{ ...props, ...next }} />);
    },
  };
}

/** Presses the first window card; returns once the popup has drawn and the store has settled. */
async function openPopup() {
  await screen.findByTestId('wf-heat-strip');
  await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
  const dialog = await screen.findByTestId('window-sheet');
  await settle();
  return dialog;
}

const pickCtx = () => {
  const base = matrixCtx();
  return {
    ...base,
    windowCards: [{
      ...base.windowCards[0],
      pick: {
        kind: 'best', regionName: 'North York Moors & Coast', headline: 'Breaking clear',
        detail: 'Low cloud clears.', locationName: 'Whitby', locationId: WHITBY,
      },
    }],
  };
};

const handoff = (id, name, nonce) => ({
  locationSheetHandoff: {
    id, name, regionName: 'North York Moors & Coast', nonce,
  },
});

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
  resetViewport();
});

describe('registration from the derived open state', () => {
  it('registers in the commit that opens the popup, without waiting for its lazy chunk', async () => {
    mount();
    await screen.findByTestId('wf-heat-strip');
    // A bare synchronous click: only the shell's own effects have run, so a layer here is the shell's registration.
    fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]);
    expect(layers()).toBe(1);
    await screen.findByTestId('window-sheet');
    await settle();
    // The chunk arriving added no layer and no entry.
    expect(layers()).toBe(1);
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });
});

describe('the window popup', () => {
  it('Back closes it and takes its entry, with no second press needed', async () => {
    mount();
    await openPopup();
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });

    await back();

    await waitFor(() => expect(popup()).toBeNull());
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0 });
    expect(b.index).toBe(0);
  });

  it('closing it with its own close control unregisters and traverses once', async () => {
    mount();
    const dialog = await openPopup();

    fireEvent.click(within(dialog).getByRole('button', { name: /close/i }));
    await settle();

    expect(popup()).toBeNull();
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(b.index).toBe(0);
  });

  it('a card that leaves windowCards unregisters the layer and traverses (a data-driven vanish)', async () => {
    const { rerenderShell } = mount();
    await openPopup();
    expect(layers()).toBe(1);

    rerenderShell({}, { ...matrixCtx(), windowCards: [], heatStripCards: [] });
    await settle();

    expect(popup()).toBeNull();
    expect(layers()).toBe(0);
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(b.index).toBe(0);
  });
});

describe('the all-spots sheet', () => {
  it('Back closes only the sheet; a second Back closes the popup', async () => {
    mount();
    await openPopup();
    fireEvent.click(screen.getByTestId('window-spot-all'));
    await settle();
    expect(__inspectForTests()).toMatchObject({ layers: 2, committedDepth: 2 });

    await back();
    expect(spotSheet()).toBeNull();
    expect(popup()).toBeInTheDocument();
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });

    await back();
    await waitFor(() => expect(popup()).toBeNull());
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0 });
  });
});

describe('the location sheet', () => {
  it('opened from the popup: Back closes the sheet only, then the popup', async () => {
    mount();
    await openPopup();
    fireEvent.click(within(screen.getByTestId('window-sheet')).getAllByTestId('window-spot')[0]);
    await screen.findByTestId('location-sheet');
    await settle();
    expect(layers()).toBe(2);

    await back();
    expect(locationSheet()).toBeNull();
    expect(popup()).toBeInTheDocument();

    await back();
    await waitFor(() => expect(popup()).toBeNull());
    expect(layers()).toBe(0);
  });

  it('opened alone (a handoff from the Map): Back closes it', async () => {
    const { rerenderShell } = mount({ ctx: {} });
    await screen.findByTestId('ask-field');
    rerenderShell(handoff(WHITBY, 'Whitby', 1));
    await screen.findByTestId('location-sheet');
    await settle();
    expect(layers()).toBe(1);

    await back();

    await waitFor(() => expect(locationSheet()).toBeNull());
    expect(layers()).toBe(0);
  });

  it('a swap from one place to another stays at the same depth: no go and no extra push', async () => {
    const { rerenderShell } = mount({ ctx: {} });
    await screen.findByTestId('ask-field');
    rerenderShell(handoff(WHITBY, 'Whitby', 1));
    await screen.findByTestId('location-sheet');
    await settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);

    rerenderShell(handoff(99, 'Saltburn', 2));
    await settle();

    expect(locationSheet()).toBeInTheDocument();
    expect(layers()).toBe(1);
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(b.history.go).not.toHaveBeenCalled();
  });
});

describe('the pick dialog', () => {
  it('Back closes it and leaves the popup', async () => {
    mount({ ctx: pickCtx() });
    await openPopup();
    fireEvent.click(screen.getByTestId('window-sheet-pick'));
    expect(screen.getByTestId('window-pick-dialog')).toBeInTheDocument();
    await settle();
    expect(layers()).toBe(2);

    await back();

    expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
    expect(popup()).toBeInTheDocument();
    expect(layers()).toBe(1);
  });
});

describe('Plan search', () => {
  it('Back closes it', async () => {
    mount({ ctx: {} });
    await screen.findByTestId('ask-field');
    fireEvent.click(screen.getByTestId('window-first-search'));
    await screen.findByTestId('plan-search-panel');
    await settle();
    expect(layers()).toBe(1);

    await back();

    await waitFor(() => expect(search()).toBeNull());
    expect(layers()).toBe(0);
  });

  it('over the popup it is a second layer, and Back takes search first, then the popup', async () => {
    mount();
    await openPopup();
    fireEvent.click(screen.getByTestId('window-first-search'));
    await screen.findByTestId('plan-search-panel');
    await settle();
    expect(layers()).toBe(2);

    await back();
    expect(search()).toBeNull();
    expect(popup()).toBeInTheDocument();

    await back();
    await waitFor(() => expect(popup()).toBeNull());
  });

  it('is refused while the modal guide is up (the !guideModal guard): no third layer', async () => {
    mount();
    await openPopup();
    fireEvent.click(screen.getByTestId('window-first-guide'));
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
    await settle();
    const before = layers();

    fireEvent.click(screen.getByTestId('window-first-search'));
    await settle();

    expect(search()).toBeNull();
    expect(layers()).toBe(before);
  });
});

describe('selectTab with two layers', () => {
  it('makes one go(-2) and Back afterwards reopens nothing', async () => {
    mount({ props: { mapPane: MAP_PANE } });
    await openPopup();
    fireEvent.click(screen.getByTestId('window-spot-all'));
    await settle();
    expect(layers()).toBe(2);

    fireEvent.click(tab('Map'));
    await settle();

    expect(popup()).toBeNull();
    expect(spotSheet()).toBeNull();
    expect(layers()).toBe(0);
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.go).toHaveBeenCalledWith(-2);
    expect(b.index).toBe(0);

    await back();
    expect(popup()).toBeNull();
    expect(spotSheet()).toBeNull();
  });
});
