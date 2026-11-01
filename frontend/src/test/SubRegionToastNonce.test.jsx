import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import SubRegionToast from '../components/subregions/SubRegionToast.jsx';

describe('SubRegionToast timer', () => {
  afterEach(() => { vi.useRealTimers(); });

  it('restarts its five seconds when the same line is shown again (a new nonce)', () => {
    vi.useFakeTimers();
    const onDone = vi.fn();
    const { rerender } = render(<SubRegionToast message="Started." nonce={1} onDone={onDone} />);

    act(() => { vi.advanceTimersByTime(4000); });
    rerender(<SubRegionToast message="Started." nonce={2} onDone={onDone} />);
    act(() => { vi.advanceTimersByTime(4000); });
    expect(onDone).not.toHaveBeenCalled();

    act(() => { vi.advanceTimersByTime(1000); });
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('does not restart for the same nonce', () => {
    vi.useFakeTimers();
    const onDone = vi.fn();
    const { rerender } = render(<SubRegionToast message="Started." nonce={1} onDone={onDone} />);
    act(() => { vi.advanceTimersByTime(4000); });
    rerender(<SubRegionToast message="Started." nonce={1} onDone={onDone} />);
    act(() => { vi.advanceTimersByTime(1000); });
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});
