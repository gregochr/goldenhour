import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import useHashSelection from '../hooks/useHashSelection.js';
import { writeRunsQuery } from '../utils/runsQuery.js';
import { writeSchedulerQuery } from '../utils/schedulerQuery.js';
import { registerBackLayer, __resetForTests } from '../utils/dialogHistory.js';

/**
 * The design's invariant: every `replaceState` writer that touches the hash copies `history.state`, so a
 * layer's marker survives it. These are the real writers (real jsdom history; wiring only).
 */
describe('the layer marker survives each hash state writer', () => {
  let layer;

  beforeEach(async () => {
    Object.defineProperty(navigator, 'userActivation', { value: { isActive: true }, configurable: true });
    window.history.replaceState(null, '', '#manage/users/7');
    __resetForTests();
    layer = registerBackLayer({ onBack: () => {} });
    await waitFor(() => expect(window.history.state?.phLayer?.depth).toBe(1));
  });

  afterEach(() => {
    layer.release();
    delete navigator.userActivation;
    __resetForTests();
    window.history.replaceState(null, '', '#');
  });

  const marker = () => window.history.state?.phLayer;

  it('useHashSelection select({replace: true})', () => {
    const { result } = renderHook(() => useHashSelection('users'));
    act(() => result.current.select(9, { replace: true }));
    expect(window.location.hash).toBe('#manage/users/9');
    expect(marker()?.depth).toBe(1);
  });

  it('useHashSelection clear({replace: true})', () => {
    const { result } = renderHook(() => useHashSelection('users'));
    act(() => result.current.clear({ replace: true }));
    expect(window.location.hash).toBe('#manage/users');
    expect(marker()?.depth).toBe(1);
  });

  it('writeRunsQuery', () => {
    window.history.replaceState(window.history.state, '', '#manage/runs');
    writeRunsQuery({ view: 'cost' });
    expect(window.location.hash).toContain('view=cost');
    expect(marker()?.depth).toBe(1);
  });

  it('writeSchedulerQuery', () => {
    window.history.replaceState(window.history.state, '', '#manage/scheduler');
    writeSchedulerQuery({ view: 'all' });
    expect(window.location.hash).toContain('view=all');
    expect(marker()?.depth).toBe(1);
  });
});
