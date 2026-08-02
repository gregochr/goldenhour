import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';
import usePlanLayout, { PLAN_LAYOUT_KEY, PLAN_V1, PLAN_V2 } from '../hooks/usePlanLayout.js';
import WindowFirstShell from '../components/WindowFirstShell.jsx';

/**
 * A harness rather than a real consumer, deliberately: the only component that reads this hook is
 * `AppInner`, which cannot be rendered without auth, three SSE streams and the whole forecast load —
 * that would test all of that, not the flag. See docs/engineering/frontend-test-standards.md
 * § Structure; if a smaller real consumer appears, render that instead.
 */
function Host() {
  const [layout, setLayout] = usePlanLayout();
  return (
    <div>
      <span data-testid="layout">{layout}</span>
      <button onClick={() => setLayout(PLAN_V2)}>to v2</button>
      <button onClick={() => setLayout('sideways')}>to nonsense</button>
    </div>
  );
}

describe('usePlanLayout', () => {
  beforeEach(() => localStorage.clear());

  it('defaults to the current Plan tab, so the flag ships off', () => {
    render(<Host />);
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V1);
  });

  it('persists a switch under the versioned key', () => {
    render(<Host />);
    fireEvent.click(screen.getByText('to v2'));
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V2);
    expect(JSON.parse(localStorage.getItem(PLAN_LAYOUT_KEY))).toBe(PLAN_V2);
  });

  it('restores a stored layout on mount', () => {
    localStorage.setItem(PLAN_LAYOUT_KEY, JSON.stringify(PLAN_V2));
    render(<Host />);
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V2);
  });

  // The failure this guards is narrow but total: an unrecognised value must not render neither
  // layout. It can arrive from a half-written key or from a build the user has since rolled back.
  it('falls back to v1 when the stored value is not a layout', () => {
    localStorage.setItem(PLAN_LAYOUT_KEY, JSON.stringify('v99'));
    render(<Host />);
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V1);
  });

  it('refuses to store a value that is not a layout', () => {
    render(<Host />);
    fireEvent.click(screen.getByText('to nonsense'));
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V1);
    // The rendered value alone is already guaranteed by the READ guard, so asserting only that
    // leaves this test unable to fail if the write guard is deleted — it was, and it passed.
    // Storage is the only observable that distinguishes the two.
    expect(JSON.parse(localStorage.getItem(PLAN_LAYOUT_KEY))).toBe(PLAN_V1);
  });
});

describe('WindowFirstShell', () => {
  // The shorter of the two routes back — the app header's ⚙ renders in both arms and also reaches
  // the toggle. This one exists because the arm below it is empty while the shell is a stub.
  it('offers a way back to the current Plan', () => {
    const onExit = vi.fn();
    render(<WindowFirstShell onExit={onExit} />);
    fireEvent.click(screen.getByRole('button', { name: /back to the current plan/i }));
    expect(onExit).toHaveBeenCalledTimes(1);
  });
});
