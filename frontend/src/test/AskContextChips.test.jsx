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

  it('says "follow-up · N so far" over a follow-up: a plain label, last, with no ✕', () => {
    render(<AskContextChips viewLabel="Map · My area" windowLabel="Sat sunrise" followUp={3} />);

    const chip = screen.getByTestId('ask-chip-followup');
    expect(chip).toHaveTextContent('follow-up · 3 so far');
    expect(screen.getByTestId('ask-chip-view').nextElementSibling).toBe(chip);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it.each([undefined, 0])('draws no follow-up chip for %s: a first question’s chips are what they were', (followUp) => {
    render(<AskContextChips viewLabel="Map · My area" followUp={followUp} />);

    expect(screen.queryByTestId('ask-chip-followup')).toBeNull();
  });
});
