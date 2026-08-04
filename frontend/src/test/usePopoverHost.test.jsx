import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import usePopoverHost from '../hooks/usePopoverHost.js';
import PopoverHost from '../components/PopoverHost.jsx';
import computePopoverPlacement from '../utils/popoverPlacement.js';

/** A viewport-sized anchor stub, since jsdom gives every element a zero rect. */
function anchorAt({ left, top, right }) {
  return { getBoundingClientRect: () => ({ left, top, right, bottom: top, width: right - left, height: 0 }) };
}

/** Two triggers sharing one host — the shape every window-first popover uses. */
function Harness({ width }) {
  const { popover, show, hide, hideAll } = usePopoverHost();
  return (
    <div>
      <button
        type="button"
        data-testid="chip-a"
        onFocus={(e) => show('a', e.currentTarget, 'detail for A', { width })}
        onBlur={() => hide('a')}
      >
        A
      </button>
      <button
        type="button"
        data-testid="chip-b"
        onFocus={(e) => show('b', e.currentTarget, 'detail for B', { width })}
        onBlur={() => hide('b')}
      >
        B
      </button>
      <button type="button" data-testid="close-all" onClick={hideAll}>close</button>
      <PopoverHost popover={popover} className="tip" />
    </div>
  );
}

describe('usePopoverHost', () => {
  it('renders nothing until a trigger opens it', () => {
    render(<Harness />);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('renders the open popover into document.body, not inside the trigger', () => {
    // The lifetime guarantee. A panel rendered inside the chip that opened it dies when that chip
    // re-renders — and on the Plan screen a chip re-renders whenever the briefing refreshes. It is
    // also why the panel is portalled rather than merely position:fixed: a transform, filter or
    // will-change on ANY ancestor silently re-bases fixed positioning onto that ancestor.
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));

    const tip = screen.getByRole('tooltip');
    expect(tip).toHaveTextContent('detail for A');
    expect(tip.parentElement).toBe(document.body);
    expect(screen.getByTestId('chip-a')).not.toContainElement(tip);
  });

  it('shows exactly one panel however many triggers have been used', () => {
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));
    fireEvent.focus(screen.getByTestId('chip-b'));

    expect(screen.getAllByRole('tooltip')).toHaveLength(1);
    expect(screen.getByRole('tooltip')).toHaveTextContent('detail for B');
  });

  it('a departing trigger cannot close the panel the next one just opened', () => {
    // A pointer sweeping a row of chips fires the outgoing chip's leave AFTER the incoming chip's
    // enter. Unkeyed, the chip the reader has just left would cancel the panel for the chip they
    // are now on — so the popover flickers off at exactly the moment it is wanted.
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));
    fireEvent.focus(screen.getByTestId('chip-b'));

    fireEvent.blur(screen.getByTestId('chip-a'));

    expect(screen.getByRole('tooltip')).toHaveTextContent('detail for B');
  });

  it('its own trigger does close it', () => {
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));

    fireEvent.blur(screen.getByTestId('chip-a'));

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('dismisses on scroll, because the placement it was given has gone stale', () => {
    // Placement is computed once from the anchor's viewport rect and nothing recomputes it, so the
    // instant anything scrolls the panel describes a position the anchor has left — hanging in
    // empty space pointing at nothing. The shipped region-chip gloss has this defect today.
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));

    act(() => { window.dispatchEvent(new Event('scroll')); });

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('dismisses on a scroll in an inner scroller, which does not bubble', () => {
    // Registered in the CAPTURE phase for exactly this: scroll does not bubble, and the scroller
    // on this screen is an inner element (the spot strip), not the document.
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));

    act(() => {
      screen.getByTestId('chip-b').dispatchEvent(new Event('scroll', { bubbles: false }));
    });

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('dismisses on resize, which invalidates the same measurement', () => {
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));

    act(() => { window.dispatchEvent(new Event('resize')); });

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('Escape closes it, which the shipped gloss offers no way to do', () => {
    // A keyboard user opens one by focusing a chip. Without this their only way out is to move
    // focus somewhere else entirely.
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('leaves other keys alone', () => {
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));

    fireEvent.keyDown(document, { key: 'a' });

    expect(screen.getByRole('tooltip')).toBeInTheDocument();
  });

  it('unbinds its document listeners once closed', () => {
    // The listeners exist only while a panel is open. Left bound, every scroll anywhere in the app
    // would run a state setter for a popover that is not there.
    render(<Harness />);
    fireEvent.focus(screen.getByTestId('chip-a'));
    fireEvent.click(screen.getByTestId('close-all'));

    // No panel, and dispatching the events it listens for must not resurrect or throw.
    act(() => { window.dispatchEvent(new Event('scroll')); });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });
});

describe('computePopoverPlacement', () => {
  it('anchors above the trigger, growing upward from a fixed baseline', () => {
    // Bottom-anchored so a two-line body and a six-line body sit the same distance off the chip,
    // instead of the taller one drifting down over it.
    const { style } = computePopoverPlacement(anchorAt({ left: 100, top: 300, right: 160 }).getBoundingClientRect());

    expect(style.position).toBe('fixed');
    expect(style.bottom).toBe(`${window.innerHeight - 300 + 8}px`);
    expect(style.left).toBe('100px');
  });

  it('flips to a right anchor when a left-anchored panel would overrun the viewport', () => {
    const nearRight = window.innerWidth - 40;
    const { style, alignRight } = computePopoverPlacement(
      anchorAt({ left: nearRight, top: 200, right: nearRight + 30 }).getBoundingClientRect(),
      { width: 260 },
    );

    expect(alignRight).toBe(true);
    expect(style.right).toBeDefined();
    expect(style.left).toBeUndefined();
  });

  it('never flips when no width is given, because there is nothing to overrun-test', () => {
    const { alignRight } = computePopoverPlacement(
      anchorAt({ left: window.innerWidth - 10, top: 200, right: window.innerWidth }).getBoundingClientRect(),
    );

    expect(alignRight).toBe(false);
  });

  it('holds the panel off both viewport edges', () => {
    const offLeft = computePopoverPlacement(anchorAt({ left: -50, top: 200, right: 10 }).getBoundingClientRect());
    expect(offLeft.style.left).toBe('10px');

    const offRight = computePopoverPlacement(
      anchorAt({ left: window.innerWidth, top: 200, right: window.innerWidth + 60 }).getBoundingClientRect(),
      { width: 260 },
    );
    expect(offRight.style.right).toBe('10px');
  });
});
