import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import InfoTip from '../components/InfoTip.jsx';

describe('InfoTip', () => {
  it('renders the trigger icon', () => {
    render(<InfoTip text="Some help text" />);
    expect(screen.getByTestId('infotip-trigger')).toBeInTheDocument();
  });

  it('does not show popover initially', () => {
    render(<InfoTip text="Some help text" />);
    expect(screen.queryByTestId('infotip-popover')).not.toBeInTheDocument();
  });

  it('shows popover on click', () => {
    render(<InfoTip text="Some help text" />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    expect(screen.getByTestId('infotip-popover')).toBeInTheDocument();
    expect(screen.getByText('Some help text')).toBeInTheDocument();
  });

  it('hides popover on second click (toggle)', () => {
    render(<InfoTip text="Some help text" />);
    const trigger = screen.getByTestId('infotip-trigger');
    fireEvent.click(trigger);
    expect(screen.getByTestId('infotip-popover')).toBeInTheDocument();
    fireEvent.click(trigger);
    expect(screen.queryByTestId('infotip-popover')).not.toBeInTheDocument();
  });

  it('hides popover on click outside', () => {
    render(
      <div>
        <InfoTip text="Some help text" />
        <button data-testid="outside">Outside</button>
      </div>,
    );
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    expect(screen.getByTestId('infotip-popover')).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByTestId('outside'));
    expect(screen.queryByTestId('infotip-popover')).not.toBeInTheDocument();
  });

  it('applies optional className', () => {
    render(<InfoTip text="tip" className="text-red-500" />);
    const wrapper = screen.getByTestId('infotip-trigger').parentElement;
    expect(wrapper.className).toContain('text-red-500');
  });

  it('popover uses wide max-width and left-aligned positioning', () => {
    render(<InfoTip text="Some help text" />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    const popover = screen.getByTestId('infotip-popover');
    expect(popover.className).toContain('w-max');
    expect(popover.className).toContain('left-0');
    expect(popover.className).not.toContain('max-w-[220px]');
  });
});
