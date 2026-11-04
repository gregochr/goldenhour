import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import SchedulerTabs, { schedulerTabId, SCHEDULER_PANEL_ID } from '../components/scheduler/SchedulerTabs.jsx';

function setup(props = {}) {
  const onChange = vi.fn();
  render(<SchedulerTabs view="day" onChange={onChange} {...props} />);
  return { onChange };
}

const day = () => screen.getByRole('tab', { name: /Day's jobs/ });
const all = () => screen.getByRole('tab', { name: /All jobs/ });

describe('SchedulerTabs', () => {
  it('marks the active tab selected with a roving tab stop and wires up the panel', () => {
    setup({ view: 'all' });
    expect(screen.getByRole('tablist', { name: 'Scheduler views' })).toBeInTheDocument();
    expect(all()).toHaveAttribute('aria-selected', 'true');
    expect(all()).toHaveAttribute('tabindex', '0');
    expect(day()).toHaveAttribute('aria-selected', 'false');
    expect(day()).toHaveAttribute('tabindex', '-1');
    expect(day()).toHaveAttribute('id', schedulerTabId('day'));
    expect(day()).toHaveAttribute('aria-controls', SCHEDULER_PANEL_ID);
  });

  it('shows the day word beside Day\'s jobs and the total beside All jobs', () => {
    setup({ total: 42, dayWord: 'Fri 30 Oct' });
    expect(screen.getByTestId('scheduler-tab-day-word')).toHaveTextContent('Fri 30 Oct');
    expect(screen.getByTestId('scheduler-tab-all-word')).toHaveTextContent('42');
  });

  it('defaults the day word to Today and hides the total word while it is unknown', () => {
    setup();
    expect(screen.getByTestId('scheduler-tab-day-word')).toHaveTextContent('Today');
    expect(screen.queryByTestId('scheduler-tab-all-word')).toBeNull();
  });

  it('shows a total of zero rather than hiding it', () => {
    setup({ total: 0 });
    expect(screen.getByTestId('scheduler-tab-all-word')).toHaveTextContent('0');
  });

  it('reports the clicked tab', () => {
    const { onChange } = setup();
    fireEvent.click(all());
    expect(onChange).toHaveBeenCalledWith('all');
  });

  it('moves to the next tab on ArrowRight, wrapping at the end, and focuses it', () => {
    const { onChange } = setup({ view: 'day' });
    fireEvent.keyDown(day(), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenLastCalledWith('all');
    expect(all()).toHaveFocus();
  });

  it('wraps from the last tab to the first on ArrowRight', () => {
    const { onChange } = setup({ view: 'all' });
    fireEvent.keyDown(all(), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenLastCalledWith('day');
    expect(day()).toHaveFocus();
  });

  it('moves to the previous tab on ArrowLeft, wrapping at the start', () => {
    const { onChange } = setup({ view: 'day' });
    fireEvent.keyDown(day(), { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenLastCalledWith('all');
    expect(all()).toHaveFocus();
  });

  it('goes to the first tab on Home and the last on End', () => {
    const { onChange } = setup({ view: 'all' });
    fireEvent.keyDown(all(), { key: 'Home' });
    expect(onChange).toHaveBeenLastCalledWith('day');
    fireEvent.keyDown(day(), { key: 'End' });
    expect(onChange).toHaveBeenLastCalledWith('all');
  });

  it('ignores other keys and modified arrows', () => {
    const { onChange } = setup();
    fireEvent.keyDown(day(), { key: 'Tab' });
    fireEvent.keyDown(day(), { key: 'ArrowRight', ctrlKey: true });
    fireEvent.keyDown(day(), { key: 'ArrowRight', metaKey: true });
    fireEvent.keyDown(day(), { key: 'ArrowRight', altKey: true });
    expect(onChange).not.toHaveBeenCalled();
  });
});
