import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen, waitFor,
} from '@testing-library/react';
import { resetViewport } from './askViewport.js';
import {
  MAP_PANE, OPERATIONS_PANE, matrixCtx, renderAskShell,
} from './askShellHarness.jsx';
import {
  deferred, NOW, readyResponse, settings,
} from './askFixtures.js';

/**
 * The {@code /} key, which moved from Plan search to Ask (F2; plan §6 Q1, decided): at 1024px and up,
 * on Plan, Coming up and the Map, {@code /} opens the dock and puts the cursor in its question field —
 * and keeps EVERY refusal the search shortcut had, plus the one Ask adds (it is not live unless Ask
 * is).
 *
 * <p>Its own file for the reason {@code AskShellDialogs.test.jsx} is: the refusals cross the matrix's
 * lazy boundaries, and the first test pays for them once.
 *
 * <p>⚠️ <b>How each refusal is asserted.</b> Two ways, never one. {@code defaultPrevented} is the
 * observable where the key's default IS the rule — a {@code /} typed into a field, or pressed with a
 * modifier, must still reach whoever it was for — and is lazy-proof, since it does not depend on
 * anything having rendered. And the absence of the dock is asserted only after the control it would
 * have opened has RENDERED (the field is on screen, disabled or not), and is paired with a control: the
 * same press, once the refusal is lifted, DOES open it. Without that, an absence is what a key that was
 * never wired also produces — the lazy-absence lesson this app has paid for before.
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

const dock = () => screen.queryByTestId('ask-dock');

/**
 * Waits for the Ask field and for the commit's passive effects behind it: the key handler is an
 * effect, and {@code findBy} can resolve on the DOM mutation before React has run it. A press in that
 * window is a race no reader can run (they cannot press in the frame the field appears), so the tests
 * do not either — and without this the very first test of a loaded run failed once.
 */
async function fieldReady() {
  const found = await screen.findByTestId('ask-field');
  await act(async () => {});
  return found;
}
const field = () => screen.getByTestId('ask-field');
const tab = (name) => screen.getByRole('tab', { name });

/** Presses `/` on `target` and returns the event, so a test can read `defaultPrevented`. */
function slash(target = document, init = {}) {
  const press = new KeyboardEvent('keydown', {
    key: '/', bubbles: true, cancelable: true, ...init,
  });
  fireEvent(target, press);
  return press;
}

/** The key is refused: it opens nothing and is left for whoever it was meant for. */
function expectRefused(press) {
  expect(press.defaultPrevented).toBe(false);
  expect(dock()).toBeNull();
}

/** The control for every refusal: the same key, with the refusal lifted, opens the dock. */
async function expectActs() {
  const press = slash();
  expect(press.defaultPrevented).toBe(true);
  expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
}

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

describe('what / does at 1024px and up', () => {
  it('opens the dock on Plan and puts the cursor in its question field', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    expect(dock()).toBeNull();

    const press = slash();

    expect(press.defaultPrevented).toBe(true);
    expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });

  it('does the same in the narrower docked band (1100px)', async () => {
    renderAskShell({ width: 1100 });
    await fieldReady();
    slash();
    expect(await screen.findByTestId('ask-dock')).toHaveAttribute('data-band', 'desktop');
    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });

  it('and on Coming up', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    fireEvent.click(tab('Coming up'));
    slash();
    expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });

  it('and on the Map', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await fieldReady();
    fireEvent.click(tab('Map'));
    slash();
    expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });

  it('with the dock already open, puts the cursor back in the field rather than closing or reopening it', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    slash();
    const open = await screen.findByTestId('ask-dock');
    screen.getByTestId('ask-input').blur();
    expect(document.body).toHaveFocus();

    const press = slash();

    expect(press.defaultPrevented).toBe(true);
    expect(screen.getByTestId('ask-dock')).toBe(open);
    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });

  it('is not tied to the field being the thing in focus: it works with focus on the tab list', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    tab('Plan').focus();
    slash(tab('Plan'));
    expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
  });

  it('with Shift held still acts, because on some layouts / is Shift+7', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    const press = slash(document, { shiftKey: true });
    expect(press.defaultPrevented).toBe(true);
    expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
  });

  it('⚠️ no longer opens Plan search, which keeps its ⌕ button — and the button still does', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();

    slash();
    await screen.findByTestId('ask-dock');
    expect(screen.queryByTestId('plan-search')).toBeNull();
    expect(screen.queryByTestId('plan-search-panel')).toBeNull();

    // The button, with the dock open: search opens, and the dock goes inert under it.
    fireEvent.click(screen.getByTestId('window-first-search'));
    expect(await screen.findByTestId('plan-search-panel')).toBeInTheDocument();
    expect(screen.getByTestId('ask-dock')).toHaveAttribute('inert');
  });
});

describe('what / does NOT do', () => {
  it('nothing on Operations, which has no Ask', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await fieldReady();
    // The control: on Plan the same key acts. Then the dock is closed again so the absence is real.
    await expectActs();
    fireEvent.click(screen.getByTestId('ask-dock-close'));
    expect(dock()).toBeNull();

    fireEvent.click(tab('Operations'));
    expect(screen.queryByTestId('ask-field')).toBeNull();
    expectRefused(slash());
  });

  it('nothing below 1024px: the sheet is the surface there and it has no key', async () => {
    renderAskShell({ width: 1023 });
    await fieldReady();
    expectRefused(slash());
    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
  });

  it('nothing on the phone either, bar and all', async () => {
    renderAskShell({ width: 390 });
    await screen.findByTestId('ask-bar');
    expectRefused(slash());
    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
  });

  it('acts at 1024 exactly and not at 1023, the boundary between the sheet and the dock', async () => {
    const { viewport } = renderAskShell({ width: 1023 });
    await fieldReady();
    expectRefused(slash());
    act(() => viewport.resize(1024));
    await expectActs();
  });

  it.each([
    ['an input', () => document.createElement('input')],
    ['a textarea', () => document.createElement('textarea')],
    ['a select', () => document.createElement('select')],
    ['a contenteditable element', () => {
      // jsdom has no `isContentEditable` (30.0.1): the host is given the `true` a browser computes
      // from the attribute, and keeps the attribute too, so a guard that reads either one refuses.
      const host = document.createElement('div');
      host.setAttribute('contenteditable', 'true');
      Object.defineProperty(host, 'isContentEditable', { value: true });
      return host;
    }],
  ])('⚠️ is ignored while the reader is typing in %s, and the character reaches it', async (_kind, make) => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    const typed = make();
    document.body.appendChild(typed);
    try {
      typed.focus();
      expect(typed).toHaveFocus();
      expectRefused(slash(typed));
    } finally {
      typed.remove();
    }
    await expectActs();
  });

  it('⚠️ is ignored in the dock\'s own question field, where a slash is a slash', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    slash();
    const input = await screen.findByTestId('ask-input');
    const press = slash(input);
    expect(press.defaultPrevented).toBe(false);
    expect(screen.getAllByTestId('ask-dock')).toHaveLength(1);
  });

  it.each(['metaKey', 'ctrlKey', 'altKey'])(
    '⚠️ is ignored when %s is held, so browser and OS shortcuts are untouched',
    async (modifier) => {
      renderAskShell({ width: 1280 });
      await fieldReady();
      expectRefused(slash(document, { [modifier]: true }));
      await expectActs();
    },
  );

  it('⚠️ is ignored while search is open, so what the reader typed survives', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    fireEvent.click(screen.getByTestId('window-first-search'));
    const panel = await screen.findByTestId('plan-search-panel');
    expect(field()).toBeDisabled();
    // Off the input, so it is the shell's own rule that refuses and not the field rule.
    document.activeElement?.blur();

    expectRefused(slash());
    expect(panel).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('plan-search-panel')).toBeNull());
    await expectActs();
  });

  it('⚠️ is ignored over an open window popup, and the popup stays', async () => {
    // Search was the one thing allowed over the popup (M3); the dock is not: its column is covered
    // by the popup's own scrim, and a live control must not appear under an aria-modal dialog.
    renderAskShell({ width: 1280, ctx: matrixCtx() });
    await openPopup();
    expect(field()).toBeDisabled();

    expectRefused(slash());

    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('window-sheet')).toBeNull());
    await expectActs();
  });

  it('⚠️ is ignored over a layer stacked on the popup, and both layers stay', async () => {
    renderAskShell({ width: 1280, ctx: matrixCtx() });
    await openPopup();
    fireEvent.click(screen.getByTestId('window-spot-all'));
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();

    expectRefused(slash());

    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
  });

  it('⚠️ is ignored over the four-day location sheet', async () => {
    const { rerenderShell } = renderAskShell({ width: 1280 });
    await fieldReady();
    rerenderShell({
      locationSheetHandoff: {
        id: 123, name: 'Whitby', regionName: 'North York Moors & Coast', nonce: 1,
      },
    });
    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(field()).toBeDisabled();
    expectRefused(slash());
    expect(screen.getByTestId('location-sheet')).toBeInTheDocument();
  });

  it('⚠️ is ignored while settings is open, and acts again when it closes', async () => {
    const { rerenderShell } = renderAskShell({ width: 1280 });
    await fieldReady();
    rerenderShell({ settingsOpen: true });
    expect(field()).toBeDisabled();
    expectRefused(slash());
    rerenderShell({ settingsOpen: false });
    await expectActs();
  });

  it('⚠️ is ignored while a dialog this shell does not own is open (the settings modal, the map overlay)', async () => {
    // The shell cannot see these as state: they are siblings of it in `App`. Containment answers it.
    renderAskShell({ width: 1280 });
    await fieldReady();
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('data-foreign-dialog', '');
    document.body.appendChild(foreign);

    expectRefused(slash());
    // `getByRole` throws on a second dialog, so this also says nothing else opened.
    expect(screen.getByRole('dialog')).toBe(foreign);

    foreign.remove();
    await expectActs();
  });

  it('is ignored while the arm is greyed for a dead backend', async () => {
    // The shell is `pointer-events: none` under `contentDisabled`; a keyboard shortcut into it would
    // be the one live control on a surface that says it is not.
    const { rerenderShell } = renderAskShell({ width: 1280 });
    await fieldReady();
    rerenderShell({ contentDisabled: true });
    expect(field()).toBeDisabled();
    expectRefused(slash());
    rerenderShell({ contentDisabled: false });
    await expectActs();
  });

  it('is ignored while Ask is unreachable: the field is drawn, disabled, and the key does not slip past it', async () => {
    getAskSettings.mockRejectedValue(new Error('network'));
    renderAskShell({ width: 1280, askSettings: false });
    expect(await fieldReady()).toBeDisabled();
    expectRefused(slash());
  });

  it('is ignored while Ask is off: no field, no dock, and the key is the page\'s', async () => {
    renderAskShell({ width: 1280, askSettings: settings({ enabled: false }) });
    // Settled: the settings read has landed and Ask resolved to "off".
    await waitFor(() => expect(getAskSettings).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.queryByTestId('ask-field')).toBeNull();
    expectRefused(slash());
  });

  it('is ignored while the first settings read is out: nothing is drawn yet and nothing acts', async () => {
    const pending = deferred();
    getAskSettings.mockReturnValue(pending.promise);
    renderAskShell({ width: 1280, askSettings: false });
    await act(async () => {});
    expect(screen.queryByTestId('ask-field')).toBeNull();
    expectRefused(slash());
    await act(async () => { pending.resolve(settings()); });
    await fieldReady();
    await expectActs();
  });
});
