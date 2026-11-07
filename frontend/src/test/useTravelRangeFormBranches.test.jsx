import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useTravelRangeForm from '../hooks/useTravelRangeForm.js';

const VALID = { startDate: '2026-11-10', endDate: '2026-11-12', note: ' trip ' };

describe('useTravelRangeForm — field messages and double presses', () => {
  it('clears only the message under the field that was changed, and the form-level one', async () => {
    const save = vi.fn();
    const { result } = renderHook(() => useTravelRangeForm(
      { startDate: '', endDate: '', note: '' }, save, 'Could not save',
    ));

    await act(async () => { await result.current.submit(); });
    expect(result.current.errors.startDate).toBe('Both a start and end date are required');
    expect(result.current.errors.endDate).toBe('Both a start and end date are required');

    act(() => result.current.set('startDate', '2026-11-10'));

    expect(result.current.errors.startDate).toBeUndefined();
    expect(result.current.errors.endDate).toBe('Both a start and end date are required');
    expect(result.current.form.startDate).toBe('2026-11-10');
    expect(save).not.toHaveBeenCalled();
  });

  it('a second submit while the save is in flight sends nothing and reports false', async () => {
    let finish;
    const save = vi.fn(() => new Promise((resolve) => { finish = resolve; }));
    const { result } = renderHook(() => useTravelRangeForm(VALID, save, 'Could not save'));

    let first;
    act(() => { first = result.current.submit(); });
    let second;
    await act(async () => { second = await result.current.submit(); });

    expect(second).toBe(false);
    expect(save).toHaveBeenCalledTimes(1);
    expect(save).toHaveBeenCalledWith({ startDate: '2026-11-10', endDate: '2026-11-12', note: 'trip' });
    expect(result.current.saving).toBe(true);
    await act(async () => { finish(); await first; });
    expect(result.current.saving).toBe(false);
  });

  it('places a server refusal under the field it names, and a nameless one at form level', async () => {
    const save = vi.fn()
      .mockRejectedValueOnce({ response: { status: 400, data: { error: 'endDate must not be before startDate' } } })
      .mockRejectedValueOnce(new Error('network'));
    const { result } = renderHook(() => useTravelRangeForm(VALID, save, 'Could not save'));

    await act(async () => { await result.current.submit(); });
    expect(result.current.errors).toEqual({ endDate: 'endDate must not be before startDate' });
    expect(result.current.formError).toBe('');

    await act(async () => { await result.current.submit(); });
    expect(result.current.errors).toEqual({});
    expect(result.current.formError).toBe('Could not save');
  });

  it('tells the view when a save starts and ends, and false again on unmount', async () => {
    let finish;
    const save = vi.fn(() => new Promise((resolve) => { finish = resolve; }));
    const onSavingChange = vi.fn();
    const { result, unmount } = renderHook(() => useTravelRangeForm(VALID, save, 'x', onSavingChange));

    let pending;
    act(() => { pending = result.current.submit(); });
    expect(onSavingChange).toHaveBeenLastCalledWith(true);
    await act(async () => { finish(); await pending; });
    expect(onSavingChange).toHaveBeenLastCalledWith(false);

    onSavingChange.mockClear();
    unmount();
    expect(onSavingChange).toHaveBeenCalledWith(false);
  });
});
