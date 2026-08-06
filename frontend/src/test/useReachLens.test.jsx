import React from 'react';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstLensBar from '../components/WindowFirstLensBar.jsx';
import useReachLens from '../hooks/useReachLens.js';
import { REACH_LENS_KEY } from '../utils/reachLens.js';

const MONDAY = '2026-08-03';
const TUESDAY = '2026-08-04';
const SATURDAY = '2026-08-08';

/**
 * The hook rendered through the one component that consumes its value.
 *
 * <p>`todayStr` is a prop rather than a frozen clock because that is how the provider supplies it —
 * derived once per render from {@code Intl.DateTimeFormat} in Europe/London — so a day roll is
 * modelled by re-rendering with the next date, which is exactly what happens on the next poll.
 */
function Lens({ todayStr, locked = false }) {
  const lens = useReachLens(todayStr, locked);
  return <WindowFirstLensBar lens={lens} spotCount={0} windowCount={0} />;
}

/**
 * The hook's setters behind buttons that are always enabled.
 *
 * <p>A harness, and it earns the exception the standards allow. The bar renders its tiers
 * {@code disabled} for a locked user, and React does not dispatch a click handler on a disabled
 * button — so no test driving the real component can reach {@code selectTier}'s own lock, and one
 * that clicked the greyed tier passed with the guard deleted. This is the only route to it.
 */
function Direct({ todayStr, locked = false }) {
  const lens = useReachLens(todayStr, locked);
  return (
    <div>
      <span data-testid="tier">{lens.tierId}</span>
      <button type="button" onClick={() => lens.selectTier('150')}>widen</button>
      <button type="button" onClick={() => lens.selectTier('nonsense')}>bogus</button>
      <button type="button" onClick={lens.resetToDefault}>reset</button>
    </div>
  );
}

const pressed = () => within(screen.getByTestId('window-first-lens-tiers'))
  .getAllByRole('button')
  .filter((b) => b.getAttribute('aria-pressed') === 'true')
  .map((b) => b.textContent);

const stored = () => JSON.parse(localStorage.getItem(REACH_LENS_KEY) || 'null');

describe('useReachLens — the default', () => {
  beforeEach(() => localStorage.clear());

  it('takes the weekday tier on a weekday and the weekend tier at the weekend', () => {
    const { unmount } = render(<Lens todayStr={TUESDAY} />);
    expect(pressed()).toEqual(['45 min']);
    unmount();

    render(<Lens todayStr={SATURDAY} />);
    expect(pressed()).toEqual(['2h 30min']);
  });

  it('stores nothing until the user chooses', () => {
    // The default "needs no storage, no column and no owner" (plan §2.5). Writing it on mount
    // would make every user's first page load a write, and would make the day roll a no-op
    // because the stamp would already be today's.
    render(<Lens todayStr={TUESDAY} />);
    expect(stored()).toBeNull();
  });
});

describe('useReachLens — persistence', () => {
  beforeEach(() => localStorage.clear());

  it('records the chosen tier against the day it was chosen on', () => {
    render(<Lens todayStr={TUESDAY} />);
    fireEvent.click(screen.getByRole('button', { name: '2h 30min' }));
    expect(stored()).toEqual({ reach: '150', reachDay: TUESDAY });
  });

  it('restores a choice made earlier the same day', () => {
    localStorage.setItem(REACH_LENS_KEY, JSON.stringify({ reach: '90', reachDay: TUESDAY }));
    render(<Lens todayStr={TUESDAY} />);
    expect(pressed()).toEqual(['1h 30min']);
  });

  it('ignores a choice made yesterday, which is the whole expiry policy', () => {
    // Plan §5: "Day-derived default; reach expires at the day roll." A Saturday's "2h 30min" that
    // survived into Monday morning would quietly widen every weekday page thereafter.
    localStorage.setItem(REACH_LENS_KEY, JSON.stringify({ reach: '150', reachDay: MONDAY }));
    render(<Lens todayStr={TUESDAY} />);
    expect(pressed()).toEqual(['45 min']);
  });

  it('leaves the expired entry alone rather than rewriting it as today\'s', () => {
    localStorage.setItem(REACH_LENS_KEY, JSON.stringify({ reach: '150', reachDay: MONDAY }));
    render(<Lens todayStr={TUESDAY} />);
    expect(stored()).toEqual({ reach: '150', reachDay: MONDAY });
  });

  it('falls back to the new day\'s default when the date rolls under an open browser', () => {
    // No effect and no timer does this — the choice is held WITH its day and the active tier is
    // derived, so a stamp that stops matching simply stops applying. The provider re-renders on
    // its ten-minute poll, which is what supplies the new date.
    const { rerender } = render(<Lens todayStr={MONDAY} />);
    fireEvent.click(screen.getByRole('button', { name: 'Any' }));
    expect(pressed()).toEqual(['Any']);

    rerender(<Lens todayStr={TUESDAY} />);
    expect(pressed()).toEqual(['45 min']);
  });

  it('re-derives the default itself across a day roll into the weekend', () => {
    // Both halves move at midnight — the stored choice expires and the default changes — and only
    // deriving both from the same `todayStr` keeps them in step.
    const { rerender } = render(<Lens todayStr={TUESDAY} />);
    expect(pressed()).toEqual(['45 min']);

    rerender(<Lens todayStr={SATURDAY} />);
    expect(pressed()).toEqual(['2h 30min']);
  });

  it('refuses a write while locked, whatever route reaches it', () => {
    // The bar's own buttons are `disabled`, so this is the guard's only reachable test. It is not
    // theatre: without it a LITE user's click — from a synthetic event, a devtools edit, or a
    // future caller that forgets — would persist a tier that then took effect the moment their
    // role changed, with nothing on screen ever having moved to explain it.
    render(<Direct todayStr={TUESDAY} locked />);
    fireEvent.click(screen.getByText('widen'));

    expect(screen.getByTestId('tier')).toHaveTextContent('any');
    expect(stored()).toBeNull();
  });

  it('refuses a reset while locked, which routes through the same setter', () => {
    render(<Direct todayStr={TUESDAY} locked />);
    fireEvent.click(screen.getByText('reset'));

    expect(screen.getByTestId('tier')).toHaveTextContent('any');
    expect(stored()).toBeNull();
  });

  it('refuses a tier id this build does not have', () => {
    // Storing it would put a value in the key that `readStoredTierId` then rejects on every load —
    // a setting that silently does nothing rather than one that visibly failed.
    render(<Direct todayStr={TUESDAY} />);
    fireEvent.click(screen.getByText('bogus'));

    expect(screen.getByTestId('tier')).toHaveTextContent('45');
    expect(stored()).toBeNull();
  });

  it('accepts a write when the control is live, which is the other half of the guard', () => {
    render(<Direct todayStr={TUESDAY} />);
    fireEvent.click(screen.getByText('widen'));

    expect(screen.getByTestId('tier')).toHaveTextContent('150');
    expect(stored()).toEqual({ reach: '150', reachDay: TUESDAY });
  });

  it('persists a reset, so the default cannot be undone by a stale entry', () => {
    render(<Lens todayStr={TUESDAY} />);
    fireEvent.click(screen.getByRole('button', { name: 'Any' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back to 45 min' }));

    expect(pressed()).toEqual(['45 min']);
    expect(stored()).toEqual({ reach: '45', reachDay: TUESDAY });
  });
});
