import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import BottomSheet from '../components/BottomSheet.jsx';

/**
 * `BottomSheet`'s two Ask opt-ins (`size="tall"`, `closeOnEscape`) and the one slot that rides with
 * them (`footer`), plus the proof that every existing caller is untouched
 * (`docs/engineering/ask-photocast-plan.md` §1 #13, F1b).
 *
 * <p>`BottomSheet.test.jsx` carries the pre-existing behaviour and is NOT edited by this phase — that
 * unmodified file passing is itself half the evidence. This file adds the other half: the defaults,
 * asserted by value, so a change that moves one of them fails here by name.
 */

const sheetOf = () => screen.getByTestId('bottom-sheet');
/** The scroller: the sheet's only `overflow-y-auto` child. */
const scrollerOf = () => screen.getByTestId('bottom-sheet-scroller');

afterEach(() => {
  delete window.visualViewport;
  window.innerHeight = 768;
});

describe('BottomSheet — the defaults every existing caller has', () => {
  it('caps the sheet at 60vh', () => {
    render(<BottomSheet open onClose={vi.fn()}><p>Body</p></BottomSheet>);
    expect(sheetOf().style.maxHeight).toBe('60vh');
    expect(sheetOf().style.height).toBe('');
  });

  it('subtracts the scroller\'s chrome from 60vh, with and without the close strip', () => {
    const { rerender } = render(<BottomSheet open onClose={vi.fn()}><p>Body</p></BottomSheet>);
    expect(scrollerOf().style.maxHeight).toBe('calc(60vh - 40px - var(--safe-b))');
    rerender(<BottomSheet open onClose={vi.fn()} reserveCloseStrip><p>Body</p></BottomSheet>);
    expect(scrollerOf().style.maxHeight).toBe('calc(60vh - 64px - var(--safe-b))');
  });

  it('carries no size marker, which is what keeps it out of the tall sheet\'s flex-column rule', () => {
    render(<BottomSheet open onClose={vi.fn()}><p>Body</p></BottomSheet>);
    expect(sheetOf()).not.toHaveAttribute('data-size');
  });

  it('keeps the exact class strings it has always had, focus:outline-none included', () => {
    render(<BottomSheet open onClose={vi.fn()}><p>Body</p></BottomSheet>);
    expect(sheetOf().className).toBe(
      'app-safe-sheet fixed bottom-0 rounded-t-2xl bg-plex-surface border-t border-plex-border animate-slide-up focus:outline-none',
    );
    expect(scrollerOf().className).toBe('overflow-y-auto px-4 pb-6');
  });

  it('does not move with the visual viewport: it sits at the bottom whatever the keyboard does', () => {
    window.visualViewport = Object.assign(new EventTarget(), { height: 400, offsetTop: 0 });
    render(<BottomSheet open onClose={vi.fn()}><p>Body</p></BottomSheet>);
    expect(sheetOf().style.bottom).toBe('');
    expect(sheetOf().style.maxHeight).toBe('60vh');
  });

  it('does not close on Escape', () => {
    const onClose = vi.fn();
    render(<BottomSheet open onClose={onClose}><p>Body</p></BottomSheet>);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('overrides no padding: the sheet keeps the stylesheet\'s own safe-area padding', () => {
    render(<BottomSheet open onClose={vi.fn()}><p>Body</p></BottomSheet>);
    expect(sheetOf().style.paddingBottom).toBe('');
  });

  it('renders no footer', () => {
    render(<BottomSheet open onClose={vi.fn()}><p>Body</p></BottomSheet>);
    expect(sheetOf().children).toHaveLength(3);
  });
});

describe('BottomSheet — size="tall"', () => {
  it('is the layout viewport minus 24px, as a height and not only a maximum', () => {
    window.innerHeight = 844;
    render(<BottomSheet open onClose={vi.fn()} size="tall"><p>Body</p></BottomSheet>);
    expect(sheetOf().style.height).toBe('820px');
    expect(sheetOf().style.maxHeight).toBe('820px');
    expect(sheetOf()).toHaveAttribute('data-size', 'tall');
  });

  it('changes BOTH 60vh sites: the scroller\'s budget follows the tall height, not 60vh', () => {
    // The first site is the sheet's own maxHeight, the second the calc the scroller subtracts its
    // chrome from. Changing only the first would leave a 60vh scroller inside a tall sheet.
    window.innerHeight = 844;
    render(<BottomSheet open onClose={vi.fn()} size="tall" reserveCloseStrip><p>Body</p></BottomSheet>);
    expect(sheetOf().style.maxHeight).not.toContain('60vh');
    expect(scrollerOf().style.maxHeight).toBe('calc(820px - 64px - var(--safe-b))');
  });

  it('follows the visual viewport: the keyboard shortens the sheet and lifts it clear', () => {
    window.innerHeight = 844;
    const viewport = Object.assign(new EventTarget(), { height: 844, offsetTop: 0 });
    window.visualViewport = viewport;
    render(<BottomSheet open onClose={vi.fn()} size="tall"><p>Body</p></BottomSheet>);
    expect(sheetOf().style.height).toBe('820px');
    expect(sheetOf().style.bottom).toBe('0px');

    act(() => {
      viewport.height = 508;
      viewport.dispatchEvent(new Event('resize'));
    });

    expect(sheetOf().style.height).toBe('484px');
    expect(sheetOf().style.bottom).toBe('336px');
  });

  it('never goes negative on a viewport shorter than its margin', () => {
    window.innerHeight = 20;
    render(<BottomSheet open onClose={vi.fn()} size="tall"><p>Body</p></BottomSheet>);
    expect(sheetOf().style.height).toBe('0px');
  });

  it('gives the sheet\'s home-indicator padding back as the keyboard lifts it, continuously', () => {
    // Padding kept on top of a lifted sheet would float the input row that far above the keyboard;
    // a switch at "inset > 0" would strip it all on the 1-2px an iOS toolbar animation can report.
    window.innerHeight = 844;
    const viewport = Object.assign(new EventTarget(), { height: 844, offsetTop: 0 });
    window.visualViewport = viewport;
    render(<BottomSheet open onClose={vi.fn()} size="tall"><p>Body</p></BottomSheet>);
    expect(sheetOf().style.paddingBottom).toBe('max(0px, calc(var(--safe-b) - 0px))');

    act(() => {
      viewport.height = 508;
      viewport.dispatchEvent(new Event('resize'));
    });
    expect(sheetOf().style.paddingBottom).toBe('max(0px, calc(var(--safe-b) - 336px))');
  });

  it('holds the close strip at 24px: it must not shrink in the tall sheet\'s flex column', () => {
    // An empty flex item has no minimum size; a long answer squeezed the strip and slid the scroller
    // up under the close button (measured by arithmetic: ~12px at 1500px of content in 820px).
    render(<BottomSheet open onClose={vi.fn()} size="tall" reserveCloseStrip><p>Body</p></BottomSheet>);
    expect(screen.getByTestId('bottom-sheet-strip')).toHaveClass('h-6', 'shrink-0');
  });

  it('draws no strip unless asked', () => {
    render(<BottomSheet open onClose={vi.fn()} size="tall"><p>Body</p></BottomSheet>);
    expect(screen.queryByTestId('bottom-sheet-strip')).toBeNull();
  });

  it('renders the footer below the scroller and outside it', () => {
    render(
      <BottomSheet open onClose={vi.fn()} size="tall" footer={<form data-testid="foot" />}>
        <p>Body</p>
      </BottomSheet>,
    );
    expect(scrollerOf()).not.toContainElement(screen.getByTestId('foot'));
    expect(screen.getByTestId('foot').previousElementSibling).toBe(scrollerOf());
  });
});

describe('BottomSheet — closeOnEscape', () => {
  it('closes on Escape when opted in', () => {
    const onClose = vi.fn();
    render(<BottomSheet open onClose={onClose} closeOnEscape><p>Body</p></BottomSheet>);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('ignores every other key', () => {
    const onClose = vi.fn();
    render(<BottomSheet open onClose={onClose} closeOnEscape><p>Body</p></BottomSheet>);
    fireEvent.keyDown(document, { key: 'Enter' });
    fireEvent.keyDown(document, { key: 'a' });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('leaves an Escape that cancels an IME composition alone', () => {
    const onClose = vi.fn();
    render(<BottomSheet open onClose={onClose} closeOnEscape><p>Body</p></BottomSheet>);
    fireEvent.keyDown(document, { key: 'Escape', isComposing: true });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('answers only while it is the TOPMOST dialog: the one under another does nothing', () => {
    const under = vi.fn();
    const over = vi.fn();
    render(
      <>
        <BottomSheet open onClose={under} closeOnEscape label="Under"><p>Under</p></BottomSheet>
        <BottomSheet open onClose={over} closeOnEscape label="Over"><p>Over</p></BottomSheet>
      </>,
    );
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(over).toHaveBeenCalledTimes(1);
    expect(under).not.toHaveBeenCalled();
  });

  it('is topmost again once the sheet over it has gone', () => {
    const under = vi.fn();
    const { rerender } = render(
      <>
        <BottomSheet open onClose={under} closeOnEscape label="Under"><p>Under</p></BottomSheet>
        <BottomSheet open onClose={vi.fn()} label="Over"><p>Over</p></BottomSheet>
      </>,
    );
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(under).not.toHaveBeenCalled();

    rerender(
      <>
        <BottomSheet open onClose={under} closeOnEscape label="Under"><p>Under</p></BottomSheet>
        <BottomSheet open={false} onClose={vi.fn()} label="Over"><p>Over</p></BottomSheet>
      </>,
    );
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(under).toHaveBeenCalledTimes(1);
  });

  it('stops listening when the sheet closes', () => {
    const onClose = vi.fn();
    const { rerender } = render(<BottomSheet open onClose={onClose} closeOnEscape><p>Body</p></BottomSheet>);
    rerender(<BottomSheet open={false} onClose={onClose} closeOnEscape><p>Body</p></BottomSheet>);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('stops listening when it unmounts', () => {
    const onClose = vi.fn();
    const { unmount } = render(<BottomSheet open onClose={onClose} closeOnEscape><p>Body</p></BottomSheet>);
    unmount();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
  });
});
