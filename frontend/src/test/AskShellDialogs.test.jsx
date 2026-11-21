import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import React from 'react';
import {
  act, cleanup, fireEvent, screen,
} from '@testing-library/react';
import * as guideContext from '../context/GuideContext.jsx';
import { useMapList } from '../context/MapListContext.jsx';
import { resetViewport } from './askViewport.js';
import { matrixCtx, renderAskShell, TODAY } from './askShellHarness.jsx';
import {
  NOW, readyResponse, settings, WHITBY,
} from './askFixtures.js';

/**
 * "Opening Ask never closes a dialog" and "the sheet is the single modal" (plan §2.6, Dialogs):
 * while the location sheet, search or settings is open the
 * Ask entry is DISABLED — a refusal, with nothing taken down — and while Ask's sheet is open nothing
 * else can be opened.
 *
 * <p>Its own file because every test here crosses the matrix's lazy boundaries: the first one pays
 * for the chunks and the rest are cache hits, and keeping that in one place keeps
 * {@code AskShellEntry.test.jsx} fast and its first test cheap.
 *
 * <p>jsdom has no {@code inert}, so "nothing behind the sheet can open" is asserted as what the
 * shell does about it that jsdom CAN see: the attribute, the refused {@code /} shortcut, and the
 * refusal of a second modal.
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

const bar = () => screen.getByTestId('ask-bar');
const askDialog = () => screen.queryByRole('dialog', { name: 'Ask PhotoCast' });
const modalCount = () => document.querySelectorAll('[aria-modal="true"]').length;

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
  document.querySelectorAll('[data-foreign-dialog]').forEach((node) => node.remove());
  resetViewport();
});

describe('Ask over an open dialog is refused, and nothing is closed', () => {
  it('search: the bar is disabled while search is open, and search stays', async () => {
    renderAskShell({ width: 390 });
    await screen.findByTestId('ask-bar');
    fireEvent.click(screen.getByTestId('window-first-search'));
    expect(await screen.findByTestId('plan-search-panel')).toBeInTheDocument();
    expect(bar()).toBeDisabled();
    fireEvent.click(bar());
    expect(askDialog()).toBeNull();
    expect(screen.getByTestId('plan-search-panel')).toBeInTheDocument();
  });

  it('the four-day location sheet (a handoff from the Map): disabled, and the sheet stays', async () => {
    const { rerenderShell } = renderAskShell({ width: 390 });
    await screen.findByTestId('ask-bar');
    rerenderShell({
      locationSheetHandoff: {
        id: WHITBY, name: 'Whitby', regionName: 'North York Moors & Coast', nonce: 1,
      },
    });
    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(bar()).toBeDisabled();
    fireEvent.click(bar());
    expect(askDialog()).toBeNull();
    expect(screen.getByTestId('location-sheet')).toBeInTheDocument();
  });

  it('settings: disabled while it is open, and re-enabled when it closes', async () => {
    const { rerenderShell } = renderAskShell({ width: 390 });
    await screen.findByTestId('ask-bar');
    rerenderShell({ settingsOpen: true });
    expect(bar()).toBeDisabled();
    fireEvent.click(bar());
    expect(askDialog()).toBeNull();
    rerenderShell({ settingsOpen: false });
    expect(bar()).toBeEnabled();
  });

  it('a dialog this shell does not own (anything with role="dialog" outside it): pressing opens nothing', async () => {
    // The map overlay and the settings modal are siblings of the shell, so the shell cannot see them
    // as state — it finds them the way `/` does, in the document, at press time.
    renderAskShell({ width: 390 });
    await screen.findByTestId('ask-bar');
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('aria-modal', 'true');
    foreign.setAttribute('data-foreign-dialog', '');
    document.body.appendChild(foreign);

    fireEvent.click(bar());

    expect(askDialog()).toBeNull();
    expect(modalCount()).toBe(1);
  });

});

describe('with Ask\'s sheet open, nothing else can open', () => {
  it('the "/" key does nothing under the sheet: below 1024px it has no meaning at all (the dock\'s own suite holds the controls)', async () => {
    renderAskShell({ width: 390 });
    fireEvent.click(await screen.findByTestId('ask-bar'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });

    const pressed = fireEvent.keyDown(document, { key: '/' });

    // `fireEvent` returns false when the handler called preventDefault — i.e. when the key acted.
    expect(pressed).toBe(true);
    expect(screen.queryByTestId('plan-search-panel')).toBeNull();
    expect(screen.queryByTestId('ask-dock')).toBeNull();
    expect(modalCount()).toBe(1);
  });

  it('the page behind it is inert, so a Tab out of the sheet reaches neither a window card nor anything the app draws around the shell', async () => {
    renderAskShell({ width: 390, ctx: matrixCtx() });
    await screen.findByTestId('wf-heat-strip');
    fireEvent.click(await screen.findByTestId('ask-bar'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    const container = screen.getByTestId('window-first-shell').closest('body > *');
    expect(container).toHaveAttribute('inert');
    expect(container).toContainElement(screen.getAllByTestId('wf-heat-card')[0]);
    // The shell's own root is not what carries it any more: a banner above the shell would escape that.
    expect(screen.getByTestId('window-first-shell')).not.toHaveAttribute('inert');
  });

  it('a tab switch while it is open closes it before anything else is shown', async () => {
    renderAskShell({ width: 390, ctx: matrixCtx() });
    await screen.findByTestId('wf-heat-strip');
    fireEvent.click(await screen.findByTestId('ask-bar'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    fireEvent.click(screen.getByRole('tab', { name: 'Coming up' }));
    expect(askDialog()).toBeNull();
    expect(modalCount()).toBe(0);
  });
});

/* The Map List's depth cap, in both directions (glance-to-map-plan.md G1; review finding M2). Tablet width, where Ask has an entry on the Map. */
const LIST_KEY = `${TODAY}:SUNSET`;

/** The Map pane stand-in: it holds the shell's List door and presses it, reporting the answer. */
function MapProbe({ report }) {
  const door = useMapList();
  return (
    <div className="wf-map-tab" data-testid="map-probe">
      <button
        type="button"
        data-testid="probe-open"
        onClick={() => report(door?.openList({
          key: LIST_KEY, spotKeys: new Set(['#1']), floorId: 'any', tierId: 'any', onPick: vi.fn(),
        }))}
      >
        open
      </button>
    </div>
  );
}

const mountOnMap = () => {
  const report = vi.fn();
  renderAskShell({
    width: 800,
    props: { mapPane: <MapProbe report={report} />, initialTab: 'map' },
  });
  return report;
};
const pressList = () => act(async () => { fireEvent.click(screen.getByTestId('probe-open')); });

describe('the Map List and the depth cap', () => {
  it('is refused over Ask\'s sheet, which stays open', async () => {
    const report = mountOnMap();
    fireEvent.click(await screen.findByTestId('ask-field'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    await pressList();
    expect(report).toHaveBeenCalledWith(false);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    expect(screen.getByRole('dialog', { name: 'Ask PhotoCast' })).toBeInTheDocument();
  });

  it('is refused over search, which stays open', async () => {
    const report = mountOnMap();
    await screen.findByTestId('ask-field');
    fireEvent.click(screen.getByTestId('window-first-search'));
    expect(await screen.findByTestId('plan-search-panel')).toBeInTheDocument();
    await pressList();
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
    const report = mountOnMap();
    await screen.findByTestId('ask-field');
    await pressList();
    expect(report).toHaveBeenCalledWith(false);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
  });

  it('opens when nothing else stands, and then disables Ask\'s entry until it closes', async () => {
    const report = mountOnMap();
    expect(await screen.findByTestId('ask-field')).toBeEnabled();
    await pressList();
    expect(report).toHaveBeenCalledWith(true);
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('ask-field')).toBeDisabled();
    fireEvent.click(screen.getByTestId('window-spot-sheet-close'));
    expect(screen.getByTestId('ask-field')).toBeEnabled();
  });
});
