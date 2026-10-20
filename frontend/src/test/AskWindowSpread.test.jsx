/**
 * The server's figures beside the model's sentence (`components/ask/AskWindowSpread.jsx`;
 * ask-window-context plan §2.4, §6 Q4 — W2): drawn under the summary, inside the answer's live region,
 * for every role, on a typed answer and on a Ready one; absent whenever the answer carries no windows.
 * The words themselves are `askSpread.test.js`'s.
 */

import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, within } from '@testing-library/react';
import {
  auth, askTyped, renderAsk, tapReady, finishOpening,
  installAskConversationMocks, removeAskConversationMocks,
} from './askConversationHarness.jsx';
import { ownResponse, readyResponse } from './askFixtures.js';
import AskWindowSpread from '../components/ask/AskWindowSpread.jsx';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn(), fetchRegionDriveTimes: vi.fn() }));
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: auth.role }) }));
import { ask, getReady } from '../api/askApi.js';

beforeEach(installAskConversationMocks);
afterEach(removeAskConversationMocks);

/** The owner's weekend as W1 serves it: a stay-home Saturday with two exceptions, a Sunday with none. */
const WINDOWS = [
  {
    windowId: '2026-10-17_sunset',
    spread: {
      over: 'every region', rated: 180, bands: [20, 60, 98, 2, 0], gameOn: 2, gameOnPct: 1, go: 0,
      below4: 178, stars1to2: 80, regions: { counted: 6 }, pockets: [{ region: 'Northumberland', gameOn: 2, mean: 3.1 }],
    },
  },
  {
    windowId: '2026-10-18_sunrise',
    spread: {
      over: 'every region', rated: 150, bands: [30, 70, 50, 0, 0], gameOn: 0, gameOnPct: 0, go: 0,
      below4: 150, stars1to2: 100, regions: { counted: 6 }, pockets: [],
    },
  },
];

const SAT_LINE = 'Sat sunset · 180 rated across every region · 2 at 4★+';
const SUN_LINE = 'Sun sunrise · 150 rated across every region · none at 4★+';

describe('AskWindowSpread — on a typed answer', () => {
  it.each(['LITE_USER', 'PRO_USER', 'ADMIN'])('draws one line per carried window under the summary, inside the live region, for a %s', async (role) => {
    auth.role = role;
    ask.mockResolvedValue(ownResponse({ windows: WINDOWS }));
    await renderAsk();

    await askTyped('Best spot this weekend?', { view: 'plan' });

    const live = screen.getByTestId('ask-live');
    const list = within(live).getByRole('list', { name: 'Rated places, by window' });
    const lines = within(list).getAllByTestId('ask-spread-line');
    expect(lines.map((li) => li.querySelector('[aria-hidden="true"]').textContent)).toEqual([SAT_LINE, SUN_LINE]);
    // Under the summary, above the events and picks: the figure stands beside the sentence it qualifies.
    const summary = within(live).getByTestId('ask-summary');
    expect(summary.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const events = within(live).getByTestId('ask-events');
    expect(list.compareDocumentPosition(events) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('speaks the figures as counts: the glyph is hidden and the spoken twin has no star', () => {
    render(<AskWindowSpread windows={[WINDOWS[0]]} />);

    const line = screen.getByTestId('ask-spread-line');
    expect(line.querySelector('[aria-hidden="true"]')).toHaveTextContent(SAT_LINE);
    expect(line.querySelector('.sr-only')).toHaveTextContent('Sat sunset: 180 rated across every region, 2 at 4 stars or more');
    expect(line.querySelector('.sr-only').textContent).not.toContain('★');
    expect(line).toHaveAttribute('data-window', '2026-10-17_sunset');
  });

  it('draws nothing for an answer that carries no windows — every answer stored before W1', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('Best spot this weekend?', { view: 'plan' });

    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-spread')).toBeNull();
  });

  it('draws nothing for a can’t-answer, which the server never gives windows', async () => {
    ask.mockResolvedValue({
      answerable: false, kind: 'cant', summary: 'No car park information.', picks: [], events: [],
      missing: 'car park information', try: [], allowanceLeft: 3, allowanceLimit: 3, charged: false,
      generatedAt: null, runLabel: null, windows: WINDOWS,
    });
    await renderAsk();

    await askTyped('Is the car park busy?', { view: 'plan' });

    expect(screen.getByTestId('ask-summary')).toHaveTextContent('No car park information.');
    expect(screen.queryByTestId('ask-spread')).toBeNull();
  });

  it('stands under a no-pick answer too — the stay-home weekend is the case it exists for', async () => {
    ask.mockResolvedValue(ownResponse({ picks: [], events: [], windows: WINDOWS }));
    await renderAsk();

    await askTyped('Best spot this weekend?', { view: 'plan' });

    expect(screen.queryByTestId('ask-picks')).toBeNull();
    expect(screen.getAllByTestId('ask-spread-line')).toHaveLength(2);
  });
});

describe('AskWindowSpread — on a Ready answer', () => {
  it('draws the lines a stored Ready answer carries, for a LITE reader, with no request', async () => {
    auth.role = 'LITE_USER';
    const ready = readyResponse();
    ready.questions[0].answer.windows = [WINDOWS[0]];
    getReady.mockResolvedValue(ready);
    await renderAsk();

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(await screen.findByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
    const list = within(screen.getByTestId('ask-live')).getByTestId('ask-spread');
    expect(within(list).getByTestId('ask-spread-line').querySelector('[aria-hidden="true"]')).toHaveTextContent(SAT_LINE);
    expect(ask).not.toHaveBeenCalled();
  });

  it('draws nothing for a Ready answer stored without them', async () => {
    await renderAsk();

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(await screen.findByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
    expect(screen.queryByTestId('ask-spread')).toBeNull();
  });
});

describe('AskWindowSpread — alone', () => {
  it('renders nothing at all, not an empty list, with no lines to draw', () => {
    const { container } = render(<AskWindowSpread windows={[]} />);
    expect(container).toBeEmptyDOMElement();
    expect(render(<AskWindowSpread />).container).toBeEmptyDOMElement();
  });
});
