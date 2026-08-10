import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import PlanLayoutErrorBoundary from '../components/PlanLayoutErrorBoundary.jsx';

/**
 * A boundary can only be tested by throwing at it, and React routes every caught error to
 * `console.error` regardless of the boundary. The spy is scoped to this file and restored after
 * each test rather than installed in `setup.js`, which would hide genuine PropTypes failures and
 * React warnings across all 123 test files.
 */
const silenceReactErrorLog = () => vi.spyOn(console, 'error').mockImplementation(() => {});

function Boom({ message = 'the sky fell' }) {
  throw new Error(message);
}

const Fine = () => <p data-testid="healthy-arm">the arm rendered</p>;

afterEach(() => vi.restoreAllMocks());

describe('PlanLayoutErrorBoundary', () => {
  // The negative baseline. Without it, every assertion below would still pass for a boundary that
  // renders its fallback unconditionally.
  it('renders the arm it guards when nothing throws', () => {
    render(
      <PlanLayoutErrorBoundary onRecover={vi.fn()}>
        <Fine />
      </PlanLayoutErrorBoundary>,
    );
    expect(screen.getByTestId('healthy-arm')).toBeInTheDocument();
    expect(screen.queryByTestId('plan-layout-error')).toBeNull();
  });

  it('shows the failure rather than an empty page', () => {
    const spy = silenceReactErrorLog();
    render(
      <PlanLayoutErrorBoundary onRecover={vi.fn()}>
        <Boom message="tide rollup exploded" />
      </PlanLayoutErrorBoundary>,
    );
    expect(screen.getByTestId('plan-layout-error')).toBeInTheDocument();
    // The message, specifically — a fallback that swallowed it would still pass a presence check,
    // and the message is the only thing a bug report can quote.
    expect(screen.getByTestId('plan-layout-error-detail')).toHaveTextContent('tide rollup exploded');
    expect(spy).toHaveBeenCalled();
  });

  // This is the test that pins the decision, and it is the one worth keeping if any is dropped: the
  // boundary must NOT flip the flag by itself. Catching has already closed the reload trap, and a
  // silent auto-reset would make a real crash indistinguishable from a mis-click.
  it('never changes the layout on its own', () => {
    silenceReactErrorLog();
    const onRecover = vi.fn();
    render(
      <PlanLayoutErrorBoundary onRecover={onRecover}>
        <Boom />
      </PlanLayoutErrorBoundary>,
    );
    expect(screen.getByTestId('plan-layout-error')).toBeInTheDocument();
    expect(onRecover).not.toHaveBeenCalled();
  });

  // By role and accessible name, not by testid: a testid keeps passing while the words rot, and the
  // words are the whole point — they must read like the escape the healthy arm offers.
  it('offers the route back, and takes it only when pressed', () => {
    silenceReactErrorLog();
    const onRecover = vi.fn();
    render(
      <PlanLayoutErrorBoundary onRecover={onRecover}>
        <Boom />
      </PlanLayoutErrorBoundary>,
    );
    const button = screen.getByRole('button', { name: /back to the current plan/i });
    fireEvent.click(button);
    expect(onRecover).toHaveBeenCalledTimes(1);
  });

  it('lets the caller name the route, for the day the arms swap round', () => {
    silenceReactErrorLog();
    const onRecover = vi.fn();
    render(
      <PlanLayoutErrorBoundary onRecover={onRecover} recoverLabel="← Back to the window-first Plan">
        <Boom />
      </PlanLayoutErrorBoundary>,
    );
    fireEvent.click(screen.getByRole('button', { name: /back to the window-first plan/i }));
    expect(onRecover).toHaveBeenCalledTimes(1);
  });

  // The dead arm normally supplies the page's only <h1>. A fallback without one leaves the signed-in
  // app with no heading at all.
  it('keeps the page heading when the arm that owned it is gone', () => {
    silenceReactErrorLog();
    render(
      <PlanLayoutErrorBoundary onRecover={vi.fn()}>
        <Boom />
      </PlanLayoutErrorBoundary>,
    );
    expect(screen.getByRole('heading', { level: 1, name: /photocast/i })).toBeInTheDocument();
  });

  it('announces the failure to assistive tech', () => {
    silenceReactErrorLog();
    render(
      <PlanLayoutErrorBoundary onRecover={vi.fn()}>
        <Boom />
      </PlanLayoutErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});
