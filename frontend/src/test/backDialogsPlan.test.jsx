import { pressMenuRow } from './shellMapDoor.js';
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
import { MAP_PANE, matrixCtx, shellCtx } from './askShellHarness.jsx';
import {
  NOW, readyResponse, settings, WHITBY,
} from './askFixtures.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

/**
 * Back on the Plan shell's own dialogs (back-closes-dialogs-plan.md, PR 2b): the four-day location sheet and Plan
 * search (the window popup, the all-spots sheet over it and the pick dialog went in G2b, glance-to-map-plan.md). Each registers from the shell's DERIVED open state,
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

const locationSheet = () => screen.queryByRole('dialog', { name: /the next \d+ windows?/ });
const searchButton = () => screen.getByRole('button', { name: /Find a place, region or day/ });
const guideDoor = () => pressMenuRow('masthead-menu-guide');
const guideDialog = () => screen.queryByTestId('guide-dialog');
const search = () => screen.queryByTestId('plan-search-panel');
const layers = () => __inspectForTests().layers;

/** Mounts the shell the way App does, with a guide provider so the guide dialog can sit over a sheet. */
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

const handoff = (id, name, nonce) => ({
  locationSheetHandoff: {
    id, name, regionName: 'North York Moors & Coast', nonce,
  },
});

/** Opens Whitby's four-day sheet by the Map callout's handoff; returns once it has drawn and the store has settled. */
async function openSheet() {
  const view = mount();
  await screen.findByTestId('ask-field');
  view.rerenderShell(handoff(WHITBY, 'Whitby', 1));
  const dialog = await screen.findByRole('dialog', { name: /the next \d+ windows?/ });
  await settle();
  return { ...view, dialog };
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
  resetViewport();
});

describe('registration from the derived open state', () => {
  it('registers in the commit that opens the sheet, without waiting for its lazy chunk', async () => {
    const view = mount();
    await screen.findByTestId('ask-field');
    // A bare synchronous rerender: only the shell's own effects have run, so a layer here is the shell's registration.
    act(() => view.rerenderShell(handoff(WHITBY, 'Whitby', 1)));
    // The chunk is cold (this is the first open in the file): a layer with no sheet on screen is the shell's own.
    expect(locationSheet()).toBeNull();
    expect(layers()).toBe(1);
    await screen.findByRole('dialog', { name: /the next \d+ windows?/ });
    await settle();
    // The chunk arriving added no layer and no entry.
    expect(layers()).toBe(1);
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });
});

describe('the location sheet', () => {
  it('closing it with its own close control unregisters and traverses once', async () => {
    await openSheet();

    fireEvent.click(screen.getByTestId('location-sheet-close'));
    await settle();

    expect(locationSheet()).toBeNull();
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(b.index).toBe(0);
  });

  it('opened alone (a handoff from the Map): Back closes it', async () => {
    const { rerenderShell } = mount({ ctx: {} });
    await screen.findByTestId('ask-field');
    rerenderShell(handoff(WHITBY, 'Whitby', 1));
    await screen.findByRole('dialog', { name: /the next \d+ windows?/ });
    await settle();
    expect(layers()).toBe(1);

    await back();

    await waitFor(() => expect(locationSheet()).toBeNull());
    expect(layers()).toBe(0);
  });

  it('registration is keyed on state: re-keying the open place makes no go and no second push', async () => {
    const { rerenderShell } = mount({ ctx: {} });
    await screen.findByTestId('ask-field');
    rerenderShell(handoff(WHITBY, 'Whitby', 1));
    await screen.findByRole('dialog', { name: /the next \d+ windows?/ });
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

describe('Plan search', () => {
  it('Back closes it', async () => {
    mount({ ctx: {} });
    await screen.findByTestId('ask-field');
    fireEvent.click(searchButton());
    await screen.findByTestId('plan-search-panel');
    await settle();
    expect(layers()).toBe(1);

    await back();

    await waitFor(() => expect(search()).toBeNull());
    expect(layers()).toBe(0);
  });

  it('is refused while the modal guide is up (the !guideModal guard): no third layer', async () => {
    await openSheet();
    guideDoor();
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
    await settle();
    const before = layers();
    expect(before).toBe(2);

    fireEvent.click(searchButton());
    await settle();

    expect(search()).toBeNull();
    expect(layers()).toBe(before);

    // 2a's guide over 2b's sheet: Back closes the guide only.
    await back();
    expect(guideDialog()).toBeNull();
    expect(locationSheet()).toBeInTheDocument();
    expect(layers()).toBe(1);
  });

  it('a pick from search is a real swap: search gives way to the sheet at the same depth, no go and one push', async () => {
    mount();
    await screen.findByTestId('ask-field');
    fireEvent.click(searchButton());
    await screen.findByTestId('plan-search-panel');
    await settle();
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });

    fireEvent.change(screen.getByTestId('plan-search-input'), { target: { value: 'Whitby' } });
    fireEvent.click(screen.getAllByRole('option', { name: /Whitby/ })[0]);
    await screen.findByRole('dialog', { name: /the next \d+ windows?/ });
    await settle();

    expect(search()).toBeNull();
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });
    expect(b.history.go).not.toHaveBeenCalled();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });
});

describe('closeShellLayers with a layer open', () => {
  const closeByTab = async () => {
    const view = mount({ props: { mapPane: MAP_PANE } });
    await screen.findByTestId('ask-field');
    view.rerenderShell({ ...handoff(WHITBY, 'Whitby', 1), mapPane: MAP_PANE });
    await screen.findByRole('dialog', { name: /the next \d+ windows?/ });
    await settle();
    expect(layers()).toBe(1);
    fireEvent.click(screen.getByTestId('further-ahead-jump'));
    await settle();
  };

  it('makes one go(-1) and takes the layer', async () => {
    await closeByTab();

    expect(locationSheet()).toBeNull();
    expect(layers()).toBe(0);
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(b.index).toBe(0);
  });

  it('Back afterwards reopens nothing', async () => {
    await closeByTab();

    await back();

    expect(locationSheet()).toBeNull();
  });
});
