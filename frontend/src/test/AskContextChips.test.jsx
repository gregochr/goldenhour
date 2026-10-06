import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import AskContextChips from '../components/ask/AskContextChips.jsx';

describe('AskContextChips', () => {
  it('shows what the question is asked about: a removable window and a fixed view', () => {
    render(<AskContextChips viewLabel="Map · My area" windowLabel="Sat sunrise" onRemoveWindow={vi.fn()} />);

    expect(screen.getByRole('group', { name: 'Asking about' })).toBeInTheDocument();
    expect(screen.getByTestId('ask-chip-window')).toHaveTextContent('Sat sunrise');
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Map · My area');
  });

  it('names the ✕ for what it removes, and calls back when pressed', () => {
    const onRemove = vi.fn();
    render(<AskContextChips viewLabel="Map · My area" windowLabel="Sat sunrise" onRemoveWindow={onRemove} />);

    fireEvent.click(screen.getByRole('button', { name: 'Remove Sat sunrise from the question' }));

    expect(onRemove).toHaveBeenCalledTimes(1);
  });

  it('gives the view chip no ✕: the scope sent is changed on the Map, never here', () => {
    render(<AskContextChips viewLabel="Plan · all regions" />);

    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByTestId('ask-chip-window')).toBeNull();
    expect(screen.getByTestId('ask-chip-view')).toHaveTextContent('Plan · all regions');
  });
});
