import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useHashSelection, { selectionFromHash } from '../hooks/useHashSelection.js';

// The shared hash hook of the Manage list-and-panel tabs. The numeric default is what the Data tabs use and is pinned
// here; the {parse, format} option is the Runs tab's (its hash is job/{id} or start, not one number).

/** Moves the hash the way a link or Back does, then lets the browser's event fire. */
function goTo(hash) {
  act(() => {
    window.location.hash = hash;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  });
}

// Module-level, as the Runs tab's own are, so the hook's listener stays stable.
const parse = (segments) => {
  if (segments.length === 1 && segments[0] === 'start') return { type: 'start' };
  if (segments.length === 2 && segments[0] === 'job' && /^\d+$/.test(segments[1])) {
    return { type: 'job', id: Number(segments[1]) };
  }
  return null;
};
const format = (selection) => (selection.type === 'start' ? 'start' : `job/${selection.id}`);
const OPTIONS = { parse, format, keepQuery: true };

beforeEach(() => {
  window.location.hash = '';
});

describe('useHashSelection: the numeric default', () => {
  it('reads a numeric id after the tab', () => {
    window.location.hash = '#manage/waitlist/12';
    expect(selectionFromHash('waitlist')).toBe(12);
  });

  it('reads nothing from the bare tab, a non-numeric id or another tab', () => {
    window.location.hash = '#manage/waitlist';
    expect(selectionFromHash('waitlist')).toBeNull();
    window.location.hash = '#manage/waitlist/abc';
    expect(selectionFromHash('waitlist')).toBeNull();
    window.location.hash = '#manage/users/12';
    expect(selectionFromHash('waitlist')).toBeNull();
  });

  it('ignores a query on the hash when reading the id', () => {
    window.location.hash = '#manage/locations/12?region=3';
    expect(selectionFromHash('locations')).toBe(12);
  });

  it('writes #manage/{tab}/{id} on select and #manage/{tab} on clear', () => {
    const { result } = renderHook(() => useHashSelection('waitlist'));
    act(() => result.current.select(12));
    expect(window.location.hash).toBe('#manage/waitlist/12');
    expect(result.current.selectedId).toBe(12);
    act(() => result.current.clear());
    expect(window.location.hash).toBe('#manage/waitlist');
    expect(result.current.selectedId).toBeNull();
  });

  it('follows the hash when it moves', () => {
    const { result } = renderHook(() => useHashSelection('waitlist'));
    goTo('#manage/waitlist/7');
    expect(result.current.selectedId).toBe(7);
  });

  it('ignores a hash outside manage/, leaving the selection as it was', () => {
    window.location.hash = '#manage/waitlist/7';
    const { result } = renderHook(() => useHashSelection('waitlist'));
    goTo('#something-else');
    expect(result.current.selectedId).toBe(7);
  });
});

describe('useHashSelection: an inbound query', () => {
  it('drops the query by default when a row is selected (a filter the reader may have changed is not re-applied)', () => {
    window.location.hash = '#manage/locations?region=3';
    const { result } = renderHook(() => useHashSelection('locations'));
    act(() => result.current.select(12));
    expect(window.location.hash).toBe('#manage/locations/12');
  });

  it('drops the query by default when the selection is cleared', () => {
    window.location.hash = '#manage/locations/12?region=3';
    const { result } = renderHook(() => useHashSelection('locations'));
    act(() => result.current.clear());
    expect(window.location.hash).toBe('#manage/locations');
  });

  it('drops it by default for a vanished selection cleared with replace', () => {
    window.location.hash = '#manage/locations/99?region=3';
    const { result } = renderHook(() => useHashSelection('locations'));
    const replace = vi.spyOn(window.history, 'replaceState');
    act(() => result.current.clear({ replace: true }));
    expect(replace).toHaveBeenCalledWith(window.history.state, '', '#manage/locations');
    replace.mockRestore();
  });

  it('keeps the query on select, clear and replace when asked (keepQuery)', () => {
    window.location.hash = '#manage/locations?region=3';
    const { result } = renderHook(() => useHashSelection('locations', { keepQuery: true }));
    act(() => result.current.select(12));
    expect(window.location.hash).toBe('#manage/locations/12?region=3');
    act(() => result.current.clear());
    expect(window.location.hash).toBe('#manage/locations?region=3');
    const replace = vi.spyOn(window.history, 'replaceState');
    act(() => result.current.clear({ replace: true }));
    expect(replace).toHaveBeenCalledWith(window.history.state, '', '#manage/locations?region=3');
    replace.mockRestore();
  });

  it('adds nothing when there is no query, with keepQuery too', () => {
    const { result } = renderHook(() => useHashSelection('locations', { keepQuery: true }));
    act(() => result.current.select(5));
    expect(window.location.hash).toBe('#manage/locations/5');
  });

  it('does not count a click event passed to clear as a request to replace', () => {
    window.location.hash = '#manage/locations/5';
    const { result } = renderHook(() => useHashSelection('locations'));
    const replace = vi.spyOn(window.history, 'replaceState');
    act(() => result.current.clear({ type: 'click' }));
    expect(replace).not.toHaveBeenCalled();
    expect(window.location.hash).toBe('#manage/locations');
    replace.mockRestore();
  });
});

describe('useHashSelection: a custom {parse, format}', () => {
  it('hands the parser every segment after the tab', () => {
    window.location.hash = '#manage/runs/job/9876';
    expect(selectionFromHash('runs', parse)).toEqual({ type: 'job', id: 9876 });
    window.location.hash = '#manage/runs/start';
    expect(selectionFromHash('runs', parse)).toEqual({ type: 'start' });
  });

  it('reads nothing for the bare tab, for a path the parser does not know, or for another tab', () => {
    window.location.hash = '#manage/runs';
    expect(selectionFromHash('runs', parse)).toBeNull();
    window.location.hash = '#manage/runs/job/abc';
    expect(selectionFromHash('runs', parse)).toBeNull();
    window.location.hash = '#manage/runs/job/1/extra';
    expect(selectionFromHash('runs', parse)).toBeNull();
    window.location.hash = '#manage/scheduler/job/1';
    expect(selectionFromHash('runs', parse)).toBeNull();
  });

  it('reads the initial selection from the hash at mount', () => {
    window.location.hash = '#manage/runs/job/9876';
    const { result } = renderHook(() => useHashSelection('runs', OPTIONS));
    expect(result.current.selectedId).toEqual({ type: 'job', id: 9876 });
  });

  it('writes the formatted path on select, and the bare tab on clear', () => {
    const { result } = renderHook(() => useHashSelection('runs', OPTIONS));
    act(() => result.current.select({ type: 'job', id: 9876 }));
    expect(window.location.hash).toBe('#manage/runs/job/9876');
    act(() => result.current.select({ type: 'start' }));
    expect(window.location.hash).toBe('#manage/runs/start');
    act(() => result.current.clear());
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('keeps an inbound ?query when it selects and clears (the Runs tab opts in)', () => {
    window.location.hash = '#manage/runs?range=today&kind=aurora';
    const { result } = renderHook(() => useHashSelection('runs', OPTIONS));
    act(() => result.current.select({ type: 'job', id: 5 }));
    expect(window.location.hash).toBe('#manage/runs/job/5?range=today&kind=aurora');
    act(() => result.current.clear());
    expect(window.location.hash).toBe('#manage/runs?range=today&kind=aurora');
  });

  it('follows the hash, and does not hand back a new object for the same selection', () => {
    window.location.hash = '#manage/runs/job/5';
    const { result } = renderHook(() => useHashSelection('runs', OPTIONS));
    const first = result.current.selectedId;
    goTo('#manage/runs/job/5');
    expect(result.current.selectedId).toBe(first);
    goTo('#manage/runs/start');
    expect(result.current.selectedId).toEqual({ type: 'start' });
    goTo('#manage/runs');
    expect(result.current.selectedId).toBeNull();
  });
});
