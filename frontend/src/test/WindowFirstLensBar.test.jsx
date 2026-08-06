import React from 'react';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstLensBar from '../components/WindowFirstLensBar.jsx';
import useReachLens from '../hooks/useReachLens.js';
import { PLAN_REACH_KEY } from '../utils/reachLens.js';

const TUESDAY = '2026-08-04';
const SATURDAY = '2026-08-08';

/**
 * The production pairing, minus the briefing.
 *
 * <p>Not a harness invented for the test: the provider calls {@code useReachLens} and the shell
 * renders its value into this bar, and the hook's output is the bar's only prop. Rendering the two
 * together is therefore the real consumer relationship — what is left out is the briefing fetch,
 * which the bar reads nothing from.
 */
function Lens({ todayStr = TUESDAY, locked = false, spotCount = 34, windowCount = 6 }) {
  const lens = useReachLens(todayStr, locked);
  return <WindowFirstLensBar lens={lens} spotCount={spotCount} windowCount={windowCount} />;
}

const tiers = () => within(screen.getByTestId('window-first-lens-tiers')).getAllByRole('button');
const pressed = () => tiers().filter((b) => b.getAttribute('aria-pressed') === 'true')
  .map((b) => b.textContent);

describe('WindowFirstLensBar — the control', () => {
  beforeEach(() => localStorage.clear());

  it('draws all four tiers, in the order the design does', () => {
    render(<Lens />);
    expect(tiers().map((b) => b.textContent)).toEqual(['45 min', '1h 30min', '2h 30min', 'Any']);
  });

  it('starts on the weekday default with exactly one tier pressed', () => {
    render(<Lens todayStr={TUESDAY} />);
    expect(pressed()).toEqual(['45 min']);
  });

  it('starts on the weekend default at the weekend', () => {
    render(<Lens todayStr={SATURDAY} />);
    expect(pressed()).toEqual(['2h 30min']);
  });

  it('moves the pressed state when a tier is chosen', () => {
    render(<Lens todayStr={TUESDAY} />);
    fireEvent.click(screen.getByRole('button', { name: '1h 30min' }));
    expect(pressed()).toEqual(['1h 30min']);
  });

  it('marks the chosen tier for a sighted user too, not only for aria', () => {
    // `.wf-seg-btn.on` is the ONLY rule in index.css that distinguishes the active tier — there is
    // no `[aria-pressed="true"]` selector and no other differentiator. Every other test here reads
    // `aria-pressed`, so deleting the class half of the ternary would render four identical chips
    // with the whole file still green. Pinned in both halves for the same reason `far` is pinned on
    // the card AND on its drive line: each can be deleted alone.
    render(<Lens todayStr={TUESDAY} />);
    fireEvent.click(screen.getByRole('button', { name: '2h 30min' }));

    expect(screen.getByRole('button', { name: '2h 30min' })).toHaveClass('on');
    expect(screen.getByRole('button', { name: '45 min' })).not.toHaveClass('on');
  });

  it('names the group so the four buttons are not four loose ones', () => {
    // Their labels are bare durations: without the group's name, "45 min" announces as a duration
    // with no statement of what it does.
    render(<Lens />);
    expect(screen.getByRole('group', { name: 'How far tonight' })).toBeInTheDocument();
  });

  it('states the tier, the day that derived it, and the count it left', () => {
    render(<Lens todayStr={TUESDAY} spotCount={34} windowCount={6} />);
    expect(screen.getByTestId('window-first-lens-readout'))
      .toHaveTextContent('45 min · weekday default · 34 spots across 6 windows');
  });
});

describe('WindowFirstLensBar — marking a temporary override', () => {
  beforeEach(() => localStorage.clear());

  it('shows neither the pill nor the reset while the bar sits on its default', () => {
    // Both mark a deviation. On the default there is none, and a permanently visible "today only"
    // would say the default itself expires.
    render(<Lens todayStr={TUESDAY} />);
    expect(screen.queryByTestId('window-first-lens-temporary')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-reset')).toBeNull();
  });

  it('marks a chosen tier as today only and offers a named way back', () => {
    render(<Lens todayStr={TUESDAY} />);
    fireEvent.click(screen.getByRole('button', { name: 'Any' }));

    expect(screen.getByTestId('window-first-lens-temporary')).toHaveTextContent('today only');
    // Named for the tier it returns to, not "Reset": the user has to know what they are going back
    // to before pressing it.
    expect(screen.getByRole('button', { name: 'Back to 45 min' })).toBeInTheDocument();
  });

  it('names the weekend default on the reset when the weekend derived it', () => {
    render(<Lens todayStr={SATURDAY} />);
    fireEvent.click(screen.getByRole('button', { name: '45 min' }));
    expect(screen.getByRole('button', { name: 'Back to 2h 30min' })).toBeInTheDocument();
  });

  it('returns to the default and clears both marks', () => {
    render(<Lens todayStr={TUESDAY} />);
    fireEvent.click(screen.getByRole('button', { name: '2h 30min' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back to 45 min' }));

    expect(pressed()).toEqual(['45 min']);
    expect(screen.queryByTestId('window-first-lens-temporary')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-reset')).toBeNull();
  });

  it('drops the "default" clause from the readout once a tier is chosen', () => {
    render(<Lens todayStr={TUESDAY} />);
    fireEvent.click(screen.getByRole('button', { name: 'Any' }));

    const readout = screen.getByTestId('window-first-lens-readout');
    expect(readout).toHaveTextContent('Any · 34 spots across 6 windows');
    expect(readout).not.toHaveTextContent('default');
  });

  it('renders no reset at all rather than an inert hidden one', () => {
    // The mock hides it with `display: none`, which leaves a button in the tab order that goes
    // nowhere — a control whose only visible effect is nothing, which §6 bans.
    render(<Lens todayStr={TUESDAY} />);
    expect(screen.queryByRole('button', { name: /^Back to/ })).toBeNull();
  });
});

describe('WindowFirstLensBar — the LITE treatment', () => {
  beforeEach(() => localStorage.clear());

  it('greys the control group and disables every tier', () => {
    // CLAUDE.md's PRO/ADMIN pattern, and plan §7 puts this bar in it. `disabled` as well as the
    // wrapper's pointer-events, because pointer-events does not stop a keyboard.
    render(<Lens locked />);

    expect(screen.getByTestId('window-first-lens-controls').className)
      .toContain('wf-lens-locked');
    for (const button of tiers()) expect(button).toBeDisabled();
  });

  it('leaves the control ungreyed for anyone who can use it', () => {
    // The negative half. Without it the assertion above passes on a bar that greys every role,
    // which is a strictly worse product than one that greys none.
    render(<Lens />);
    expect(screen.getByTestId('window-first-lens-controls').className)
      .not.toContain('wf-lens-locked');
  });

  it('offers the upsell rather than a control that silently does nothing', () => {
    render(<Lens locked />);
    expect(screen.getByTestId('window-first-lens-upsell')).toHaveTextContent('Pro');
  });

  it('keeps the upsell OUTSIDE the greyed box, because it is not the inactive part', () => {
    // Measured on the running app: inside the wrapper the pill composites to 3.68:1 against AA's
    // 4.5:1 floor, and 12.59:1 outside it. WCAG 1.4.3 exempts an inactive component — which is
    // what licenses the greyed tiers — and does not exempt the call to action beside them.
    // `HotTopicStrip` already renders its own upsell as a sibling of the blurred content.
    render(<Lens locked />);
    expect(screen.getByTestId('window-first-lens-controls'))
      .not.toContainElement(screen.getByTestId('window-first-lens-upsell'));
  });

  it('pins to Any, so the greyed control withholds a tool and never a spot', () => {
    // "Breadcrumbs not paywalls". A tier a LITE user cannot widen would hide forecast content —
    // on a weekday, every spot past 45 minutes, with no route to it.
    render(<Lens locked todayStr={TUESDAY} />);
    expect(pressed()).toEqual(['Any']);
  });

  it('claims no override, however far Any sits from today\'s default', () => {
    // "today only" and the reset both mark a choice the user made. A locked user has made none,
    // so a bar that showed them would offer a way back from something they never did.
    render(<Lens locked todayStr={TUESDAY} />);
    expect(screen.queryByTestId('window-first-lens-temporary')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-reset')).toBeNull();
  });

  it('claims no default either, because Any is not the day\'s', () => {
    // The readout's clause is a claim about where the value came from. "Any · weekday default" on
    // a Tuesday would be plainly false.
    render(<Lens locked todayStr={TUESDAY} />);
    const readout = screen.getByTestId('window-first-lens-readout');
    expect(readout).toHaveTextContent('Any · 34 spots across 6 windows');
    expect(readout).not.toHaveTextContent('default');
  });

  it('closes the DOM route to the tiers, and stores nothing on the attempt', () => {
    // ⚠️ The click here proves LESS than it looks. React does not dispatch a click handler on a
    // `disabled` button, so this passes whether or not the hook guards the write — an earlier
    // version of this test was the whole coverage for that guard and a mutation deleting it
    // survived. What this pins is the DOM half: the button is unreachable and the render is
    // unmoved. The guard itself is exercised programmatically in `useReachLens.test.jsx`, which
    // is the only way to reach it.
    render(<Lens locked todayStr={TUESDAY} />);
    fireEvent.click(screen.getByRole('button', { name: '45 min' }));

    expect(screen.getByRole('button', { name: '45 min' })).toBeDisabled();
    expect(pressed()).toEqual(['Any']);
    expect(localStorage.getItem(PLAN_REACH_KEY)).toBeNull();
  });
});
