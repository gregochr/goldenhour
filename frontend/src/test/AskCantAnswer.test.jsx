/**
 * "Not in the forecast" (`components/ask/AskCantAnswer.jsx`), split from `AskConversation.test.jsx`.
 */

import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  fireEvent, screen, within,
} from '@testing-library/react';
import {
  auth, ctx, renderAsk, askTyped, finishOpening, installAskConversationMocks,
  removeAskConversationMocks,
} from './askConversationHarness.jsx';
import {
  cantResponse,
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
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: auth.role }) }));
import { ask } from '../api/askApi.js';

beforeEach(installAskConversationMocks);
afterEach(removeAskConversationMocks);

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
