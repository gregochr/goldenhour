import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import RunsViewTabs from '../components/runs/RunsViewTabs.jsx';

describe('RunsViewTabs keyboard', () => {
  it('ArrowRight moves to the next tab and focuses it', () => {
    const onChange = vi.fn();
    render(<RunsViewTabs view="day" onChange={onChange} dayWord="Today" />);
    fireEvent.keyDown(screen.getByTestId('runs-view-tab-day'), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenCalledWith('cost');
    expect(screen.getByTestId('runs-view-tab-cost')).toHaveFocus();
    expect(screen.getByTestId('runs-view-tab-day-word')).toHaveTextContent('Today');
  });

  it('ArrowRight wraps from the last tab to the first', () => {
    const onChange = vi.fn();
    render(<RunsViewTabs view="timing" onChange={onChange} />);
    fireEvent.keyDown(screen.getByTestId('runs-view-tab-timing'), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenCalledWith('day');
    expect(screen.queryByTestId('runs-view-tab-day-word')).toBeNull();
  });

  it('ArrowLeft wraps from the first tab to the last', () => {
    const onChange = vi.fn();
    render(<RunsViewTabs view="day" onChange={onChange} />);
    fireEvent.keyDown(screen.getByTestId('runs-view-tab-day'), { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenCalledWith('timing');
  });

  it('ArrowLeft steps back one tab', () => {
    const onChange = vi.fn();
    render(<RunsViewTabs view="timing" onChange={onChange} />);
    fireEvent.keyDown(screen.getByTestId('runs-view-tab-timing'), { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenCalledWith('cost');
  });

  it('Home and End jump to the ends', () => {
    const onChange = vi.fn();
    render(<RunsViewTabs view="cost" onChange={onChange} />);
    fireEvent.keyDown(screen.getByTestId('runs-view-tab-cost'), { key: 'End' });
    expect(onChange).toHaveBeenLastCalledWith('timing');
    fireEvent.keyDown(screen.getByTestId('runs-view-tab-cost'), { key: 'Home' });
    expect(onChange).toHaveBeenLastCalledWith('day');
  });

  it('ignores other keys and modified arrows', () => {
    const onChange = vi.fn();
    render(<RunsViewTabs view="day" onChange={onChange} />);
    const tab = screen.getByTestId('runs-view-tab-day');
    fireEvent.keyDown(tab, { key: 'a' });
    fireEvent.keyDown(tab, { key: 'ArrowRight', ctrlKey: true });
    fireEvent.keyDown(tab, { key: 'ArrowRight', metaKey: true });
    fireEvent.keyDown(tab, { key: 'ArrowRight', altKey: true });
    expect(onChange).not.toHaveBeenCalled();
  });
});
