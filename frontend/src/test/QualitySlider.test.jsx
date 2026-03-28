import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import QualitySlider from '../components/QualitySlider.jsx';
import { TIER_LABELS } from '../utils/tierUtils.js';

describe('QualitySlider', () => {
  it('renders with correct initial value', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    const slider = screen.getByRole('slider');
    expect(slider).toHaveValue('2');
  });

  it('shows the showing/total count', () => {
    render(<QualitySlider value={3} onChange={() => {}} showing={8} total={18} />);
    expect(screen.getByText(/Showing 8 of 18 cells/)).toBeInTheDocument();
  });

  it('shows the tier label for the current value', () => {
    render(<QualitySlider value={0} onChange={() => {}} showing={2} total={10} />);
    // The label is rendered with a bullet prefix: "· GO + king tide only"
    expect(screen.getByText((content) => content.includes(TIER_LABELS[0]))).toBeInTheDocument();
  });

  it('shows Best label on the left', () => {
    render(<QualitySlider value={0} onChange={() => {}} showing={2} total={10} />);
    expect(screen.getByText('Best')).toBeInTheDocument();
  });

  it('shows All label on the right', () => {
    render(<QualitySlider value={5} onChange={() => {}} showing={10} total={10} />);
    expect(screen.getByText('All')).toBeInTheDocument();
  });

  it('calls onChange with new integer value on change', () => {
    const onChange = vi.fn();
    render(<QualitySlider value={2} onChange={onChange} showing={5} total={12} />);
    fireEvent.change(screen.getByRole('slider'), { target: { value: '4' } });
    expect(onChange).toHaveBeenCalledWith(4);
  });

  it('has aria-label on the slider', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByRole('slider')).toHaveAttribute('aria-label', 'Quality threshold');
  });

  it('has aria-valuetext set to current tier label', () => {
    render(<QualitySlider value={3} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', TIER_LABELS[3]);
  });

  it('has data-testid quality-slider', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByTestId('quality-slider')).toBeInTheDocument();
  });
});
