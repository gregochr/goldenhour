import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import AskBar from '../components/ask/AskBar.jsx';
import AskField from '../components/ask/AskField.jsx';

/**
 * The two Ask entry controls on their own: what they promise through their props. F2 reuses
 * {@code AskField} at 340px with the `/` hint, so the props it will set are pinned here before it
 * exists; the shell's wiring of both is in {@code AskShellEntry.test.jsx}.
 */

describe('AskBar', () => {
  it('is a button named by its prompt, which opens the dialog it names', () => {
    const onOpen = vi.fn();
    render(<AskBar prompt="Ask about rare events…" onOpen={onOpen} />);
    const bar = screen.getByRole('button', { name: 'Ask about rare events…' });
    expect(bar).toHaveAttribute('aria-haspopup', 'dialog');
    fireEvent.click(bar);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('keeps its decorative "Ask" kicker and arrow out of the accessible name', () => {
    render(<AskBar prompt="Ask about this weekend…" onOpen={vi.fn()} />);
    expect(screen.getByTestId('ask-bar')).toHaveAccessibleName('Ask about this weekend…');
  });

  it('reports whether its sheet is open', () => {
    const { rerender } = render(<AskBar prompt="p" onOpen={vi.fn()} />);
    expect(screen.getByTestId('ask-bar')).toHaveAttribute('aria-expanded', 'false');
    rerender(<AskBar prompt="p" onOpen={vi.fn()} expanded />);
    expect(screen.getByTestId('ask-bar')).toHaveAttribute('aria-expanded', 'true');
  });

  it('is disabled for real, and a disabled bar opens nothing', () => {
    const onOpen = vi.fn();
    render(<AskBar prompt="p" onOpen={onOpen} disabled />);
    expect(screen.getByTestId('ask-bar')).toBeDisabled();
    fireEvent.click(screen.getByTestId('ask-bar'));
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('hands its node to the caller, which is where focus returns', () => {
    const ref = React.createRef();
    render(<AskBar prompt="p" onOpen={vi.fn()} buttonRef={ref} />);
    expect(ref.current).toBe(screen.getByTestId('ask-bar'));
  });
});

describe('AskField', () => {
  it('is the 260px form by default, with no "/" hint and no shortcut claimed', () => {
    render(<AskField onOpen={vi.fn()} />);
    const field = screen.getByRole('button', { name: 'Ask about the forecasts…' });
    expect(field).toHaveAttribute('data-width', '260');
    expect(field).not.toHaveAttribute('aria-keyshortcuts');
    expect(screen.queryByText('/')).toBeNull();
  });

  it('is the 340px form with the hint when F2 asks for it, and claims the shortcut', () => {
    render(<AskField onOpen={vi.fn()} width={340} showKeyHint />);
    const field = screen.getByTestId('ask-field');
    expect(field).toHaveAttribute('data-width', '340');
    expect(field).toHaveAttribute('aria-keyshortcuts', '/');
    expect(screen.getByText('/')).toBeInTheDocument();
  });

  it('keeps the key cap out of the accessible name', () => {
    render(<AskField onOpen={vi.fn()} width={340} showKeyHint />);
    expect(screen.getByTestId('ask-field')).toHaveAccessibleName('Ask about the forecasts…');
  });

  it('opens on press, reports expanded, and is disabled for real when told', () => {
    const onOpen = vi.fn();
    const { rerender } = render(<AskField onOpen={onOpen} />);
    fireEvent.click(screen.getByTestId('ask-field'));
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('ask-field')).toHaveAttribute('aria-expanded', 'false');

    rerender(<AskField onOpen={onOpen} expanded disabled />);
    expect(screen.getByTestId('ask-field')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('ask-field')).toBeDisabled();
  });

  it('takes a different prompt, which becomes its name', () => {
    render(<AskField onOpen={vi.fn()} prompt="Ask about the map…" />);
    expect(screen.getByRole('button', { name: 'Ask about the map…' })).toBeInTheDocument();
  });
});
