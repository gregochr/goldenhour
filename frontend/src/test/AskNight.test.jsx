/**
 * Ask PhotoCast's night pick (`docs/engineering/ask-night-plan.md` §2.6, N3): the card a night pick draws, the
 * night payloads it joins to and WHEN they are fetched, what a LITE reader never gets, the thread's night
 * version and reset line, and the night "Plan this" view.
 *
 * <p>The conversation is the real {@code AskProvider} over the real {@code AskConversation}; the briefing
 * provider is spied (the shell suites' idiom) so a test chooses the role, and the two night endpoints are
 * mocks — which is what lets a test assert a request was NOT made.
 */
import React, { useEffect } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import { AskProvider, useAsk } from '../context/AskContext.jsx';
import AskConversation from '../components/ask/AskConversation.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import {
  astroRow, auroraRow, briefing, KIELDER, nightPick, NOW, ownResponse, pick, readyResponse, settings,
} from './askFixtures.js';

vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));
vi.mock('../api/auroraApi.js', () => ({ getAuroraForecastResults: vi.fn() }));
import { ask, getAskSettings, getReady } from '../api/askApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAuroraForecastResults } from '../api/auroraApi.js';

const TODAY = '2026-10-05';
const TOMORROW = '2026-10-06';

/** What the spied provider returns; a test replaces fields before rendering. */
let ctx;
const baseCtx = (over = {}) => ({
  briefing: briefing(),
  reachById: new Map([[KIELDER, { driveMinutes: 95, distanceMiles: 60 }]]),
  effectiveReachById: new Map(),
  scoreRows: [],
  homePlace: 'Newcastle',
  isPro: true,
  todayStr: TODAY,
  tomorrowStr: TOMORROW,
  ...over,
});

let conv;
function Capture() {
  const value = useAsk();
  useEffect(() => { conv = value; });
  return null;
}

const tree = (props = {}) => (
  <AskProvider>
    <Capture />
    <AskConversation view="plan" viewLabel="Plan · all regions" {...props} />
  </AskProvider>
);

const nightAnswer = (over = {}) => ownResponse({
  summary: 'Kielder is the clearest dark sky tonight.',
  picks: [nightPick()],
  events: [],
  nightVersion: 'v1',
  ...over,
});

/** Renders, waits for Ask to be on, and asks one typed question. */
async function renderAsk(props = {}) {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockImplementation(() => ctx);
  const view = render(tree(props));
  await waitFor(() => expect(conv.availability).toBe('on'));
  return view;
}
const askTyped = (text) => act(async () => { await conv.askTyped(text, { view: 'plan' }); });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  vi.resetAllMocks();
  localStorage.clear();
  ctx = baseCtx();
  conv = undefined;
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  getAstroConditions.mockImplementation(async (date) => [astroRow({ forecastDate: date })]);
  getAuroraForecastResults.mockImplementation(async () => [auroraRow()]);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('the night pick card', () => {
  it('draws the night: the place, "4★ sky", the kind, "Tonight · 20:40–05:10", the Bortle class and the HOME drive', async () => {
    ask.mockResolvedValue(nightAnswer());
    await renderAsk();

    await askTyped('Clear skies tonight?');

    expect(await screen.findByTestId('ask-pick-1')).toBeInTheDocument();
    expect(screen.getByTestId('ask-pick-name-1')).toHaveTextContent('Kielder Observatory');
    expect(screen.getByTestId('ask-pick-score-1-sky')).toHaveTextContent('4★ sky');
    expect(within(screen.getByTestId('ask-pick-1')).getByText('ASTRO')).toHaveAttribute('data-target', 'ASTRO');
    expect(screen.getByTestId('ask-pick-when-1')).toHaveTextContent('Tonight · 20:40–05:10');
    expect(screen.getByTestId('ask-pick-bortle-1')).toHaveTextContent('Bortle 3');
    expect(screen.getByTestId('ask-pick-drive-1')).toHaveTextContent('1h 35min');
    expect(screen.getByTestId('ask-pick-drive-1')).toHaveTextContent('Drive from home');
  });

  it('has no verdict word and no tide: a night has neither', async () => {
    ask.mockResolvedValue(nightAnswer());
    await renderAsk();
    await askTyped('Clear skies tonight?');
    await screen.findByTestId('ask-pick-1');

    expect(screen.getByTestId('ask-pick-1')).not.toHaveTextContent(/worth it|maybe|poor|not scored/i);
    expect(screen.queryByTestId('ask-pick-tide-1')).toBeNull();
  });

  it('says each star once to a screen reader, and names the night and kind on the rank circle', async () => {
    ask.mockResolvedValue(nightAnswer());
    await renderAsk();
    await askTyped('Clear skies tonight?');
    await screen.findByTestId('ask-pick-1');

    const pill = screen.getByTestId('ask-pick-score-1-sky');
    expect(within(pill).getByText('4★ sky')).toHaveAttribute('aria-hidden', 'true');
    expect(pill).toHaveTextContent('Sky forecast 4 stars');
    expect(screen.getByTestId('ask-pick-rank-1')).toHaveAccessibleName(
      'Pick 1, Kielder Observatory, Tonight astro, 4 stars',
    );
  });

  it('for PRO, shows "aurora 3★" beside the sky star when an aurora pick makes that night\'s payload held', async () => {
    ask.mockResolvedValue(nightAnswer({
      picks: [
        nightPick({ rank: 1 }),
        nightPick({ rank: 2, targetType: 'AURORA', windowId: '2026-10-05_aurora', ratingAtAnswer: 3 }),
      ],
    }));
    await renderAsk();

    await askTyped('Aurora or stars tonight?');

    await screen.findByTestId('ask-pick-2');
    expect(screen.getByTestId('ask-pick-score-1-aurora')).toHaveTextContent('aurora 3★');
    // The aurora pick leads with its own star.
    const aurora = within(screen.getByTestId('ask-pick-2')).getAllByTestId(/ask-pick-score-2-/);
    expect(aurora.map((n) => n.textContent)).toEqual([
      expect.stringContaining('aurora 3★'), expect.stringContaining('4★ sky'),
    ]);
  });

  it('for LITE, shows the astro card alone and NEVER requests the aurora payload', async () => {
    ctx = baseCtx({ isPro: false });
    ask.mockResolvedValue(nightAnswer());
    await renderAsk();

    await askTyped('Clear skies tonight?');

    await screen.findByTestId('ask-pick-1');
    expect(screen.getByTestId('ask-pick-score-1-sky')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-pick-score-1-aurora')).toBeNull();
    expect(getAstroConditions).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastResults).not.toHaveBeenCalled();
  });

  it('⚠️ for LITE, an aurora pick has no card and the aurora endpoint is still not touched', async () => {
    ctx = baseCtx({ isPro: false });
    ask.mockResolvedValue(nightAnswer({
      picks: [nightPick({ targetType: 'AURORA', windowId: '2026-10-05_aurora', ratingAtAnswer: 3 })],
    }));
    await renderAsk();

    await askTyped('Any aurora tonight?');
    await act(async () => {});

    expect(screen.queryByTestId('ask-pick-1')).toBeNull();
    expect(getAuroraForecastResults).not.toHaveBeenCalled();
    expect(getAstroConditions).not.toHaveBeenCalled();
  });

  it('drops a pick whose place is not on the night\'s payload, and never renumbers the ones that stay', async () => {
    ask.mockResolvedValue(nightAnswer({
      picks: [
        nightPick({ rank: 1, locationId: 999, locationName: 'Gone' }),
        nightPick({ rank: 2 }),
      ],
    }));
    await renderAsk();

    await askTyped('Clear skies tonight?');

    await screen.findByTestId('ask-pick-2');
    expect(screen.queryByTestId('ask-pick-1')).toBeNull();
    expect(screen.getByTestId('ask-pick-2')).toHaveTextContent('Kielder Observatory');
  });

  it('draws a solar pick and a night pick together from their own sources', async () => {
    ask.mockResolvedValue(nightAnswer({ picks: [pick({ rank: 1 }), nightPick({ rank: 2 })] }));
    await renderAsk();

    await askTyped('Best spot tonight, sunset or stars?');

    await screen.findByTestId('ask-pick-2');
    expect(screen.getByTestId('ask-pick-score-1')).toHaveAttribute('data-tier', 'WORTH_IT');
    expect(screen.getByTestId('ask-pick-score-2-sky')).toBeInTheDocument();
  });
});

describe('the night payloads are fetched by the dates the picks name', () => {
  it('asks for each distinct astro date once — and for no date the answer does not name', async () => {
    ask.mockResolvedValue(nightAnswer({
      picks: [
        nightPick({ rank: 1, date: TODAY }),
        nightPick({ rank: 2, date: TOMORROW, windowId: '2026-10-06_astro' }),
        nightPick({ rank: 3, date: TOMORROW, windowId: '2026-10-06_astro', locationId: 999 }),
      ],
    }));
    await renderAsk();

    await askTyped('Clear skies this week?');
    await screen.findByTestId('ask-pick-1');

    expect(getAstroConditions.mock.calls.map(([d]) => d).sort()).toEqual([TODAY, TOMORROW]);
    expect(getAuroraForecastResults).not.toHaveBeenCalled();
  });

  it('asks for each distinct aurora date once, for PRO', async () => {
    ask.mockResolvedValue(nightAnswer({
      picks: [
        nightPick({ rank: 1, targetType: 'AURORA', windowId: '2026-10-05_aurora', ratingAtAnswer: 3 }),
        nightPick({ rank: 2, targetType: 'AURORA', windowId: '2026-10-05_aurora', ratingAtAnswer: 3, locationId: 131 }),
      ],
    }));
    await renderAsk();

    await askTyped('Aurora tonight?');
    await screen.findByTestId('ask-pick-1');

    expect(getAuroraForecastResults).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastResults).toHaveBeenCalledWith(TODAY);
    expect(getAstroConditions).not.toHaveBeenCalled();
  });

  it('⚠️ never works out "tonight": a pick for the RUNNING night before dawn (yesterday) gets its card', async () => {
    // 00:30 UK on the 6th: the civil date is the 6th, the night in progress is the 5th's.
    vi.setSystemTime(new Date('2026-10-05T23:30:00Z'));
    ctx = baseCtx({ todayStr: TOMORROW, tomorrowStr: '2026-10-07' });
    ask.mockResolvedValue(nightAnswer({
      picks: [nightPick({ date: TODAY, windowId: '2026-10-05_astro' })],
    }));
    await renderAsk();

    await askTyped('Clear skies right now?');

    expect(await screen.findByTestId('ask-pick-1')).toBeInTheDocument();
    expect(getAstroConditions).toHaveBeenCalledWith(TODAY);
    expect(getAstroConditions).not.toHaveBeenCalledWith(TOMORROW);
    // The Map pill's own word for a night that is not today's or tomorrow's: the weekday.
    expect(screen.getByTestId('ask-pick-when-1')).toHaveTextContent('Monday night');
  });

  it('fetches nothing for an answer of solar picks', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('Where is good?');
    await screen.findByTestId('ask-pick-1');

    expect(getAstroConditions).not.toHaveBeenCalled();
    expect(getAuroraForecastResults).not.toHaveBeenCalled();
  });

  it('a failed fetch leaves that night\'s picks without a card — and the answer\'s sentence standing', async () => {
    getAstroConditions.mockRejectedValue(new Error('network'));
    ask.mockResolvedValue(nightAnswer());
    await renderAsk();

    await askTyped('Clear skies tonight?');
    await screen.findByTestId('ask-summary');
    await act(async () => {});

    expect(screen.queryByTestId('ask-pick-1')).toBeNull();
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Kielder is the clearest dark sky tonight.');
  });

  it('asks again when the forecast is rebuilt, and the card follows the new figure without blinking out', async () => {
    ask.mockResolvedValue(nightAnswer());
    const view = await renderAsk();
    await askTyped('Clear skies tonight?');
    await screen.findByTestId('ask-pick-1');
    expect(screen.getByTestId('ask-pick-score-1-sky')).toHaveTextContent('4★ sky');
    getAstroConditions.mockImplementation(async () => [astroRow({ stars: 5 })]);

    // A new run: the briefing's own `generatedAt` moves.
    ctx = baseCtx({ briefing: { ...briefing(), generatedAt: '2026-10-05T14:31:00' } });
    view.rerender(tree());

    await waitFor(() => expect(screen.getByTestId('ask-pick-score-1-sky')).toHaveTextContent('5★ sky'));
    expect(getAstroConditions).toHaveBeenCalledTimes(2);
  });

  it('holds nothing across answers: a new answer fetches its own dates, and a cleared one holds none', async () => {
    ask.mockResolvedValueOnce(nightAnswer());
    await renderAsk();
    await askTyped('Clear skies tonight?');
    await screen.findByTestId('ask-pick-1');

    ask.mockResolvedValueOnce(nightAnswer({
      picks: [nightPick({ date: TOMORROW, windowId: '2026-10-06_astro' })],
    }));
    await askTyped('And tomorrow?');
    await screen.findByTestId('ask-pick-1');

    expect(getAstroConditions.mock.calls.map(([d]) => d)).toEqual([TODAY, TOMORROW]);
    expect(screen.getByTestId('ask-pick-when-1')).toHaveTextContent('Tomorrow night');
  });
});

describe('the thread carries the night version', () => {
  const lastBody = () => ask.mock.calls.at(-1)[0];

  it('echoes the night answer\'s nightVersion on its exchange in the next question, as generatedAt is', async () => {
    ask.mockResolvedValueOnce(nightAnswer({ nightVersion: 'astro:2026-10-05T14:30|MODERATE' }));
    await renderAsk();
    await askTyped('Clear skies tonight?');
    await screen.findByTestId('ask-pick-1');

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('Anywhere closer?');

    expect(lastBody().thread).toHaveLength(1);
    expect(lastBody().thread[0].nightVersion).toBe('astro:2026-10-05T14:30|MODERATE');
    expect(lastBody().thread[0].picks).toEqual([{ locationId: KIELDER, windowId: '2026-10-05_astro' }]);
  });

  it('⚠️ sends no nightVersion at all for an exchange that named no night', async () => {
    ask.mockResolvedValueOnce(ownResponse());
    await renderAsk();
    await askTyped('Where is good?');
    await screen.findByTestId('ask-pick-1');

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('Anywhere closer?');

    expect(lastBody().thread[0]).not.toHaveProperty('nightVersion');
  });

  it('echoes a Ready night answer\'s version too, from the Ready question it sits in', async () => {
    await renderAsk();
    const readyQuestion = {
      id: 'NIGHT_SKY',
      text: 'Anything for the night sky tonight?',
      tabs: ['plan'],
      generatedAt: '2026-10-05T14:00:00',
      runLabel: '14:00',
      nightVersion: 'astro:2026-10-05T14:30',
      answer: {
        answerable: true, kind: 'ready', summary: 'Kielder is clear.', picks: [nightPick()], events: [], try: [],
      },
    };
    await act(async () => { conv.openReady(readyQuestion, { view: 'plan' }); });
    await waitFor(() => expect(conv.phase).toBe('answer'));

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('Anywhere closer?');

    expect(lastBody().thread[0]).toMatchObject({ ready: true, nightVersion: 'astro:2026-10-05T14:30' });
  });

  it('tells the reader the NIGHT forecast moved when the server says so', async () => {
    ask.mockResolvedValueOnce(nightAnswer());
    await renderAsk();
    await askTyped('Clear skies tonight?');
    await screen.findByTestId('ask-pick-1');

    ask.mockResolvedValueOnce(nightAnswer({
      summary: 'A fresh answer.', threadReset: true, threadResetReason: 'night forecast updated',
    }));
    await askTyped('Anywhere closer?');

    expect(screen.getByTestId('ask-thread-reset')).toHaveTextContent(
      'The night forecast has updated since your last question — this is a fresh answer.',
    );
  });
});

describe('"Plan this" for a night pick', () => {
  const planActions = () => ({ openInPlan: vi.fn(), setPostcode: vi.fn() });

  async function planNight(props = {}) {
    ask.mockResolvedValue(nightAnswer());
    await renderAsk(props);
    await askTyped('Clear skies tonight?');
    await screen.findByTestId('ask-pick-1');
    await act(async () => { fireEvent.click(screen.getByTestId('ask-plan-this-1')); });
  }

  it('shows the night\'s start-based departure, the home drive, the dark window and the sky', async () => {
    await planNight();

    expect(screen.getByTestId('ask-plan-name')).toHaveTextContent('Kielder Observatory');
    expect(screen.getByTestId('ask-plan-when')).toHaveTextContent('Tonight · 20:40–05:10');
    expect(screen.getByTestId('ask-plan-score-sky')).toHaveTextContent('4★ sky');
    // 20:40 BST dusk, a 95-minute drive and the sheet's own setup allowance (leaveByParts).
    expect(screen.getByTestId('ask-plan-leave-value')).toHaveTextContent(/\d{2}:\d{2}/);
    expect(screen.getByTestId('ask-plan-drive-value')).toHaveTextContent('1h 35min');
    expect(screen.getByTestId('ask-plan-window-value')).toHaveTextContent('20:40–05:10');
    expect(screen.getByTestId('ask-plan-sky-value')).toHaveTextContent('Bortle 3 · Waxing Crescent · 23%');
  });

  it('has no golden/blue light cell and no tide cell: those are solar', async () => {
    await planNight();

    expect(screen.queryByTestId('ask-plan-light')).toBeNull();
    expect(screen.queryByTestId('ask-plan-tide')).toBeNull();
  });

  it('the note is the served astro summary, then the served cloud line', async () => {
    await planNight();

    expect(screen.getByTestId('ask-plan-note'))
      .toHaveTextContent('Clear and dark: low cloud, good visibility, little moon.');
    expect(screen.getByTestId('ask-plan-clear-sky')).toHaveTextContent('Cloud cover 12% — clear for most of the night.');
  });

  it('⚠️ offers no "Open in Plan ›": the location sheet\'s windows are solar and hold no night', async () => {
    const actions = planActions();
    await planNight({ planActions: actions });

    expect(screen.queryByTestId('ask-plan-open')).toBeNull();
    expect(actions.openInPlan).not.toHaveBeenCalled();
  });

  it('still offers "Open in Plan ›" for a SOLAR pick on the same surface — the omission is the night\'s alone', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk({ planActions: planActions() });
    await askTyped('Where is good?');
    await screen.findByTestId('ask-pick-1');
    await act(async () => { fireEvent.click(screen.getByTestId('ask-plan-this-1')); });

    expect(screen.getByTestId('ask-plan-open')).toBeInTheDocument();
  });

  it('the figures are dashes, not inventions, when the drive and the window are not served', async () => {
    ctx = baseCtx({ reachById: new Map() });
    getAstroConditions.mockImplementation(async () => [astroRow({ nightStart: null, nightEnd: null })]);
    await planNight();

    expect(screen.getByTestId('ask-plan-leave-value')).toHaveTextContent('Not known');
    expect(screen.getByTestId('ask-plan-drive-value')).toHaveTextContent('Not known');
    expect(screen.getByTestId('ask-plan-window-value')).toHaveTextContent('Not known');
  });
});
