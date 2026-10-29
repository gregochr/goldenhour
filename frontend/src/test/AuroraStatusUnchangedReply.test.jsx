/**
 * An unchanged `GET /api/aurora/status` reply must change nothing downstream (performance plan P3b).
 *
 * <p>The real API module and the real provider are used; only the HTTP client is replaced, answering
 * the raw text a `responseType: 'text'` request gets. Two polls that return the same bytes must leave
 * the context value untouched (`Object.is`), so no consumer re-renders and no thumbnail repaints; a
 * changed reply must change it.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render } from '@testing-library/react';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn() } }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));

import apiClient from '../api/axiosClient.js';
import { AuroraStatusProvider, useAuroraStatusContext } from '../context/AuroraStatusContext.jsx';
import { clearJsonMemo } from '../utils/jsonMemo.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000;
const RAW = JSON.stringify({ level: 'MODERATE', active: true, currentNightEndsAt: null });

let seen;
let renders;

function Consumer() {
  seen.push(useAuroraStatusContext());
  renders();
  return <span data-testid="consumer" />;
}

async function mount() {
  await act(async () => {
    render(<AuroraStatusProvider><Consumer /></AuroraStatusProvider>);
  });
}

async function poll() {
  await act(async () => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
}

beforeEach(() => {
  seen = [];
  renders = vi.fn();
  clearJsonMemo();
  apiClient.get.mockReset();
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('AuroraStatusProvider with an unchanged reply', () => {
  it('keeps the context value and the status object identical, and renders no consumer again', async () => {
    // A fresh response object per request, as a real client gives: the text is what repeats.
    apiClient.get.mockImplementation(async () => ({ data: RAW, status: 200 }));
    await mount();
    const afterMount = renders.mock.calls.length;
    const first = seen[seen.length - 1];
    expect(first.status).toMatchObject({ level: 'MODERATE' });

    await poll();
    expect(apiClient.get).toHaveBeenCalledTimes(2);
    const second = seen[seen.length - 1];
    expect(Object.is(second, first)).toBe(true);
    expect(Object.is(second.status, first.status)).toBe(true);
    expect(renders.mock.calls.length).toBe(afterMount);
  });

  it('publishes a new value when the reply changes', async () => {
    apiClient.get.mockResolvedValueOnce({ data: RAW, status: 200 });
    await mount();
    const first = seen[seen.length - 1];

    apiClient.get.mockResolvedValueOnce({
      data: JSON.stringify({ level: 'STRONG', active: true, currentNightEndsAt: null }),
      status: 200,
    });
    await poll();
    const second = seen[seen.length - 1];
    expect(Object.is(second, first)).toBe(false);
    expect(second.status.level).toBe('STRONG');
  });
});
