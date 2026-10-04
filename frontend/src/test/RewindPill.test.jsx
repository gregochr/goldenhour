import { describe, it, expect, afterEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import RewindPill from '../components/RewindPill.jsx';
import { getRewind, setRewind } from '../utils/rewind.js';

describe('RewindPill', () => {
  afterEach(() => act(() => setRewind(null)));

  it('names the rewound moment in UK wall time; the live region is the text, not the bar with its button', () => {
    render(<RewindPill rewind={{ to: '2026-10-04T04:58:00Z', focus: null }} />);
    const bar = screen.getByTestId('rewind-pill');
    expect(bar).not.toHaveAttribute('role');
    expect(bar).not.toHaveAttribute('aria-modal');
    // In normal flow — never `fixed`, which painted over the Map tab's bottom-left chrome.
    expect(bar.className).not.toMatch(/\bfixed\b/);
    const text = screen.getByTestId('rewind-pill-text');
    expect(text).toHaveAttribute('role', 'status');
    expect(text).toHaveTextContent('Rewound to Sun 4 Oct, 05:58 UK');
    expect(text.querySelector('button')).toBeNull();
  });

  it('Back to live clears the rewind', () => {
    setRewind('2026-10-04T04:58:00Z');
    render(<RewindPill rewind={getRewind()} />);
    fireEvent.click(screen.getByTestId('rewind-pill-exit'));
    expect(getRewind()).toBeNull();
  });
});
