import React, { useEffect } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen, waitFor, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ASK_SURFACE_SELECTOR } from '../hooks/useOutsideDismiss.js';
import { useAsk } from '../context/AskContext.jsx';
import { resetViewport } from './askViewport.js';
import {
  MAP_PANE, OPERATIONS_PANE, matrixCtx, renderAskShell,
} from './askShellHarness.jsx';
import {
  NOW, ownResponse, readyResponse, settings, WHITBY,
} from './askFixtures.js';

/**
 * Ask's docked column as the shell wires it (F2; plan §2.6): the four width bands, the field beside
 * the tab row, the dock's placement as a sibling of the whole shell column, that it is NOT a dialog,
 * that it is {@code inert} (never the page) under every shell dialog, its Escape, and that it survives
 * a switch between Plan, Coming up and Map and goes with Operations.
 *
 * <p>The {@code /} key has {@code AskShellKey.test.jsx}; the phone's bar and the tablet's sheet are in
 * {@code AskShellEntry.test.jsx}. jsdom has no layout and no {@code inert}: what is asserted is the
 * attribute the shell sets, the structure it builds and the behaviour it has, never a pixel — the
 * stylesheet's half is pinned as text in {@code askDockCss.test.js}.
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
import { ask, getAskSettings, getReady } from '../api/askApi.js';

const field = () => screen.getByTestId('ask-field');
const dock = () => screen.queryByTestId('ask-dock');
const tab = (name) => screen.getByRole('tab', { name });
const shellRoot = () => screen.getByTestId('window-first-shell');
const column = () => screen.getByTestId('window-first-shell-col');
const modalCount = () => document.querySelectorAll('[aria-modal="true"]').length;
const appContainer = () => shellRoot().closest('body > *');
const fieldReady = () => screen.findByTestId('ask-field');

/** Presses the field and returns the dock it opens. */
async function openDock() {
  fireEvent.click(await fieldReady());
  return screen.findByTestId('ask-dock');
}

/** Asks one typed question in the open dock and waits for its answer. */
async function askOne(question = 'Best at dawn?') {
  ask.mockResolvedValue(ownResponse());
  await userEvent.type(screen.getByTestId('ask-input'), `${question}{Enter}`);
  return screen.findByTestId('ask-picks');
}

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

describe('the four width bands, either side of each boundary', () => {
  it.each([
    // [width, which entry is drawn, field width, key cap drawn, key named to AT]
    [639, 'ask-bar', null, null, null],
    [640, 'ask-field', '260', false, false],
    [1023, 'ask-field', '260', false, false],
    [1024, 'ask-field', '260', false, true],
    [1179, 'ask-field', '260', false, true],
    [1180, 'ask-field', '340', true, true],
    [1600, 'ask-field', '340', true, true],
  ])('at %i px: %s, %s wide, cap %s, shortcut named %s', async (width, entry, fieldWidth, cap, named) => {
    renderAskShell({ width });
    const drawn = await screen.findByTestId(entry);
    if (entry === 'ask-bar') {
      expect(screen.queryByTestId('ask-field')).toBeNull();
      return;
    }
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    expect(drawn).toHaveAttribute('data-width', fieldWidth);
    // The cap needs room (180px up); the attribute needs none, so it is named wherever `/` WORKS —
    // from 1024px — and never below it, where the sheet has no key.
    expect(drawn.querySelector('.wf-askf-kbd') !== null).toBe(cap);
    if (named) expect(drawn).toHaveAttribute('aria-keyshortcuts', '/');
    else expect(drawn).not.toHaveAttribute('aria-keyshortcuts');
  });

  it('the field opens the SHEET below 1024 and the DOCK from 1024: one control, two surfaces', async () => {
    const { viewport } = renderAskShell({ width: 1023 });
    fireEvent.click(await fieldReady());
    expect(await screen.findByRole('dialog', { name: 'Ask PhotoCast' })).toBeInTheDocument();
    expect(dock()).toBeNull();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull());

    act(() => viewport.resize(1024));
    fireEvent.click(field());
    expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('the dock is 380px from 1180 and 360px below it, and follows the window across the boundary', async () => {
    const { viewport } = renderAskShell({ width: 1180 });
    expect(await openDock()).toHaveAttribute('data-band', 'wide');
    act(() => viewport.resize(1179));
    expect(dock()).toHaveAttribute('data-band', 'desktop');
    act(() => viewport.resize(1180));
    expect(dock()).toHaveAttribute('data-band', 'wide');
  });

  it('is on Plan, Coming up and the Map, with the field beside the tab list and never inside it', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await fieldReady();
    for (const name of ['Plan', 'Coming up', 'Map']) {
      fireEvent.click(tab(name));
      expect(field()).toBeInTheDocument();
      expect(screen.getByRole('tablist')).not.toContainElement(field());
      expect(screen.getByTestId('window-first-tabrow')).toContainElement(field());
    }
  });
});

describe('Operations has no Ask, and holds the field\'s place', () => {
  it('draws no field there, a ghost of its width instead, and closes an open dock', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await openDock();

    fireEvent.click(tab('Operations'));

    expect(dock()).toBeNull();
    expect(screen.queryByTestId('ask-field')).toBeNull();
    // Operations is pinned to the list's right edge: without the ghost it would slide under the
    // pointer by the field's width the moment it was pressed.
    expect(screen.getByTestId('ask-field-ghost')).toHaveAttribute('data-width', '340');
    expect(screen.getByTestId('ask-field-ghost')).toHaveAttribute('aria-hidden', 'true');
  });

  it('and does not bring the dock back when the reader returns', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await openDock();
    fireEvent.click(tab('Operations'));
    fireEvent.click(tab('Plan'));
    expect(dock()).toBeNull();
    expect(field()).toHaveAttribute('aria-expanded', 'false');
  });

  it('the ghost is the narrower width in the narrower band', async () => {
    renderAskShell({ width: 1100, props: { operationsPane: OPERATIONS_PANE } });
    await fieldReady();
    fireEvent.click(tab('Operations'));
    expect(screen.getByTestId('ask-field-ghost')).toHaveAttribute('data-width', '260');
  });
});

describe('the dock is a column beside the shell, not a dialog', () => {
  it('is a complementary landmark named Ask PhotoCast, with no aria-modal anywhere', async () => {
    renderAskShell({ width: 1280 });
    const open = await openDock();
    expect(screen.getByRole('complementary', { name: 'Ask PhotoCast' })).toBe(open);
    expect(open).toHaveAttribute('data-ask-surface');
    // The exact selector `useOutsideDismiss` exempts: the two ends of that rule are pinned together.
    expect(open.matches(ASK_SURFACE_SELECTOR)).toBe(true);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(modalCount()).toBe(0);
    expect(open).not.toHaveAttribute('aria-modal');
  });

  it('the field says so: no popup-dialog claim, expanded, and a pointer at the dock only while it is open', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    expect(field()).not.toHaveAttribute('aria-haspopup');
    expect(field()).toHaveAttribute('aria-expanded', 'false');
    expect(field()).not.toHaveAttribute('aria-controls');

    const open = await openDock();

    expect(field()).toHaveAttribute('aria-expanded', 'true');
    expect(field()).toHaveAttribute('aria-controls', open.id);
    expect(field()).not.toHaveAttribute('aria-haspopup');
  });

  it('the page is NOT inert while it is open — nothing but a dialog ever makes it so', async () => {
    renderAskShell({ width: 1280, ctx: matrixCtx() });
    await openDock();
    expect(appContainer()).not.toHaveAttribute('inert');
    expect(shellRoot()).not.toHaveAttribute('inert');
    expect(column()).not.toHaveAttribute('inert');
    expect(dock()).not.toHaveAttribute('inert');
  });

  it('is a SIBLING of the whole shell column: the masthead, the tab row and the panel are all inside the other one', async () => {
    renderAskShell({ width: 1280 });
    const open = await openDock();

    expect(open.parentElement).toBe(shellRoot());
    expect(column().parentElement).toBe(shellRoot());
    expect(column()).not.toContainElement(open);
    expect(column()).toContainElement(screen.getByTestId('window-first-masthead'));
    expect(column()).toContainElement(screen.getByTestId('window-first-tabs'));
    expect(column()).toContainElement(screen.getByTestId('window-first-pane'));
    // Declared order: column, then dock — the row the stylesheet centres as a pair.
    expect(column().nextElementSibling).toBe(open);
  });

  it('the shell root is a docked row only while it is open', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    expect(shellRoot()).not.toHaveClass('wf-shell--docked');
    expect(column().style.maxWidth).toBe('');
    await openDock();
    expect(shellRoot()).toHaveClass('wf-shell--docked');
    fireEvent.click(screen.getByTestId('ask-dock-close'));
    expect(shellRoot()).not.toHaveClass('wf-shell--docked');
  });

  it('⚠️ the masthead and the panel share ONE width while it is open (O-17: the two never drift apart)', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await openDock();
    const mastheadWrap = screen.getByTestId('window-first-masthead').parentElement;
    const panelWrap = screen.getByTestId('window-first-pane').parentElement;

    expect(mastheadWrap.style.maxWidth).toBe('1080px');
    expect(panelWrap.style.maxWidth).toBe(mastheadWrap.style.maxWidth);
    // …and both are inside the one column the dock narrows, which is what makes it true at any width.
    expect(column()).toContainElement(mastheadWrap);
    expect(column()).toContainElement(panelWrap);
    expect(column().style.maxWidth).toBe(mastheadWrap.style.maxWidth);

    fireEvent.click(tab('Map'));
    const mapPanelWrap = screen.getByTestId('window-first-panel-map').parentElement;
    expect(mapPanelWrap.style.maxWidth).toBe(mastheadWrap.style.maxWidth);
    expect(column()).toContainElement(mapPanelWrap);
  });

  it('the Map pane sits inside the column the dock narrows, so its own ResizeObserver sees the new width', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await openDock();
    fireEvent.click(tab('Map'));
    expect(column()).toContainElement(screen.getByTestId('map-pane-stub'));
    expect(dock()).not.toContainElement(screen.getByTestId('map-pane-stub'));
    // The Map root is a row, not a column, while docked — the dock is the frame's own height.
    expect(shellRoot().className).toContain('flex-1');
    expect(shellRoot().className).not.toContain('flex-col');
    expect(dock()).not.toHaveAttribute('data-sticky');
  });

  it('on Plan and Coming up the dock sticks to the viewport; on the Map it does not', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await openDock();
    expect(dock()).toHaveAttribute('data-sticky');
    fireEvent.click(tab('Coming up'));
    expect(dock()).toHaveAttribute('data-sticky');
    fireEvent.click(tab('Map'));
    expect(dock()).not.toHaveAttribute('data-sticky');
    fireEvent.click(tab('Plan'));
    expect(dock()).toHaveAttribute('data-sticky');
    // Undocked, the Map's own flex chain is byte-for-byte what it was.
    fireEvent.click(screen.getByTestId('ask-dock-close'));
    fireEvent.click(tab('Map'));
    expect(shellRoot().className).toContain('flex-col');
    expect(column().className).toContain('flex-col');
  });

  it('publishes its distance from the viewport top while sticky, so its input row is never below the fold', async () => {
    renderAskShell({ width: 1280 });
    const open = await openDock();
    const rect = vi.spyOn(open, 'getBoundingClientRect').mockReturnValue({ top: 48 });

    act(() => { window.dispatchEvent(new Event('scroll')); });
    await waitFor(() => expect(open.style.getPropertyValue('--wf-dock-top')).toBe('48px'));

    // Stuck: the dock's own top is 0 (or, defensively, negative) and the whole 100dvh is its to use.
    rect.mockReturnValue({ top: -12 });
    act(() => { window.dispatchEvent(new Event('scroll')); });
    await waitFor(() => expect(open.style.getPropertyValue('--wf-dock-top')).toBe('0px'));
  });

  it('publishes nothing on the Map, where it is the frame\'s own height', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await openDock();
    fireEvent.click(tab('Map'));
    expect(dock().style.getPropertyValue('--wf-dock-top')).toBe('');
  });
});

describe('opening, closing and the conversation', () => {
  it('pressing the field opens it and focuses the question field', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    expect(screen.getByTestId('ask-input')).toHaveFocus();
    expect(field()).toHaveAttribute('aria-expanded', 'true');
  });

  it('pressing it again keeps the dock and puts the cursor back, rather than closing what was just asked', async () => {
    renderAskShell({ width: 1280 });
    const open = await openDock();
    screen.getByTestId('ask-input').blur();
    fireEvent.click(field());
    expect(dock()).toBe(open);
    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });

  it('the ✕ closes it and returns focus to the field, never to <body>', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    screen.getByTestId('ask-dock-close').focus();

    fireEvent.click(screen.getByTestId('ask-dock-close'));

    expect(dock()).toBeNull();
    expect(field()).toHaveFocus();
    expect(field()).toHaveAttribute('aria-expanded', 'false');
  });

  it('⚠️ closing keeps the conversation: the answer is there when the dock is reopened', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    await askOne();

    fireEvent.click(screen.getByTestId('ask-dock-close'));
    expect(dock()).toBeNull();
    fireEvent.click(field());

    expect(await screen.findByTestId('ask-picks')).toBeInTheDocument();
    // "Clear answer" is the one way to END it, and it moves focus to the field first.
    const clear = screen.getByTestId('ask-clear');
    clear.focus();
    fireEvent.click(clear);
    expect(screen.queryByTestId('ask-picks')).toBeNull();
    expect(screen.getByTestId('ask-input')).toHaveFocus();
    expect(await screen.findByTestId('ask-ready-list')).toBeInTheDocument();
  });

  it('sends the view it is open on, with no region and no window, and says what it sends', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await openDock();
    expect(screen.getByTestId('ask-dock-context')).toHaveTextContent('· on Plan');
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Plan · all regions');
    await askOne('Best at dawn?');
    expect(ask).toHaveBeenLastCalledWith({ question: 'Best at dawn?', regionIds: [], view: 'plan' });

    fireEvent.click(tab('Coming up'));
    expect(screen.getByTestId('ask-dock-context')).toHaveTextContent('· on Coming up');
    // ⚠️ F3: the answer on screen was ASKED on Plan, and a tab switch must not relabel it — the chip
    // says what was sent. The dock's header follows the tab; the chips follow the question.
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Plan · all regions');
    // With nothing on screen the chips say what the NEXT question will carry: this tab's.
    fireEvent.click(screen.getByTestId('ask-clear'));
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Coming up · all regions');
    await askOne('Any rare events?');
    expect(ask).toHaveBeenLastCalledWith({ question: 'Any rare events?', regionIds: [], view: 'coming-up' });
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Coming up · all regions');

    // The Map pane here is a stub that publishes nothing, so the dock says — and sends — "all
    // regions", no window (the real pane's channel is `AskMapContext.test.jsx`'s).
    fireEvent.click(tab('Map'));
    expect(screen.getByTestId('ask-dock-context')).toHaveTextContent('· on Map');
    fireEvent.click(screen.getByTestId('ask-clear'));
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · all regions');
    await askOne('Best on the map?');
    expect(ask).toHaveBeenLastCalledWith({ question: 'Best on the map?', regionIds: [], view: 'map' });
  });

  it('an unreachable Ask draws the field disabled and no dock, and a refused press opens nothing', async () => {
    getAskSettings.mockRejectedValue(new Error('network'));
    renderAskShell({ width: 1280, askSettings: false });
    expect(await fieldReady()).toBeDisabled();
    fireEvent.click(field());
    expect(dock()).toBeNull();
  });

  it('a dialog this shell does not own refuses the press and nothing is taken down', async () => {
    renderAskShell({ width: 1280 });
    await fieldReady();
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('aria-modal', 'true');
    foreign.setAttribute('data-foreign-dialog', '');
    document.body.appendChild(foreign);

    fireEvent.click(field());

    expect(dock()).toBeNull();
    expect(modalCount()).toBe(1);
  });
});

describe('a thread in the dock (T4)', () => {
  it('stacks the earlier exchanges INSIDE the conversation’s scroller, with the input row outside it', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    await askOne('One?');
    await askOne('Two?');
    await askOne('Three?');

    const scroller = screen.getByTestId('ask-dock-scroller');
    const history = within(scroller).getByTestId('ask-thread');
    expect(within(history).getAllByTestId('ask-thread-exchange')).toHaveLength(2);
    // The field is a sibling AFTER the scroller: it does not scroll away with a long stack.
    expect(scroller).not.toContainElement(screen.getByTestId('ask-input-row'));
    expect(scroller.compareDocumentPosition(screen.getByTestId('ask-input-row')) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    // "Clear" ends the lot, and sits in the scroller after the conversation.
    expect(within(scroller).getByTestId('ask-clear')).toHaveTextContent(/^Clear$/);
  });

  it('keeps focus on the question field through a follow-up: the answer does not take it', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    await askOne('One?');

    await askOne('Two?');

    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });
});

describe('Escape', () => {
  it('⚠️ inside the dock closes it, returns focus to the field and goes no further', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    const elsewhere = vi.fn();
    document.addEventListener('keydown', elsewhere);
    try {
      const press = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
      fireEvent(screen.getByTestId('ask-input'), press);

      expect(dock()).toBeNull();
      expect(document.activeElement).toBe(field());
      // Stopped: no document-level Escape rule (a popup's, a map panel's) saw the same press.
      expect(elsewhere).not.toHaveBeenCalled();
    } finally {
      document.removeEventListener('keydown', elsewhere);
    }
  });

  it('from the ✕ or a card inside the dock too: anywhere focus is in it', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    await askOne();
    screen.getByTestId('ask-dock-close').focus();
    fireEvent.keyDown(screen.getByTestId('ask-dock-close'), { key: 'Escape' });
    expect(dock()).toBeNull();
    expect(field()).toHaveFocus();
  });

  it('⚠️ is left alone when focus is anywhere else: the dock stays and the press goes where it always went', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    const elsewhere = vi.fn();
    document.addEventListener('keydown', elsewhere);
    try {
      fireEvent.keyDown(document.body, { key: 'Escape' });
      fireEvent.keyDown(tab('Plan'), { key: 'Escape' });
      expect(dock()).not.toBeNull();
      expect(elsewhere).toHaveBeenCalledTimes(2);
    } finally {
      document.removeEventListener('keydown', elsewhere);
    }
  });

  it('ignores an IME\'s composing Escape', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    fireEvent.keyDown(screen.getByTestId('ask-input'), { key: 'Escape', isComposing: true });
    expect(dock()).not.toBeNull();
  });

  it('⚠️ the popup\'s own Escape ladder is untouched by an open dock: one press, one layer, the dock stays', async () => {
    renderAskShell({ width: 1280, ctx: matrixCtx() });
    await openDock();
    screen.getByTestId('ask-input').blur();
    await openPopup();
    fireEvent.click(screen.getByTestId('window-spot-all'));
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('window-spot-sheet')).toBeNull());
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('window-sheet')).toBeNull());
    expect(dock()).not.toBeNull();
  });
});

describe('under a dialog the dock is inert and the page is not', () => {
  const inert = () => screen.getByTestId('ask-dock');

  it('a window popup', async () => {
    renderAskShell({ width: 1280, ctx: matrixCtx() });
    await openDock();
    expect(inert()).not.toHaveAttribute('inert');
    await openPopup();
    expect(inert()).toHaveAttribute('inert');
    expect(field()).toBeDisabled();
    expect(appContainer()).not.toHaveAttribute('inert');
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('window-sheet')).toBeNull());
    expect(inert()).not.toHaveAttribute('inert');
    expect(field()).toBeEnabled();
  });

  it('the drill-down stacked over it', async () => {
    renderAskShell({ width: 1280, ctx: matrixCtx() });
    await openDock();
    await openPopup();
    fireEvent.click(screen.getByTestId('window-spot-all'));
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
    expect(inert()).toHaveAttribute('inert');
    expect(appContainer()).not.toHaveAttribute('inert');
  });

  it('search', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    fireEvent.click(screen.getByTestId('window-first-search'));
    await screen.findByTestId('plan-search-panel');
    expect(inert()).toHaveAttribute('inert');
    expect(appContainer()).not.toHaveAttribute('inert');
  });

  it('the four-day location sheet', async () => {
    const { rerenderShell } = renderAskShell({ width: 1280 });
    await openDock();
    rerenderShell({
      locationSheetHandoff: {
        id: WHITBY, name: 'Whitby', regionName: 'North York Moors & Coast', nonce: 1,
      },
    });
    await screen.findByTestId('location-sheet');
    expect(inert()).toHaveAttribute('inert');
    expect(appContainer()).not.toHaveAttribute('inert');
  });

  it('settings, and live again when it closes', async () => {
    const { rerenderShell } = renderAskShell({ width: 1280 });
    await openDock();
    rerenderShell({ settingsOpen: true });
    expect(inert()).toHaveAttribute('inert');
    expect(field()).toBeDisabled();
    rerenderShell({ settingsOpen: false });
    expect(inert()).not.toHaveAttribute('inert');
  });

  it('the pick dialog stacked over the popup', async () => {
    const base = matrixCtx();
    const windowCards = [{
      ...base.windowCards[0],
      pick: {
        kind: 'best', regionName: 'North York Moors & Coast', headline: 'Breaking clear',
        detail: 'Low cloud clears.', locationName: 'Whitby', locationId: WHITBY,
      },
    }];
    renderAskShell({ width: 1280, ctx: { ...base, windowCards } });
    await openDock();
    await openPopup();
    fireEvent.click(screen.getByTestId('window-sheet-pick'));
    expect(screen.getByTestId('window-pick-dialog')).toBeInTheDocument();
    expect(inert()).toHaveAttribute('inert');
    expect(field()).toBeDisabled();
    expect(appContainer()).not.toHaveAttribute('inert');
  });

  it('⚠️ NOT a dead backend: the field is disabled and the dock stays operable, so it can always be closed', async () => {
    const { rerenderShell } = renderAskShell({ width: 1280 });
    await openDock();
    rerenderShell({ contentDisabled: true });
    expect(field()).toBeDisabled();
    expect(inert()).not.toHaveAttribute('inert');
    // An inert ✕ would pin the column open, dimmed, for as long as the backend is down.
    fireEvent.click(screen.getByTestId('ask-dock-close'));
    expect(dock()).toBeNull();
  });

  it('opening a dialog never closes the dock, and the dock never closes a dialog', async () => {
    renderAskShell({ width: 1280, ctx: matrixCtx() });
    await openDock();
    await openPopup();
    expect(dock()).not.toBeNull();
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    expect(modalCount()).toBe(1);
  });
});

describe('focus is never left to fall to <body>', () => {
  it('⚠️ a dialog opened programmatically while the reader is typing in the dock hands focus to the tab in force', async () => {
    // Settings from the map's ⌂, a four-day sheet from a map handoff: nothing the reader pressed in
    // the dock. `inert` would otherwise drop focus to <body> and the dock's own Escape with it.
    const { rerenderShell } = renderAskShell({ width: 1280 });
    await openDock();
    expect(screen.getByTestId('ask-input')).toHaveFocus();

    rerenderShell({ settingsOpen: true });

    expect(dock()).toHaveAttribute('inert');
    expect(tab('Plan')).toHaveFocus();
  });

  it('is left alone when focus is elsewhere: a press on a window card is the reader\'s own focus', async () => {
    renderAskShell({ width: 1280, ctx: matrixCtx() });
    await openDock();
    const card = (await screen.findAllByTestId('wf-heat-card'))[0];
    card.focus();
    await act(async () => { fireEvent.click(card); });
    await screen.findByTestId('window-sheet');
    expect(dock()).toHaveAttribute('inert');
    expect(tab('Plan')).not.toHaveFocus();
  });

  it('⚠️ the window narrowing past 1024px under a focused dock hands focus to the tab in force', async () => {
    // 200% zoom on a 1440px window, an iPad rotating: the dock is released in the render and its node
    // goes with the focus in it.
    const { viewport } = renderAskShell({ width: 1280 });
    await openDock();
    expect(screen.getByTestId('ask-input')).toHaveFocus();

    act(() => viewport.resize(1023));

    expect(dock()).toBeNull();
    expect(document.body).not.toHaveFocus();
    expect(tab('Plan')).toHaveFocus();
  });

  it('closing by the ✕ or Escape is not second-guessed: focus stays on the field', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    fireEvent.keyDown(screen.getByTestId('ask-input'), { key: 'Escape' });
    expect(field()).toHaveFocus();
  });
});

describe('the sticky dock\'s bookkeeping', () => {
  it('listens to scroll and resize while it is sticky, and stops when it closes', async () => {
    const added = vi.spyOn(window, 'addEventListener');
    const removed = vi.spyOn(window, 'removeEventListener');
    renderAskShell({ width: 1280 });
    await openDock();
    const names = (spy) => spy.mock.calls.map((call) => call[0]);
    expect(names(added)).toEqual(expect.arrayContaining(['scroll', 'resize']));

    fireEvent.click(screen.getByTestId('ask-dock-close'));

    expect(names(removed)).toEqual(expect.arrayContaining(['scroll', 'resize']));
  });

  it('re-measures on a resize, and when a banner changes the body\'s size', async () => {
    const observed = [];
    const original = global.ResizeObserver;
    global.ResizeObserver = class {
      constructor(callback) { this.callback = callback; }
      observe(node) { observed.push({ node, callback: this.callback }); }
      unobserve() {}
      disconnect() {}
    };
    try {
      renderAskShell({ width: 1280 });
      const open = await openDock();
      const rect = vi.spyOn(open, 'getBoundingClientRect').mockReturnValue({ top: 30 });
      const onBody = observed.filter((entry) => entry.node === document.body);
      expect(onBody.length).toBeGreaterThan(0);

      act(() => { onBody[0].callback(); });
      await waitFor(() => expect(open.style.getPropertyValue('--wf-dock-top')).toBe('30px'));

      rect.mockReturnValue({ top: 12 });
      act(() => { window.dispatchEvent(new Event('resize')); });
      await waitFor(() => expect(open.style.getPropertyValue('--wf-dock-top')).toBe('12px'));
    } finally {
      global.ResizeObserver = original;
    }
  });
});

describe('a tab switch', () => {
  it('⚠️ keeps the dock open and the conversation in it, across Plan, Coming up and the Map', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    const open = await openDock();
    await askOne();

    for (const name of ['Coming up', 'Map', 'Plan']) {
      fireEvent.click(tab(name));
      expect(screen.getByTestId('ask-dock')).toBe(open);
      expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
    }
  });

  it('is not closed by the shell\'s one list of layers either: selectTab names the tab in force and the dock stays', async () => {
    // The cog, the postcode nudge and the settings edge all close layers through `selectTab`; the dock
    // is not a layer and none of them may take it down.
    const { rerenderShell } = renderAskShell({ width: 1280 });
    await openDock();
    fireEvent.click(screen.getByTestId('window-first-settings'));
    expect(dock()).not.toBeNull();
    rerenderShell({ settingsOpen: true });
    rerenderShell({ settingsOpen: false });
    expect(dock()).not.toBeNull();
  });

  it('"Show on map ›" on a dock card moves to the Map with the dock still open and the answer kept', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await openDock();
    await askOne();

    fireEvent.click(screen.getByTestId('ask-show-on-map-1'));

    expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    expect(dock()).not.toBeNull();
    expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
    // Not offered where it goes nowhere, and focus lands on the tab rather than falling to <body>.
    expect(screen.queryByTestId('ask-show-on-map-1')).toBeNull();
    await waitFor(() => expect(tab('Map')).toHaveFocus());
  });

  it('offers "Show on map ›" on Coming up too, and not on the Map', async () => {
    renderAskShell({ width: 1280, props: { mapPane: MAP_PANE } });
    await openDock();
    await askOne();
    expect(screen.getByTestId('ask-show-on-map-1')).toBeInTheDocument();
    fireEvent.click(tab('Coming up'));
    expect(screen.getByTestId('ask-show-on-map-1')).toBeInTheDocument();
    fireEvent.click(tab('Map'));
    expect(screen.queryByTestId('ask-show-on-map-1')).toBeNull();
  });

  it('offers it nowhere when there is no Map pane to go to', async () => {
    renderAskShell({ width: 1280 });
    await openDock();
    await askOne();
    expect(screen.queryByTestId('ask-show-on-map-1')).toBeNull();
  });
});

describe('it cannot come back by itself, and it does not outlive what draws it', () => {
  it('narrowing past 1024 takes the dock away, and widening again does not reopen it', async () => {
    const { viewport } = renderAskShell({ width: 1280 });
    await openDock();
    await askOne();

    act(() => viewport.resize(1023));
    expect(dock()).toBeNull();
    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();

    act(() => viewport.resize(1280));
    expect(dock()).toBeNull();
    // The conversation is untouched: reopening finds it.
    fireEvent.click(field());
    expect(await screen.findByTestId('ask-picks')).toBeInTheDocument();
  });

  it('widening a tablet\'s sheet past 1024 does not leave both surfaces', async () => {
    const { viewport } = renderAskShell({ width: 800 });
    fireEvent.click(await fieldReady());
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    act(() => viewport.resize(1280));
    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
    expect(dock()).toBeNull();
  });

  it('is not drawn while Ask is off', async () => {
    renderAskShell({ width: 1280, askSettings: settings({ enabled: false }) });
    await waitFor(() => expect(getAskSettings).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.queryByTestId('ask-field')).toBeNull();
    expect(dock()).toBeNull();
  });
});

describe('the Map\'s scope and window reach the dock and the sheet (F3)', () => {
  const LAKES = {
    regionIds: [3],
    regionNames: ['The Lake District'],
    windowId: '2026-10-10_sunrise',
    windowLabel: 'Saturday sunrise',
    viewLabel: 'Map · The Lake District',
  };
  /** A Map pane that publishes the way the real one does: on mount, and a null when it goes. */
  function PublishingMapPane() {
    const { registerMapContext } = useAsk();
    useEffect(() => {
      registerMapContext(LAKES);
      return () => registerMapContext(null);
    }, [registerMapContext]);
    return <div data-testid="map-pane-stub">map</div>;
  }
  const props = { mapPane: <PublishingMapPane /> };

  it('on the Map the dock says — and the Ready list is fetched for — the one region in scope, with the window chip', async () => {
    renderAskShell({ width: 1280, props });
    await openDock();
    fireEvent.click(tab('Map'));

    await waitFor(() => expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District'));
    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Saturday sunrise');
    await waitFor(() => expect(getReady).toHaveBeenLastCalledWith(3));
  });

  it('a typed question from the Map is sent with that region, that window and the map view', async () => {
    renderAskShell({ width: 1280, props });
    await openDock();
    fireEvent.click(tab('Map'));
    await waitFor(() => expect(screen.getByTestId('ask-chip-window')).toBeInTheDocument());

    await askOne('Best here?');

    expect(ask).toHaveBeenLastCalledWith({
      question: 'Best here?', regionIds: [3], view: 'map', windowId: '2026-10-10_sunrise',
    });
  });

  it('⚠️ Plan and Coming up still send all regions, and fetch the Ready list for all', async () => {
    renderAskShell({ width: 1280, props });
    await openDock();
    fireEvent.click(tab('Map'));
    await waitFor(() => expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Lake District'));

    fireEvent.click(tab('Plan'));

    await waitFor(() => expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Plan · all regions'));
    expect(screen.queryByTestId('ask-chip-window')).toBeNull();
    await waitFor(() => expect(getReady).toHaveBeenLastCalledWith('all'));
    await askOne('Best at dawn?');
    expect(ask).toHaveBeenLastCalledWith({ question: 'Best at dawn?', regionIds: [], view: 'plan' });
  });

  it('an answer asked on the Map keeps saying so on Plan — the dock outlives the tab, the answer keeps its context', async () => {
    renderAskShell({ width: 1280, props });
    await openDock();
    fireEvent.click(tab('Map'));
    await waitFor(() => expect(screen.getByTestId('ask-chip-window')).toBeInTheDocument());
    await askOne('Best here?');

    fireEvent.click(tab('Plan'));

    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District');
    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Saturday sunrise');
  });

  it('the tablet\'s sheet over the Map carries the same context', async () => {
    renderAskShell({ width: 800, props });
    await fieldReady();
    fireEvent.click(tab('Map'));
    await waitFor(() => expect(tab('Map')).toHaveAttribute('aria-selected', 'true'));
    fireEvent.click(field());
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });

    await waitFor(() => expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District'));
    ask.mockResolvedValue(ownResponse());
    await userEvent.type(screen.getByTestId('ask-input'), 'Best here?{Enter}');

    expect(ask).toHaveBeenLastCalledWith({
      question: 'Best here?', regionIds: [3], view: 'map', windowId: '2026-10-10_sunrise',
    });
  });
});
