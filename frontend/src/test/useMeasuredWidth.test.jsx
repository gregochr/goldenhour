import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import useMeasuredWidth from '../hooks/useMeasuredWidth.js';

// A chart that only mounts once there is data (zero runs, then a refresh with runs) must still be measured.

let observed;

class FakeResizeObserver {
  constructor(callback) {
    this.callback = callback;
    observed.push(this);
  }

  observe() {}

  disconnect() {
    this.gone = true;
  }
}

beforeEach(() => {
  observed = [];
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function Probe({ show }) {
  const [ref, width] = useMeasuredWidth();
  return (
    <div>
      {show && <div ref={ref} data-testid="node" />}
      <span data-testid="width">{width}</span>
    </div>
  );
}

describe('useMeasuredWidth', () => {
  it('measures a node that mounts after the first render', () => {
    const { rerender } = render(<Probe show={false} />);
    expect(observed).toHaveLength(0);
    rerender(<Probe show />);
    expect(observed).toHaveLength(1);
    act(() => observed[0].callback([{ contentRect: { width: 612.7 } }]));
    expect(screen.getByTestId('width')).toHaveTextContent('612');
  });

  it('stops observing a node that unmounts, and measures the next one', () => {
    const { rerender } = render(<Probe show />);
    rerender(<Probe show={false} />);
    expect(observed[0].gone).toBe(true);
    rerender(<Probe show />);
    expect(observed).toHaveLength(2);
    act(() => observed[1].callback([{ contentRect: { width: 300 } }]));
    expect(screen.getByTestId('width')).toHaveTextContent('300');
  });
});
