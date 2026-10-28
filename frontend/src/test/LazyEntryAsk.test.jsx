import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen, waitFor, within,
} from '@testing-library/react';
import { resetViewport } from './askViewport.js';
import { MAP_PANE, renderAskShell } from './askShellHarness.jsx';
import { NOW, readyResponse, settings } from './askFixtures.js';
import { ASK_DOCK_ID } from '../components/ask/askIds.js';

/**
 * P2d: Ask's sheet and dock are lazy chunks, fetched the first time they open. The chunks are held
 * pending here (a `vi.mock` factory that waits on a gate runs when the lazy `import()` first asks), so
 * what the shell does BEFORE a chunk arrives is what is asserted: the sheet's `inert` page and its
 * focus return, the `askSheetLoaded` latch (closing keeps the instance, losing the entry resets it),
 * and the dock's stand-in carrying the id `aria-controls` names.
 *
 * The chunk is loaded once per file, so the order of the tests is the order of the gates: sheet
 * first, dock second.
 */
const g = vi.hoisted(() => {
  const gate = () => {
    let resolve;
    const promise = new Promise((r) => { resolve = r; });
    return { promise, resolve };
  };
  return { sheet: gate(), dock: gate(), mounts: 0, unmounts: 0 };
});

vi.mock('../components/ask/AskSheet.jsx', async () => {
  await g.sheet.promise;
  const actual = await vi.importActual('../components/ask/AskSheet.jsx');
  const Actual = actual.default;
  /** Counts mounts and unmounts of the real sheet. */
  function Counted(props) {
    React.useEffect(() => {
      g.mounts += 1;
      return () => { g.unmounts += 1; };
    }, []);
    return <Actual {...props} />;
  }
  return { ...actual, default: Counted };
});
vi.mock('../components/ask/AskDock.jsx', async () => {
  await g.dock.promise;
  return vi.importActual('../components/ask/AskDock.jsx');
});
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

const tab = (name) => screen.getByRole('tab', { name: name === 'At a Glance' ? /^At a Glance/ : name });
const appContainer = () => screen.getByTestId('window-first-shell').closest('body > *');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  getReady.mockReset().mockResolvedValue(readyResponse());
  getAskSettings.mockReset().mockResolvedValue(settings());
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  resetViewport();
});

describe('the sheet (phone)', () => {
  it('makes the page inert on the press, before its chunk arrives, and hands focus back on close', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE } });
    const bar = await screen.findByTestId('ask-bar');
    act(() => { bar.focus(); });
    fireEvent.click(bar);

    // The chunk is held: no sheet yet, but the shell has already taken the page out of play.
    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
    expect(appContainer().hasAttribute('inert')).toBe(true);
    expect(g.mounts).toBe(0);

    await act(async () => { g.sheet.resolve(); });
    const sheet = await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    expect(g.mounts).toBe(1);

    fireEvent.click(within(sheet).getByRole('button', { name: /close/i }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull());
    expect(appContainer().hasAttribute('inert')).toBe(false);
    await waitFor(() => expect(screen.getByTestId('ask-bar')).toHaveFocus());
  });

  it('keeps the sheet instance mounted across a close and a reopen (the latch)', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE } });
    const bar = await screen.findByTestId('ask-bar');
    fireEvent.click(bar);
    const sheet = await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    const mountsAfterFirstOpen = g.mounts;
    const unmountsBefore = g.unmounts;

    fireEvent.click(within(sheet).getByRole('button', { name: /close/i }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull());
    // Closed, not unmounted: the instance is still there for focus to return through.
    expect(g.unmounts).toBe(unmountsBefore);

    fireEvent.click(screen.getByTestId('ask-bar'));
    expect(await screen.findByRole('dialog', { name: 'Ask PhotoCast' })).toBeInTheDocument();
    expect(g.mounts).toBe(mountsAfterFirstOpen);
  });

  it('lets the sheet go, and the latch with it, when the entry goes (the Map has no phone bar)', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE } });
    const bar = await screen.findByTestId('ask-bar');
    fireEvent.click(bar);
    const sheet = await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    fireEvent.click(within(sheet).getByRole('button', { name: /close/i }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull());
    const unmountsBefore = g.unmounts;

    fireEvent.click(tab('Map'));
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    await waitFor(() => expect(g.unmounts).toBe(unmountsBefore + 1));

    // Back on Plan the bar returns, and the sheet is mounted afresh only when it is next opened.
    fireEvent.click(tab('At a Glance'));
    const mountsBefore = g.mounts;
    await screen.findByTestId('ask-bar');
    expect(g.mounts).toBe(mountsBefore);
  });
});

describe('the dock (desktop)', () => {
  it('stands in with the id the field controls, then is replaced by the real dock', async () => {
    renderAskShell({ width: 1280 });
    const field = await screen.findByTestId('ask-field');
    fireEvent.click(field);

    const fallback = await screen.findByTestId('ask-dock-fallback');
    expect(fallback).toHaveAttribute('id', ASK_DOCK_ID);
    expect(fallback).toHaveAttribute('aria-hidden', 'true');
    expect(screen.queryByTestId('ask-dock')).toBeNull();

    await act(async () => { g.dock.resolve(); });
    expect(await screen.findByTestId('ask-dock')).toHaveAttribute('id', ASK_DOCK_ID);
    expect(screen.queryByTestId('ask-dock-fallback')).toBeNull();
  });
});
