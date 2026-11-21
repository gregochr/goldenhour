import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen,
} from '@testing-library/react';
import * as guideContext from '../context/GuideContext.jsx';
import { useMapList } from '../context/MapListContext.jsx';
import { resetViewport } from './askViewport.js';
import { renderAskShell, TODAY } from './askShellHarness.jsx';
import { NOW, readyResponse, settings } from './askFixtures.js';

/**
 * The Map List's depth cap, in both directions (glance-to-map-plan.md G1; review finding M2). The shell's
 * {@code tryOpenMapList} refuses over Ask's sheet, search and the guide, taking nothing down; and with the List
 * open, {@code askDialogOpen} counts it, so Ask's entry is disabled. The shell suite for the List holds the
 * other refusals (settings, a foreign dialog, a second list).
 *
 * <p>Tablet width (800) because that is where Ask has an entry on the Map with a sheet to open.
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

vi.setConfig({ testTimeout: 30000 });

const KEY = `${TODAY}:SUNSET`;

/** The Map pane stand-in: it holds the shell's List door and presses it, reporting the answer. */
function MapProbe({ report }) {
  const door = useMapList();
  return (
    <div className="wf-map-tab" data-testid="map-probe">
      <button
        type="button"
        data-testid="probe-open"
        onClick={() => report(door?.openList({
          key: KEY, spotKeys: new Set(['#1']), floorId: 'any', tierId: 'any', onPick: vi.fn(),
        }))}
      >
        open
      </button>
    </div>
  );
}

const mount = () => {
  const report = vi.fn();
  renderAskShell({
    width: 800,
    props: { mapPane: <MapProbe report={report} />, initialTab: 'map' },
  });
  return report;
};
const press = () => act(async () => { fireEvent.click(screen.getByTestId('probe-open')); });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  vi.resetAllMocks();
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  resetViewport();
});

describe('the Map List and the depth cap', () => {
  it('is refused over Ask\'s sheet, which stays open', async () => {
    const report = mount();
    fireEvent.click(await screen.findByTestId('ask-field'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    await press();
    expect(report).toHaveBeenCalledWith(false);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    expect(screen.getByRole('dialog', { name: 'Ask PhotoCast' })).toBeInTheDocument();
  });

  it('is refused over search, which stays open', async () => {
    const report = mount();
    await screen.findByTestId('ask-field');
    fireEvent.click(screen.getByTestId('window-first-search'));
    expect(await screen.findByTestId('plan-search-panel')).toBeInTheDocument();
    await press();
    expect(report).toHaveBeenCalledWith(false);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    expect(screen.getByTestId('plan-search-panel')).toBeInTheDocument();
  });

  it('is refused over a modal guide', async () => {
    vi.spyOn(guideContext, 'useGuide').mockReturnValue({
      open: true,
      presentation: 'sheet',
      page: 'map',
      hash: '',
      nonce: 0,
      focusKey: 0,
      tracked: null,
      guideModal: true,
      lastClose: { seq: 0, restoreFocus: true, doorInDock: false },
      openGuide: vi.fn(),
      closeGuide: vi.fn(),
      trackGuide: vi.fn(),
    });
    const report = mount();
    await screen.findByTestId('ask-field');
    await press();
    expect(report).toHaveBeenCalledWith(false);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
  });

  it('opens when nothing else stands, and then disables Ask\'s entry until it closes', async () => {
    const report = mount();
    expect(await screen.findByTestId('ask-field')).toBeEnabled();
    await press();
    expect(report).toHaveBeenCalledWith(true);
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('ask-field')).toBeDisabled();
    fireEvent.click(screen.getByTestId('window-spot-sheet-close'));
    expect(screen.getByTestId('ask-field')).toBeEnabled();
  });
});
