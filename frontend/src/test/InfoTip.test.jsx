import { describe, it, expect, vi } from 'vitest';
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

  it('popover uses wide max-width and fixed positioning to escape overflow:hidden ancestors', () => {
    render(<InfoTip text="Some help text" />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    const popover = screen.getByTestId('infotip-popover');
    expect(popover.className).toContain('w-max');
    expect(popover.className).not.toContain('max-w-[220px]');
    // Popover must use position:fixed (not absolute) so it escapes overflow:hidden containers
    expect(popover.style.position).toBe('fixed');
    expect(Number(popover.style.zIndex)).toBeGreaterThanOrEqual(9000);
  });

  it('trigger click does not propagate to parent handler', () => {
    const parentHandler = vi.fn();
    render(
      <button onClick={parentHandler} data-testid="parent">
        <InfoTip text="help" />
      </button>,
    );
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    expect(parentHandler).not.toHaveBeenCalled();
  });

  it('popover content click does not propagate to parent handler', () => {
    const parentHandler = vi.fn();
    render(
      <button onClick={parentHandler} data-testid="parent">
        <InfoTip text="help text content" />
      </button>,
    );
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    fireEvent.click(screen.getByTestId('infotip-popover'));
    expect(parentHandler).not.toHaveBeenCalled();
  });

  it('popover keyDown does not propagate to parent handler', () => {
    const parentHandler = vi.fn();
    render(
      <div onKeyDown={parentHandler} data-testid="parent">
        <InfoTip text="help text" />
      </div>,
    );
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    fireEvent.keyDown(screen.getByTestId('infotip-popover'), { key: 'a' });
    expect(parentHandler).not.toHaveBeenCalled();
  });

  it('trigger has aria-label for screen readers', () => {
    render(<InfoTip text="Some help text" />);
    expect(screen.getByTestId('infotip-trigger')).toHaveAttribute('aria-label', 'More info');
  });

  it('trigger has the extended click target pseudo-element class', () => {
    render(<InfoTip text="Some help text" />);
    const trigger = screen.getByTestId('infotip-trigger');
    expect(trigger.className).toContain('before:absolute');
    expect(trigger.className).toContain('before:-inset-1');
  });

  it('popover has role="presentation" to suppress interaction semantics', () => {
    render(<InfoTip text="Some help text" />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    expect(screen.getByTestId('infotip-popover')).toHaveAttribute('role', 'presentation');
  });
});
