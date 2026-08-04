import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
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
  const renderShell = (props = {}) => {
    const handlers = { onExit: vi.fn(), onOpenSettings: vi.fn(), onSignOut: vi.fn(), ...props };
    render(<WindowFirstShell {...handlers} />);
    return handlers;
  };

  // The shorter of the two routes back — the masthead's ⚙ opens the settings modal, which owns the
  // toggle. This one exists because the arm below it is empty while the shell is a stub.
  it('offers a way back to the current Plan', () => {
    const { onExit } = renderShell();
    fireEvent.click(screen.getByRole('button', { name: /back to the current plan/i }));
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('carries the wordmark as the page heading, because it replaces the app header', () => {
    // App suppresses its own <header> for this arm, so if the masthead did not carry the wordmark
    // the signed-in app would have no h1 at all — and src/test/e2e/forecast.spec.js:46 finds the
    // app with getByRole('heading', { name: /PhotoCast/ }). That e2e would break the moment the
    // flag default flips at P15, which is far too late to notice.
    renderShell();
    expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
  });

  it('carries the cog and Sign out the suppressed header used to own', () => {
    // Both are lifted handlers, not new state. Losing either would strand a v2 user with no route
    // to settings — which is the only route back once the temporary exit button goes.
    const { onOpenSettings, onSignOut } = renderShell();

    // By ROLE AND NAME, not test-id: a test-id keeps passing while the accessible name rots, and
    // these two are the only route out of the arm once the temporary exit button goes.
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }));
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it('renders one tab, selected, and no tab whose pane does not exist', () => {
    // The design draws four. Coming up is P13; Map and Manage arrive when this subtree takes over
    // view state. A tab that renders nothing is a demo control and §6 bans those from the shipped
    // build — so this pins that each tab lands WITH its pane rather than ahead of it.
    renderShell();

    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(1);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    // Exactly "Plan": the ◉ is decorative and must not leak into the accessible name, which is the
    // rule ViewToggle:56 already follows for its own glyphs.
    expect(tabs[0]).toHaveAccessibleName('Plan');
  });

  it('carries exactly two masthead controls, so no build/health pill creeps in', () => {
    // The mock shows "● UP v2.17.7" unconditionally; §7 drops it, because build version and
    // service health are not a pilot user's business and HealthIndicator is admin-only. Asserting
    // the ABSENCE of that string would pass whether or not anything was ever built — this counts
    // what the masthead actually offers instead, so an added control has to be argued for.
    renderShell();
    const masthead = screen.getByTestId('window-first-masthead');
    const names = within(masthead).getAllByRole('button').map((b) => b.textContent.trim());
    expect(names).toEqual(['⚙', 'Sign out']);
  });

  it('renders at the design\'s 1080px frame, not the v1 arm\'s 896px column', () => {
    // One of P4a's two deliverables, and nothing else pinned it. The v1 arm is max-w-4xl (896px);
    // a shell that inherited that would be ~200px under the frame every later phase is drawn to.
    renderShell();
    expect(screen.getByTestId('window-first-shell')).toHaveStyle({ maxWidth: '1080px' });
  });

  it('greys the pane when the backend is DOWN, but never the way out', () => {
    // The v1 header sits OUTSIDE the element carrying the DOWN treatment, so it has never been
    // able to disable Settings or Sign out. Here the masthead is inside the shell: gating the
    // whole subtree would strand a user on a dead page with no cog, no Sign out and no exit —
    // exactly when they most need one.
    renderShell({ contentDisabled: true });

    expect(screen.getByTestId('window-first-pane').className).toContain('pointer-events-none');
    expect(screen.getByTestId('window-first-masthead').className)
      .not.toContain('pointer-events-none');
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  });
});
