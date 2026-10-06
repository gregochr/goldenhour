import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen, waitFor, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AskApiError } from '../api/askApi.js';
import { resetViewport } from './askViewport.js';
import {
  MAP_PANE, matrixCtx, renderAskShell, TODAY,
} from './askShellHarness.jsx';
import {
  NOW, ownResponse, pick, readyResponse, SALTBURN, settings,
} from './askFixtures.js';

/**
 * "Plan this" and "Open in Plan ›" as the shell wires them on the dock, the tablet's sheet and the
 * phone's sheet (F5, plan §2.8) — the phone Map's peek is on the real chain in
 * {@code AskPeekChain.test.jsx}: the second seam (the two doors out of the plan view), the two-deep rule
 * on each host, focus both ways across the location sheet, the Plan-card highlight's liveness, and a tab
 * switch that keeps the plan.
 *
 * <p>jsdom has no layout and no {@code inert}: what is asserted is the attribute the shell sets and the
 * behaviour it has, never a browser's refusal to focus an inert node. The lazy matrix and location sheet
 * are real, so each file pays that boundary once.
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

const TOMORROW = '2026-10-06';

const tab = (name) => screen.getByRole('tab', { name });
const modalCount = () => document.querySelectorAll('[aria-modal="true"]').length;
const dock = () => screen.queryByTestId('ask-dock');
const askSheet = () => screen.queryByRole('dialog', { name: 'Ask PhotoCast' });
const locationSheet = () => screen.queryByTestId('location-sheet');
const heatCard = (key) => screen.queryAllByTestId('wf-heat-card')
  .find((el) => el.getAttribute('aria-labelledby')?.endsWith(key.replace(/:/g, '-')));
const highlighted = () => screen.queryAllByTestId('wf-heat-card')
  .filter((card) => card.getAttribute('data-ask-highlight') === 'true');
const sheetRows = () => screen.getAllByTestId('location-sheet-row');
const rowToggle = (row) => within(row).getByTestId('location-sheet-row-toggle');
/** The window key of the one row the location sheet has OPEN, or undefined. */
const openRowKey = () => sheetRows().find((row) => rowToggle(row).getAttribute('aria-expanded') === 'true')?.dataset.window;
const rowKeyed = (key) => sheetRows().find((row) => row.dataset.window === key);
/** A dialog this shell does not own, as the map overlay or settings is. */
function foreignDialog(label = 'Something else') {
  const node = document.createElement('div');
  node.setAttribute('role', 'dialog');
  node.setAttribute('aria-modal', 'true');
  node.setAttribute('aria-label', label);
  node.setAttribute('data-foreign-dialog', '');
  document.body.appendChild(node);
  return node;
}

/** The matrix context with BOTH of the fixture briefing's windows as cards, so a pick can be elsewhere. */
function planCtx() {
  const base = matrixCtx();
  const [tonight] = base.heatStripCards;
  const tomorrow = {
    ...tonight,
    key: `${TOMORROW}:SUNRISE`,
    date: TOMORROW,
    targetType: 'SUNRISE',
    dow: 'Tue',
    sunrise: true,
    label: 'Tomorrow Sunrise',
    time: '06:58',
  };
  return { ...base, heatStripCards: [tonight, tomorrow], scoreRows: [] };
}

/** Whitby tomorrow's sunrise first, Saltburn tonight second — two different windows. */
const answer = () => ownResponse({
  picks: [
    pick({
      rank: 1, date: TOMORROW, targetType: 'SUNRISE', windowId: `${TOMORROW}_sunrise`,
    }),
    pick({
      rank: 2, locationId: SALTBURN, locationName: 'Saltburn', windowId: '2026-10-05_sunset',
    }),
  ],
  events: [],
});

const askOne = async (response = answer()) => {
  ask.mockResolvedValue(response);
  await userEvent.type(screen.getByTestId('ask-input'), 'Where is good?{Enter}');
  return screen.findByTestId('ask-picks');
};
const planThis = (rank) => act(async () => { fireEvent.click(screen.getByTestId(`ask-plan-this-${rank}`)); });

/** The dock, with an answer, on Plan. */
async function dockWithAnswer({
  width = 1280, props = {}, response, ctx = {},
} = {}) {
  const view = renderAskShell({ width, ctx: { ...planCtx(), ...ctx }, props });
  fireEvent.click(await screen.findByTestId('ask-field'));
  await screen.findByTestId('ask-dock');
  await askOne(response);
  return view;
}

/** The phone's sheet, with an answer, on Plan. */
async function sheetWithAnswer({ width = 390, props = {}, response } = {}) {
  const view = renderAskShell({ width, ctx: planCtx(), props });
  fireEvent.click(await screen.findByTestId(width < 640 ? 'ask-bar' : 'ask-field'));
  await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
  await askOne(response);
  return view;
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

describe('on the dock (1024px and up)', () => {
  it('"Plan this ›" is on every card and opens that pick’s plan inside the dock', async () => {
    await dockWithAnswer();

    expect(screen.getByTestId('ask-plan-this-1')).toBeInTheDocument();
    expect(screen.getByTestId('ask-plan-this-2')).toBeInTheDocument();
    await planThis(1);

    expect(within(dock()).getByTestId('ask-plan')).toBeInTheDocument();
    expect(within(dock()).getByTestId('ask-plan-name')).toHaveTextContent('Whitby');
    // The question field stays below it: asking again is one tap away.
    expect(within(dock()).getByTestId('ask-input')).toBeInTheDocument();
  });

  it('"Open in Plan ›" opens the location sheet at the PICK’s window, as the one modal — the dock stays and goes inert', async () => {
    await dockWithAnswer();
    await planThis(1);

    fireEvent.click(screen.getByTestId('ask-plan-open'));

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Whitby');
    // Pick 1 is TOMORROW's sunrise: that row is the open one, not the sheet's own first/best row.
    expect(openRowKey()).toBe(`${TOMORROW}:SUNRISE`);
    expect(rowToggle(rowKeyed(`${TODAY}:SUNSET`))).toHaveAttribute('aria-expanded', 'false');
    // Two-deep: one modal, the dock beside it dead rather than a second layer.
    expect(modalCount()).toBe(1);
    expect(dock()).toBeInTheDocument();
    expect(dock()).toHaveAttribute('inert');
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
  });

  it('from the MAP tab it moves to Plan, and the dock stays open beside it', async () => {
    await dockWithAnswer({ props: { mapPane: MAP_PANE } });
    fireEvent.click(tab('Map'));
    expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    await planThis(2);

    fireEvent.click(screen.getByTestId('ask-plan-open'));

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    expect(dock()).toBeInTheDocument();
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Saltburn');
    // Pick 2 is Saltburn TONIGHT: the window comes from the pick, whichever tab it was pressed on.
    expect(openRowKey()).toBe(`${TODAY}:SUNSET`);
    expect(modalCount()).toBe(1);
  });

  it('the way OUT: closing the location sheet returns focus to the dock’s "Open in Plan ›", never <body>', async () => {
    await dockWithAnswer();
    await planThis(1);
    const open = screen.getByTestId('ask-plan-open');
    open.focus();
    fireEvent.click(open);
    await screen.findByTestId('location-sheet');
    // The sheet takes focus for itself (a frame later); the dock went inert, so nothing in it holds it.
    await waitFor(() => expect(document.activeElement).toBe(locationSheet()));
    expect(dock()).toHaveAttribute('inert');

    fireEvent.click(screen.getByTestId('location-sheet-close'));

    await waitFor(() => expect(locationSheet()).toBeNull());
    expect(dock()).not.toHaveAttribute('inert');
    expect(document.activeElement).toBe(screen.getByTestId('ask-plan-open'));
    expect(document.activeElement).not.toBe(document.body);
  });

  it('the way OUT after a mouse press that focused nothing (Safari, Firefox): still the dock’s button, not <body>', async () => {
    await dockWithAnswer();
    await planThis(1);
    // A click with no focus() first: `activeElement` is <body>, as it is for a mouse press on a button there.
    expect(document.activeElement).not.toBe(screen.getByTestId('ask-plan-open'));
    fireEvent.click(screen.getByTestId('ask-plan-open'));
    await screen.findByTestId('location-sheet');

    fireEvent.click(screen.getByTestId('location-sheet-close'));
    await waitFor(() => expect(locationSheet()).toBeNull());

    expect(document.activeElement).toBe(screen.getByTestId('ask-plan-open'));
  });

  it('the way out never yanks a reader who arrowed to another tab (which closes the sheet too)', async () => {
    await dockWithAnswer({ props: { mapPane: MAP_PANE } });
    await planThis(1);
    const open = screen.getByTestId('ask-plan-open');
    open.focus();
    fireEvent.click(open);
    await screen.findByTestId('location-sheet');

    // ArrowRight from the Plan tab selects the next tab, focuses it and takes the dialogs down.
    fireEvent.keyDown(tab('Plan'), { key: 'ArrowRight' });

    await waitFor(() => expect(locationSheet()).toBeNull());
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
    expect(document.activeElement).toBe(tab('Coming up'));
  });

  it('the way out never yanks a reader who has gone elsewhere', async () => {
    await dockWithAnswer();
    await planThis(1);
    const open = screen.getByTestId('ask-plan-open');
    open.focus();
    fireEvent.click(open);
    await screen.findByTestId('location-sheet');
    // The reader Tabs out of the (non-trapping) sheet onto something real.
    const search = screen.getByTestId('window-first-search');
    search.focus();

    fireEvent.click(screen.getByTestId('location-sheet-close'));
    await waitFor(() => expect(locationSheet()).toBeNull());

    // Left exactly where they chose to be — neither yanked back to the dock nor dropped to <body>.
    expect(document.activeElement).toBe(search);
  });

  it('a dialog this shell does not own refuses the press — nothing opens over it (with the press shown to work first)', async () => {
    await dockWithAnswer();
    await planThis(1);
    // The positive control, which also warms the lazy sheet: without it a sheet that DID open would be
    // only the Suspense fallback and the negative below would pass vacuously.
    fireEvent.click(screen.getByTestId('ask-plan-open'));
    await screen.findByTestId('location-sheet');
    fireEvent.click(screen.getByTestId('location-sheet-close'));
    await waitFor(() => expect(locationSheet()).toBeNull());
    const foreign = foreignDialog();

    fireEvent.click(screen.getByTestId('ask-plan-open'));
    await act(async () => { await Promise.resolve(); });

    expect(locationSheet()).toBeNull();
    expect(modalCount()).toBe(1);
    expect(dock()).not.toHaveAttribute('inert');
    // ...and once the foreign dialog is gone the same press works again.
    foreign.remove();
    fireEvent.click(screen.getByTestId('ask-plan-open'));
    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
  });

  it('the postcode nudge opens settings on the postcode with a return address; the dock, which is not a layer, stays', async () => {
    const { props } = await dockWithAnswer({ ctx: { reachById: new Map(), homePlace: null } });
    await planThis(1);

    fireEvent.click(screen.getByTestId('ask-plan-postcode'));

    expect(props.onOpenSettings).toHaveBeenCalledTimes(1);
    expect(props.onOpenSettings).toHaveBeenCalledWith(expect.any(Function));
    expect(dock()).toBeInTheDocument();
    // The return address is where the sheet's own restore would go: the Ask field, enabled again.
    expect(props.onOpenSettings.mock.calls[0][0]()).toBe(screen.getByTestId('ask-field'));
  });

  it('keeps the plan across a tab switch, and a Plan card highlight is only live while the Plan tab is', async () => {
    await dockWithAnswer({ props: { mapPane: MAP_PANE } });
    await planThis(2);
    expect(highlighted()).toHaveLength(1);

    fireEvent.click(tab('Coming up'));

    expect(dock()).toBeInTheDocument();
    expect(screen.getByTestId('ask-plan')).toBeInTheDocument();
    expect(highlighted()).toHaveLength(0);

    fireEvent.click(tab('Plan'));
    expect(screen.getByTestId('ask-plan')).toBeInTheDocument();
    expect(highlighted()).toHaveLength(1);
  });

  it('a new question from the plan view supersedes it — the input row is live below it', async () => {
    await dockWithAnswer();
    await planThis(1);
    ask.mockResolvedValue(ownResponse({ summary: 'Something else entirely.', events: [] }));

    await userEvent.type(screen.getByTestId('ask-input'), 'And another?{Enter}');

    await screen.findByTestId('ask-picks');
    expect(screen.queryByTestId('ask-plan')).toBeNull();
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Something else entirely.');
  });

  it('"Clear answer" is offered in the plan view and ends the conversation', async () => {
    await dockWithAnswer();
    await planThis(1);

    fireEvent.click(screen.getByTestId('ask-clear'));

    expect(screen.queryByTestId('ask-plan')).toBeNull();
    expect(screen.getByTestId('ask-conversation')).toHaveAttribute('data-phase', 'empty');
    expect(highlighted()).toHaveLength(0);
  });
});

describe('the Plan-card highlight on the dock', () => {
  it('lands on the selected pick’s card with its rank, and opens no popup', async () => {
    await dockWithAnswer();
    await screen.findByTestId('wf-heat-strip');

    fireEvent.click(screen.getByTestId('ask-pick-select-2'));

    const card = heatCard(`${TODAY}:SUNSET`);
    expect(card).toHaveAttribute('data-ask-highlight', 'true');
    expect(within(card).getByTestId('wf-heat-ask-rank')).toHaveTextContent('2');
    expect(highlighted()).toHaveLength(1);
    expect(screen.queryByTestId('window-sheet')).toBeNull();
    expect(card).not.toHaveAttribute('data-open');
  });

  it('follows the choice: another pick moves it to the other card', async () => {
    await dockWithAnswer();
    await screen.findByTestId('wf-heat-strip');
    fireEvent.click(screen.getByTestId('ask-pick-select-2'));

    fireEvent.click(screen.getByTestId('ask-pick-select-1'));

    expect(heatCard(`${TODAY}:SUNSET`)).not.toHaveAttribute('data-ask-highlight');
    expect(heatCard(`${TOMORROW}:SUNRISE`)).toHaveAttribute('data-ask-highlight', 'true');
    expect(within(heatCard(`${TOMORROW}:SUNRISE`)).getByTestId('wf-heat-ask-rank')).toHaveTextContent('1');
  });

  it('is applied by "Plan this" too, to the pick the plan is about', async () => {
    await dockWithAnswer();
    await screen.findByTestId('wf-heat-strip');

    await planThis(1);

    expect(heatCard(`${TOMORROW}:SUNRISE`)).toHaveAttribute('data-ask-highlight', 'true');
  });

  it('a pick that has a slot but whose window the matrix does not draw highlights nothing', async () => {
    // The matrix draws only tonight; tomorrow's sunrise has a pick card (the briefing has the slot) but no
    // matrix card — the case the plan names for "outside the six rendered events".
    const only = planCtx();
    only.heatStripCards = [only.heatStripCards[0]];
    renderAskShell({ width: 1280, ctx: only });
    fireEvent.click(await screen.findByTestId('ask-field'));
    await screen.findByTestId('ask-dock');
    await askOne();
    await screen.findByTestId('wf-heat-strip');

    fireEvent.click(screen.getByTestId('ask-pick-select-1'));

    expect(screen.getByTestId('ask-pick-select-1')).toHaveAttribute('aria-current', 'true');
    expect(highlighted()).toHaveLength(0);
  });

  it('is a different mark from the open card: both can sit on one card', async () => {
    await dockWithAnswer();
    await screen.findByTestId('wf-heat-strip');
    fireEvent.click(screen.getByTestId('ask-pick-select-2'));

    await act(async () => { fireEvent.click(heatCard(`${TODAY}:SUNSET`)); });
    await screen.findByTestId('window-sheet');

    const card = heatCard(`${TODAY}:SUNSET`);
    expect(card).toHaveAttribute('data-open', 'true');
    expect(card).toHaveAttribute('data-ask-highlight', 'true');
  });

  it('is gone the moment Ask is: a server that says 404 hides every surface, and a ring with nothing to clear it would stick', async () => {
    await dockWithAnswer();
    await screen.findByTestId('wf-heat-strip');
    fireEvent.click(screen.getByTestId('ask-pick-select-2'));
    expect(highlighted()).toHaveLength(1);
    ask.mockRejectedValue(new AskApiError({ status: 404, code: null, error: null }));

    await userEvent.type(screen.getByTestId('ask-input'), 'Another?{Enter}');

    await waitFor(() => expect(dock()).toBeNull());
    expect(highlighted()).toHaveLength(0);
  });

  it('goes with the conversation when it is cleared', async () => {
    await dockWithAnswer();
    await screen.findByTestId('wf-heat-strip');
    fireEvent.click(screen.getByTestId('ask-pick-select-2'));
    expect(highlighted()).toHaveLength(1);

    fireEvent.click(screen.getByTestId('ask-clear'));

    expect(highlighted()).toHaveLength(0);
  });
});

describe.each([
  ['the phone sheet (390px)', 390],
  ['the tablet sheet (800px)', 800],
])('on %s', (_name, width) => {
  it('"Plan this ›" is on every card', async () => {
    await sheetWithAnswer({ width });

    expect(screen.getByTestId('ask-plan-this-1')).toBeInTheDocument();
    expect(screen.getByTestId('ask-plan-this-2')).toBeInTheDocument();
    await planThis(2);
    expect(within(askSheet()).getByTestId('ask-plan-name')).toHaveTextContent('Saltburn');
    expect(within(askSheet()).getByTestId('ask-input')).toBeInTheDocument();
  });

  it('"Open in Plan ›" CLOSES the Ask sheet first, then the location sheet is the single modal at the pick’s window', async () => {
    await sheetWithAnswer({ width });
    await planThis(1);

    fireEvent.click(screen.getByTestId('ask-plan-open'));

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(askSheet()).toBeNull();
    expect(modalCount()).toBe(1);
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Whitby');
    expect(openRowKey()).toBe(`${TOMORROW}:SUNRISE`);
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
  });

  it('refuses over a dialog this shell does not own — and only Ask’s OWN sheet is excused, not any dialog', async () => {
    await sheetWithAnswer({ width });
    await planThis(1);
    // The positive control: the same press works with only Ask's sheet standing (and warms the lazy sheet).
    fireEvent.click(screen.getByTestId('ask-plan-open'));
    await screen.findByTestId('location-sheet');
    fireEvent.click(screen.getByTestId('location-sheet-close'));
    await waitFor(() => expect(locationSheet()).toBeNull());
    fireEvent.click(screen.getByTestId(width < 640 ? 'ask-bar' : 'ask-field'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    const foreign = foreignDialog('The map overlay');

    fireEvent.click(screen.getByTestId('ask-plan-open'));
    await act(async () => { await Promise.resolve(); });

    expect(locationSheet()).toBeNull();
    expect(askSheet()).not.toBeNull();
    foreign.remove();
    fireEvent.click(screen.getByTestId('ask-plan-open'));
    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(askSheet()).toBeNull();
  });

  it('the postcode nudge closes the Ask sheet FIRST, then opens settings on the postcode with a return address', async () => {
    const { props } = renderAskShell({
      width,
      ctx: { ...planCtx(), reachById: new Map(), homePlace: null },
    });
    fireEvent.click(await screen.findByTestId(width < 640 ? 'ask-bar' : 'ask-field'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    await askOne();
    await planThis(1);

    fireEvent.click(screen.getByTestId('ask-plan-postcode'));

    // Settings is a sibling of the shell and an aria-modal dialog of its own: the sheet is gone before it
    // arrives, so it never stands over a live modal.
    expect(askSheet()).toBeNull();
    expect(modalCount()).toBe(0);
    expect(props.onOpenSettings).toHaveBeenCalledTimes(1);
    expect(props.onOpenSettings).toHaveBeenCalledWith(expect.any(Function));
  });

  it.each([
    ['once it has taken focus', true],
    ['at once, before it has', false],
  ])('the way OUT, %s: focus returns to the Ask trigger that opened the sheet — never <body>', async (_when, settle) => {
    await sheetWithAnswer({ width });
    await planThis(1);
    const open = screen.getByTestId('ask-plan-open');
    open.focus();
    fireEvent.click(open);
    await screen.findByTestId('location-sheet');
    if (settle) await waitFor(() => expect(document.activeElement).toBe(locationSheet()));

    fireEvent.click(screen.getByTestId('location-sheet-close'));

    await waitFor(() => expect(locationSheet()).toBeNull());
    expect(document.activeElement).not.toBe(document.body);
    // The trigger is disabled while a dialog is up and enabled again by now: it is where the reader came from.
    expect(document.activeElement).toBe(screen.getByTestId(width < 640 ? 'ask-bar' : 'ask-field'));
  });

  it('keeps the conversation: the plan is there when Ask is reopened', async () => {
    await sheetWithAnswer({ width });
    await planThis(1);
    fireEvent.click(screen.getByTestId('ask-plan-open'));
    await screen.findByTestId('location-sheet');
    fireEvent.click(screen.getByTestId('location-sheet-close'));
    await waitFor(() => expect(locationSheet()).toBeNull());

    fireEvent.click(screen.getByTestId(width < 640 ? 'ask-bar' : 'ask-field'));

    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    expect(screen.getByTestId('ask-plan')).toBeInTheDocument();
  });

  it('the highlight is NOT applied while the sheet covers the pane, and IS once the sheet closes', async () => {
    await sheetWithAnswer({ width });
    await screen.findByTestId('wf-heat-strip');

    fireEvent.click(screen.getByTestId('ask-pick-select-2'));
    expect(highlighted()).toHaveLength(0);

    // The ✕ closes (and keeps the conversation); the pick is still chosen.
    fireEvent.click(within(askSheet()).getByRole('button', { name: 'Close' }));

    await waitFor(() => expect(askSheet()).toBeNull());
    expect(heatCard(`${TODAY}:SUNSET`)).toHaveAttribute('data-ask-highlight', 'true');
    expect(within(heatCard(`${TODAY}:SUNSET`)).getByTestId('wf-heat-ask-rank')).toHaveTextContent('2');
  });
});

describe('the tablet sheet over the Map', () => {
  it('"Open in Plan ›" leaves the Map for the Plan tab with the Ask sheet closed first', async () => {
    renderAskShell({ width: 800, ctx: planCtx(), props: { mapPane: MAP_PANE } });
    fireEvent.click(await screen.findByTestId('ask-field'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    // Ask from the Map tab itself.
    fireEvent.click(within(askSheet()).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(askSheet()).toBeNull());
    fireEvent.click(tab('Map'));
    fireEvent.click(screen.getByTestId('ask-field'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    await askOne();
    await planThis(1);

    fireEvent.click(screen.getByTestId('ask-plan-open'));

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(askSheet()).toBeNull();
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    expect(modalCount()).toBe(1);
  });
});
