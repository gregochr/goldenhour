/**
 * The window pill's "is better" line on `components/map/WindowControl.jsx`
 * (`docs/engineering/map-landing-nocard-plan.md` P2).
 *
 * <p>Two things are pinned here and neither can be seen through `textContent`:
 * <ul>
 *   <li>⚠️ **focus**: the line unmounts itself the moment the window moves off the first one, and a
 *   focused control that unmounts drops focus to `<body>`, where the pane's keyboard handler never
 *   fires. Both routes (the press, and ←/→ with the line focused) must hand focus to the pill FIRST.
 *   Each is proved by re-rendering WITHOUT the prop, as the parent does, and reading
 *   `document.activeElement`;</li>
 *   <li>the "shows only if it fits" check: with the line drawn, an overflowing pill or verdict cell
 *   withholds it (and `data-better` with it). jsdom has no layout, so `scrollWidth` / `clientWidth` and
 *   `ResizeObserver` are stubbed; the real numbers were measured in Chromium (the plan, D1).</li>
 * </ul>
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import WindowControl from '../components/map/WindowControl.jsx';

function solar(id, eventType, date, dayLabel, time) {
  return {
    id, kind: 'solar', eventType, date, label: `${dayLabel} ${eventType.toLowerCase()}`, dayLabel, time,
    bestRating: 4, scored: true, served: true, badges: [],
  };
}

const FIRST = solar('solar:2026-09-02:SUNSET', 'SUNSET', '2026-09-02', 'Today', '19:45');
const SECOND = solar('solar:2026-09-03:SUNRISE', 'SUNRISE', '2026-09-03', 'Tomorrow', '06:32');
const THIRD = solar('solar:2026-09-03:SUNSET', 'SUNSET', '2026-09-03', 'Tomorrow', '19:43');
const EVENTS = [FIRST, SECOND, THIRD];

const verdict = (tier) => ({ tier, regionName: 'The Lakes', sharingCount: 0, allInScope: false });
const VERDICTS = new Map([
  [FIRST.id, verdict('MAYBE')], [SECOND.id, verdict('WORTH_IT')], [THIRD.id, verdict('WORTH_IT')],
]);
const BETTER = { row: SECOND, text: 'Tomorrow sunrise is better', tier: 'WORTH_IT' };

function renderControl(props = {}) {
  const onSelect = vi.fn();
  const utils = render(
    <WindowControl
      events={EVENTS}
      activeIndex={0}
      onSelect={onSelect}
      verdicts={VERDICTS}
      betterNext={BETTER}
      {...props}
    />,
  );
  /** Re-renders the way the parent does once the window has moved: same control, no line. */
  const rerenderWithout = (extra = {}) => utils.rerender(
    <WindowControl
      events={EVENTS}
      activeIndex={1}
      onSelect={onSelect}
      verdicts={VERDICTS}
      betterNext={null}
      {...extra}
    />,
  );
  return { onSelect, rerenderWithout, ...utils };
}

const line = () => screen.queryByTestId('wf-win-better');

describe('WindowControl — the "is better" line', () => {
  it('renders only with the prop, and carries data-better on the root only while it shows', () => {
    const { unmount } = renderControl();
    expect(line()).toBeInTheDocument();
    expect(screen.getByTestId('wf-win-control')).toHaveAttribute('data-better');
    unmount();

    renderControl({ betterNext: null });
    expect(line()).toBeNull();
    expect(screen.getByTestId('wf-win-control')).not.toHaveAttribute('data-better');
  });

  it('is a sibling of the pill, never inside it', () => {
    renderControl();
    expect(screen.getByTestId('wf-win-pill').contains(line())).toBe(false);
    expect(line().parentElement).toBe(screen.getByTestId('wf-win-control'));
  });

  it('is named exactly by its sentence (the › is aria-hidden) and described by the tier word', () => {
    renderControl();
    const button = screen.getByRole('button', { name: 'Tomorrow sunrise is better' });
    expect(button).toBe(line());
    expect(button).not.toHaveAttribute('aria-label');
    expect(button).toHaveAccessibleDescription('Worth it');
    expect(button.querySelector('[aria-hidden="true"]')).toHaveTextContent('›');
  });

  it('carries the ACTIVE window\'s tier so its frame matches the pill\'s tint', () => {
    renderControl();
    expect(line()).toHaveAttribute('data-tier', 'MAYBE');
    expect(screen.getByTestId('wf-win-pill')).toHaveAttribute('data-tier', 'MAYBE');
  });

  it('a press selects the second row once and leaves the menu closed', () => {
    const { onSelect } = renderControl();
    fireEvent.click(line());
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith(SECOND);
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
    expect(screen.getByTestId('wf-win-pill')).toHaveAttribute('aria-expanded', 'false');
  });

  it('a press while the menu is open closes it', () => {
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    fireEvent.click(line());
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
  });

  it('⚠️ after a press, with the line gone, focus is on the pill (not <body>)', () => {
    const { rerenderWithout } = renderControl();
    line().focus();
    expect(document.activeElement).toBe(line());
    fireEvent.click(line());
    rerenderWithout();
    expect(line()).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('⚠️ ArrowRight with focus on the line steps on, and focus is on the pill once the line is gone', () => {
    const { onSelect, rerenderWithout } = renderControl();
    line().focus();
    fireEvent.keyDown(document.activeElement, { key: 'ArrowRight' });
    expect(onSelect).toHaveBeenCalledWith(SECOND);
    rerenderWithout();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('⚠️ ArrowLeft with focus on the line steps back, and focus is on the pill once the line is gone', () => {
    // The line shows on the first window in production, where `‹` is disabled; a fixture on the second
    // window gives ArrowLeft somewhere to go, which is the route under test.
    const { onSelect, rerenderWithout } = renderControl({ activeIndex: 1 });
    line().focus();
    fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' });
    expect(onSelect).toHaveBeenCalledWith(FIRST);
    rerenderWithout();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('an arrow key pressed on the › stepper does not move focus (only the line\'s own routes do)', () => {
    renderControl();
    const next = screen.getByTestId('wf-win-next');
    next.focus();
    fireEvent.keyDown(next, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(next);
  });

  it('ArrowLeft on the line with nothing to step back to leaves focus where it is (a no-op move)', () => {
    const { onSelect } = renderControl();
    line().focus();
    fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' });
    expect(onSelect).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(line());
  });

  it('⚠️ when the PARENT withdraws a focused line, focus goes to the pill and not <body>', () => {
    const { rerenderWithout } = renderControl();
    line().focus();
    rerenderWithout({ activeIndex: 0 });
    expect(line()).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('does not steal focus when the parent withdraws a line that was NOT focused', () => {
    const { rerenderWithout } = renderControl();
    const next = screen.getByTestId('wf-win-next');
    next.focus();
    rerenderWithout({ activeIndex: 0 });
    expect(document.activeElement).toBe(next);
  });

  it('ignores a press while an older day is loading', () => {
    const { onSelect } = renderControl({ olderDayLoadingLabel: 'Thu 13' });
    fireEvent.click(line());
    expect(onSelect).not.toHaveBeenCalled();
  });
});

describe('WindowControl — the line is shown only if it fits', () => {
  /** What the stubbed layout answers: which elements overflow. */
  let overflow;
  let observers;

  beforeEach(() => {
    overflow = { pill: false, cell: false };
    observers = [];
    const define = (prop, read) => {
      Object.defineProperty(HTMLElement.prototype, prop, { configurable: true, get: read });
    };
    const tooWide = (node) => (
      (node.getAttribute('data-testid') === 'wf-win-pill' && overflow.pill)
      || (node.classList.contains('wf-win-verdict') && overflow.cell)
    );
    define('clientWidth', () => 200);
    define('scrollWidth', function scrollWidth() { return tooWide(this) ? 260 : 200; });
    globalThis.ResizeObserver = class {
      constructor(callback) { this.callback = callback; observers.push(this); }

      observe() {}

      disconnect() {}
    };
  });
  afterEach(() => {
    for (const prop of ['clientWidth', 'scrollWidth']) {
      // jsdom defines these on Element.prototype; deleting ours unshadows them.
      delete HTMLElement.prototype[prop];
    }
    delete globalThis.ResizeObserver;
  });

  /** The control's width as the observer reports it. */
  const resize = (width) => act(() => {
    for (const observer of observers) observer.callback([{ contentRect: { width } }]);
  });

  it('shows the line when nothing overflows, and withholds it, with data-better, when the pill does', () => {
    const { unmount } = renderControl();
    expect(line()).toBeInTheDocument();
    unmount();

    overflow.pill = true;
    renderControl();
    expect(line()).toBeNull();
    expect(screen.getByTestId('wf-win-control')).not.toHaveAttribute('data-better');
  });

  it('withholds the line when only the verdict cell overflows (the word squeezed out of it)', () => {
    overflow.cell = true;
    renderControl();
    expect(line()).toBeNull();
    expect(screen.getByTestId('wf-win-control')).not.toHaveAttribute('data-better');
  });

  it('allows one pixel of rounding before it calls an overflow', () => {
    const stubbed = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollWidth');
    try {
      Object.defineProperty(HTMLElement.prototype, 'scrollWidth', { configurable: true, get: () => 201 });
      renderControl();
      expect(line()).toBeInTheDocument();
    } finally {
      Object.defineProperty(HTMLElement.prototype, 'scrollWidth', stubbed);
    }
  });

  it('⚠️ re-measures when the pill\'s CONTENT changes at the same window, text and width (a pick arrives)', () => {
    const { rerender } = renderControl();
    resize(620);
    expect(line()).toBeInTheDocument();

    overflow.pill = true;
    rerender(
      <WindowControl
        events={[{ ...FIRST, pickKind: 'also' }, SECOND, THIRD]}
        activeIndex={0}
        onSelect={vi.fn()}
        verdicts={VERDICTS}
        betterNext={BETTER}
      />,
    );
    expect(line()).toBeNull();
  });

  it('⚠️ re-tries a withheld line when the content signature changes and the pill now fits', () => {
    overflow.pill = true;
    const { rerender } = renderControl();
    resize(620);
    expect(line()).toBeNull();

    overflow.pill = false;
    // Same window, same sentence, same width: only what the pill says has changed (a shorter region).
    rerender(
      <WindowControl
        events={EVENTS}
        activeIndex={0}
        onSelect={vi.fn()}
        verdicts={new Map([...VERDICTS, [FIRST.id, { ...verdict('MAYBE'), regionName: 'Lakes' }]])}
        betterNext={BETTER}
      />,
    );
    expect(line()).toBeInTheDocument();
  });

  it('keeps a withheld line withheld while nothing the pill shows has changed', () => {
    overflow.pill = true;
    const { rerender } = renderControl();
    resize(620);
    overflow.pill = false;
    rerender(
      <WindowControl
        events={EVENTS}
        activeIndex={0}
        onSelect={vi.fn()}
        verdicts={VERDICTS}
        betterNext={BETTER}
      />,
    );
    expect(line()).toBeNull();
  });

  it('re-tries when the control\'s width changes, and stays withheld at the width that failed', () => {
    overflow.pill = true;
    renderControl();
    resize(500);
    expect(line()).toBeNull();

    // Same key again (same width): still withheld, with no flicker or loop.
    resize(500);
    expect(line()).toBeNull();

    // A new width is a new key, and the layout now has room.
    overflow.pill = false;
    resize(620);
    expect(line()).toBeInTheDocument();
    expect(screen.getByTestId('wf-win-control')).toHaveAttribute('data-better');

    // And back to a width that does not fit: withheld again.
    overflow.pill = true;
    resize(480);
    expect(line()).toBeNull();
  });

  it('re-tries when the window changes (a new key) and when the sentence changes', () => {
    overflow.pill = true;
    const { rerender } = renderControl();
    expect(line()).toBeNull();

    overflow.pill = false;
    rerender(
      <WindowControl
        events={EVENTS}
        activeIndex={0}
        onSelect={vi.fn()}
        verdicts={VERDICTS}
        betterNext={{ ...BETTER, text: 'Tomorrow sunrise is better!' }}
      />,
    );
    expect(line()).toBeInTheDocument();
  });

  it('⚠️ withholding a FOCUSED line hands focus to the pill first', () => {
    renderControl();
    resize(620);
    expect(line()).toBeInTheDocument();
    line().focus();
    expect(document.activeElement).toBe(line());

    overflow.pill = true;
    resize(480);

    expect(line()).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('withholding an UNFOCUSED line does not steal focus', () => {
    renderControl();
    resize(620);
    const next = screen.getByTestId('wf-win-next');
    next.focus();

    overflow.pill = true;
    resize(480);

    expect(line()).toBeNull();
    expect(document.activeElement).toBe(next);
  });
});
