import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen, waitFor, within,
} from '@testing-library/react';
import { resetViewport } from './askViewport.js';
import { MAP_PANE, renderAskShell } from './askShellHarness.jsx';
import { NOW, readyResponse, settings } from './askFixtures.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

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

/**
 * Back on the Ask sheet (below 1024 px; back-closes-dialogs-plan.md, PR 2a). The shell's Plan dates are
 * pinned with a Date-only fake clock, so the store's deferred shrink (a real `setTimeout`) is awaited with
 * a real macrotask here instead of `tick`'s fake-timer branch.
 */

let b;

const sheet = () => screen.queryByRole('dialog', { name: 'Ask PhotoCast' });

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

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  getReady.mockReset().mockResolvedValue(readyResponse());
  getAskSettings.mockReset().mockResolvedValue(settings());
  b = installBackBrowser();
});

afterEach(() => {
  cleanup();
  uninstallBackBrowser();
  vi.useRealTimers();
  resetViewport();
});

describe('the Ask sheet', () => {
  it('Back closes it and hands the page back, with no second press needed', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE } });
    fireEvent.click(await screen.findByTestId('ask-bar'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    await settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(__inspectForTests().layers).toBe(1);

    await back();

    await waitFor(() => expect(sheet()).toBeNull());
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0 });
  });

  it('Back keeps the conversation: the answer is still there when the sheet reopens', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE } });
    fireEvent.click(await screen.findByTestId('ask-bar'));
    fireEvent.click(await screen.findByTestId('ask-ready-BEST_NEXT'));
    await screen.findByTestId('ask-picks');
    await settle();

    await back();
    await waitFor(() => expect(sheet()).toBeNull());
    fireEvent.click(screen.getByTestId('ask-bar'));

    expect(await screen.findByTestId('ask-picks')).toBeInTheDocument();
  });

  it('registers from the open intent: the entry is pushed in the commit that opens it, with the chunk still pending', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE } });
    fireEvent.click(await screen.findByTestId('ask-bar'));
    // Not waiting for the lazy sheet: the registration lives in the shell, outside its Suspense.
    await settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    await settle();
    // The sheet arriving adds no layer and no entry.
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('closing it with its own close control takes its history entry with it', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE } });
    fireEvent.click(await screen.findByTestId('ask-bar'));
    const dialog = await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    await settle();

    fireEvent.click(within(dialog).getByRole('button', { name: /close/i }));
    await settle();

    await waitFor(() => expect(sheet()).toBeNull());
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(b.index).toBe(0);
  });

  it('desktop: the docked panel is not a layer', async () => {
    renderAskShell({ width: 1280 });
    await screen.findByTestId('ask-field');
    await settle();

    expect(__inspectForTests().layers).toBe(0);
    expect(b.history.pushState).not.toHaveBeenCalled();
  });
});
