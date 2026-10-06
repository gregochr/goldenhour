import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AskApiError } from '../api/askApi.js';
import { READY_ONLY_PLACEHOLDER } from '../utils/askModel.js';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { installViewport, resetViewport } from './askViewport.js';
import {
  MAP_PANE, OPERATIONS_PANE, renderAskShell, shellCtx,
} from './askShellHarness.jsx';
import {
  cantResponse, deferred, NOW, ownResponse, readyResponse, settings,
} from './askFixtures.js';

/**
 * Ask's entry and sheet as the shell wires them (F1b): the phone's bar, the tablet's field, the
 * availability matrix, the sheet as THE modal, the tab switch that closes it, and "Show on map ›".
 *
 * <p>The shell is real and so is {@code AskProvider}; the three Ask endpoints are mocks. The
 * dialog-refusal rules (a popup, a drill-down, search, settings) are in
 * {@code AskShellDialogs.test.jsx} — they need the matrix's lazy chunks, and keeping them in a
 * file of their own keeps each file's first test paying that cost once.
 *
 * <p>jsdom has no layout and no {@code inert}: what is asserted is the attribute the component sets
 * and the behaviour it has, never a pixel or a browser's refusal to focus an inert node.
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

const bar = () => screen.getByTestId('ask-bar');
const field = () => screen.getByTestId('ask-field');
const sheet = () => screen.getByRole('dialog', { name: 'Ask PhotoCast' });
const tab = (name) => screen.getByRole('tab', { name });
const shellRoot = () => screen.getByTestId('window-first-shell');
/**
 * The React app's container — the ancestor of the shell that is a direct child of <body> (`#root` in
 * production, the Testing Library container here): what the sheet makes `inert`.
 */
const appContainer = () => shellRoot().closest('body > *');
const modalCount = () => document.querySelectorAll('[aria-modal="true"]').length;

/** Waits for Ask to resolve to "on" on the phone, which is when its bar first exists. */
const barReady = () => screen.findByTestId('ask-bar');
const fieldReady = () => screen.findByTestId('ask-field');

/** Presses the bar and returns the open sheet. */
async function openSheet() {
  fireEvent.click(await barReady());
  return screen.findByRole('dialog', { name: 'Ask PhotoCast' });
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
  resetViewport();
});

describe('the phone bar (below 640px)', () => {
  it('is drawn on Plan with the Plan prompt', async () => {
    renderAskShell({ width: 390 });
    expect(await barReady()).toHaveAccessibleName('Ask about this weekend…');
    expect(bar()).toBeEnabled();
  });

  it('is drawn on Coming up with that tab\'s prompt', async () => {
    renderAskShell({ width: 390 });
    await barReady();
    fireEvent.click(tab('Coming up'));
    expect(bar()).toHaveAccessibleName('Ask about rare events…');
  });

  it('is not drawn on the Map or on Operations', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await barReady();
    fireEvent.click(tab('Map'));
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    fireEvent.click(tab('Operations'));
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    fireEvent.click(tab('Plan'));
    expect(bar()).toBeInTheDocument();
  });

  it('draws no tablet field beside the tabs', async () => {
    renderAskShell({ width: 390 });
    await barReady();
    expect(screen.queryByTestId('ask-field')).toBeNull();
  });

  it('marks the shell, so the page can reserve the bar\'s 58px, only while the bar is drawn', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await barReady();
    expect(shellRoot()).toHaveClass('wf-ask-bar-on');
    fireEvent.click(tab('Map'));
    expect(shellRoot()).not.toHaveClass('wf-ask-bar-on');
    fireEvent.click(tab('Operations'));
    expect(shellRoot()).not.toHaveClass('wf-ask-bar-on');
  });

  it('wears the classes the stylesheet styles it by (a renamed class is an unstyled, in-flow bar)', async () => {
    renderAskShell({ width: 390 });
    expect(await barReady()).toHaveClass('wf-ask-bar');
  });

  it('is not drawn, and reserves nothing, when Ask is off or not yet known', async () => {
    getAskSettings.mockReturnValue(new Promise(() => {}));
    renderAskShell({ width: 390, askSettings: false });
    await act(async () => {});
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    expect(shellRoot()).not.toHaveClass('wf-ask-bar-on');
  });

  it('says it opens a dialog, and whether one is open', async () => {
    renderAskShell({ width: 390 });
    expect(await barReady()).toHaveAttribute('aria-haspopup', 'dialog');
    expect(bar()).toHaveAttribute('aria-expanded', 'false');
    await openSheet();
    expect(bar()).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('the tablet field (640 to 1023px)', () => {
  it.each(['Plan', 'Coming up', 'Map'])('is drawn on %s', async (name) => {
    renderAskShell({ width: 800, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await fieldReady();
    fireEvent.click(tab(name));
    expect(field()).toBeEnabled();
  });

  it('is not drawn on Operations', async () => {
    renderAskShell({ width: 800, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await fieldReady();
    fireEvent.click(tab('Operations'));
    expect(screen.queryByTestId('ask-field')).toBeNull();
  });

  it('sits BESIDE the tab list, never inside it', async () => {
    renderAskShell({ width: 800, props: { mapPane: MAP_PANE } });
    await fieldReady();
    const tablist = screen.getByRole('tablist');
    expect(tablist).not.toContainElement(field());
    expect(field().parentElement).toBe(tablist.parentElement);
    // The roving-tab-index widget holds exactly its tabs and nothing of Ask's.
    expect(within(tablist).getAllByRole('tab').map((t) => t.textContent))
      .toEqual(['◉ Plan', 'Coming up', '◍ Map']);
    expect(within(tablist).queryByRole('button', { name: /ask/i })).toBeNull();
  });

  it('is the 260px form with no key hint (the "/" is F2\'s)', async () => {
    renderAskShell({ width: 800 });
    expect(await fieldReady()).toHaveAttribute('data-width', '260');
    expect(field()).toHaveClass('wf-askf');
    expect(field()).not.toHaveAttribute('aria-keyshortcuts');
    expect(within(field()).queryByText('/')).toBeNull();
  });

  it.each([
    [3, { mapPane: MAP_PANE }],
    [4, { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE }],
  ])('records the tab count (%i), which is what collapses it beside four tabs', async (count, props) => {
    // Both counts: a hard-coded 4 would collapse every field under 720px.
    renderAskShell({ width: 800, props });
    await fieldReady();
    expect(screen.getByTestId('window-first-tabrow')).toHaveAttribute('data-tab-count', String(count));
    expect(screen.getByTestId('window-first-tabrow')).toHaveClass('wf-tabrow');
  });

  it('holds the field\'s width on Operations, so the tab pinned to the right edge does not slide', async () => {
    renderAskShell({ width: 800, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await fieldReady();
    expect(screen.queryByTestId('ask-field-ghost')).toBeNull();
    fireEvent.click(tab('Operations'));
    const ghost = screen.getByTestId('ask-field-ghost');
    expect(ghost).toHaveClass('wf-askf');
    expect(screen.getByTestId('window-first-tabrow')).toContainElement(ghost);
    // A box, never a control: nothing to focus, nothing to name.
    expect(ghost).toHaveAttribute('aria-hidden', 'true');
    expect(ghost.tagName).toBe('DIV');
    expect(screen.queryByTestId('ask-field')).toBeNull();
  });

  it('holds nothing on the phone, which has no field to hold the width of', async () => {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await barReady();
    fireEvent.click(tab('Operations'));
    expect(screen.queryByTestId('ask-field-ghost')).toBeNull();
  });

  it('draws no phone bar', async () => {
    renderAskShell({ width: 800 });
    await fieldReady();
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    expect(shellRoot()).not.toHaveClass('wf-ask-bar-on');
  });

  it('opens the sheet from the Map tab, labelled with what is sent', async () => {
    renderAskShell({ width: 800, props: { mapPane: MAP_PANE } });
    await fieldReady();
    fireEvent.click(tab('Map'));
    fireEvent.click(field());
    expect(await screen.findByRole('dialog', { name: 'Ask PhotoCast' })).toBeInTheDocument();
    expect(screen.getByTestId('ask-context')).toHaveTextContent('Map · all regions');
  });
});

describe('the breakpoints', () => {
  it.each([
    [639, 'ask-bar', 'ask-field'],
    [640, 'ask-field', 'ask-bar'],
    [1023, 'ask-field', 'ask-bar'],
  ])('at %i px the %s is drawn and the %s is not', async (width, drawn, absent) => {
    renderAskShell({ width });
    expect(await screen.findByTestId(drawn)).toBeInTheDocument();
    expect(screen.queryByTestId(absent)).toBeNull();
  });

  it('at 1024 px neither is drawn: the desktop dock and its field are F2\'s', async () => {
    renderAskShell({ width: 1024 });
    // Ask has resolved to "on" by the time the settings read has settled; the absence is then real.
    await waitFor(() => expect(getAskSettings).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    expect(screen.queryByTestId('ask-field')).toBeNull();
  });

  it('swaps the bar for the field when the window is widened across 640', async () => {
    const { viewport } = renderAskShell({ width: 639 });
    await barReady();
    act(() => viewport.resize(640));
    expect(field()).toBeInTheDocument();
    expect(screen.queryByTestId('ask-bar')).toBeNull();
  });
});

describe('availability: whether Ask exists at all', () => {
  it('draws nothing while the first settings read is still out ("pending")', async () => {
    const pending = deferred();
    getAskSettings.mockReturnValue(pending.promise);
    renderAskShell({ width: 390, askSettings: false });
    await act(async () => {});
    expect(screen.queryByTestId('ask-bar')).toBeNull();
  });

  it('draws nothing when the settings say Ask is off', async () => {
    renderAskShell({ width: 390, askSettings: settings({ enabled: false, used: 0, limit: 0, left: 0 }) });
    await waitFor(() => expect(getAskSettings).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
  });

  it('draws the bar DISABLED when the first read failed and nothing is known ("down")', async () => {
    getAskSettings.mockRejectedValue(new Error('network'));
    renderAskShell({ width: 390, askSettings: false });
    expect(await barReady()).toBeDisabled();
  });

  it('draws the bar enabled when Ask is on', async () => {
    renderAskShell({ width: 390 });
    expect(await barReady()).toBeEnabled();
  });

  it('disables the field the same way when the backend is down', async () => {
    renderAskShell({ width: 800, props: { contentDisabled: true } });
    expect(await fieldReady()).toBeDisabled();
  });

  it('disables the field when the first read failed ("down"), as it does the bar', async () => {
    getAskSettings.mockRejectedValue(new Error('network'));
    renderAskShell({ width: 800, askSettings: false });
    expect(await fieldReady()).toBeDisabled();
  });

  it('disables the bar when the backend is down (contentDisabled), and pressing it opens nothing', async () => {
    renderAskShell({ width: 390, props: { contentDisabled: true } });
    fireEvent.click(await barReady());
    expect(bar()).toBeDisabled();
    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
  });

  it('draws nothing at all when no provider is mounted (the default context is "off")', async () => {
    // This is the rewound page: `App` does not mount the provider, so every surface is absent and
    // nothing reads the settings endpoint.
    installViewport(390);
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx());
    render(<WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()} />);
    await act(async () => {});
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    expect(getAskSettings).not.toHaveBeenCalled();
  });
});

describe('the sheet', () => {
  it('opens as a modal dialog named "Ask PhotoCast"', async () => {
    renderAskShell({ width: 390 });
    await openSheet();
    expect(sheet()).toHaveAttribute('aria-modal', 'true');
  });

  it('is THE single modal: exactly one aria-modal element is in the document', async () => {
    renderAskShell({ width: 390 });
    expect(modalCount()).toBe(0);
    await openSheet();
    expect(modalCount()).toBe(1);
  });

  it('makes the whole app container inert while it is open — banners and footer included — and only then', async () => {
    renderAskShell({ width: 390 });
    await barReady();
    expect(appContainer()).not.toHaveAttribute('inert');
    await openSheet();
    expect(appContainer()).toHaveAttribute('inert');
    // The container holds the shell and everything the app draws around it...
    expect(appContainer()).toContainElement(shellRoot());
    // ...and NOT the sheet, which is portalled to a sibling of it under <body>.
    expect(appContainer()).not.toContainElement(sheet());
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    expect(appContainer()).not.toHaveAttribute('inert');
  });

  it('takes the inert off again when the shell goes (a sign-out, a rewind), so nothing is left dead', async () => {
    const { unmount } = renderAskShell({ width: 390 });
    await openSheet();
    const container = appContainer();
    expect(container).toHaveAttribute('inert');
    unmount();
    expect(container).not.toHaveAttribute('inert');
  });

  it('is the tall size, as a marker the stylesheet and the keyboard-follow read', async () => {
    renderAskShell({ width: 390 });
    await openSheet();
    expect(sheet()).toHaveAttribute('data-size', 'tall');
  });

  it('puts focus in the question field when it opens, and it STAYS there a frame later', async () => {
    renderAskShell({ width: 390 });
    await openSheet();
    await waitFor(() => expect(screen.getByTestId('ask-input')).toHaveFocus());
    // `useDialogFocus` moves focus to the dialog root a frame later unless something inside already
    // holds it; `waitFor` above would pass before that frame, so force one and assert after it.
    await new Promise((resolve) => { requestAnimationFrame(() => requestAnimationFrame(resolve)); });
    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });

  it('wears the classes the stylesheet styles the question field and its row by', async () => {
    renderAskShell({ width: 390 });
    await openSheet();
    expect(screen.getByTestId('ask-input')).toHaveClass('wf-ask-in-field');
    expect(screen.getByTestId('ask-input-row')).toHaveClass('wf-ask-in');
    expect(screen.getByTestId('ask-send')).toHaveClass('wf-ask-in-go');
  });

  it('leaves focus on the dialog, not on a field that can take no text, when typed questions are off', async () => {
    renderAskShell({ width: 390, askSettings: settings({ left: 0, used: 3 }) });
    await openSheet();
    await waitFor(() => expect(sheet()).toHaveFocus());
    await new Promise((resolve) => { requestAnimationFrame(() => requestAnimationFrame(resolve)); });
    expect(sheet()).toHaveFocus();
    expect(screen.getByTestId('ask-input')).not.toHaveFocus();
  });

  it('offers the Ready questions of the tab it was opened from, and the Plan view chip', async () => {
    renderAskShell({ width: 390 });
    await openSheet();
    expect(await screen.findByTestId('ask-ready-BEST_NEXT')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-ready-RARE_EVENTS')).toBeNull();
    expect(screen.getByTestId('ask-context')).toHaveTextContent('Plan · all regions');
    expect(screen.queryByTestId('ask-chip-window')).toBeNull();
  });

  it('is labelled for Coming up when opened there', async () => {
    renderAskShell({ width: 390 });
    await barReady();
    fireEvent.click(tab('Coming up'));
    await openSheet();
    expect(screen.getByTestId('ask-context')).toHaveTextContent('Coming up · all regions');
    expect(await screen.findByTestId('ask-ready-RARE_EVENTS')).toBeInTheDocument();
  });

  describe('closing it', () => {
    it('✕ closes it and returns focus to the bar', async () => {
      renderAskShell({ width: 390 });
      await openSheet();
      fireEvent.click(screen.getByTestId('bottom-sheet-close'));
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      await waitFor(() => expect(bar()).toHaveFocus());
    });

    it('Escape closes it and returns focus to the bar', async () => {
      renderAskShell({ width: 390 });
      await openSheet();
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      await waitFor(() => expect(bar()).toHaveFocus());
    });

    it('the scrim closes it', async () => {
      renderAskShell({ width: 390 });
      await openSheet();
      fireEvent.click(screen.getByTestId('bottom-sheet-overlay'));
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
    });

    it('returns focus to the bar when the bar already held it (a keyboard open)', async () => {
      renderAskShell({ width: 390 });
      await barReady();
      bar().focus();
      fireEvent.click(bar());
      await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
      fireEvent.keyDown(document, { key: 'Escape' });
      await waitFor(() => expect(bar()).toHaveFocus());
    });

    it('returns focus to the tablet field, which is the opener there', async () => {
      renderAskShell({ width: 800 });
      fireEvent.click(await fieldReady());
      await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
      fireEvent.keyDown(document, { key: 'Escape' });
      await waitFor(() => expect(field()).toHaveFocus());
    });

    it.each([
      ['the ✕', () => fireEvent.click(screen.getByTestId('bottom-sheet-close'))],
      ['the scrim', () => fireEvent.click(screen.getByTestId('bottom-sheet-overlay'))],
      ['Escape', () => fireEvent.keyDown(document, { key: 'Escape' })],
    ])('%s only CLOSES it: the answer is still there when it reopens', async (_name, close) => {
      // The reader paid a question for that answer; a mis-tap on a full-screen scrim must not bin it.
      renderAskShell({ width: 390 });
      await openSheet();
      fireEvent.click(await screen.findByTestId('ask-ready-BEST_NEXT'));
      await screen.findByTestId('ask-picks');
      close();
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      expect(bar()).toHaveAttribute('aria-expanded', 'false');

      await openSheet();
      expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
      expect(screen.getByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
    });

    it('keeps a question that is still out, and its answer lands for the reopened sheet', async () => {
      const pending = deferred();
      ask.mockReturnValue(pending.promise);
      renderAskShell({ width: 390 });
      await openSheet();
      await userEvent.type(screen.getByTestId('ask-input'), 'Best at dawn?{Enter}');
      await screen.findByTestId('ask-busy');

      fireEvent.click(screen.getByTestId('bottom-sheet-close'));
      await openSheet();
      expect(screen.getByTestId('ask-busy')).toBeInTheDocument();

      await act(async () => { pending.resolve(ownResponse()); });
      expect(await screen.findByTestId('ask-picks')).toBeInTheDocument();
    });
  });

  describe('"Clear answer"', () => {
    /** Opens the sheet on a finished Ready answer. */
    async function answered() {
      renderAskShell({ width: 390 });
      await openSheet();
      fireEvent.click(await screen.findByTestId('ask-ready-BEST_NEXT'));
      await screen.findByTestId('ask-picks');
    }

    it('is the way back to the Ready suggestions, which only the empty state shows', async () => {
      await answered();
      expect(screen.queryByTestId('ask-ready-list')).toBeNull();
      fireEvent.click(screen.getByRole('button', { name: 'Clear answer' }));
      expect(await screen.findByTestId('ask-ready-list')).toBeInTheDocument();
      expect(screen.queryByTestId('ask-picks')).toBeNull();
    });

    it('moves focus to the question field first, because the button goes when it is pressed', async () => {
      await answered();
      const clear = screen.getByTestId('ask-clear');
      clear.focus();
      fireEvent.click(clear);
      expect(screen.queryByTestId('ask-clear')).toBeNull();
      expect(screen.getByTestId('ask-input')).toHaveFocus();
    });

    it('is drawn for an answer, a not-in-the-forecast reply and a failure — and for nothing else', async () => {
      renderAskShell({ width: 390 });
      await openSheet();
      expect(screen.queryByTestId('ask-clear')).toBeNull();

      ask.mockRejectedValueOnce(new AskApiError({ status: 502, code: 'ENGINE_FAILED', error: 'Couldn’t answer just now. No question used.' }));
      await userEvent.type(screen.getByTestId('ask-input'), 'Best at dawn?{Enter}');
      await screen.findByTestId('ask-error');
      expect(screen.getByTestId('ask-clear')).toBeInTheDocument();

      ask.mockResolvedValueOnce(cantResponse());
      await userEvent.type(screen.getByTestId('ask-input'), 'Is there parking?{Enter}');
      await screen.findByTestId('ask-cant');
      expect(screen.getByTestId('ask-clear')).toBeInTheDocument();
    });

    it('is not drawn while a question is out: clearing then would drop a charged answer', async () => {
      const pending = deferred();
      ask.mockReturnValue(pending.promise);
      renderAskShell({ width: 390 });
      await openSheet();
      await userEvent.type(screen.getByTestId('ask-input'), 'Best at dawn?{Enter}');
      await screen.findByTestId('ask-busy');
      expect(screen.queryByTestId('ask-clear')).toBeNull();
      await act(async () => { pending.resolve(ownResponse()); });
    });
  });

  describe('it cannot come back by itself', () => {
    it('does not reopen when the window is widened past 1023px and narrowed again', async () => {
      const { viewport } = renderAskShell({ width: 800 });
      fireEvent.click(await fieldReady());
      await screen.findByRole('dialog', { name: 'Ask PhotoCast' });

      act(() => viewport.resize(1024));
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      expect(appContainer()).not.toHaveAttribute('inert');

      act(() => viewport.resize(800));
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      expect(field()).toHaveAttribute('aria-expanded', 'false');
    });

    it('does not reopen when the tablet narrows to a phone on the Map (where there is no entry) and widens', async () => {
      const { viewport } = renderAskShell({ width: 800, props: { mapPane: MAP_PANE } });
      await fieldReady();
      fireEvent.click(tab('Map'));
      fireEvent.click(field());
      await screen.findByRole('dialog', { name: 'Ask PhotoCast' });

      act(() => viewport.resize(390));
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      act(() => viewport.resize(800));
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
    });

    it('lands focus on the tab in force when the entry that opened it has gone, never on <body>', async () => {
      // The bar unmounts with the sheet (the window crosses 1023px), so there is no opener to return to.
      const { viewport } = renderAskShell({ width: 390 });
      await openSheet();
      await waitFor(() => expect(screen.getByTestId('ask-input')).toHaveFocus());

      act(() => viewport.resize(1024));

      expect(screen.queryByTestId('ask-bar')).toBeNull();
      await waitFor(() => expect(tab('Plan')).toHaveFocus());
    });

    it('moves to the other entry when the phone is widened with the sheet open, and closes by it', async () => {
      const { viewport } = renderAskShell({ width: 600 });
      await openSheet();
      act(() => viewport.resize(700));
      // The opener is now the field; the sheet is still the one modal and still answers Escape.
      expect(screen.getByRole('dialog', { name: 'Ask PhotoCast' })).toBeInTheDocument();
      fireEvent.keyDown(document, { key: 'Escape' });
      await waitFor(() => expect(field()).toHaveFocus());
    });
  });

  describe('a tab change closes it, by every route', () => {
    it('pressing a tab', async () => {
      renderAskShell({ width: 390 });
      await openSheet();
      fireEvent.click(tab('Coming up'));
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      expect(modalCount()).toBe(0);
      expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
    });

    it('a tab asked for from outside the bar (the shell\'s tabRequest)', async () => {
      const { rerenderShell } = renderAskShell({ width: 390, props: { mapPane: MAP_PANE } });
      await openSheet();
      rerenderShell({ tabRequest: { id: 'map', nonce: 1 } });
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    });

    it('settings opening (the settings edge calls selectTab, naming the tab in force)', async () => {
      const { rerenderShell } = renderAskShell({ width: 390 });
      await openSheet();
      rerenderShell({ settingsOpen: true });
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    });

    it('mid-request: the sheet closes, the answer still lands, and it is waiting on return', async () => {
      const pending = deferred();
      ask.mockReturnValue(pending.promise);
      renderAskShell({ width: 390 });
      await openSheet();
      await userEvent.type(screen.getByTestId('ask-input'), 'Best at dawn?{Enter}');
      await screen.findByTestId('ask-busy');

      fireEvent.click(tab('Coming up'));
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      await act(async () => { pending.resolve(ownResponse()); });
      fireEvent.click(tab('Plan'));

      await openSheet();
      expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
    });

    it('keeps the conversation: the answer is there when the reader comes back', async () => {
      renderAskShell({ width: 390 });
      await openSheet();
      fireEvent.click(await screen.findByTestId('ask-ready-BEST_NEXT'));
      await screen.findByTestId('ask-picks');
      fireEvent.click(tab('Coming up'));
      fireEvent.click(tab('Plan'));

      await openSheet();
      expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
    });
  });
});

describe('"Show on map ›"', () => {
  /** Opens the sheet and lets Whitby\'s and Saltburn\'s Ready answer land. */
  async function openAnswer(options = {}) {
    renderAskShell({ width: 390, props: { mapPane: MAP_PANE }, ...options });
    await openSheet();
    fireEvent.click(await screen.findByTestId('ask-ready-BEST_NEXT'));
    await screen.findByTestId('ask-picks');
  }

  it('is offered on every pick, named for the place', async () => {
    await openAnswer();
    expect(screen.getByRole('button', { name: 'Show on map — Whitby' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show on map — Saltburn' })).toBeInTheDocument();
  });

  it('closes the sheet and moves to the Map tab', async () => {
    await openAnswer();
    fireEvent.click(screen.getByTestId('ask-show-on-map-1'));
    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
    expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('window-first-panel-map')).not.toHaveAttribute('hidden');
    expect(modalCount()).toBe(0);
  });

  it('puts focus on the Map tab, not on <body>, because the bar that opened the sheet is gone', async () => {
    await openAnswer();
    fireEvent.click(screen.getByTestId('ask-show-on-map-1'));
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    await waitFor(() => expect(tab('Map')).toHaveFocus());
  });

  it('keeps the answer: back on Plan the sheet reopens on it', async () => {
    await openAnswer();
    fireEvent.click(screen.getByTestId('ask-show-on-map-1'));
    fireEvent.click(tab('Plan'));

    await openSheet();
    expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
  });

  it('selects the pick that was pressed, so the Map linkage can start from it', async () => {
    await openAnswer();
    expect(screen.getByTestId('ask-pick-select-2')).not.toHaveAttribute('aria-current');
    fireEvent.click(screen.getByTestId('ask-show-on-map-2'));
    fireEvent.click(tab('Plan'));

    await openSheet();
    expect(screen.getByTestId('ask-pick-select-2')).toHaveAttribute('aria-current', 'true');
    expect(screen.getByTestId('ask-pick-select-1')).not.toHaveAttribute('aria-current');
  });

  it('is not offered when the shell was handed no Map pane', async () => {
    await openAnswer({ props: {} });
    expect(screen.queryByTestId('ask-show-on-map-1')).toBeNull();
  });

  it('is not offered on the Map tab itself, where there is nowhere to go (tablet)', async () => {
    renderAskShell({ width: 800, props: { mapPane: MAP_PANE } });
    await fieldReady();
    fireEvent.click(tab('Map'));
    fireEvent.click(field());
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    fireEvent.click(await screen.findByTestId('ask-ready-COASTAL_HIGH'));
    await screen.findByTestId('ask-picks');
    expect(screen.queryByTestId('ask-show-on-map-1')).toBeNull();
  });

  it('on the tablet\'s Map the field reopens the sheet with the answer retained', async () => {
    renderAskShell({ width: 800, props: { mapPane: MAP_PANE } });
    fireEvent.click(await fieldReady());
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    fireEvent.click(await screen.findByTestId('ask-ready-BEST_NEXT'));
    await screen.findByTestId('ask-picks');
    fireEvent.click(screen.getByTestId('ask-show-on-map-1'));
    expect(tab('Map')).toHaveAttribute('aria-selected', 'true');

    fireEvent.click(field());
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
  });
});

describe('the question field', () => {
  async function openTyping() {
    renderAskShell({ width: 390 });
    await openSheet();
    return userEvent.setup();
  }

  it('submits on Enter, as a typed question for this view with no region and no window', async () => {
    ask.mockResolvedValue(ownResponse());
    const user = await openTyping();
    await user.type(screen.getByTestId('ask-input'), 'Best at dawn?{Enter}');
    expect(ask).toHaveBeenCalledTimes(1);
    expect(ask).toHaveBeenCalledWith({ question: 'Best at dawn?', regionIds: [], view: 'plan' });
    expect(await screen.findByTestId('ask-picks')).toBeInTheDocument();
  });

  it('submits on the ↑ button too', async () => {
    ask.mockResolvedValue(ownResponse());
    const user = await openTyping();
    await user.type(screen.getByTestId('ask-input'), 'Best at dawn?');
    await user.click(screen.getByRole('button', { name: 'Send question' }));
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('sends the Coming up view from Coming up', async () => {
    ask.mockResolvedValue(ownResponse());
    renderAskShell({ width: 390 });
    await barReady();
    fireEvent.click(tab('Coming up'));
    await openSheet();
    await userEvent.type(screen.getByTestId('ask-input'), 'Any rare events?{Enter}');
    expect(ask).toHaveBeenCalledWith({ question: 'Any rare events?', regionIds: [], view: 'coming-up' });
  });

  it('clears itself once the question is on its way', async () => {
    ask.mockResolvedValue(ownResponse());
    const user = await openTyping();
    await user.type(screen.getByTestId('ask-input'), 'Best at dawn?{Enter}');
    await screen.findByTestId('ask-picks');
    expect(screen.getByTestId('ask-input')).toHaveValue('');
  });

  it('never submits while a question is out: a second Enter does nothing', async () => {
    const pending = deferred();
    ask.mockReturnValue(pending.promise);
    const user = await openTyping();
    await user.type(screen.getByTestId('ask-input'), 'First?{Enter}');
    await screen.findByTestId('ask-busy');

    await user.type(screen.getByTestId('ask-input'), 'Second?{Enter}');
    await user.click(screen.getByRole('button', { name: 'Send question' }));

    expect(ask).toHaveBeenCalledTimes(1);
    // The second question was kept in the field: it was neither sent nor thrown away.
    expect(screen.getByTestId('ask-input')).toHaveValue('Second?');
    await act(async () => { pending.resolve(ownResponse()); });
  });

  it('says the send button is unavailable while empty or busy, and it is never `disabled`', async () => {
    const pending = deferred();
    ask.mockReturnValue(pending.promise);
    const user = await openTyping();
    const send = screen.getByRole('button', { name: 'Send question' });
    expect(send).toHaveAttribute('aria-disabled', 'true');
    expect(send).not.toBeDisabled();
    await user.type(screen.getByTestId('ask-input'), 'First?');
    expect(send).not.toHaveAttribute('aria-disabled');
    await user.click(send);
    await screen.findByTestId('ask-busy');
    expect(send).toHaveAttribute('aria-disabled', 'true');
    expect(send).not.toBeDisabled();
    await act(async () => { pending.resolve(ownResponse()); });
  });

  it('keeps focus in the field through a submit, so the keyboard stays up', async () => {
    ask.mockResolvedValue(ownResponse());
    const user = await openTyping();
    await user.type(screen.getByTestId('ask-input'), 'Best at dawn?{Enter}');
    await screen.findByTestId('ask-picks');
    expect(screen.getByTestId('ask-input')).toHaveFocus();
  });

  it('ignores a blank question', async () => {
    const user = await openTyping();
    await user.type(screen.getByTestId('ask-input'), '   {Enter}');
    expect(ask).not.toHaveBeenCalled();
  });

  it('puts the text back when the server refuses the question, so a typo is a correction', async () => {
    ask.mockRejectedValue(new AskApiError({ status: 400, code: 'INVALID', error: 'Use letters and numbers only.' }));
    const user = await openTyping();
    await user.type(screen.getByTestId('ask-input'), 'Best @ dawn?{Enter}');
    expect(await screen.findByTestId('ask-input-error')).toHaveTextContent('Use letters and numbers only.');
    expect(screen.getByTestId('ask-input')).toHaveValue('Best @ dawn?');
  });

  it('renders the refusal once: the field does not repeat what the conversation says', async () => {
    ask.mockRejectedValue(new AskApiError({ status: 429, code: 'RATE_LIMITED', error: 'Slow down a moment.' }));
    const user = await openTyping();
    await user.type(screen.getByTestId('ask-input'), 'Quick?{Enter}');
    await screen.findByTestId('ask-input-error');
    expect(screen.getAllByText('Slow down a moment.')).toHaveLength(1);
  });

  it('asks the keyboard for a send key and stops at the server\'s 200 characters', async () => {
    // The 16px that stops iOS zooming is the stylesheet's (`askCss.test.js` pins it, and the class
    // test in "the sheet" pins that this input wears it).
    await openTyping();
    expect(screen.getByTestId('ask-input')).toHaveAttribute('enterkeyhint', 'send');
    expect(screen.getByTestId('ask-input')).toHaveAttribute('maxlength', '200');
  });

  it('is named for what it is, in the words its placeholder shows', async () => {
    await openTyping();
    expect(screen.getByRole('textbox', { name: 'Ask about the forecasts' })).toBe(screen.getByTestId('ask-input'));
    expect(screen.getByTestId('ask-input')).toHaveAttribute('placeholder', 'Ask about the forecasts…');
  });

  describe('when typed questions are off (state 7)', () => {
    it('reads "Ready questions only today", takes no text and says it is disabled', async () => {
      renderAskShell({ width: 390, askSettings: settings({ left: 0, used: 3 }) });
      await openSheet();
      const input = screen.getByTestId('ask-input');
      expect(input).toHaveAttribute('placeholder', READY_ONLY_PLACEHOLDER);
      expect(input).toHaveAttribute('readonly');
      expect(input).toHaveAttribute('aria-disabled', 'true');
      // The reason is also said as a sentence the field is described by: a placeholder is not
      // reliably announced on a read-only field.
      expect(input).toHaveAccessibleDescription(READY_ONLY_PLACEHOLDER);
      await userEvent.type(input, 'hello{Enter}');
      expect(input).toHaveValue('');
      expect(ask).not.toHaveBeenCalled();
    });

    it('still opens Ready answers', async () => {
      renderAskShell({ width: 390, askSettings: settings({ left: 0, used: 3 }) });
      await openSheet();
      fireEvent.click(await screen.findByTestId('ask-ready-BEST_NEXT'));
      expect(await screen.findByTestId('ask-picks')).toBeInTheDocument();
      expect(ask).not.toHaveBeenCalled();
    });

    it('when it flips while the reader is typing, focus stays on the field instead of falling to <body>', async () => {
      ask.mockRejectedValue(new AskApiError({
        status: 429, code: 'ALLOWANCE_EXHAUSTED', error: 'You have used today\'s questions.',
      }));
      renderAskShell({ width: 390 });
      await openSheet();
      const input = screen.getByTestId('ask-input');
      await waitFor(() => expect(input).toHaveFocus());
      await userEvent.type(input, 'One more?{Enter}');

      await waitFor(() => expect(input).toHaveAttribute('readonly'));
      expect(input).toHaveAttribute('placeholder', READY_ONLY_PLACEHOLDER);
      // `not.toBeDisabled()` is the half jsdom cannot see for itself: it keeps focus on a control
      // that has become `disabled`, where every browser drops it to <body>.
      expect(input).not.toBeDisabled();
      expect(input).toHaveFocus();
    });
  });
});
