import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));
vi.mock('../hooks/useMeasuredWidth.js', () => ({ default: vi.fn() }));

import SlowerScatter from '../components/runs/timing/SlowerScatter.jsx';
import { useIsMobile } from '../hooks/useIsMobile.js';
import useMeasuredWidth from '../hooks/useMeasuredWidth.js';

const NOW = new Date('2026-11-04T12:00:00Z');

const run = (id, date, startedAt, durationSeconds) => ({ id, date, startedAt, durationSeconds });

const RUNS = [
  run('old', '2026-10-10', '2026-10-10T06:00:00Z', 600),
  run('mid', '2026-10-25', '2026-10-25T06:00:00Z', 700),
  run('new', '2026-11-03', '2026-11-03T06:00:00Z', 1500),
];

const timing = (over = {}) => ({
  type: 'NEAR_TERM',
  rangeDays: 30,
  runs: RUNS,
  stats: {
    p90Seconds: 1000,
    firstHalfMedianSeconds: 600,
    secondHalfMedianSeconds: 900,
    changePercent: 50,
  },
  ...over,
});

const setup = (props = {}) => {
  const onOpenDay = vi.fn();
  render(<SlowerScatter timing={timing()} onOpenDay={onOpenDay} now={NOW} {...props} />);
  return { onOpenDay };
};

beforeEach(() => {
  useIsMobile.mockReturnValue(false);
  useMeasuredWidth.mockReturnValue([vi.fn(), 0]);
});

describe('SlowerScatter', () => {
  it('shows the empty state and no chart when nothing was measured', () => {
    setup({ timing: timing({ runs: [] }) });
    expect(screen.getByTestId('runs-scatter-empty')).toHaveTextContent('No runs measured in this range.');
    expect(screen.queryByTestId('runs-scatter-svg')).toBeNull();
    expect(screen.queryByTestId('runs-scatter-trend')).toBeNull();
  });

  it('marks only runs above the served p90 as slow', () => {
    setup();
    expect(screen.getByTestId('runs-scatter-dot-new').dataset.slow).toBe('true');
    expect(screen.getByTestId('runs-scatter-dot-old').dataset.slow).toBe('false');
    expect(screen.getByRole('button', { name: /slower than 9 in 10/ })).toBe(screen.getByTestId('runs-scatter-dot-new'));
  });

  it('marks nothing slow when there is no p90', () => {
    setup({ timing: timing({ stats: { p90Seconds: null } }) });
    screen.getAllByRole('button').forEach((dot) => expect(dot.dataset.slow).toBe('false'));
  });

  it('writes the trend with the change percentage', () => {
    setup();
    expect(screen.getByTestId('runs-scatter-trend')).toHaveTextContent(
      'Median 10m in the first 15 days, 15m in the last 15 (+50%).',
    );
  });

  it('ends the trend with a full stop when no change is served', () => {
    setup({
      timing: timing({
        stats: { p90Seconds: 1000, firstHalfMedianSeconds: 600, secondHalfMedianSeconds: 900, changePercent: null },
      }),
    });
    expect(screen.getByTestId('runs-scatter-trend').textContent).toBe(
      'Median 10m in the first 15 days, 15m in the last 15.',
    );
  });

  it('adds the Ask note for the Ask ready type only', () => {
    const { unmount } = render(
      <SlowerScatter timing={timing({ type: 'ASK_READY' })} onOpenDay={vi.fn()} now={NOW} />,
    );
    expect(screen.getByTestId('runs-scatter-trend')).toHaveTextContent(
      'Ask ready answers scales with the number of locations it covers.',
    );
    unmount();
    setup();
    expect(screen.getByTestId('runs-scatter-trend').textContent).not.toContain('Ask ready');
  });

  it('omits the trend when a half median is missing', () => {
    setup({
      timing: timing({
        stats: { p90Seconds: 1000, firstHalfMedianSeconds: null, secondHalfMedianSeconds: 900, changePercent: 5 },
      }),
    });
    expect(screen.queryByTestId('runs-scatter-trend')).toBeNull();
  });

  it('sizes the viewBox from the measured width, else the 420 fallback', () => {
    const { unmount } = render(<SlowerScatter timing={timing()} onOpenDay={vi.fn()} now={NOW} />);
    expect(screen.getByTestId('runs-scatter-svg').getAttribute('viewBox')).toBe('0 0 420 200');
    unmount();
    useMeasuredWidth.mockReturnValue([vi.fn(), 640]);
    setup();
    expect(screen.getByTestId('runs-scatter-svg').getAttribute('viewBox')).toBe('0 0 640 200');
  });

  it('opens that run\'s day on click', () => {
    const { onOpenDay } = setup();
    fireEvent.click(screen.getByTestId('runs-scatter-dot-mid'));
    expect(onOpenDay).toHaveBeenCalledWith('2026-10-25');
  });

  it('opens the day on Enter and Space, and not on other keys', () => {
    const { onOpenDay } = setup();
    const dot = screen.getByTestId('runs-scatter-dot-mid');
    fireEvent.keyDown(dot, { key: 'a' });
    expect(onOpenDay).not.toHaveBeenCalled();
    fireEvent.keyDown(dot, { key: 'Enter' });
    expect(onOpenDay).toHaveBeenLastCalledWith('2026-10-25');
    fireEvent.keyDown(dot, { key: ' ' });
    expect(onOpenDay).toHaveBeenCalledTimes(2);
  });

  it('shows a tooltip to the right of an early dot on hover and hides it on leave', () => {
    setup();
    expect(screen.queryByTestId('runs-scatter-tooltip')).toBeNull();
    fireEvent.mouseEnter(screen.getByTestId('runs-scatter-dot-old'));
    const tip = screen.getByTestId('runs-scatter-tooltip');
    expect(tip).toHaveTextContent('07:00');
    expect(tip).toHaveTextContent('10m');
    expect(tip.textContent).not.toContain('slower than 9 in 10');
    expect(tip).toHaveTextContent('click to open the day');
    const dotX = Number(screen.getByTestId('runs-scatter-dot-old').getAttribute('cx'));
    const tipX = Number(/translate\(([\d.]+) /.exec(tip.getAttribute('transform'))[1]);
    expect(tipX).toBeGreaterThan(dotX);
    fireEvent.mouseLeave(screen.getByTestId('runs-scatter-dot-old'));
    expect(screen.queryByTestId('runs-scatter-tooltip')).toBeNull();
  });

  it('flips the tooltip to the left of a late dot and flags a slow run', () => {
    setup();
    fireEvent.mouseEnter(screen.getByTestId('runs-scatter-dot-new'));
    const tip = screen.getByTestId('runs-scatter-tooltip');
    expect(tip).toHaveTextContent('25m · slower than 9 in 10');
    const dotX = Number(screen.getByTestId('runs-scatter-dot-new').getAttribute('cx'));
    const tipX = Number(/translate\(([\d.]+) /.exec(tip.getAttribute('transform'))[1]);
    expect(tipX).toBeLessThan(dotX);
  });

  it('shows the tooltip on focus and clears it on blur', () => {
    setup();
    const dot = screen.getByTestId('runs-scatter-dot-mid');
    fireEvent.focus(dot);
    expect(screen.getByTestId('runs-scatter-tooltip')).toHaveTextContent('11m 40s');
    fireEvent.blur(dot);
    expect(screen.queryByTestId('runs-scatter-tooltip')).toBeNull();
  });

  it('never flags a run slow in the tooltip when there is no p90', () => {
    setup({ timing: timing({ stats: { p90Seconds: null } }) });
    fireEvent.mouseEnter(screen.getByTestId('runs-scatter-dot-new'));
    expect(screen.getByTestId('runs-scatter-tooltip').textContent).not.toContain('slower than 9 in 10');
  });

  it('on a phone, draws a visible dot plus a transparent hit circle that carries the press', () => {
    useIsMobile.mockReturnValue(true);
    const { onOpenDay } = setup();
    const hit = screen.getByTestId('runs-scatter-dot-new');
    expect(hit.getAttribute('r')).toBe('10');
    expect(hit.getAttribute('class')).toContain('fill-transparent');
    expect(screen.getAllByRole('button')).toHaveLength(3);
    fireEvent.click(hit);
    expect(onOpenDay).toHaveBeenCalledWith('2026-11-03');
  });
});
