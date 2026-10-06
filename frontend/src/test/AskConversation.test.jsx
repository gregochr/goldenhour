import React, { useEffect } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { AskProvider, READY_OPEN_MS, useAsk } from '../context/AskContext.jsx';
import AskConversation from '../components/ask/AskConversation.jsx';
import { AskApiError } from '../api/askApi.js';
import {
  briefing, cantResponse, ECLIPSE_SAFETY_NOTE, NOW, ownResponse, pick, readyResponse,
  settings, WHITBY,
} from './askFixtures.js';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn(), fetchRegionDriveTimes: vi.fn() }));
// The real error class (so what the tests throw is what `askApi` throws), the three calls mocked.
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegionDriveTimes, fetchRegions } from '../api/regionApi.js';
import { ask, getAskSettings, getReady } from '../api/askApi.js';

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));

const LAKES = {
  id: 3, name: 'The Lake District', enabled: true, baseName: 'Keswick', baseLat: 54.6013, baseLon: -3.1347,
};

/** What the provider's `reachById` serves: HOME, 1h 35min to Whitby. */
const HOME_REACH = [{ locationId: WHITBY, driveMinutes: 95, distanceMiles: 60 }];

/**
 * The context's latest value. Ask has no field or surface yet (F1b/F2 build them), so a test drives
 * the actions through this capture rather than through a control that does not exist — a hook with
 * no single natural consumer, which the test standards allow a small harness for.
 */
let ctx;
function Capture() {
  const value = useAsk();
  // In an effect, not the render: a render must not write a variable outside the component. Every
  // read of `ctx` below follows an awaited `act`, which has flushed it.
  useEffect(() => { ctx = value; });
  return null;
}

/** The briefing provider's own view, so a test can see that the home map is home. */
function Probe() {
  const { briefing: b, reachById, effectiveReachById, setOrigin } = useWindowFirstBriefing();
  return (
    <>
      <span data-testid="probe-generated">{b?.generatedAt ?? 'none'}</span>
      <span data-testid="probe-home-drive">{reachById.get(WHITBY)?.driveMinutes ?? 'none'}</span>
      <span data-testid="probe-effective-drive">{effectiveReachById.get(WHITBY)?.driveMinutes ?? 'none'}</span>
      <button type="button" onClick={() => setOrigin(LAKES)}>move the plan origin</button>
    </>
  );
}

/** The tree under test, so a test can re-render it with other props. */
const tree = (props = {}) => (
  <WindowFirstBriefingProvider>
    <AskProvider>
      <Capture />
      <Probe />
      <AskConversation
        view="plan"
        viewLabel="Plan · all regions"
        {...props}
      />
    </AskProvider>
  </WindowFirstBriefingProvider>
);

/** Renders Ask on the real briefing provider, and waits until the briefing and home reach are in. */
async function renderAsk(props = {}) {
  const view = render(tree(props));
  await screen.findByText('2026-10-05T05:02:11');
  await waitFor(() => expect(screen.getByTestId('probe-home-drive')).toHaveTextContent('95'));
  return view;
}

/** A refusal as the API module throws it. */
const refusal = (status, code, error) => new AskApiError({ status, code, error: error ?? null });

const askTyped = (...args) => act(async () => { await ctx.askTyped(...args); });
/** Opens a Ready question straight from the list, for a state whose suggestions are not on screen. */
const openReadyById = (id) => act(() => ctx.openReady(readyResponse().questions.find((q) => q.id === id)));
const tapReady = async (id) => {
  fireEvent.click(await screen.findByTestId(`ask-ready-${id}`));
};
/** Lets the Ready answer's busy line finish (the one timer the provider holds). */
const finishOpening = () => act(async () => { await vi.advanceTimersByTimeAsync(READY_OPEN_MS); });

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(NOW);
  localStorage.clear();
  mockRole = 'LITE_USER';
  vi.resetAllMocks();
  getDailyBriefing.mockResolvedValue(briefing());
  fetchTravelDayRanges.mockResolvedValue([]);
  getAllEvaluationScores.mockResolvedValue([]);
  getReach.mockResolvedValue(HOME_REACH);
  getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
  fetchRegions.mockResolvedValue([LAKES]);
  fetchRegionDriveTimes.mockResolvedValue({ 3: { [WHITBY]: 30 } });
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  ctx = undefined;
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('AskConversation — the empty state', () => {
  it('offers the Ready questions of this tab, each tagged Ready, and none of another tab’s', async () => {
    await renderAsk();

    const list = await screen.findByTestId('ask-ready-list');
    expect(within(list).getByTestId('ask-ready-BEST_NEXT')).toHaveTextContent('Best spot tonight?');
    expect(within(list).getByTestId('ask-ready-AM_OR_PM')).toHaveTextContent('Sunrise or sunset tomorrow?');
    expect(within(list).queryByTestId('ask-ready-COASTAL_HIGH')).toBeNull();
    expect(within(list).queryByTestId('ask-ready-RARE_EVENTS')).toBeNull();
    expect(within(list).getAllByText('Ready')).toHaveLength(2);
  });

  it('changes the suggestions with the tab', async () => {
    await renderAsk({ view: 'coming-up', viewLabel: 'Coming up · all' });

    const list = await screen.findByTestId('ask-ready-list');
    expect(within(list).getAllByRole('button').map((b) => b.dataset.testid))
      .toEqual(['ask-ready-RARE_EVENTS']);
  });

  it('names the run from the payload, never a hard-coded one', async () => {
    const body = readyResponse();
    body.questions.forEach((q) => { q.runLabel = '07:15'; q.generatedAt = '2026-10-05T06:15:00'; });
    getReady.mockResolvedValue(body);

    await renderAsk();

    expect(await screen.findByText('Ready from the 07:15 run · free')).toBeInTheDocument();
    expect(screen.queryByText(/06:00/)).toBeNull();
  });

  it('names the newest run among the questions offered', async () => {
    await renderAsk({ view: 'map', viewLabel: 'Map · My area' });

    expect(await screen.findByText('Ready from the 18:03 run · free')).toBeInTheDocument();
  });

  it('shows no Ready section, and keeps the allowance, when the server lists no questions', async () => {
    getReady.mockResolvedValue({ scope: 'all', questions: [] });

    await renderAsk();

    await screen.findByTestId('ask-allowance');
    expect(screen.queryByTestId('ask-ready-list')).toBeNull();
  });

  it('shows no Ready section when the list cannot be fetched', async () => {
    getReady.mockRejectedValue(refusal(500, null, null));

    await renderAsk();

    await screen.findByTestId('ask-allowance');
    expect(screen.queryByTestId('ask-ready-list')).toBeNull();
  });

  it('fetches the list for the scope it is shown, once, after a briefing exists', async () => {
    await renderAsk({ scope: 3 });
    await screen.findByTestId('ask-ready-list');

    expect(getReady).toHaveBeenCalledTimes(1);
    expect(getReady).toHaveBeenCalledWith(3);
  });

  it('fetches the list again when the briefing is rebuilt, and shows none of the old one meanwhile', async () => {
    await renderAsk();
    await screen.findByTestId('ask-ready-list');
    getDailyBriefing.mockResolvedValue({ ...briefing(), generatedAt: '2026-10-05T17:03:40' });
    getReady.mockReturnValue(new Promise(() => {}));

    // The provider polls every ten minutes.
    await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000); });

    await screen.findByText('2026-10-05T17:03:40');
    expect(getReady).toHaveBeenCalledTimes(2);
    expect(screen.queryByTestId('ask-ready-list')).toBeNull();
  });

  it('fetches again when a hidden conversation is shown, and not before', async () => {
    const view = await renderAsk({ hidden: true });
    expect(getReady).not.toHaveBeenCalled();

    view.rerender(tree({ hidden: false }));

    await screen.findByTestId('ask-ready-list');
    expect(getReady).toHaveBeenCalledTimes(1);
  });

  describe('the allowance line', () => {
    it('says what a free reader has, and where Pro goes, with the Pro text as text — not a link', async () => {
      await renderAsk();

      const line = await screen.findByTestId('ask-allowance');
      expect(line).toHaveTextContent('3 of 3 own questions left today');
      expect(within(line).getByTestId('pro-pill')).toHaveTextContent('Pro');
      expect(line).toHaveTextContent('30 a day');
      expect(within(line).queryByRole('link')).toBeNull();
    });

    it.each(['PRO_USER', 'ADMIN'])('shows a %s only "N of M left today"', async (role) => {
      mockRole = role;
      getAskSettings.mockResolvedValue(settings({ used: 4, limit: 30, left: 26 }));

      await renderAsk();

      const line = await screen.findByTestId('ask-allowance');
      expect(line).toHaveTextContent(/^26 of 30 left today$/);
      expect(within(line).queryByTestId('pro-pill')).toBeNull();
    });

    it('says "No own questions left today" at zero, and still names the Pro allowance', async () => {
      getAskSettings.mockResolvedValue(settings({ used: 3, left: 0 }));

      await renderAsk();

      const line = await screen.findByTestId('ask-allowance');
      expect(line).toHaveTextContent('No own questions left today');
      expect(line).toHaveTextContent('30 a day');
    });

    it('says "0 of 30 left today" to a Pro reader at zero', async () => {
      mockRole = 'PRO_USER';
      getAskSettings.mockResolvedValue(settings({ used: 30, limit: 30, left: 0 }));

      await renderAsk();

      expect(await screen.findByTestId('ask-allowance')).toHaveTextContent('0 of 30 left today');
    });

    it('claims nothing until the server has answered — no "0 left" while it loads', async () => {
      getAskSettings.mockReturnValue(new Promise(() => {}));

      await renderAsk();
      await screen.findByTestId('ask-ready-list');

      expect(screen.queryByTestId('ask-allowance')).toBeNull();
    });

    it('claims nothing when the read fails', async () => {
      getAskSettings.mockRejectedValue(refusal(500, null, null));

      await renderAsk();
      await screen.findByTestId('ask-ready-list');

      expect(screen.queryByTestId('ask-allowance')).toBeNull();
    });

    it('explains a disabled field when typed questions are unavailable but Ready ones work', async () => {
      getAskSettings.mockResolvedValue(settings({ typedAvailable: false }));

      await renderAsk();

      expect(await screen.findByTestId('ask-typed-off')).toHaveTextContent(/unavailable right now/);
      await waitFor(() => expect(ctx.typedDisabled).toBe(true));
      expect(screen.getByTestId('ask-ready-BEST_NEXT')).toBeEnabled();
    });
  });

  describe('the "Asking about" chips', () => {
    it('shows the window chip and the view chip', async () => {
      await renderAsk({ view: 'map', viewLabel: 'Map · My area', windowLabel: 'Sat sunrise' });

      expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Sat sunrise');
      expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · My area');
    });

    it('removes the window chip with its ✕, and the question is then sent without the window', async () => {
      ask.mockResolvedValue(ownResponse());
      await renderAsk({
        view: 'map', viewLabel: 'Map · My area', windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset',
      });

      fireEvent.click(screen.getByRole('button', { name: 'Remove Mon sunset from the question' }));

      expect(screen.queryByTestId('ask-chip-window')).toBeNull();
      expect(ctx.contextWindow).toBe(false);
      expect(ctx.removedWindow).toBe('2026-10-05_sunset');
      await askTyped('What about Sunday?', { windowId: '2026-10-05_sunset', regionIds: [3], view: 'map' });
      expect(ask).toHaveBeenCalledWith({ question: 'What about Sunday?', regionIds: [3], view: 'map' });
    });

    it('puts the window back when the surface restores it (a tab switch)', async () => {
      await renderAsk({
        view: 'map', viewLabel: 'Map · My area', windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset',
      });
      fireEvent.click(screen.getByTestId('ask-chip-window-remove'));

      act(() => ctx.restoreContextWindow());

      expect(screen.getByTestId('ask-chip-window')).toBeInTheDocument();
      expect(ctx.contextWindow).toBe(true);
    });

    it('brings the chip back by itself when a DIFFERENT window arrives — a removal is of that window only', async () => {
      ask.mockResolvedValue(ownResponse());
      const props = { view: 'map', viewLabel: 'Map · My area' };
      const view = await renderAsk({
        ...props, windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset',
      });
      fireEvent.click(screen.getByTestId('ask-chip-window-remove'));
      expect(screen.queryByTestId('ask-chip-window')).toBeNull();

      view.rerender(tree({ ...props, windowLabel: 'Tue sunrise', windowId: '2026-10-06_sunrise' }));

      expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Tue sunrise');
      // ...and the new window IS sent, while the removed one still is not.
      await askTyped('What about this one?', { windowId: '2026-10-06_sunrise', view: 'map' });
      expect(ask.mock.calls[0][0].windowId).toBe('2026-10-06_sunrise');
      await askTyped('And the other?', { windowId: '2026-10-05_sunset', view: 'map' });
      expect(ask.mock.calls[1][0]).not.toHaveProperty('windowId');
    });
  });
});

describe('AskConversation — hidden', () => {
  it('is an empty, hidden shell: a live region behind a hidden layer announces nothing', async () => {
    ask.mockResolvedValue(ownResponse());
    const view = await renderAsk();
    await askTyped('Where is good?', { regionIds: [], view: 'plan' });
    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();

    view.rerender(tree({ hidden: true }));

    const shell = screen.getByTestId('ask-conversation');
    expect(shell).not.toBeVisible();
    expect(screen.getByTestId('ask-live')).toHaveAttribute('aria-live', 'polite');
    expect(screen.getByTestId('ask-live')).toBeEmptyDOMElement();
    expect(shell).not.toHaveTextContent(/Saltburn|Where is good|Ready/);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('fetches nothing for the Ready list while hidden', async () => {
    await renderAsk({ hidden: true });

    expect(getReady).not.toHaveBeenCalled();
  });

  it('shows no busy line while hidden, even mid-question', async () => {
    const pending = new Promise(() => {});
    ask.mockReturnValue(pending);
    const view = await renderAsk();
    act(() => { ctx.askTyped('Where is good?', { view: 'plan' }); });
    expect(screen.getByRole('status')).toBeInTheDocument();

    view.rerender(tree({ hidden: true }));

    expect(screen.queryByRole('status')).toBeNull();
  });

  it('keeps the SAME live regions mounted from empty to answer, so the arrival is announced', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();
    const live = screen.getByTestId('ask-live');
    const notice = screen.getByTestId('ask-notice-region');
    const status = screen.getByRole('status');
    expect(live).toBeEmptyDOMElement();

    await askTyped('Where is good?', { view: 'plan' });

    // A region that was remounted (a key on the phase) would be announced inconsistently.
    expect(screen.getByTestId('ask-live')).toBe(live);
    expect(screen.getByTestId('ask-notice-region')).toBe(notice);
    expect(screen.getByRole('status')).toBe(status);
    expect(live).toHaveAttribute('aria-live', 'polite');
    expect(live).toHaveTextContent('Saltburn is your best bet');
  });

  it('puts the busy line in a status that was mounted EMPTY, and the visible line out of the tree', async () => {
    await renderAsk();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();

    await tapReady('BEST_NEXT');

    expect(screen.getByRole('status')).toHaveTextContent('Opening this morning’s answer');
    expect(screen.getByTestId('ask-busy')).toHaveAttribute('aria-hidden', 'true');
  });

  it('puts an earlier answer that a refusal brings BACK outside the live region, so it is not read out again', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    expect(within(screen.getByTestId('ask-live')).getByTestId('ask-summary')).toBeInTheDocument();
    ask.mockRejectedValue(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));

    await askTyped('and again?', { view: 'plan' });

    expect(ctx.restored).toBe(true);
    expect(screen.getByTestId('ask-live')).toBeEmptyDOMElement();
    expect(within(screen.getByTestId('ask-restored')).getByTestId('ask-summary'))
      .toHaveTextContent('Whitby is the best of tonight');
    expect(screen.getByTestId('ask-notice-region')).toHaveTextContent('Slow down a moment.');
  });

  it('puts a NEW answer back in the live region after a restored one', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));
    await askTyped('and again?', { view: 'plan' });
    ask.mockResolvedValueOnce(ownResponse());

    await askTyped('Where is good?', { view: 'plan' });

    expect(ctx.restored).toBe(false);
    expect(screen.queryByTestId('ask-restored')).toBeNull();
    expect(within(screen.getByTestId('ask-live')).getByTestId('ask-summary'))
      .toHaveTextContent('Saltburn is your best bet');
  });

  it('says a refusal in its own live region, so the answer around it is not read out again', async () => {
    ask.mockRejectedValue(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(within(screen.getByTestId('ask-notice-region')).getByTestId('ask-input-error'))
      .toHaveTextContent('Slow down a moment.');
    expect(screen.getByTestId('ask-live')).not.toHaveTextContent('Slow down');
  });
});

describe('AskConversation — a Ready answer', () => {
  it('makes no request to open it: the answer is already in the list', async () => {
    await renderAsk();
    expect(getReady).toHaveBeenCalledTimes(1);
    const settingsReads = getAskSettings.mock.calls.length;

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(await screen.findByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
    expect(ask).not.toHaveBeenCalled();
    expect(getReady).toHaveBeenCalledTimes(1);
    expect(getAskSettings).toHaveBeenCalledTimes(settingsReads);
  });

  it('holds the busy line first, as a status, with the reader’s question as a bubble', async () => {
    await renderAsk();

    await tapReady('BEST_NEXT');

    expect(screen.getByRole('status')).toHaveTextContent('Opening this morning’s answer');
    expect(screen.getByTestId('ask-question')).toHaveTextContent('Best spot tonight?');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
    expect(ask).not.toHaveBeenCalled();
  });

  it('holds it for 400 ms — the design’s figure, not a number this file borrows from the code', async () => {
    expect(READY_OPEN_MS).toBe(400);
    await renderAsk();
    await tapReady('BEST_NEXT');

    await act(async () => { await vi.advanceTimersByTimeAsync(399); });
    expect(screen.queryByTestId('ask-summary')).toBeNull();

    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('says evening for an evening run, never "this morning" over it', async () => {
    await renderAsk({ view: 'map', viewLabel: 'Map · My area' });

    await tapReady('COASTAL_HIGH');

    expect(screen.getByRole('status')).toHaveTextContent('Opening this evening’s answer');
  });

  it('closes with "Ready answer from the run · no question used", the run read off the question', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^Ready answer from the 06:02 run · no question used$/);
  });

  it('opens a Ready answer with events: the eclipse card shows its safety note', async () => {
    await renderAsk({ view: 'coming-up', viewLabel: 'Coming up · all' });
    await tapReady('RARE_EVENTS');
    await finishOpening();

    const events = screen.getByTestId('ask-events');
    expect(within(events).getByTestId('ask-event-card')).toHaveTextContent('Partial solar eclipse');
    expect(within(events).getByRole('note')).toHaveTextContent(ECLIPSE_SAFETY_NOTE);
    expect(screen.queryByTestId('ask-picks')).toBeNull();
  });

  it('leaves the allowance exactly as the server last said', async () => {
    await renderAsk();
    await screen.findByTestId('ask-allowance');

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(ctx.allowance).toMatchObject({ left: 3, used: 0 });
  });

  it('offers "Plan this" on every pick (F5) and never "Add to Coming up" (removed, plan §1 #9)', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    // Two picks, two buttons — it was `null` until F5 built it; "Add to Coming up" still is.
    expect(screen.getAllByText(/plan this/i)).toHaveLength(2);
    expect(screen.queryByText(/add to coming up/i)).toBeNull();
  });
});

describe('AskConversation — the picks', () => {
  it('shows each pick as a card joined to the briefing, with the HOME drive', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    const picks = screen.getByTestId('ask-picks');
    expect(within(picks).getByTestId('ask-pick-name-1')).toHaveTextContent('Whitby');
    expect(within(picks).getByTestId('ask-pick-score-1')).toHaveTextContent('Worth it');
    expect(within(picks).getByTestId('ask-pick-drive-1')).toHaveTextContent('1h 35min');
    expect(within(picks).getByTestId('ask-pick-name-2')).toHaveTextContent('Saltburn');
    // Saltburn has no drive in the home map: unknown stays unknown.
    expect(within(picks).queryByTestId('ask-pick-drive-2')).toBeNull();
  });

  it('drops a pick the client’s briefing has no slot for, and keeps the rest and the summary', async () => {
    const body = readyResponse();
    body.questions[0].answer.picks.unshift(pick({ rank: 1, locationId: 999, locationName: 'Elsewhere' }));
    body.questions[0].answer.picks[1].rank = 2;
    body.questions[0].answer.picks[2].rank = 3;
    getReady.mockResolvedValue(body);
    await renderAsk();

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
    expect(screen.queryByText('Elsewhere')).toBeNull();
    expect(screen.queryByTestId('ask-pick-1')).toBeNull();
    expect(screen.getByTestId('ask-pick-2')).toBeInTheDocument();
    expect(screen.getByTestId('ask-pick-3')).toBeInTheDocument();
  });

  it('drives from HOME even when the Plan origin has moved away', async () => {
    await renderAsk();
    expect(screen.getByTestId('probe-effective-drive')).toHaveTextContent('95');

    fireEvent.click(screen.getByRole('button', { name: 'move the plan origin' }));
    // The Plan now plans from Keswick: its own map says 30 minutes. If this did not move, the
    // assertion below would prove nothing about which map Ask read.
    await waitFor(() => expect(screen.getByTestId('probe-effective-drive')).toHaveTextContent('30'));
    expect(screen.getByTestId('probe-home-drive')).toHaveTextContent('95');

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(screen.getByTestId('ask-pick-drive-1')).toHaveTextContent('1h 35min');
    expect(screen.getByTestId('ask-pick-drive-1')).not.toHaveTextContent('30 min');
  });

  it('selects a card on a press, and says so', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    fireEvent.click(screen.getByTestId('ask-pick-select-2'));

    expect(ctx.selectedPick).toBe(2);
    expect(screen.getByTestId('ask-pick-2')).toHaveAttribute('data-selected', 'true');
    expect(screen.getByTestId('ask-pick-1')).not.toHaveAttribute('data-selected');
  });

  it('ignores a rank no card carries, and clears on null', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    act(() => ctx.selectPick(1));

    act(() => ctx.selectPick(9));
    expect(ctx.selectedPick).toBe(1);

    act(() => ctx.selectPick(null));
    expect(ctx.selectedPick).toBeNull();
  });

  it('forgets the selection when another answer arrives — even one that carries the same rank', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    act(() => ctx.selectPick(1));
    expect(ctx.selectedPick).toBe(1);

    // AM_OR_PM's one pick is ALSO rank 1 (Whitby): only a reset, not the "no such card" guard, clears it.
    await openReadyById('AM_OR_PM');
    await finishOpening();

    expect(ctx.selectedPick).toBeNull();
  });

  it('lets go of the selection when a rebuilt briefing no longer has the selected card', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    act(() => ctx.selectPick(2));
    expect(ctx.selectedPick).toBe(2);
    const withoutSaltburn = briefing();
    withoutSaltburn.days[0].eventSummaries[0].regions[0].slots.splice(1, 1);
    getDailyBriefing.mockResolvedValue(withoutSaltburn);

    await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000); });

    await waitFor(() => expect(screen.queryByTestId('ask-pick-2')).toBeNull());
    expect(ctx.selectedPick).toBeNull();
    expect(screen.getByTestId('ask-pick-1')).toBeInTheDocument();
  });

  it('does not offer to deselect: pressing the selected card keeps it selected', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    fireEvent.click(screen.getByTestId('ask-pick-select-1'));
    fireEvent.click(screen.getByTestId('ask-pick-select-1'));

    expect(ctx.selectedPick).toBe(1);
    expect(screen.getByTestId('ask-pick-select-1')).toHaveAttribute('aria-current', 'true');
  });

  it('hands each card’s own row to the surface, with the card it belongs to', async () => {
    const pickActions = vi.fn((card) => <button type="button">{`Show ${card.name} on map ›`}</button>);
    await renderAsk({ pickActions });
    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(screen.getByRole('button', { name: 'Show Whitby on map ›' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show Saltburn on map ›' })).toBeInTheDocument();
    expect(pickActions.mock.calls[0][0]).toMatchObject({ rank: 1, locationId: WHITBY });
  });
});

describe('AskConversation — a typed question', () => {
  it('shows the reader’s question, then "Reading forecasts and hot topics" while it is out', async () => {
    ask.mockReturnValue(new Promise(() => {}));
    await renderAsk();

    act(() => { ctx.askTyped('Where is good this evening?', { view: 'plan' }); });

    expect(screen.getByTestId('ask-question')).toHaveTextContent('Where is good this evening?');
    expect(screen.getByRole('status')).toHaveTextContent('Reading forecasts and hot topics');
  });

  it('sends the question, the regions, the view and the window — and only those', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk({ view: 'map', viewLabel: 'Map · Lakes', windowLabel: 'Mon sunset' });

    await askTyped('  Where is good?  ', { windowId: '2026-10-05_sunset', regionIds: [3], view: 'map' });

    expect(ask).toHaveBeenCalledWith({
      question: 'Where is good?', regionIds: [3], view: 'map', windowId: '2026-10-05_sunset',
    });
  });

  it('sends no window key when the surface has none, and no regions as the empty list', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(ask).toHaveBeenCalledWith({ question: 'Where is good?', regionIds: [], view: 'plan' });
  });

  it('does nothing for a blank question', async () => {
    await renderAsk();

    await askTyped('   ', { view: 'plan' });

    expect(ask).not.toHaveBeenCalled();
    expect(ctx.phase).toBe('empty');
  });

  it('answers with the summary, the events, the picks and the footer, in that order', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    const live = screen.getByTestId('ask-live');
    const order = ['ask-summary', 'ask-events', 'ask-picks', 'ask-footer']
      .map((id) => within(live).getByTestId(id));
    order.slice(1).forEach((el, i) => {
      expect(order[i].compareDocumentPosition(el)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    });
    expect(screen.getByTestId('ask-pick-name-1')).toHaveTextContent('Saltburn');
  });

  it('closes a charged answer with the count the SERVER stated', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: 2, allowanceLimit: 3 }));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer'))
      .toHaveTextContent(/^Answered from the 06:02 run · 2 of 3 left today$/);
  });

  it('shows the count the RESPONSE states, even when the allowance cannot be re-read', async () => {
    // Three left before; the server says one left after (other tabs asked too). A client that merely
    // counted down would say two.
    ask.mockResolvedValue(ownResponse({ allowanceLeft: 1, allowanceLimit: 3 }));
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    getAskSettings.mockRejectedValue(refusal(500, null, null));

    await askTyped('Where is good?', { view: 'plan' });

    expect(ctx.allowance).toMatchObject({ left: 1, limit: 3, used: 2 });
    expect(screen.getByTestId('ask-footer')).toHaveTextContent('1 of 3 left today');
  });

  it('never counts down locally: a charged answer that states no figure leaves the count where it was', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: null, allowanceLimit: null }));
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    getAskSettings.mockRejectedValue(refusal(500, null, null));

    await askTyped('Where is good?', { view: 'plan' });

    expect(ctx.allowance.left).toBe(3);
  });

  it('prefers the server’s own re-read to the response when they differ (a second tab asked too)', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: 2, allowanceLimit: 3 }));
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    getAskSettings.mockResolvedValue(settings({ used: 2, left: 1 }));

    await askTyped('Where is good?', { view: 'plan' });

    await waitFor(() => expect(ctx.allowance.left).toBe(1));
  });

  it('closes a charged answer that states no figure with the run alone', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: null, allowanceLimit: null }));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^Answered from the 06:02 run$/);
  });

  it('does not count a cache hit: charged:false closes "no question used"', async () => {
    ask.mockResolvedValue(ownResponse({ charged: false, allowanceLeft: 3 }));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer'))
      .toHaveTextContent(/^Answered from the 06:02 run · no question used$/);
    expect(ctx.allowance.left).toBe(3);
  });

  it('renders a typed reply of kind ready as a Ready answer: no question used', async () => {
    ask.mockResolvedValue(ownResponse({ kind: 'ready', charged: false, allowanceLeft: 3 }));
    await renderAsk();

    await askTyped('best spot tonight', { view: 'plan' });

    expect(ctx.kind).toBe('ready');
    expect(screen.getByTestId('ask-footer'))
      .toHaveTextContent(/^Ready answer from the 06:02 run · no question used$/);
  });

  it('drops the run from the footer when the server named none', async () => {
    ask.mockResolvedValue(ownResponse({ runLabel: null }));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^Answered · 2 of 3 left today$/);
  });

  it('answers with no picks and no events as just the summary and the footer', async () => {
    ask.mockResolvedValue(ownResponse({ picks: [], events: [] }));
    await renderAsk();

    await askTyped('Is it worth it?', { view: 'plan' });

    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-picks')).toBeNull();
    expect(screen.queryByTestId('ask-events')).toBeNull();
  });

  it.each(['LITE_USER', 'PRO_USER', 'ADMIN'])('shows an event’s safety note on a typed answer to a %s', async (role) => {
    mockRole = role;
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('When is the eclipse?', { view: 'plan' });

    expect(screen.getByRole('note')).toHaveTextContent(ECLIPSE_SAFETY_NOTE);
  });

  it('resolves what became of the question, so a field knows whether to keep the text it typed', async () => {
    await renderAsk();
    const outcome = (q) => act(async () => { outcome.last = await ctx.askTyped(q, { view: 'plan' }); });

    await outcome('   ');
    expect(outcome.last).toBe('ignored');

    ask.mockResolvedValueOnce(ownResponse());
    await outcome('Where is good?');
    expect(outcome.last).toBe('answered');

    ask.mockResolvedValueOnce(cantResponse());
    await outcome('Is the car park busy?');
    expect(outcome.last).toBe('answered');

    ask.mockRejectedValueOnce(refusal(400, 'INVALID', 'Not that.'));
    await outcome('?');
    expect(outcome.last).toBe('refused');

    ask.mockRejectedValueOnce(refusal(404, null, null));
    await outcome('Where?');
    expect(outcome.last).toBe('refused');

    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'x'));
    await outcome('Again?');
    expect(outcome.last).toBe('failed');
  });

  it('resolves "superseded" for a question a newer one overtook', async () => {
    await renderAsk();
    let release;
    ask.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
    let first;
    act(() => { first = ctx.askTyped('first?', { view: 'plan' }); });
    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('second?', { view: 'plan' });

    await act(async () => { release(ownResponse()); });

    expect(await first).toBe('superseded');
  });

  it('numbers each answer that lands, and keeps the number when a refusal puts an earlier answer back', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    const first = ctx.answer.id;
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));

    await askTyped('and again?', { view: 'plan' });
    expect(ctx.answer.id).toBe(first);

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('Where is good?', { view: 'plan' });
    const second = ctx.answer.id;
    expect(second).toBeGreaterThan(first);

    // Two answers in a row, however they arrived, never share a number.
    ask.mockResolvedValueOnce(ownResponse({ summary: 'A third answer.' }));
    await askTyped('And this?', { view: 'plan' });
    expect(ctx.answer.id).toBeGreaterThan(second);
  });
});

describe('AskConversation — Not in the forecast', () => {
  it('shows the dashed box, the sentence, what PhotoCast lacks, and two Ready questions to try', async () => {
    ask.mockResolvedValue(cantResponse());
    await renderAsk();

    await askTyped('Is the car park busy?', { view: 'plan' });

    const box = screen.getByTestId('ask-cant');
    expect(box).toHaveTextContent('Not in the forecast');
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('It has no car park information.');
    expect(screen.getByTestId('ask-missing')).toHaveTextContent('PhotoCast doesn’t have: car park information');
    const tries = within(screen.getByTestId('ask-try'));
    expect(tries.getByText('Try asking')).toBeInTheDocument();
    expect(tries.getAllByRole('button')).toHaveLength(2);
    expect(tries.getAllByText('Ready')).toHaveLength(2);
    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^No question used$/);
    expect(screen.queryByTestId('ask-picks')).toBeNull();
    expect(screen.queryByTestId('ask-events')).toBeNull();
  });

  it('reads answerable:false alone as not-in-the-forecast, whatever the kind says', async () => {
    const { kind, ...noKind } = cantResponse();
    ask.mockResolvedValue(noKind);
    await renderAsk();

    await askTyped('Is the car park busy?', { view: 'plan' });

    expect(kind).toBe('cant');
    expect(screen.getByTestId('ask-cant')).toBeInTheDocument();
    expect(ctx.phase).toBe('cant');
    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^No question used$/);
  });

  it('reads kind:cant alone as not-in-the-forecast, even from the engine with a run to name', async () => {
    ask.mockResolvedValue(cantResponse({
      answerable: true, generatedAt: '2026-10-05T05:02:11', runLabel: '06:02',
    }));
    await renderAsk();

    await askTyped('Is the car park busy?', { view: 'plan' });

    expect(screen.getByTestId('ask-cant')).toBeInTheDocument();
    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^No question used$/);
  });

  it('keeps the pre-filter’s no-briefing shape: no run is named anywhere', async () => {
    ask.mockResolvedValue(cantResponse({ generatedAt: null, runLabel: null }));
    await renderAsk();

    await askTyped('Is the car park busy?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^No question used$/);
  });

  it('opens a suggested question without a request', async () => {
    ask.mockResolvedValue(cantResponse());
    await renderAsk();
    await askTyped('Is the car park busy?', { view: 'plan' });
    ask.mockClear();

    fireEvent.click(within(screen.getByTestId('ask-try')).getByTestId('ask-ready-BEST_NEXT'));
    await finishOpening();

    expect(ask).not.toHaveBeenCalled();
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
    expect(screen.queryByTestId('ask-cant')).toBeNull();
  });

  it.each([
    ['none', [], 0],
    ['one', [{ id: 'BEST_NEXT', text: 'Best spot tonight?' }], 1],
    ['two', cantResponse().try, 2],
  ])('shows %s suggestion(s)', async (_name, tries, shown) => {
    ask.mockResolvedValue(cantResponse({ try: tries }));
    await renderAsk({ view: 'map', viewLabel: 'Map · My area' });
    await screen.findByTestId('ask-ready-list');

    await askTyped('Is the car park busy?', { view: 'map' });

    if (shown === 0) {
      expect(screen.queryByTestId('ask-try')).toBeNull();
    } else {
      expect(within(screen.getByTestId('ask-try')).getAllByRole('button')).toHaveLength(shown);
    }
  });

  it('leaves out a suggestion the reader’s own list cannot open', async () => {
    ask.mockResolvedValue(cantResponse({
      try: [{ id: 'GONE', text: 'Withdrawn?' }, { id: 'BEST_NEXT', text: 'Best spot tonight?' }],
    }));
    await renderAsk();
    await screen.findByTestId('ask-ready-list');

    await askTyped('Is the car park busy?', { view: 'plan' });

    const tries = within(screen.getByTestId('ask-try'));
    expect(tries.getAllByRole('button')).toHaveLength(1);
    expect(tries.queryByText('Withdrawn?')).toBeNull();
  });

  it('prints a 60-character missing phrase whole', async () => {
    const missing = 'x'.repeat(60);
    ask.mockResolvedValue(cantResponse({ missing }));
    await renderAsk();

    await askTyped('Is the car park busy?', { view: 'plan' });

    expect(missing).toHaveLength(60);
    expect(screen.getByTestId('ask-missing')).toHaveTextContent(`PhotoCast doesn’t have: ${missing}`);
  });

  it('prints no "doesn’t have" line when nothing is named', async () => {
    ask.mockResolvedValue(cantResponse({ missing: null }));
    await renderAsk();

    await askTyped('Is the car park busy?', { view: 'plan' });

    expect(screen.queryByTestId('ask-missing')).toBeNull();
  });
});

describe('AskConversation — what each refusal in the API table shows', () => {
  const tryAsk = async () => {
    await renderAsk();
    await askTyped('Where is good?', { view: 'plan' });
  };

  it('400 INVALID: the server’s sentence under the field, and the conversation put back', async () => {
    ask.mockRejectedValue(refusal(400, 'INVALID', 'The question contains characters we cannot use.'));
    await tryAsk();

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('The question contains characters we cannot use.');
    expect(ctx.phase).toBe('empty');
    expect(screen.getByTestId('ask-ready-list')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-error')).toBeNull();
  });

  it('400 INVALID with no sentence falls back to one', async () => {
    ask.mockRejectedValue(refusal(400, 'INVALID', null));
    await tryAsk();

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('The question could not be used.');
  });

  it('429 RATE_LIMITED: "Slow down a moment.", nothing used, and the earlier answer is still there', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    ask.mockRejectedValue(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));

    await askTyped('and again?', { view: 'plan' });

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('Slow down a moment.');
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
    expect(ctx.typedDisabled).toBe(false);
  });

  it.each([
    ['429 ALLOWANCE_EXHAUSTED', 429, 'ALLOWANCE_EXHAUSTED', 'You’ve used today’s questions. Ready questions are still available.'],
    ['429 DAILY_LIMIT', 429, 'DAILY_LIMIT', 'That’s the most questions we can take from you today.'],
  ])('%s: typed questions go off for the UK day, the sentence is shown, Ready questions still work', async (_n, status, code, error) => {
    ask.mockRejectedValue(refusal(status, code, error));
    await tryAsk();

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent(error);
    // The settings read cannot say so (it still reports three left), so the refusal is remembered.
    expect(ctx.allowance.left).toBe(3);
    expect(ctx.typedDisabled).toBe(true);
    expect(screen.getByTestId('ask-ready-BEST_NEXT')).toBeEnabled();
    fireEvent.click(screen.getByTestId('ask-ready-BEST_NEXT'));
    await finishOpening();
    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
  });

  it('503 TYPED_UNAVAILABLE: shows the sentence, and goes off only while the server says it is unavailable', async () => {
    ask.mockRejectedValue(refusal(503, 'TYPED_UNAVAILABLE', 'Typed questions are unavailable right now.'));
    await renderAsk();
    getAskSettings.mockResolvedValue(settings({ typedAvailable: false }));

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('unavailable right now');
    await waitFor(() => expect(ctx.typedDisabled).toBe(true));
  });

  it('503 TYPED_UNAVAILABLE is transient: once the server says typed questions are back, they are on', async () => {
    // A briefing rebuild or an accounting latch: the settings read, not a day-long memory, decides.
    ask.mockRejectedValue(refusal(503, 'TYPED_UNAVAILABLE', 'Typed questions are unavailable right now.'));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(2));
    expect(ctx.typedDisabled).toBe(false);
  });

  it.each([
    ['INVALID', 400, 'The question could not be used.'],
    ['RATE_LIMITED', 429, 'Slow down a moment.'],
    ['ALLOWANCE_EXHAUSTED', 429, 'You’ve used today’s questions. Ready questions are still available.'],
    ['DAILY_LIMIT', 429, 'That’s the most questions we can take from you today. Ready questions are still available.'],
    ['TYPED_UNAVAILABLE', 503, 'Typed questions are unavailable right now. Ready questions are still available.'],
  ])('%s with no sentence of its own falls back to one', async (code, status, sentence) => {
    ask.mockRejectedValue(refusal(status, code, null));
    await tryAsk();

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent(sentence);
  });

  describe('the day-long refusals end with the UK day', () => {
    // 23:30 UTC on the 5th is 00:30 BST on the 6th: the UK day has turned, the UTC day has not.
    const afterUkMidnight = new Date('2026-10-05T23:30:00Z');

    it('turns typed questions back on once the UK date has moved, however the tab is woken', async () => {
      ask.mockRejectedValue(refusal(429, 'DAILY_LIMIT', 'That’s the most.'));
      await tryAsk();
      expect(ctx.typedDisabled).toBe(true);
      getAskSettings.mockResolvedValue(settings());

      vi.setSystemTime(afterUkMidnight);
      act(() => { window.dispatchEvent(new Event('focus')); });

      await waitFor(() => expect(ctx.typedDisabled).toBe(false));
    });

    it('keeps them off at 22:30 UTC, which is 23:30 BST: the UK day has not turned yet', async () => {
      ask.mockRejectedValue(refusal(429, 'DAILY_LIMIT', 'That’s the most.'));
      await tryAsk();

      // The UK day turns at 23:00 UTC in BST, so 22:30 UTC is still the 5th there.
      vi.setSystemTime(new Date('2026-10-05T22:30:00Z'));
      act(() => { window.dispatchEvent(new Event('focus')); });

      expect(ctx.typedDisabled).toBe(true);
    });
  });

  it('turns typed questions off when the server says none are left, and on at one', async () => {
    getAskSettings.mockResolvedValue(settings({ used: 3, left: 0 }));
    const view = await renderAsk();
    await waitFor(() => expect(ctx.typedDisabled).toBe(true));
    view.unmount();

    getAskSettings.mockResolvedValue(settings({ used: 2, left: 1 }));
    await renderAsk();
    await waitFor(() => expect(ctx.allowance.left).toBe(1));
    expect(ctx.typedDisabled).toBe(false);
  });

  it('keeps "Try again" for the failure it is showing when a later question is only refused', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await renderAsk();
    await askTyped('Question one?', { view: 'plan' });
    expect(screen.getByTestId('ask-error-text')).toBeInTheDocument();

    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));
    await askTyped('Question two?', { view: 'plan' });
    // The error state for question one is back on screen, with the refusal beside it...
    expect(screen.getByTestId('ask-question')).toHaveTextContent('Question one?');
    ask.mockResolvedValueOnce(ownResponse());

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Try again' })); });

    // ...and "Try again" asks question ONE, not the one that was refused.
    expect(ask.mock.calls.map(([body]) => body.question)).toEqual(['Question one?', 'Question two?', 'Question one?']);
  });

  it('re-reads the allowance after a refusal that moved it, and not after one that did not', async () => {
    await renderAsk();
    const baseline = getAskSettings.mock.calls.length;

    ask.mockRejectedValueOnce(refusal(400, 'INVALID', 'x'));
    await askTyped('?', { view: 'plan' });
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'x'));
    await askTyped('?', { view: 'plan' });
    expect(getAskSettings).toHaveBeenCalledTimes(baseline);

    ask.mockRejectedValueOnce(refusal(429, 'ALLOWANCE_EXHAUSTED', 'x'));
    await askTyped('?', { view: 'plan' });
    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(baseline + 1));
  });

  it('502 ENGINE_FAILED: "Couldn’t answer just now. No question used." with a way to try again', async () => {
    ask.mockRejectedValue(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await tryAsk();

    expect(screen.getByTestId('ask-error-text')).toHaveTextContent('Couldn’t answer just now. No question used.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled();
    expect(ctx.phase).toBe('error');
    expect(ctx.typedDisabled).toBe(false);
  });

  it('retry asks the same question again, with the same context, and shows the answer', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await renderAsk({ view: 'map', viewLabel: 'Map · Lakes' });
    await askTyped('Where is good?', { windowId: '2026-10-05_sunset', regionIds: [3], view: 'map' });
    ask.mockResolvedValue(ownResponse());

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Try again' })); });

    await screen.findByTestId('ask-summary');
    expect(ask).toHaveBeenCalledTimes(2);
    expect(ask.mock.calls[1][0]).toEqual(ask.mock.calls[0][0]);
  });

  it('a lost connection says so — and does not claim the question was not used', async () => {
    ask.mockRejectedValue(refusal(null, null, null));
    await tryAsk();

    const text = screen.getByTestId('ask-error-text');
    expect(text).toHaveTextContent('Couldn’t reach PhotoCast');
    expect(text).not.toHaveTextContent('No question used');
  });

  it('401 UNAUTHENTICATED shows the server’s sentence as an error, not as a typo under the field', async () => {
    ask.mockRejectedValue(refusal(401, 'UNAUTHENTICATED', 'Please sign in again.'));
    await tryAsk();

    expect(screen.getByTestId('ask-error-text')).toHaveTextContent('Please sign in again.');
    expect(screen.queryByTestId('ask-input-error')).toBeNull();
  });

  it('404 (Ask switched off on the server): every surface is told to hide, and the view is put back', async () => {
    ask.mockRejectedValue(refusal(404, null, null));
    await tryAsk();

    expect(ctx.availability).toBe('off');
    expect(ctx.phase).toBe('empty');
    expect(screen.queryByTestId('ask-error')).toBeNull();
  });

  it('a settings read that says Ask is off also marks it off', async () => {
    getAskSettings.mockResolvedValue({
      enabled: false, used: 0, limit: 0, left: 0, typedAvailable: false,
    });

    await renderAsk();

    await waitFor(() => expect(ctx.availability).toBe('off'));
  });

  describe('availability — what a surface shows before and after the server has said', () => {
    it('is pending until the first settings read lands, so nothing flashes on a server with Ask off', async () => {
      let answer;
      getAskSettings.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
      await renderAsk();
      expect(ctx.availability).toBe('pending');

      await act(async () => { answer(settings()); });

      expect(ctx.availability).toBe('on');
    });

    it('is down — shown, but disabled — when the first read fails and nothing is known', async () => {
      getAskSettings.mockRejectedValue(refusal(null, null, null));

      await renderAsk();

      await waitFor(() => expect(ctx.availability).toBe('down'));
    });

    it('stays on when a LATER read fails: what was last said still stands', async () => {
      await renderAsk();
      await waitFor(() => expect(ctx.availability).toBe('on'));
      getAskSettings.mockRejectedValue(refusal(500, null, null));
      act(() => ctx.allowance.refetch());

      await waitFor(() => expect(ctx.allowance.status).toBe('failed'));
      expect(ctx.availability).toBe('on');
    });

    it('keeps typed questions OFF until the server has answered, and on once it has', async () => {
      let answer;
      getAskSettings.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
      await renderAsk();
      expect(ctx.availability).toBe('pending');
      expect(ctx.typedDisabled).toBe(true);

      await act(async () => { answer(settings()); });

      expect(ctx.typedDisabled).toBe(false);
    });

    it('keeps typed questions OFF when the server cannot be reached', async () => {
      getAskSettings.mockRejectedValue(refusal(null, null, null));
      await renderAsk();

      await waitFor(() => expect(ctx.availability).toBe('down'));
      expect(ctx.typedDisabled).toBe(true);
    });

    it('is OFF with no provider at all, so a shell rendered without one draws no Ask', () => {
      function Bare() {
        const bare = useAsk();
        return <span data-testid="bare">{`${bare.availability}|${bare.typedDisabled}`}</span>;
      }
      render(<Bare />);

      expect(screen.getByTestId('bare')).toHaveTextContent('off|true');
    });
  });

  it('a 200 with no summary is an error, not an empty answer', async () => {
    ask.mockResolvedValue({ answerable: true, kind: 'own' });
    await tryAsk();

    expect(ctx.phase).toBe('error');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
  });

  it('clears an earlier refusal’s sentence when the next question is asked', async () => {
    ask.mockRejectedValueOnce(refusal(400, 'INVALID', 'Not that.'));
    await renderAsk();
    await askTyped('?', { view: 'plan' });
    expect(screen.getByTestId('ask-input-error')).toBeInTheDocument();
    ask.mockResolvedValue(ownResponse());

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.queryByTestId('ask-input-error')).toBeNull();
  });
});

describe('AskConversation — focus survives a press that unmounts the control', () => {
  const root = () => screen.getByTestId('ask-conversation');

  it('moves focus to the conversation when a suggestion is pressed', async () => {
    await renderAsk();
    const suggestion = await screen.findByTestId('ask-ready-BEST_NEXT');
    suggestion.focus();
    expect(suggestion).toHaveFocus();

    fireEvent.click(suggestion);

    expect(screen.queryByTestId('ask-ready-BEST_NEXT')).toBeNull();
    expect(document.activeElement).toBe(root());
    expect(root().contains(document.activeElement)).toBe(true);
  });

  it('moves focus to the conversation when "Try again" is pressed', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await renderAsk();
    await askTyped('Where is good?', { view: 'plan' });
    const retry = screen.getByRole('button', { name: 'Try again' });
    retry.focus();
    ask.mockReturnValue(new Promise(() => {}));

    fireEvent.click(retry);

    expect(document.activeElement).toBe(root());
  });

  it('moves focus to the conversation when a window chip is removed', async () => {
    await renderAsk({
      view: 'map', viewLabel: 'Map · My area', windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset',
    });
    const remove = screen.getByRole('button', { name: 'Remove Mon sunset from the question' });
    remove.focus();

    fireEvent.click(remove);

    expect(screen.queryByTestId('ask-chip-window')).toBeNull();
    expect(document.activeElement).toBe(root());
  });

  it('moves focus when a Not-in-the-forecast suggestion is pressed too', async () => {
    ask.mockResolvedValue(cantResponse());
    await renderAsk();
    await askTyped('Is the car park busy?', { view: 'plan' });
    const suggestion = within(screen.getByTestId('ask-try')).getByTestId('ask-ready-BEST_NEXT');
    suggestion.focus();

    fireEvent.click(suggestion);

    expect(document.activeElement).toBe(root());
  });

  it('is a programmatic target only: not a tab stop', async () => {
    await renderAsk();

    expect(root()).toHaveAttribute('tabindex', '-1');
  });
});

describe('AskConversation — storage', () => {
  it('writes nothing — no cache, no storage — for an answer, and starts the next reader empty', async () => {
    ask.mockResolvedValue(ownResponse());
    const view = await renderAsk();
    await screen.findByTestId('ask-ready-list');
    const write = vi.spyOn(Storage.prototype, 'setItem');

    await askTyped('Where is good?', { view: 'plan' });
    await openReadyById('BEST_NEXT');
    await finishOpening();

    const written = write.mock.calls.map(([key, value]) => `${key}=${value}`).join('\n');
    // The briefing's own SWR entry legitimately names Saltburn; what must never be stored is anything
    // Ask itself produced: the question, either answer's prose, the allowance.
    const askText = /Whitby is the best|Saltburn is your best bet|Where is good|photocast_ask/;
    expect(written).not.toMatch(askText);
    const stored = Object.keys(localStorage).map((k) => localStorage.getItem(k)).join('\n');
    expect(stored).not.toMatch(askText);
    expect(Object.keys(localStorage).filter((k) => /ask/i.test(k))).toEqual([]);
    write.mockRestore();

    // A logout unmounts the tree; the next reader's provider starts from nothing.
    view.unmount();
    await renderAsk();
    expect(ctx).toMatchObject({ phase: 'empty', answer: null, question: '' });
    expect(screen.queryByTestId('ask-summary')).toBeNull();
  });
});

describe('AskContext — a late response is dropped only once it has landed', () => {
  let pending;
  beforeEach(() => {
    pending = [];
    ask.mockImplementation(() => new Promise((resolve, reject) => { pending.push({ resolve, reject }); }));
  });

  /** Starts a question and leaves it out; returns once it is pending. */
  const start = (q) => act(async () => { ctx.askTyped(q, { view: 'plan' }); });
  const settle = (i, body) => act(async () => { pending[i].resolve(body); });
  const fail = (i, error) => act(async () => { pending[i].reject(error); });

  it('drops an answer that lands after clear()', async () => {
    await renderAsk();
    await start('Where is good?');
    act(() => ctx.clear());
    expect(ctx.phase).toBe('empty');

    await settle(0, ownResponse());

    expect(ctx.phase).toBe('empty');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
  });

  it('drops the older answer when a newer question has been asked, and shows the newer one', async () => {
    await renderAsk();
    await start('first?');
    await start('second?');

    await settle(1, ownResponse({ summary: 'The second answer.' }));
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The second answer.');

    await settle(0, ownResponse({ summary: 'The first answer, late.' }));

    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The second answer.');
    expect(ctx.question).toBe('second?');
  });

  it('drops the older answer when it lands BEFORE the newer one, too', async () => {
    await renderAsk();
    await start('first?');
    await start('second?');

    await settle(0, ownResponse({ summary: 'The first answer.' }));

    expect(ctx.phase).toBe('busy');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
    await settle(1, ownResponse({ summary: 'The second answer.' }));
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The second answer.');
  });

  it('drops a late FAILURE too: an older request failing cannot turn the newer answer into an error', async () => {
    await renderAsk();
    await start('first?');
    await start('second?');
    await settle(1, ownResponse({ summary: 'The second answer.' }));

    await fail(0, refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));

    expect(ctx.phase).toBe('answer');
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The second answer.');
  });

  it('drops a late refusal too: it neither blocks typed questions nor writes a sentence', async () => {
    await renderAsk();
    await start('first?');
    act(() => ctx.clear());

    await fail(0, refusal(429, 'ALLOWANCE_EXHAUSTED', 'You’ve used today’s questions.'));

    expect(ctx.typedDisabled).toBe(false);
    expect(ctx.inputError).toBeNull();
  });

  it('drops a typed answer when a Ready answer was opened after it was asked', async () => {
    await renderAsk();
    await start('first?');
    await openReadyById('BEST_NEXT');
    await finishOpening();

    await settle(0, ownResponse({ summary: 'A typed answer, late.' }));

    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
  });

  it('re-reads the allowance when a charged answer is dropped, without applying its own figure', async () => {
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    const baseline = getAskSettings.mock.calls.length;
    await start('first?');
    act(() => ctx.clear());

    // The re-read is left pending, so the only thing that could move the count before it lands is the
    // dropped response's own figure.
    getAskSettings.mockReturnValue(new Promise(() => {}));
    await settle(0, ownResponse({ allowanceLeft: 0, allowanceLimit: 3 }));

    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(baseline + 1));
    expect(ctx.allowance.left).toBe(3);
    expect(ctx.typedDisabled).toBe(false);
  });

  it('a Ready answer still opening is cancelled by clear(): it never lands', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    expect(ctx.phase).toBe('busy');

    act(() => ctx.clear());
    await act(async () => { await vi.advanceTimersByTimeAsync(READY_OPEN_MS * 3); });

    expect(ctx.phase).toBe('empty');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
  });

  it('a Ready answer still opening is replaced by the next one the reader taps — the first never flashes', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await act(async () => { await vi.advanceTimersByTimeAsync(200); });
    await openReadyById('AM_OR_PM');

    // The first answer's own 400 ms ends here. If its timer were still armed it would land now.
    await act(async () => { await vi.advanceTimersByTimeAsync(250); });
    expect(ctx.phase).toBe('busy');
    expect(screen.queryByTestId('ask-summary')).toBeNull();

    await act(async () => { await vi.advanceTimersByTimeAsync(150); });
    expect(ctx.question).toBe('Sunrise or sunset tomorrow?');
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Tomorrow’s sunrise at Whitby');
  });

  it('a Ready answer still opening does not land over a typed question asked after it', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');

    await start('Where is good?');
    await act(async () => { await vi.advanceTimersByTimeAsync(READY_OPEN_MS * 2); });

    expect(ctx.phase).toBe('busy');
    expect(ctx.kind).toBe('own');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
    await settle(0, ownResponse({ summary: 'The typed answer.' }));
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The typed answer.');
  });

  it('holds no timer once it unmounts mid-open', async () => {
    // Without the briefing provider the Ready answer's own timer is the only one the provider holds.
    const view = render(<AskProvider><Capture /></AskProvider>);
    await waitFor(() => expect(ctx).toBeDefined());
    act(() => ctx.openReady(readyResponse().questions[0]));
    expect(vi.getTimerCount()).toBe(1);

    view.unmount();

    expect(vi.getTimerCount()).toBe(0);
  });

});
