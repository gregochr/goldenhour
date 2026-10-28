import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  cleanup, fireEvent, screen, waitFor,
} from '@testing-library/react';
import { resetViewport } from './askViewport.js';
import { renderAskShell } from './askShellHarness.jsx';
import { NOW, readyResponse, settings } from './askFixtures.js';
import { ASK_DOCK_ID } from '../components/ask/askIds.js';

/**
 * P2d: an Ask chunk that fails to load replaces only the dock with the `ChunkErrorBoundary`; the Plan
 * stays. The boundary's Close shuts the dock.
 */
vi.mock('../components/ask/AskDock.jsx', () => {
  throw new Error('Failed to fetch dynamically imported module');
});
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
import { getAskSettings, getReady } from '../api/askApi.js';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  vi.spyOn(console, 'error').mockImplementation(() => {});
  getReady.mockReset().mockResolvedValue(readyResponse());
  getAskSettings.mockReset().mockResolvedValue(settings());
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  resetViewport();
});

describe('a failed Ask dock chunk', () => {
  it('shows the error boundary in the dock place and leaves the Plan standing', async () => {
    renderAskShell({ width: 1280 });
    fireEvent.click(await screen.findByTestId('ask-field'));

    const failure = await screen.findByTestId('chunk-error');
    expect(failure.closest('aside')).toHaveAttribute('id', ASK_DOCK_ID);
    expect(screen.getByTestId('window-first-shell')).toBeInTheDocument();
    expect(screen.queryByTestId('plan-error')).toBeNull();

    fireEvent.click(screen.getByTestId('chunk-error-close'));
    await waitFor(() => expect(screen.queryByTestId('chunk-error')).toBeNull());
  });
});
