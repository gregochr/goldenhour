/**
 * "Clear answer" / "Clear" (`components/ask/AskClearAnswer.jsx`): the one button that ends the
 * conversation. Its label is "Clear answer" for a first answer and "Clear" once there is a thread above
 * the answer to lose too (`docs/engineering/ask-thread-plan.md` §2.5). The value is a stand-in for the
 * provider's; the real provider's `clear()` emptying the thread is `AskThread.test.jsx`'s.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

let mockAsk;
vi.mock('../context/AskContext.jsx', () => ({ useAsk: () => mockAsk }));

import AskClearAnswer from '../components/ask/AskClearAnswer.jsx';

const EARLIER = [{ question: 'One?', summary: 'First.', answerId: 1 }];
const conversation = (over = {}) => ({
  phase: 'answer', history: [], clear: vi.fn(), ...over,
});
const draw = () => {
  const inputRef = { current: document.createElement('input') };
  render(<AskClearAnswer inputRef={inputRef} />);
  return inputRef;
};

beforeEach(() => { mockAsk = conversation(); });

describe('AskClearAnswer', () => {
  it('reads "Clear answer" over a first answer: what a fresh conversation has always said', () => {
    draw();

    expect(screen.getByTestId('ask-clear')).toHaveTextContent(/^Clear answer$/);
    expect(screen.getByRole('button', { name: 'Clear answer' })).toBeInTheDocument();
  });

  it.each(['answer', 'cant', 'error', 'plan'])('reads "Clear" once an earlier exchange stands above the %s', (phase) => {
    mockAsk = conversation({ phase, history: EARLIER });
    draw();

    expect(screen.getByTestId('ask-clear')).toHaveTextContent(/^Clear$/);
    expect(screen.getByRole('button', { name: 'Clear' })).toBeInTheDocument();
  });

  it('ends the conversation, and moves focus to the field first (the button unmounts with it)', () => {
    mockAsk = conversation({ history: EARLIER });
    const inputRef = draw();
    const focus = vi.spyOn(inputRef.current, 'focus');

    fireEvent.click(screen.getByTestId('ask-clear'));

    expect(focus).toHaveBeenCalledTimes(1);
    expect(mockAsk.clear).toHaveBeenCalledTimes(1);
  });

  it.each(['empty', 'busy'])('is not drawn while the conversation is %s, thread or no thread', (phase) => {
    mockAsk = conversation({ phase, history: EARLIER });
    draw();

    expect(screen.queryByTestId('ask-clear')).toBeNull();
  });
});
