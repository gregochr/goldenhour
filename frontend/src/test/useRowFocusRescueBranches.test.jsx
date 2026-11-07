import React, { useRef } from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useRowFocusRescue } from '../hooks/useRowFocusRescue.js';

/**
 * A stand-in for the window menu: a wrapper carrying the hook's handlers, rows keyed by
 * {@code data-ev-id}, and a control that stays mounted (the fallback).
 */
function Menu({ rowIds, active = true, fallback = 'attached', wrapFallbackHidden = false }) {
  const fallbackRef = useRef(null);
  const detached = useRef(null);
  if (detached.current === null) detached.current = document.createElement('button');
  const empty = useRef(null);
  let refForHook = fallbackRef;
  if (fallback === 'none') refForHook = empty;
  if (fallback === 'detached') refForHook = detached;
  const rescue = useRowFocusRescue({ active, rowIds, fallbackRef: refForHook });
  const fallbackButton = (
    <button type="button" data-testid="fallback" ref={fallbackRef}>menu</button>
  );
  return (
    <div data-testid="wrapper" onFocus={rescue.onFocus} onBlur={rescue.onBlur} onPointerDown={rescue.onPointerDown}>
      {wrapFallbackHidden ? <div hidden>{fallbackButton}</div> : fallbackButton}
      {rowIds.map((id) => (
        <button type="button" key={id} data-ev-id={id} data-testid={`row-${id}`}>{id}</button>
      ))}
    </div>
  );
}

function focusRow(id) {
  screen.getByTestId(`row-${id}`).focus();
}

describe('useRowFocusRescue — guards', () => {
  afterEach(() => {
    document.querySelectorAll('[data-test-foreign]').forEach((n) => n.remove());
  });

  it('sends focus to the fallback when the focused row vanishes (the baseline the guards below hold back)', () => {
    const { rerender } = render(<Menu rowIds={['a', 'b']} />);
    focusRow('b');

    rerender(<Menu rowIds={['a']} />);

    expect(screen.getByTestId('fallback')).toHaveFocus();
  });

  it('does not move focus when the reader has since focused another element', () => {
    const outside = document.createElement('button');
    outside.setAttribute('data-test-foreign', '1');
    document.body.appendChild(outside);
    const { rerender } = render(<Menu rowIds={['a', 'b']} />);
    // The row is recorded (a focus event reached the wrapper) but real focus now sits elsewhere.
    fireEvent.focus(screen.getByTestId('row-b'));
    outside.focus();

    rerender(<Menu rowIds={['a']} />);

    expect(outside).toHaveFocus();
  });

  it('forgets the row when focus then lands on something in the list that is not a row', () => {
    const { rerender } = render(<Menu rowIds={['a', 'b']} />);
    focusRow('b');
    fireEvent.focus(screen.getByTestId('fallback'));
    screen.getByTestId('fallback').blur();

    rerender(<Menu rowIds={['a']} />);

    expect(screen.getByTestId('fallback')).not.toHaveFocus();
    expect(document.body).toHaveFocus();
  });

  it('does nothing when there is no fallback control to receive focus', () => {
    const { rerender } = render(<Menu rowIds={['a', 'b']} fallback="none" />);
    focusRow('b');

    rerender(<Menu rowIds={['a']} fallback="none" />);

    expect(document.body).toHaveFocus();
  });

  it('does nothing when the fallback is not attached to the document', () => {
    const { rerender } = render(<Menu rowIds={['a', 'b']} fallback="detached" />);
    focusRow('b');

    rerender(<Menu rowIds={['a']} fallback="detached" />);

    expect(document.body).toHaveFocus();
  });

  it('does nothing when the fallback sits inside a hidden pane', () => {
    const { rerender } = render(<Menu rowIds={['a', 'b']} wrapFallbackHidden />);
    focusRow('b');

    rerender(<Menu rowIds={['a']} wrapFallbackHidden />);

    expect(screen.getByTestId('fallback')).not.toHaveFocus();
    expect(document.body).toHaveFocus();
  });

  it('does nothing while a dialog from outside the pane is open over it', () => {
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('aria-modal', 'true');
    foreign.setAttribute('data-test-foreign', '1');
    document.body.appendChild(foreign);
    const { rerender } = render(<Menu rowIds={['a', 'b']} />);
    focusRow('b');

    rerender(<Menu rowIds={['a']} />);

    expect(screen.getByTestId('fallback')).not.toHaveFocus();
  });

  it('ignores a vanished row once the list has been closed', () => {
    const { rerender } = render(<Menu rowIds={['a', 'b']} />);
    focusRow('b');

    rerender(<Menu rowIds={['a']} active={false} />);

    expect(screen.getByTestId('fallback')).not.toHaveFocus();
  });
});
