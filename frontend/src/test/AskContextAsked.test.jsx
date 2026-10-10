import React, { useEffect } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { WindowFirstBriefingProvider } from '../context/WindowFirstBriefingContext.jsx';
import { AskProvider, useAsk } from '../context/AskContext.jsx';
import AskConversation from '../components/ask/AskConversation.jsx';
import AskInputRow from '../components/ask/AskInputRow.jsx';
import useAskRequestContext from '../hooks/useAskRequestContext.js';
import { AskApiError } from '../api/askApi.js';
import {
  briefing, NOW, ownResponse, readyResponse, settings, WHITBY,
} from './askFixtures.js';

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
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegionDriveTimes, fetchRegions } from '../api/regionApi.js';
import { ask, getAskSettings, getReady } from '../api/askApi.js';

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));

/**
 * What an answer remembers about the context it was ASKED in, and the channel the Map publishes its
 * scope and window through (F3, `docs/engineering/ask-photocast-plan.md` §2.6 and the F2 note's
 * "store the asked context on the answer").
 *
 * <p>The surfaces (the dock, the sheet) are `AskShell*.test.jsx`'s; this file drives the provider
 * through a capture of its value and the two components that read the context — the conversation's
 * chips and the input row — on the real briefing provider.
 */

let ctx;
function Capture() {
  const value = useAsk();
  useEffect(() => { ctx = value; });
  return null;
}

const MAP_CONTEXT = {
  regionIds: [3],
  regionNames: ['The Lake District'],
  windowId: '2026-10-10_sunrise',
  windowLabel: 'Saturday sunrise',
  viewLabel: 'Map · The Lake District',
};

/** Publishes what the Map pane would; the conversation reads its request context itself. */
function PublishMapContext({ context }) {
  const { registerMapContext } = useAsk();
  useEffect(() => { registerMapContext(context); }, [registerMapContext, context]);
  return null;
}

const tree = ({ mapContext = null, ...props } = {}) => (
  <WindowFirstBriefingProvider>
    <AskProvider>
      <Capture />
      <PublishMapContext context={mapContext} />
      <AskConversation view="plan" viewLabel="Plan · all regions" {...props} />
    </AskProvider>
  </WindowFirstBriefingProvider>
);

async function renderAsk(props = {}) {
  const view = render(tree(props));
  await screen.findByTestId('ask-conversation');
  await waitFor(() => expect(ctx?.availability).toBe('on'));
  return view;
}

const refusal = (status, code, error) => new AskApiError({ status, code, error: error ?? null });
const askTyped = (...args) => act(async () => { await ctx.askTyped(...args); });
const SENT = {
  view: 'map',
  viewLabel: 'Map · The Lake District',
  windowLabel: 'Saturday sunrise',
  windowId: '2026-10-10_sunrise',
  regionIds: [3],
};

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(NOW);
  localStorage.clear();
  vi.resetAllMocks();
  getDailyBriefing.mockResolvedValue(briefing());
  fetchTravelDayRanges.mockResolvedValue([]);
  getAllEvaluationScores.mockResolvedValue([]);
  getReach.mockResolvedValue([{ locationId: WHITBY, driveMinutes: 95, distanceMiles: 60 }]);
  getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
  fetchRegions.mockResolvedValue([]);
  fetchRegionDriveTimes.mockResolvedValue({});
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  ctx = undefined;
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('an answer remembers the context it was asked in', () => {
  it('stores what was sent — the view, the chip labels, the window and the regions', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('Where is good?', SENT);

    expect(ctx.asked).toEqual(SENT);
    expect(ask).toHaveBeenCalledWith({
      question: 'Where is good?', regionIds: [3], view: 'map', windowId: '2026-10-10_sunrise',
    });
  });

  it('⚠️ a tab switch does not relabel it: the chips keep saying what was SENT', async () => {
    ask.mockResolvedValue(ownResponse());
    const view = await renderAsk();
    await askTyped('Where is good?', SENT);
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District');
    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Saturday sunrise');

    // The reader moves to Coming up: the surface now says something else...
    view.rerender(tree({ view: 'coming-up', viewLabel: 'Coming up · all regions' }));

    // ...but the answer on screen was not asked there.
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District');
    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Saturday sunrise');
  });

  it('shows the asked window chip as a plain label — nothing to remove from a finished answer', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk({
      view: 'map',
      mapContext: { ...MAP_CONTEXT, windowLabel: 'Sunday sunset', windowId: '2026-10-11_sunset' },
    });
    expect(screen.getByTestId('ask-chip-window-remove')).toBeInTheDocument();

    await askTyped('Where is good?', SENT);

    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Saturday sunrise');
    expect(screen.queryByTestId('ask-chip-window-remove')).toBeNull();
  });

  it('says what the NEXT question will carry once the answer is cleared — removable again', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk({
      view: 'map',
      viewLabel: 'Map · My area',
      mapContext: {
        ...MAP_CONTEXT, viewLabel: 'Map · My area', windowLabel: 'Sunday sunset', windowId: '2026-10-11_sunset',
      },
    });
    await askTyped('Where is good?', SENT);
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District');

    act(() => ctx.clear());

    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · My area');
    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Sunday sunset');
    expect(screen.getByTestId('ask-chip-window-remove')).toBeInTheDocument();
  });

  it('a question asked with the window chip REMOVED records no window, and shows no window chip', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();
    act(() => ctx.removeContextWindow('2026-10-10_sunrise'));

    await askTyped('What about Sunday?', SENT);

    expect(ctx.asked.windowId).toBeNull();
    expect(ctx.asked.windowLabel).toBeNull();
    expect(ask.mock.calls[0][0]).not.toHaveProperty('windowId');
    expect(screen.queryByTestId('ask-chip-window')).toBeNull();
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District');
  });

  it('a Ready answer records the scope it was opened in and NO window — nothing was sent', async () => {
    await renderAsk({ view: 'map', viewLabel: 'Map · The Lake District', mapContext: MAP_CONTEXT });

    fireEvent.click(await screen.findByTestId('ask-ready-COASTAL_HIGH'));
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });

    expect(ctx.asked).toEqual({
      view: 'map', viewLabel: 'Map · The Lake District', regionIds: [3], windowId: null, windowLabel: null,
    });
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District');
    // A window chip over a Ready answer would claim the answer is about that window.
    expect(screen.queryByTestId('ask-chip-window')).toBeNull();
    expect(ask).not.toHaveBeenCalled();
  });

  it('a Ready answer over SEVERAL regions says all regions: its list is the whole catalogue, and "My area" would claim a narrower scope than the answer has', async () => {
    await renderAsk({
      view: 'map',
      viewLabel: 'Map · My area',
      mapContext: {
        ...MAP_CONTEXT, regionIds: [3, 5], windowId: null, windowLabel: null, viewLabel: 'Map · My area',
      },
    });

    fireEvent.click(await screen.findByTestId('ask-ready-COASTAL_HIGH'));
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });

    expect(ctx.asked).toEqual({
      view: 'map', viewLabel: 'Map · all regions', regionIds: [], windowId: null, windowLabel: null,
    });
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · all regions');
  });

  it('a refusal puts the earlier answer back WITH its own context', async () => {
    ask.mockResolvedValueOnce(ownResponse());
    await renderAsk();
    await askTyped('Where is good?', SENT);
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));

    await askTyped('And again?', { ...SENT, viewLabel: 'Map · Everywhere', regionIds: [] });

    expect(ctx.restored).toBe(true);
    expect(ctx.asked).toEqual(SENT);
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District');
  });
});

describe('"Try again" re-sends the question exactly as it was asked', () => {
  it('with its own view, regions and window — not the surface\'s of the moment', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    const view = await renderAsk();
    await askTyped('Where is good?', SENT);
    expect(ctx.phase).toBe('error');
    // The reader has moved on: another tab, another scope.
    view.rerender(tree({ view: 'plan', viewLabel: 'Plan · all regions' }));
    ask.mockResolvedValue(ownResponse());

    fireEvent.click(screen.getByTestId('ask-retry'));
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });

    expect(ask).toHaveBeenLastCalledWith({
      question: 'Where is good?', regionIds: [3], view: 'map', windowId: '2026-10-10_sunrise',
    });
    expect(ctx.asked).toEqual(SENT);
  });

  it('⚠️ still sends the window if the reader removed the chip AFTER asking', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED'));
    await renderAsk();
    await askTyped('Where is good?', SENT);
    expect(ctx.phase).toBe('error');
    act(() => ctx.removeContextWindow('2026-10-10_sunrise'));
    ask.mockResolvedValue(ownResponse());

    await act(async () => { await ctx.retry(); });

    expect(ask).toHaveBeenLastCalledWith({
      question: 'Where is good?', regionIds: [3], view: 'map', windowId: '2026-10-10_sunrise',
    });
  });

  it('a refused later question cannot hijack the retry of an earlier failure', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED'));
    await renderAsk();
    await askTyped('First?', SENT);
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED'));
    await askTyped('Second?', { ...SENT, regionIds: [9] });
    ask.mockResolvedValue(ownResponse());

    await act(async () => { await ctx.retry(); });

    expect(ask).toHaveBeenLastCalledWith({
      question: 'First?', regionIds: [3], view: 'map', windowId: '2026-10-10_sunrise',
    });
  });
});

describe('registerMapContext', () => {
  it('publishes the Map\'s context, and null clears it', async () => {
    await renderAsk();
    expect(ctx.mapContext).toBeNull();

    act(() => ctx.registerMapContext(MAP_CONTEXT));
    expect(ctx.mapContext).toEqual(MAP_CONTEXT);

    act(() => ctx.registerMapContext(null));
    expect(ctx.mapContext).toBeNull();
  });

  it('keeps the same object for an unchanged context — the pane publishes on a key, and a value that did not change must not re-render every Ask surface', async () => {
    await renderAsk();
    act(() => ctx.registerMapContext(MAP_CONTEXT));
    const first = ctx.mapContext;

    act(() => ctx.registerMapContext({ ...MAP_CONTEXT, regionIds: [3] }));

    expect(ctx.mapContext).toBe(first);
  });

  it('has a stable identity, so a pane may list it as an effect dependency', async () => {
    await renderAsk();
    const first = ctx.registerMapContext;

    act(() => ctx.registerMapContext(MAP_CONTEXT));

    expect(ctx.registerMapContext).toBe(first);
  });
});

describe('selectPick numbers every choice', () => {
  const answered = async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();
    await askTyped('Where is good?', { view: 'plan' });
  };

  it('a new nonce for every choice — the same pick chosen twice is two choices', async () => {
    await answered();
    expect(ctx.selectionNonce).toBe(0);

    act(() => ctx.selectPick(1));
    const first = ctx.selectionNonce;
    expect(first).toBeGreaterThan(0);

    act(() => ctx.selectPick(1));
    expect(ctx.selectionNonce).toBeGreaterThan(first);
    expect(ctx.selectedPick).toBe(1);
  });

  it('a rank with no card changes nothing, nonce included', async () => {
    await answered();
    act(() => ctx.selectPick(1));
    const before = ctx.selectionNonce;

    act(() => ctx.selectPick(9));

    expect(ctx.selectionNonce).toBe(before);
    expect(ctx.selectedPick).toBe(1);
  });

  it('clearing the selection (null) leaves the nonce and unselects', async () => {
    await answered();
    act(() => ctx.selectPick(1));
    const before = ctx.selectionNonce;

    act(() => ctx.selectPick(null));

    expect(ctx.selectedPick).toBeNull();
    expect(ctx.selectionNonce).toBe(before);
  });

  it('never reuses a nonce across answers', async () => {
    await answered();
    act(() => ctx.selectPick(1));
    const earlier = ctx.selectionNonce;

    await askTyped('Again?', { view: 'plan' });
    act(() => ctx.selectPick(1));

    expect(ctx.selectionNonce).toBeGreaterThan(earlier);
  });
});

describe('useAskRequestContext', () => {
  let seen;
  function Probe({ view, viewLabel }) {
    const value = useAskRequestContext(view, viewLabel);
    useEffect(() => { seen = value; });
    return null;
  }
  const probed = (view, viewLabel) => (
    <WindowFirstBriefingProvider>
      <AskProvider>
        <Capture />
        <Probe view={view} viewLabel={viewLabel} />
      </AskProvider>
    </WindowFirstBriefingProvider>
  );

  it('on the Map it is what the pane published', async () => {
    render(probed('map', 'Map · all regions'));
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    act(() => ctx.registerMapContext(MAP_CONTEXT));

    expect(seen).toEqual({
      view: 'map',
      regionIds: [3],
      scope: 3,
      viewLabel: 'Map · The Lake District',
      windowId: '2026-10-10_sunrise',
      windowLabel: 'Saturday sunrise',
    });
  });

  it('several regions in scope read the Ready list for ALL (§6 Q8); none is all', async () => {
    render(probed('map', 'Map · all regions'));
    await waitFor(() => expect(ctx?.availability).toBe('on'));

    act(() => ctx.registerMapContext({ ...MAP_CONTEXT, regionIds: [3, 5] }));
    expect(seen.scope).toBe('all');
    expect(seen.regionIds).toEqual([3, 5]);

    act(() => ctx.registerMapContext({ ...MAP_CONTEXT, regionIds: [], windowId: null, windowLabel: null }));
    expect(seen.scope).toBe('all');
    expect(seen.windowLabel).toBeNull();
  });

  it('⚠️ elsewhere it is "all regions" with the surface\'s own label, whatever the Map published', async () => {
    render(probed('plan', 'Plan · all regions'));
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    act(() => ctx.registerMapContext(MAP_CONTEXT));

    expect(seen).toEqual({
      view: 'plan', regionIds: [], scope: 'all', viewLabel: 'Plan · all regions', windowId: null, windowLabel: null,
    });
  });

  it('on the Map before the pane has published, it is the surface\'s own "all regions"', async () => {
    render(probed('map', 'Map · all regions'));
    await waitFor(() => expect(ctx?.availability).toBe('on'));

    expect(seen.regionIds).toEqual([]);
    expect(seen.viewLabel).toBe('Map · all regions');
    expect(seen.windowId).toBeNull();
  });
});

describe('the input row sends the context the Map published', () => {
  const rowTree = (view = 'map') => (
    <WindowFirstBriefingProvider>
      <AskProvider>
        <Capture />
        <AskInputRow inputRef={{ current: null }} view={view} viewLabel="Map · all regions" />
        <AskConversation view={view} viewLabel="Map · all regions" />
      </AskProvider>
    </WindowFirstBriefingProvider>
  );

  it('the region, the window and the view — and the chips afterwards say the same', async () => {
    ask.mockResolvedValue(ownResponse());
    render(rowTree());
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    act(() => ctx.registerMapContext(MAP_CONTEXT));

    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      .type(screen.getByTestId('ask-input'), 'Where is good?{Enter}');

    expect(ask).toHaveBeenCalledWith({
      question: 'Where is good?', regionIds: [3], view: 'map', windowId: '2026-10-10_sunrise',
    });
    await screen.findByTestId('ask-picks');
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · The Lake District');
    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Saturday sunrise');
  });

  it('a night row (no window) and Everywhere send no window and no regions', async () => {
    ask.mockResolvedValue(ownResponse());
    render(rowTree());
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    act(() => ctx.registerMapContext({
      regionIds: [], regionNames: [], windowId: null, windowLabel: null, viewLabel: 'Map · Everywhere',
    }));

    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      .type(screen.getByTestId('ask-input'), 'Where is good?{Enter}');

    expect(ask).toHaveBeenCalledWith({ question: 'Where is good?', regionIds: [], view: 'map' });
  });

  it('on Plan it sends all regions even if the Map has published a scope', async () => {
    ask.mockResolvedValue(ownResponse());
    render(rowTree('plan'));
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    act(() => ctx.registerMapContext(MAP_CONTEXT));

    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      .type(screen.getByTestId('ask-input'), 'Where is good?{Enter}');

    expect(ask).toHaveBeenCalledWith({ question: 'Where is good?', regionIds: [], view: 'plan' });
  });

  it('removing the window chip on the NEXT question takes the window out of what the row sends', async () => {
    ask.mockResolvedValue(ownResponse());
    render(rowTree());
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    act(() => ctx.registerMapContext(MAP_CONTEXT));
    act(() => ctx.removeContextWindow(MAP_CONTEXT.windowId));

    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      .type(screen.getByTestId('ask-input'), 'What about Sunday?{Enter}');

    expect(ask).toHaveBeenCalledWith({ question: 'What about Sunday?', regionIds: [3], view: 'map' });
  });
});

describe('the "Next question" row: what the field will send, when the answer above was asked in another context', () => {
  const inputRef = { current: null };
  const rowTree = (view = 'map', viewLabel = 'Map · all regions') => (
    <WindowFirstBriefingProvider>
      <AskProvider>
        <Capture />
        <AskInputRow inputRef={inputRef} view={view} viewLabel={viewLabel} />
        <AskConversation view={view} viewLabel={viewLabel} />
      </AskProvider>
    </WindowFirstBriefingProvider>
  );
  const asked = async () => {
    ask.mockResolvedValue(ownResponse());
    render(rowTree());
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    act(() => ctx.registerMapContext(MAP_CONTEXT));
    await act(async () => { await ctx.askTyped('Where is good?', SENT); });
  };

  it('is not drawn before anything is asked: the conversation\'s own live chips are at its top', async () => {
    render(rowTree());
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    act(() => ctx.registerMapContext(MAP_CONTEXT));

    expect(screen.queryByTestId('ask-next-context')).toBeNull();
  });

  it('names the window the next question will carry, with a remove button', async () => {
    await asked();

    expect(screen.getByTestId('ask-next-view')).toHaveTextContent('Map · The Lake District');
    expect(screen.getByTestId('ask-next-window')).toHaveTextContent('Saturday sunrise');
  });

  it('⚠️ shows the window the Map has MOVED to after the answer (what choosing a pick does), not the one asked', async () => {
    await asked();

    act(() => ctx.registerMapContext({
      ...MAP_CONTEXT, windowId: '2026-10-11_sunset', windowLabel: 'Sunday sunset',
    }));

    expect(screen.getByTestId('ask-next-window')).toHaveTextContent('Sunday sunset');
    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Saturday sunrise');
    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      .type(screen.getByTestId('ask-input'), 'And this one?{Enter}');
    // The second question of a session carries the first exchange (the thread, T4); this test is about the
    // window, so the exchange is named only as present.
    expect(ask).toHaveBeenLastCalledWith({
      question: 'And this one?',
      regionIds: [3],
      view: 'map',
      windowId: '2026-10-11_sunset',
      thread: [expect.objectContaining({ question: 'Where is good?' })],
    });
  });

  it('the remove button takes the window out of the next question, and focus goes to the field, not <body>', async () => {
    await asked();
    inputRef.current = screen.getByTestId('ask-input');
    const remove = screen.getByTestId('ask-next-window-remove');
    remove.focus();

    fireEvent.click(remove);

    expect(screen.queryByTestId('ask-next-window')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('ask-input'));
    // The row still says what the next question carries: the view, and no window.
    expect(screen.getByTestId('ask-next-context')).toBeInTheDocument();
    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      .type(screen.getByTestId('ask-input'), 'What about Sunday?{Enter}');
    expect(ask.mock.calls.at(-1)[0]).not.toHaveProperty('windowId');
  });

  it('is quiet on Plan when nothing differs and there is no window to remove', async () => {
    ask.mockResolvedValue(ownResponse());
    render(rowTree('plan', 'Plan · all regions'));
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    await act(async () => {
      await ctx.askTyped('Where is good?', { view: 'plan', viewLabel: 'Plan · all regions', regionIds: [] });
    });

    expect(screen.queryByTestId('ask-next-context')).toBeNull();
  });

  it('appears on Plan when the answer was asked on the Map: the next question is not the same', async () => {
    ask.mockResolvedValue(ownResponse());
    render(rowTree('plan', 'Plan · all regions'));
    await waitFor(() => expect(ctx?.availability).toBe('on'));
    await act(async () => { await ctx.askTyped('Where is good?', SENT); });

    expect(screen.getByTestId('ask-next-view')).toHaveTextContent('Plan · all regions');
    expect(screen.queryByTestId('ask-next-window')).toBeNull();
  });
});
