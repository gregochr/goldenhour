import React, { useEffect } from 'react';
import { vi } from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor,
} from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { AskProvider, READY_OPEN_MS, useAsk } from '../context/AskContext.jsx';
import AskConversation from '../components/ask/AskConversation.jsx';
import { AskApiError, getAskSettings, getReady } from '../api/askApi.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegionDriveTimes, fetchRegions } from '../api/regionApi.js';
import {
  briefing, NOW, readyResponse, settings, WHITBY,
} from './askFixtures.js';

/**
 * The tree the conversation's component tests share (`AskConversation.test.jsx` and the per-state files
 * split from it: `AskEmptyState`, `AskAnswer`, `AskCantAnswer`, `AskErrorState`, `AskContextRefusals`,
 * `AskContextLateResponse`): the real briefing provider and the real {@code AskProvider} around
 * {@code AskConversation}, with every API call a mock the calling file sets up.
 *
 * <p>Each calling file keeps its own `vi.mock` lines — `vi.mock` is hoisted per file and cannot live
 * here (the {@code askShellHarness.jsx} rule) — including the auth mock, which reads {@link auth}:
 * {@code vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: auth.role }) }))}.
 */

/** The role the mocked `useAuth` answers with; {@link installAskConversationMocks} resets it. */
export const auth = { role: 'LITE_USER' };

export const LAKES = {
  id: 3, name: 'The Lake District', enabled: true, baseName: 'Keswick', baseLat: 54.6013, baseLon: -3.1347,
};

/** What the provider's `reachById` serves: HOME, 1h 35min to Whitby. */
export const HOME_REACH = [{ locationId: WHITBY, driveMinutes: 95, distanceMiles: 60 }];

/**
 * The context's latest value, as a live binding (importers read the current one). Ask has no control
 * for every action, so a test drives them through this capture — a hook with no single natural
 * consumer, which the test standards allow a small harness for.
 */
export let ctx;

/** Renders nothing; keeps {@link ctx} current. */
export function Capture() {
  const value = useAsk();
  // In an effect, not the render: a render must not write a variable outside the component. Every
  // read of `ctx` follows an awaited `act`, which has flushed it.
  useEffect(() => { ctx = value; });
  return null;
}

/** The briefing provider's own view, so a test can see that the home map is home. */
export function Probe() {
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

/**
 * Publishes what the Map pane would (`registerMapContext`, the one channel): the region in scope and the
 * window on the pill. The conversation reads its request context itself, so this — not a prop — is how a
 * test gives it a scope or a window.
 */
export function PublishMapContext({ context }) {
  const { registerMapContext } = useAsk();
  useEffect(() => { registerMapContext(context); }, [registerMapContext, context]);
  return null;
}

/** A published Map context; the window is absent unless the test names one. */
export const mapCtx = (over = {}) => ({
  regionIds: [], regionNames: [], windowId: null, windowLabel: null, viewLabel: 'Map · My area', ...over,
});

/** The tree under test, so a test can re-render it with other props. {@code mapContext} is published. */
export const tree = ({ mapContext = null, ...props } = {}) => (
  <WindowFirstBriefingProvider>
    <AskProvider>
      <Capture />
      <PublishMapContext context={mapContext} />
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
export async function renderAsk(props = {}) {
  const view = render(tree(props));
  await screen.findByText('2026-10-05T05:02:11');
  await waitFor(() => expect(screen.getByTestId('probe-home-drive')).toHaveTextContent('95'));
  return view;
}

/** A refusal as the API module throws it. */
export const refusal = (status, code, error) => new AskApiError({ status, code, error: error ?? null });

export const askTyped = (...args) => act(async () => { await ctx.askTyped(...args); });
/** Opens a Ready question straight from the list, for a state whose suggestions are not on screen. */
export const openReadyById = (id) => act(() => ctx.openReady(readyResponse().questions.find((q) => q.id === id)));
export const tapReady = async (id) => {
  fireEvent.click(await screen.findByTestId(`ask-ready-${id}`));
};
/** Lets the Ready answer's busy line finish (the one timer the provider holds). */
export const finishOpening = () => act(async () => { await vi.advanceTimersByTimeAsync(READY_OPEN_MS); });

/** The `beforeEach` of every file that uses this harness: fake clock, reset mocks, the default responses. */
export function installAskConversationMocks() {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(NOW);
  localStorage.clear();
  auth.role = 'LITE_USER';
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
}

/** The `afterEach` of every file that uses this harness. */
export function removeAskConversationMocks() {
  cleanup();
  vi.useRealTimers();
}
