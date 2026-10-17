import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen, waitFor,
} from '@testing-library/react';
import { resetViewport } from './askViewport.js';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { matrixCtx, renderAskShell, shellCtx } from './askShellHarness.jsx';
import {
  NOW, readyResponse, settings, WHITBY,
} from './askFixtures.js';

/**
 * "Opening Ask never closes a dialog" and "the sheet is the single modal" (plan §2.6, Dialogs):
 * while a window popup, the drill-down over it, the location sheet, search or settings is open the
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
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));
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

/** Opens the first window's popup, awaiting the matrix's own lazy boundary first. */
async function openPopup() {
  await screen.findByTestId('wf-heat-strip');
  await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
  return screen.findByTestId('window-sheet');
}

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
  it('the window popup: the bar is disabled, pressing it opens nothing, the popup stays', async () => {
    renderAskShell({ width: 390, ctx: matrixCtx() });
    await openPopup();
    expect(bar()).toBeDisabled();
    fireEvent.click(bar());
    expect(askDialog()).toBeNull();
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    expect(modalCount()).toBe(1);
  });

  it('the same on the tablet: the field is disabled over the popup', async () => {
    renderAskShell({ width: 800, ctx: matrixCtx() });
    await openPopup();
    expect(screen.getByTestId('ask-field')).toBeDisabled();
    fireEvent.click(screen.getByTestId('ask-field'));
    expect(askDialog()).toBeNull();
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
  });

  it('the drill-down stacked over the popup: still disabled, both layers stay', async () => {
    renderAskShell({ width: 390, ctx: matrixCtx() });
    await openPopup();
    fireEvent.click(screen.getByTestId('window-spot-all'));
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
    expect(bar()).toBeDisabled();
    fireEvent.click(bar());
    expect(askDialog()).toBeNull();
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
  });

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

  it('is NOT disabled by a popup whose window has gone: the key outlives its card, the dialog does not', async () => {
    // `openWindowKey` is deliberately never released when its card leaves the list (the event
    // passed); the popup stops rendering, and Ask must follow what is on screen, not the stale key.
    const { rerenderShell } = renderAskShell({ width: 390, ctx: matrixCtx() });
    await openPopup();
    expect(bar()).toBeDisabled();

    vi.spyOn(briefingContext, 'useWindowFirstBriefing')
      .mockReturnValue(shellCtx({ ...matrixCtx(), windowCards: [], heatStripCards: [] }));
    rerenderShell();

    await waitFor(() => expect(screen.queryByTestId('window-sheet')).toBeNull());
    expect(bar()).toBeEnabled();
  });

  it('is enabled again as soon as the popup closes', async () => {
    renderAskShell({ width: 390, ctx: matrixCtx() });
    await openPopup();
    expect(bar()).toBeDisabled();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('window-sheet')).toBeNull());
    expect(bar()).toBeEnabled();
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
