import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import PlanErrorBoundary from '../components/PlanErrorBoundary.jsx';
import { PLAN_REACH_KEY } from '../utils/reachLens.js';

const silenceReactErrorLog = () => vi.spyOn(console, 'error').mockImplementation(() => {});

function Bomb({ armed }) {
  if (armed) throw new Error('late failure');
  return <p data-testid="healthy-subtree">fine</p>;
}

function Blank() {
  throw new Error('');
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('PlanErrorBoundary branches', () => {
  it('moves focus to the fallback heading when a healthy subtree fails on a later update', () => {
    silenceReactErrorLog();
    const { rerender } = render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Bomb armed={false} />
      </PlanErrorBoundary>,
    );
    expect(screen.getByTestId('healthy-subtree')).toBeInTheDocument();
    rerender(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Bomb armed />
      </PlanErrorBoundary>,
    );
    expect(screen.getByTestId('plan-error-detail')).toHaveTextContent('late failure');
    expect(screen.getByRole('heading', { name: 'At a Glance stopped working' })).toHaveFocus();
  });

  it('does not steal focus back to the heading when the fallback re-renders', () => {
    silenceReactErrorLog();
    const { rerender } = render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Bomb armed />
      </PlanErrorBoundary>,
    );
    const signOut = screen.getByTestId('plan-error-signout');
    signOut.focus();
    rerender(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Bomb armed />
      </PlanErrorBoundary>,
    );
    expect(screen.getByTestId('plan-error-signout')).toHaveFocus();
  });

  it('quotes the error itself when its message is empty', () => {
    silenceReactErrorLog();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Blank />
      </PlanErrorBoundary>,
    );
    expect(screen.getByTestId('plan-error-detail')).toHaveTextContent('Error');
  });

  it('clear-and-reload falls back to reloading the page itself when no reload is injected', async () => {
    silenceReactErrorLog();
    localStorage.setItem(PLAN_REACH_KEY, 'x');
    render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Bomb armed />
      </PlanErrorBoundary>,
    );
    fireEvent.click(screen.getByTestId('plan-error-clear-reload'));
    // jsdom's location.reload is a not-implemented no-op that cannot be observed; what is pinned is
    // that the handler runs to the end (a missing default would reject unhandled and fail the run)
    // after clearing the persisted selection.
    await waitFor(() => expect(localStorage.getItem(PLAN_REACH_KEY)).toBeNull());
    await new Promise((r) => { setTimeout(r, 50); });
  });
});
