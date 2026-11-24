import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OperationsScreen from '../components/OperationsScreen.jsx';

describe('OperationsScreen', () => {
  it('is a labelled region whose heading takes focus on mount and which holds its content', () => {
    render(<OperationsScreen onClose={() => {}}><p>the console</p></OperationsScreen>);
    const region = screen.getByRole('region', { name: 'Operations' });
    expect(region).toBe(screen.getByTestId('operations-screen'));
    expect(screen.getByTestId('operations-title')).toHaveFocus();
    expect(screen.getByTestId('operations-title')).toHaveAttribute('tabindex', '-1');
    expect(screen.getByTestId('operations-body')).toHaveTextContent('the console');
  });

  it('is not a dialog: no aria-modal anywhere, so it never counts as a foreign modal', () => {
    render(<OperationsScreen onClose={() => {}} />);
    expect(document.querySelector('[aria-modal]')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('"‹ PhotoCast" calls onClose', async () => {
    const onClose = vi.fn();
    render(<OperationsScreen onClose={onClose} />);
    await userEvent.click(screen.getByTestId('operations-back'));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('operations-back')).toHaveTextContent('PhotoCast');
  });

  it('tolerates no onClose', async () => {
    render(<OperationsScreen />);
    await userEvent.click(screen.getByTestId('operations-back'));
    expect(screen.getByTestId('operations-screen')).toBeInTheDocument();
  });
});
