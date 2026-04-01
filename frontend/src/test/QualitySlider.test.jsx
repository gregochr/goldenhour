import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import QualitySlider from '../components/QualitySlider.jsx';
import { TIER_LABELS } from '../utils/tierUtils.js';

const MAX_TIER = 5;

describe('QualitySlider', () => {
  it('shows Worst label on the left', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByText('Worst')).toBeInTheDocument();
  });

  it('shows Best label on the right', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByText('Best')).toBeInTheDocument();
  });

  it('slider fully left (visual 0) means everything visible (internal tier 5)', () => {
    const onChange = vi.fn();
    render(<QualitySlider value={5} onChange={onChange} showing={10} total={10} />);
    const slider = screen.getByRole('slider');
    // Internal 5 → visual 0 (fully left)
    expect(slider).toHaveValue('0');
  });

  it('slider fully right (visual 5) means best only (internal tier 0)', () => {
    render(<QualitySlider value={0} onChange={() => {}} showing={2} total={10} />);
    const slider = screen.getByRole('slider');
    // Internal 0 → visual 5 (fully right)
    expect(slider).toHaveValue('5');
  });

  it('default tier 2 renders at visual position 3', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    const slider = screen.getByRole('slider');
    // Internal 2 → visual 3
    expect(slider).toHaveValue('3');
  });

  it('dragging right raises quality (lowers internal tier)', () => {
    const onChange = vi.fn();
    render(<QualitySlider value={2} onChange={onChange} showing={5} total={12} />);
    // Drag to visual 4 → internal = 5 - 4 = 1
    fireEvent.change(screen.getByRole('slider'), { target: { value: '4' } });
    expect(onChange).toHaveBeenCalledWith(1);
  });

  it('dragging left lowers quality (raises internal tier)', () => {
    const onChange = vi.fn();
    render(<QualitySlider value={2} onChange={onChange} showing={5} total={12} />);
    // Drag to visual 1 → internal = 5 - 1 = 4
    fireEvent.change(screen.getByRole('slider'), { target: { value: '1' } });
    expect(onChange).toHaveBeenCalledWith(4);
  });

  it('shows the showing/total cell count', () => {
    render(<QualitySlider value={3} onChange={() => {}} showing={8} total={18} />);
    expect(screen.getByText(/Showing 8 of 18 cells/)).toBeInTheDocument();
  });

  it('shows the tier label for the current value', () => {
    render(<QualitySlider value={0} onChange={() => {}} showing={2} total={10} />);
    expect(screen.getByText((content) => content.includes(TIER_LABELS[0]))).toBeInTheDocument();
  });

  it('has aria-label on the slider', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByRole('slider')).toHaveAttribute('aria-label', 'Quality threshold');
  });

  it('has aria-valuetext set to current tier label', () => {
    render(<QualitySlider value={3} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', TIER_LABELS[3]);
  });
});
