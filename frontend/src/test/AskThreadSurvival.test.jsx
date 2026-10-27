/**
 * The Ask conversation SURVIVES every route between two questions except an explicit "New conversation"
 * (`docs/engineering/ask-thread-plan.md` §3 T4, "As built"; the 2026-10-11 production report that a second
 * question on the phone's Plan sheet went out without its thread).
 *
 * <p>Each case is one route a reader (or the browser) can take between question 1 and question 2 on a
 * PHONE (390px), through the real shell, the real {@code AskProvider}, the real sheet and the real
 * conversation; the assertion is the same everywhere — question 2's POST carries a {@code thread} of exactly
 * one exchange, the chip says "follow-up · 1 so far", and question 1 is drawn collapsed above the live
 * answer. The one route that MUST end the conversation (the button) is asserted to, so the contract is
 * pinned from both sides.
 *
 * <p>Not reachable from here, and said so rather than implied: the real {@code App} chain (a provider
 * remount from {@code App}'s own state), and the real {@code MapView} chain around {@code MapPeekAsk}
 * (Leaflet); the peek's row is driven directly instead.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { resetViewport, installViewport } from './askViewport.js';
import {
  MAP_PANE, matrixCtx, renderAskShell, shellCtx,
} from './askShellHarness.jsx';
import {
  NOW, ownResponse, pick, readyResponse, SALTBURN, settings,
} from './askFixtures.js';
import MapPeekAsk from '../components/map/MapPeekAsk.jsx';
import { AskProvider } from '../context/AskContext.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

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

const FIRST = 'Are there any locations where tide matches sunrise?';
const SECOND = 'Are there any closer to home?';
const SHEET = { name: 'Ask PhotoCast' };

// jsdom applies no Tailwind, so BOTH GlanceLabel spans count: the tab's name is "At a GlanceGlance" here.
const tab = (name) => screen.getByRole('tab', { name: name === 'At a Glance' ? /^At a Glance/ : name });
const sheet = () => screen.queryByRole('dialog', SHEET);

/** Answer 1: two picks, so a card exists to select, plan and show on the map. */
const answerOne = () => ownResponse({
  summary: 'Saltburn has the tide at sunrise.',
  picks: [
    pick({
      rank: 1, locationId: SALTBURN, locationName: 'Saltburn', windowId: '2026-10-05_sunset',
    }),
    pick({ rank: 2 }),
  ],
  events: [],
});

async function openSheet() {
  fireEvent.click(await screen.findByTestId('ask-bar'));
  return screen.findByRole('dialog', SHEET);
}

/** The phone shell (Plan, with a Map pane to go to), the sheet open, and question 1 answered. */
async function phoneWithFirstAnswer({ props = {} } = {}) {
  const view = renderAskShell({
    width: 390, ctx: matrixCtx(), props: { mapPane: MAP_PANE, ...props },
  });
  await openSheet();
  ask.mockResolvedValueOnce(answerOne());
  await userEvent.type(screen.getByTestId('ask-input'), `${FIRST}{Enter}`);
  await screen.findByTestId('ask-picks');
  return view;
}

/** Types question 2 (opening the sheet first if the route closed it) and returns once it is answered. */
async function askSecond() {
  if (!sheet()) await openSheet();
  ask.mockResolvedValueOnce(ownResponse({ summary: 'Answer two.', picks: [pick({ rank: 1 })], events: [] }));
  await userEvent.type(screen.getByTestId('ask-input'), `${SECOND}{Enter}`);
  await waitFor(() => expect(screen.getByTestId('ask-summary')).toHaveTextContent('Answer two.'));
}

/** What every route must leave true. */
function expectThreadKept() {
  expect(ask).toHaveBeenCalledTimes(2);
  const sent = ask.mock.calls[1][0];
  expect(sent.question).toBe(SECOND);
  expect(sent.thread).toHaveLength(1);
  expect(sent.thread[0]).toMatchObject({ question: FIRST, summary: 'Saltburn has the tide at sunrise.' });
  expect(screen.getByTestId('ask-chip-followup')).toHaveTextContent('follow-up · 1 so far');
  expect(screen.getByTestId('ask-thread-question')).toHaveTextContent(FIRST);
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  vi.resetAllMocks();
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings({ limit: 30, left: 30 }));
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  delete window.visualViewport;
  resetViewport();
});

describe('between two questions on the phone Plan sheet', () => {
  it('(a) the sheet’s ✕, then the ask bar again', async () => {
    await phoneWithFirstAnswer();
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    expect(sheet()).toBeNull();

    await askSecond();

    expectThreadKept();
  });

  it.each([
    ['the scrim', () => fireEvent.click(screen.getByTestId('bottom-sheet-overlay'))],
    ['Escape', () => fireEvent.keyDown(document, { key: 'Escape' })],
  ])('(b/k) %s closes the sheet and nothing else', async (_name, close) => {
    await phoneWithFirstAnswer();
    close();
    expect(sheet()).toBeNull();

    await askSecond();

    expectThreadKept();
  });

  it('(c) Plan → Map → Plan (the shell closes the sheet on every tab change)', async () => {
    await phoneWithFirstAnswer();
    fireEvent.click(tab('Map'));
    expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    expect(sheet()).toBeNull();
    fireEvent.click(tab('At a Glance'));
    await screen.findByTestId('ask-bar');

    await askSecond();

    expectThreadKept();
  });

  it('(c) the phone Map peek’s Ask row mounting, collapsing and unmounting does not clear the conversation', async () => {
    // The real MapView is Leaflet's; the row it mounts is driven here under the real provider. Its only
    // `clear()` is the ✕ pressed on a settled answer, never a mount, a collapse or an unmount.
    installViewport(390);
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx(matrixCtx()));
    getAskSettings.mockResolvedValue(settings({ limit: 30, left: 30 }));
    const entryRef = { current: null };
    const peek = (mode) => (
      <AskProvider>
        <MapPeekAsk mode={mode} onOpen={vi.fn()} onClose={vi.fn()} entryRef={entryRef} />
      </AskProvider>
    );
    const { rerender, unmount } = render(peek('expanded'));
    ask.mockResolvedValueOnce(answerOne());
    await userEvent.type(await screen.findByTestId('ask-input'), `${FIRST}{Enter}`);
    await screen.findByTestId('ask-picks');

    rerender(peek('minimised'));
    rerender(peek('collapsed'));
    rerender(peek('expanded'));
    ask.mockResolvedValueOnce(ownResponse({ summary: 'Answer two.', events: [] }));
    await userEvent.type(screen.getByTestId('ask-input'), `${SECOND}{Enter}`);
    await waitFor(() => expect(ask).toHaveBeenCalledTimes(2));

    expect(ask.mock.calls[1][0].thread).toHaveLength(1);
    unmount();
  });

  it('(d) select a pick, "Show on map ›", and come back to Plan', async () => {
    await phoneWithFirstAnswer();
    fireEvent.click(screen.getByTestId('ask-pick-select-1'));
    fireEvent.click(await screen.findByTestId('ask-show-on-map-1'));
    await waitFor(() => expect(tab('Map')).toHaveAttribute('aria-selected', 'true'));
    fireEvent.click(tab('At a Glance'));
    await screen.findByTestId('ask-bar');

    await askSecond();

    expectThreadKept();
  });

  it('(e) "Plan this ›" then "Back to the answer"', async () => {
    await phoneWithFirstAnswer();
    await act(async () => { fireEvent.click(screen.getByTestId('ask-plan-this-1')); });
    await screen.findByTestId('ask-plan');
    fireEvent.click(screen.getByTestId('ask-plan-back'));

    await askSecond();

    expectThreadKept();
  });

  it('(e) the sheet closed while in the plan view, then a question', async () => {
    await phoneWithFirstAnswer();
    await act(async () => { fireEvent.click(screen.getByTestId('ask-plan-this-1')); });
    await screen.findByTestId('ask-plan');
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));

    await askSecond();

    expectThreadKept();
  });

  it('(f) the button DOES end it — and its words say so, not "Clear answer"', async () => {
    await phoneWithFirstAnswer();

    const button = screen.getByTestId('ask-clear');
    expect(button).toHaveTextContent('New conversation');
    expect(button).toHaveAccessibleDescription(
      'Ends this conversation. Your earlier questions are kept until then.',
    );
    fireEvent.click(button);
    ask.mockResolvedValueOnce(ownResponse({ summary: 'Answer two.', events: [] }));
    await userEvent.type(screen.getByTestId('ask-input'), `${SECOND}{Enter}`);
    await waitFor(() => expect(ask).toHaveBeenCalledTimes(2));

    expect(ask.mock.calls[1][0]).not.toHaveProperty('thread');
  });

  it.each([
    ['visibilitychange to hidden and back', () => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    }],
    ['pagehide then pageshow (iOS backgrounding)', () => {
      window.dispatchEvent(new Event('pagehide'));
      window.dispatchEvent(new Event('pageshow'));
    }],
    ['window blur then focus', () => {
      window.dispatchEvent(new Event('blur'));
      window.dispatchEvent(new Event('focus'));
    }],
  ])('(g) %s', async (_name, background) => {
    await phoneWithFirstAnswer();
    act(() => { background(); });

    await askSecond();

    expectThreadKept();
  });

  it('(h) the keyboard: the visual viewport shrinking and growing while the sheet is open', async () => {
    const listeners = new Set();
    const vv = {
      height: 800,
      offsetTop: 0,
      addEventListener: (_type, fn) => listeners.add(fn),
      removeEventListener: (_type, fn) => listeners.delete(fn),
    };
    window.visualViewport = vv;
    await phoneWithFirstAnswer();
    expect(listeners.size).toBeGreaterThan(0);
    act(() => { vv.height = 420; listeners.forEach((fn) => fn(new Event('resize'))); });
    act(() => { vv.height = 800; listeners.forEach((fn) => fn(new Event('resize'))); });

    await askSecond();

    expectThreadKept();
  });

  it('(i) the allowance re-read when the UK day turns on a return to the page', async () => {
    await phoneWithFirstAnswer();
    const reads = getAskSettings.mock.calls.length;
    vi.setSystemTime(new Date('2026-10-06T00:30:00Z'));
    act(() => { window.dispatchEvent(new Event('focus')); });
    await waitFor(() => expect(getAskSettings.mock.calls.length).toBeGreaterThan(reads));

    await askSecond();

    expectThreadKept();
  });

  it('(j) the shell re-rendered with new props under the same provider (a parent render, not a remount)', async () => {
    const { rerenderShell } = await phoneWithFirstAnswer();
    rerenderShell({ onSignOut: vi.fn(), onOpenSettings: vi.fn(), settingsOpen: false });
    rerenderShell({ onSignOut: vi.fn(), settingsOpen: false });

    await askSecond();

    expectThreadKept();
  });
});
