import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen,
} from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import { AskProvider } from '../context/AskContext.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { installViewport } from './askViewport.js';
import {
  MAP_PANE, matrixCtx, shellCtx, TODAY,
} from './askShellHarness.jsx';
import { settings } from './askFixtures.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

/**
 * G2a (glance-to-map-plan.md): a Glance card, a lane and a Find window pick open the MAP with that window selected, and
 * Back returns to the tab they left (card and Find arrivals only). The shell here has no `legacyWindowPopup`, so this is
 * what the app does.
 *
 * <p>`App` is not mounted: `onOpenMapTab` is a spy, and the tab request `App` would raise from it is re-sent by hand
 * (`tabRequest`), which is the one thing the shell's arrival bookkeeping hangs on.
 */
vi.mock('../api/askApi.js', async (original) => {
  const real = await original();
  return {
    ...real, getReady: vi.fn(), ask: vi.fn(), getAskSettings: vi.fn().mockResolvedValue({}),
  };
});
vi.mock('../api/almanacApi.js', () => ({
  ALMANAC_DAYS: 90,
  getAlmanac: vi.fn().mockResolvedValue({
    builtFor: '2026-10-05', bands: null, counts: null, conditions: [], entries: [],
  }),
}));
vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));
import { fetchAreaDirectory, fetchAreaOutlook } from '../api/areasApi.js';

const seen = vi.hoisted(() => ({ strip: null, search: null }));
vi.mock('../components/WindowFirstHeatStrip.jsx', async (importOriginal) => {
  const actual = await importOriginal();
  const Real = actual.default;
  return { ...actual, default: (props) => { seen.strip = props; return <Real {...props} />; } };
});
vi.mock('../components/PlanSearch.jsx', () => ({
  default: (props) => {
    seen.search = props;
    return <div data-testid="plan-search-panel" />;
  },
}));

vi.setConfig({ testTimeout: 30000 });

let browser;
const KEY = `${TODAY}:SUNSET`;
const tab = (id) => document.getElementById(`window-first-tab-${id}`);
const selected = () => screen.getByRole('tab', { selected: true });

function mount() {
  installViewport(1280);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx({ ...matrixCtx() }));
  const onOpenMapTab = vi.fn();
  const base = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), locations: [], mapPane: MAP_PANE, onOpenMapTab,
  };
  const ui = (next = {}) => (
    <AskProvider>
      <WindowFirstShell {...base} {...next} />
    </AskProvider>
  );
  const view = render(ui());
  return {
    onOpenMapTab,
    /** What `App` does after a door: the Map tab is asked for. */
    arriveOnMap: async (nonce = 1) => {
      await act(async () => { view.rerender(ui({ tabRequest: { id: 'map', nonce } })); });
    },
  };
}

const arrivalLayers = () => __inspectForTests().layers;

beforeEach(() => {
  fetchAreaDirectory.mockResolvedValue([]);
  fetchAreaOutlook.mockResolvedValue({ outlook: [] });
  browser = installBackBrowser();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  uninstallBackBrowser();
});

describe('a Glance card opens the Map', () => {
  it('is a button named for the window and the map, and sends the event door with the live lens', async () => {
    const { onOpenMapTab } = mount();
    const card = await screen.findByRole('button', { name: /^Open Tonight Sunset on the map/ });
    expect(card).not.toHaveAttribute('aria-haspopup');
    expect(card).not.toHaveAttribute('aria-expanded');

    await act(async () => { fireEvent.click(card); });

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      kind: 'event', date: TODAY, eventType: 'SUNSET', from: 'plan', inPlace: false, limitMinutes: 45,
    }));
    expect(onOpenMapTab.mock.calls[0][0]).not.toHaveProperty('topicType');
    expect(screen.queryByTestId('window-sheet')).toBeNull();
  });

  it('registers one Back layer once the Map is up, and Back returns to At a Glance', async () => {
    const { arriveOnMap } = mount();
    await act(async () => { fireEvent.click(await screen.findByTestId('wf-heat-card-button')); });
    // Asked for but not yet arrived: nothing is registered.
    expect(arrivalLayers()).toBe(0);

    await arriveOnMap();
    expect(selected().id).toBe('window-first-tab-map');
    expect(arrivalLayers()).toBe(1);

    await browser.back();
    expect(selected().id).toBe('window-first-tab-plan');
    expect(arrivalLayers()).toBe(0);
  });

  it('a manual tab press ends the arrival, so Back has nothing of ours to close', async () => {
    const { arriveOnMap } = mount();
    await act(async () => { fireEvent.click(await screen.findByTestId('wf-heat-card-button')); });
    await arriveOnMap();
    expect(arrivalLayers()).toBe(1);

    await act(async () => { fireEvent.click(tab('coming-up')); });

    expect(arrivalLayers()).toBe(0);
  });

  it('pressing the Map tab again while arrived keeps the layer', async () => {
    const { arriveOnMap } = mount();
    await act(async () => { fireEvent.click(await screen.findByTestId('wf-heat-card-button')); });
    await arriveOnMap();

    await act(async () => { fireEvent.click(tab('map')); });

    expect(arrivalLayers()).toBe(1);
  });

  it('a Map arrival that no card, lane or Find asked for registers nothing', async () => {
    const { arriveOnMap } = mount();
    await screen.findByTestId('wf-heat-card-button');

    await arriveOnMap();

    expect(selected().id).toBe('window-first-tab-map');
    expect(arrivalLayers()).toBe(0);
  });
});

describe('a lane opens the Map on its window', () => {
  it('carries the tapped topic, upper-cased, and a warning lane carries none', async () => {
    const { onOpenMapTab, arriveOnMap } = mount();
    await screen.findByTestId('wf-heat-card-button');

    await act(async () => { seen.strip.onGoToPlanWindow(KEY, 'inversion'); });
    expect(onOpenMapTab).toHaveBeenLastCalledWith(expect.objectContaining({
      kind: 'event', date: TODAY, eventType: 'SUNSET', topicType: 'INVERSION', from: 'plan',
    }));

    await act(async () => { seen.strip.onGoToPlanWindow(KEY); });
    expect(onOpenMapTab.mock.calls[1][0]).not.toHaveProperty('topicType');

    await arriveOnMap();
    expect(arrivalLayers()).toBe(1);
  });

  it('ignores a key that is not date:TYPE, and does nothing with no Map door', async () => {
    const { onOpenMapTab } = mount();
    await screen.findByTestId('wf-heat-card-button');

    await act(async () => { seen.strip.onGoToPlanWindow('garbage'); });

    expect(onOpenMapTab).not.toHaveBeenCalled();
    cleanup();
    installViewport(1280);
    render(
      <AskProvider>
        <WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} locations={[]} mapPane={MAP_PANE} />
      </AskProvider>,
    );
    await screen.findByTestId('wf-heat-card-button');
    await act(async () => { seen.strip.onGoToPlanWindow(KEY); });
    expect(selected().id).toBe('window-first-tab-plan');
  });
});

describe('Find opens the Map on a window', () => {
  const openFind = async () => {
    await act(async () => {
      fireEvent(document, new KeyboardEvent('keydown', {
        key: 'k', metaKey: true, bubbles: true, cancelable: true,
      }));
    });
    await screen.findByTestId('plan-search-panel');
  };

  it('from At a Glance: the Map door, and Back returns to At a Glance', async () => {
    const { onOpenMapTab, arriveOnMap } = mount();
    await screen.findByTestId('wf-heat-card-button');
    await openFind();

    await act(async () => { seen.search.onPickWindow(KEY); seen.search.onClose(); });

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      date: TODAY, targetType: 'SUNSET', from: 'plan', inPlace: false,
    }));
    expect(screen.queryByTestId('window-sheet')).toBeNull();

    await arriveOnMap();
    expect(arrivalLayers()).toBe(1);
    await browser.back();
    expect(selected().id).toBe('window-first-tab-plan');
  });

  it('from Coming up: Back returns to Coming up', async () => {
    const { arriveOnMap } = mount();
    await screen.findByTestId('wf-heat-card-button');
    await act(async () => { fireEvent.click(tab('coming-up')); });
    await openFind();

    await act(async () => { seen.search.onPickWindow(KEY); seen.search.onClose(); });
    await arriveOnMap();
    await browser.back();

    expect(selected().id).toBe('window-first-tab-coming-up');
  });

  it('on the Map: selected in place, with no arrival layer', async () => {
    const { onOpenMapTab, arriveOnMap } = mount();
    await screen.findByTestId('wf-heat-card-button');
    await arriveOnMap();
    await openFind();

    await act(async () => { seen.search.onPickWindow(KEY); seen.search.onClose(); });

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      date: TODAY, targetType: 'SUNSET', inPlace: true,
    }));
    expect(arrivalLayers()).toBe(0);
  });
});
