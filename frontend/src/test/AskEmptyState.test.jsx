/**
 * The empty state — the Ready suggestions, the allowance line and the "Asking about" chips
 * (`components/ask/AskEmptyState.jsx`), split from `AskConversation.test.jsx`.
 */

import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, screen, waitFor, within,
} from '@testing-library/react';
import {
  auth, ctx, mapCtx, tree, renderAsk, refusal, askTyped, installAskConversationMocks,
  removeAskConversationMocks,
} from './askConversationHarness.jsx';
import {
  briefing, ownResponse, readyResponse, settings,
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
import { getDailyBriefing } from '../api/briefingApi.js';
import { ask, getAskSettings, getReady } from '../api/askApi.js';

beforeEach(installAskConversationMocks);
afterEach(removeAskConversationMocks);

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
    await renderAsk({ view: 'map', mapContext: mapCtx({ regionIds: [3] }) });
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
      auth.role = role;
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
      auth.role = 'PRO_USER';
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
      await renderAsk({
        view: 'map',
        viewLabel: 'Map · My area',
        mapContext: mapCtx({ windowLabel: 'Sat sunrise', windowId: '2026-10-10_sunrise' }),
      });

      expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Sat sunrise');
      expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · My area');
    });

    it('removes the window chip with its ✕, and the question is then sent without the window', async () => {
      ask.mockResolvedValue(ownResponse());
      await renderAsk({
        view: 'map',
        viewLabel: 'Map · My area',
        mapContext: mapCtx({ windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset' }),
      });

      fireEvent.click(screen.getByRole('button', { name: 'Remove Mon sunset from the question' }));

      expect(screen.queryByTestId('ask-chip-window')).toBeNull();
      expect(ctx.removedWindow).toBe('2026-10-05_sunset');
      await askTyped('What about Sunday?', { windowId: '2026-10-05_sunset', regionIds: [3], view: 'map' });
      expect(ask).toHaveBeenCalledWith({ question: 'What about Sunday?', regionIds: [3], view: 'map' });
    });

    it('puts the window back when the surface restores it (a tab switch)', async () => {
      await renderAsk({
        view: 'map',
        viewLabel: 'Map · My area',
        mapContext: mapCtx({ windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset' }),
      });
      fireEvent.click(screen.getByTestId('ask-chip-window-remove'));
      expect(ctx.removedWindow).toBe('2026-10-05_sunset');

      act(() => ctx.restoreContextWindow());

      expect(screen.getByTestId('ask-chip-window')).toBeInTheDocument();
      expect(ctx.removedWindow).toBeNull();
    });

    it('brings the chip back by itself when a DIFFERENT window arrives — a removal is of that window only', async () => {
      ask.mockResolvedValue(ownResponse());
      const props = { view: 'map', viewLabel: 'Map · My area' };
      const view = await renderAsk({
        ...props, mapContext: mapCtx({ windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset' }),
      });
      fireEvent.click(screen.getByTestId('ask-chip-window-remove'));
      expect(screen.queryByTestId('ask-chip-window')).toBeNull();

      view.rerender(tree({
        ...props, mapContext: mapCtx({ windowLabel: 'Tue sunrise', windowId: '2026-10-06_sunrise' }),
      }));

      expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Tue sunrise');
      // ...and the new window IS sent, while the removed one still is not.
      await askTyped('What about this one?', { windowId: '2026-10-06_sunrise', view: 'map' });
      expect(ask.mock.calls[0][0].windowId).toBe('2026-10-06_sunrise');
      await askTyped('And the other?', { windowId: '2026-10-05_sunset', view: 'map' });
      expect(ask.mock.calls[1][0]).not.toHaveProperty('windowId');
    });
  });
});
