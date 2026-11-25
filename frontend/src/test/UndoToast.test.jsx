import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, fireEvent, render, renderHook, screen, within } from '@testing-library/react';
import useUndoToast, { UNDO_TOAST_MS } from '../hooks/useUndoToast.js';
import UndoToast from '../components/shared/UndoToast.jsx';

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

/** The undo toast primitive (find-and-favourites-plan.md F4): its timing (WCAG 2.2.1) and its accessibility shape. */
describe('useUndoToast', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('never dismisses under ten seconds, and dismisses at ten', () => {
    expect(UNDO_TOAST_MS).toBeGreaterThanOrEqual(10000);
    const { result } = renderHook(() => useUndoToast({ duration: 2000 }));
    act(() => result.current.show({ message: 'Added.' }));
    act(() => { vi.advanceTimersByTime(UNDO_TOAST_MS - 1); });
    expect(result.current.toast?.message).toBe('Added.');
    act(() => { vi.advanceTimersByTime(1); });
    expect(result.current.toast).toBeNull();
  });

  it('holds while paused and restarts the full time on resume', () => {
    const { result } = renderHook(() => useUndoToast());
    act(() => result.current.show({ message: 'Added.' }));
    act(() => { vi.advanceTimersByTime(UNDO_TOAST_MS - 100); });
    act(() => result.current.pause());
    act(() => { vi.advanceTimersByTime(UNDO_TOAST_MS * 3); });
    expect(result.current.toast).not.toBeNull();
    act(() => result.current.resume());
    act(() => { vi.advanceTimersByTime(UNDO_TOAST_MS - 1); });
    expect(result.current.toast).not.toBeNull();
    act(() => { vi.advanceTimersByTime(1); });
    expect(result.current.toast).toBeNull();
  });

  it('remembers the control that had focus when it showed, and nothing when focus was on the page', () => {
    const button = document.createElement('button');
    document.body.appendChild(button);
    const { result } = renderHook(() => useUndoToast());
    act(() => result.current.show({ message: 'No focus.' }));
    expect(result.current.toast.returnTo).toBeNull();
    button.focus();
    act(() => result.current.show({ message: 'Focused.' }));
    expect(result.current.toast.returnTo).toBe(button);
    button.remove();
  });

  it('a newer toast replaces the one showing and restarts the clock', () => {
    const { result } = renderHook(() => useUndoToast());
    act(() => result.current.show({ message: 'One.' }));
    act(() => { vi.advanceTimersByTime(UNDO_TOAST_MS - 10); });
    act(() => result.current.show({ message: 'Two.' }));
    act(() => { vi.advanceTimersByTime(UNDO_TOAST_MS - 10); });
    expect(result.current.toast?.message).toBe('Two.');
    act(() => result.current.dismiss());
    expect(result.current.toast).toBeNull();
  });
});

describe('UndoToast', () => {
  const setup = (toast, handlers = {}) => {
    const props = {
      onDismiss: vi.fn(), onPause: vi.fn(), onResume: vi.fn(), ...handlers,
    };
    render(<UndoToast toast={toast} {...props} />);
    return props;
  };

  it('keeps an always-mounted status region, empty while idle, with no buttons', () => {
    setup(null);
    expect(screen.getByRole('status')).toHaveTextContent('');
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByTestId('undo-toast-idle')).toHaveClass('pointer-events-none');
  });

  it('puts the sentence in the status region and the Undo button OUTSIDE it', () => {
    setup({ id: 1, message: 'Bookmarked Steel Rigg.', onUndo: vi.fn() });
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('Bookmarked Steel Rigg.');
    expect(within(status).queryByRole('button')).toBeNull();
    expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeInTheDocument();
  });

  it('a failure message has no Undo button', () => {
    setup({ id: 2, message: 'You can keep up to 25 places.', onUndo: null });
    expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent('You can keep up to 25 places.');
  });

  it('Undo runs the action, dismisses, and hands focus back to what the reader had pressed', () => {
    const origin = document.createElement('button');
    document.body.appendChild(origin);
    const onUndo = vi.fn();
    const { onDismiss } = setup({ id: 3, message: 'Added.', onUndo, returnTo: origin });
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(onUndo).toHaveBeenCalledTimes(1);
    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(origin).toHaveFocus();
    origin.remove();
  });

  it('⚠️ a lazy returnTo is resolved AFTER the undo has been applied, so it can name the control where it moved to', () => {
    const before = document.createElement('button');
    const after = document.createElement('button');
    document.body.appendChild(before);
    // The undo moves the control: the old node goes, a new one arrives, and the lazy target finds the new one.
    const onUndo = vi.fn(() => { before.remove(); document.body.appendChild(after); });
    const returnTo = vi.fn(() => (before.isConnected ? before : after));
    setup({ id: 6, message: 'Watching.', onUndo, returnTo });
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(returnTo).toHaveBeenCalledTimes(1);
    expect(onUndo.mock.invocationCallOrder[0]).toBeLessThan(returnTo.mock.invocationCallOrder[0]);
    expect(after).toHaveFocus();
    after.remove();
  });

  it('a lazy returnTo that finds nothing leaves focus where it is, and never throws', () => {
    const { onDismiss } = setup({ id: 7, message: 'Watching.', onUndo: vi.fn(), returnTo: () => null });
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('Dismiss closes without undoing', () => {
    const onUndo = vi.fn();
    const { onDismiss } = setup({ id: 4, message: 'Added.', onUndo });
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(onUndo).not.toHaveBeenCalled();
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('pauses on pointer and focus, and resumes only when focus leaves the toast altogether', () => {
    const { onPause, onResume } = setup({ id: 5, message: 'Added.', onUndo: vi.fn() });
    const toast = screen.getByTestId('undo-toast');
    fireEvent.mouseEnter(toast);
    fireEvent.mouseLeave(toast);
    expect(onPause).toHaveBeenCalledTimes(1);
    expect(onResume).toHaveBeenCalledTimes(1);
    onPause.mockClear();
    onResume.mockClear();
    const undo = screen.getByRole('button', { name: 'Undo' });
    fireEvent.focus(undo);
    expect(onPause).toHaveBeenCalled();
    fireEvent.blur(undo, { relatedTarget: screen.getByRole('button', { name: 'Dismiss' }) });
    expect(onResume).not.toHaveBeenCalled();
    fireEvent.blur(undo, { relatedTarget: document.body });
    expect(onResume).toHaveBeenCalledTimes(1);
  });

  it('⚠️ resumes only when BOTH the pointer and focus are out: leaving with the mouse while focus is inside holds', () => {
    const { onPause, onResume } = setup({ id: 7, message: 'Added.', onUndo: vi.fn() });
    const toast = screen.getByTestId('undo-toast');
    const undo = screen.getByRole('button', { name: 'Undo' });
    fireEvent.mouseEnter(toast);
    fireEvent.focus(undo);
    onResume.mockClear();
    fireEvent.mouseLeave(toast);
    expect(onResume).not.toHaveBeenCalled();
    // Focus leaving while the pointer is gone resumes.
    fireEvent.blur(undo, { relatedTarget: document.body });
    expect(onResume).toHaveBeenCalledTimes(1);
    // And the other order: focus out first while hovered holds, the pointer leaving resumes.
    onResume.mockClear();
    onPause.mockClear();
    fireEvent.mouseEnter(toast);
    fireEvent.focus(undo);
    fireEvent.blur(undo, { relatedTarget: document.body });
    expect(onResume).not.toHaveBeenCalled();
    fireEvent.mouseLeave(toast);
    expect(onResume).toHaveBeenCalledTimes(1);
  });

  it('sits above a phone safe area on a phone, and takes no Escape', () => {
    mockIsMobile = true;
    setup({ id: 6, message: 'Added.', onUndo: vi.fn() });
    expect(screen.getByTestId('undo-toast').className).toContain('bottom-[max(1.5rem,var(--safe-b))]');
    const onKey = vi.fn();
    document.addEventListener('keydown', onKey);
    fireEvent.keyDown(screen.getByTestId('undo-toast'), { key: 'Escape' });
    expect(onKey).toHaveBeenCalled();
    expect(screen.getByTestId('undo-toast')).toBeInTheDocument();
    document.removeEventListener('keydown', onKey);
    mockIsMobile = false;
  });
});
