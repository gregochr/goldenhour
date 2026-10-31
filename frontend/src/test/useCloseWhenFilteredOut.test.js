import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import useCloseWhenFilteredOut from '../hooks/useCloseWhenFilteredOut.js';

const getId = (item) => item.id;
const A = { id: 1 };
const B = { id: 2 };

describe('useCloseWhenFilteredOut', () => {
  it('closes when the filtered set no longer holds the selection', () => {
    const onClose = vi.fn();
    const { rerender } = renderHook((props) => useCloseWhenFilteredOut(props), {
      initialProps: { selectedId: 1, items: [A, B], getId, onClose },
    });
    expect(onClose).not.toHaveBeenCalled();

    rerender({ selectedId: 1, items: [B], getId, onClose });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('stays open while the selection is still in the set', () => {
    const onClose = vi.fn();
    const { rerender } = renderHook((props) => useCloseWhenFilteredOut(props), {
      initialProps: { selectedId: 1, items: [A, B], getId, onClose },
    });

    rerender({ selectedId: 1, items: [A], getId, onClose });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('does nothing with no selection, and does not react to the selection itself changing', () => {
    const onClose = vi.fn();
    const items = [A];
    const { rerender } = renderHook((props) => useCloseWhenFilteredOut(props), {
      initialProps: { selectedId: null, items, getId, onClose },
    });
    rerender({ selectedId: null, items: [], getId, onClose });
    expect(onClose).not.toHaveBeenCalled();

    // Opening an item the filters hide (a freshly created one) is the admin's act: same set, new selection.
    rerender({ selectedId: null, items, getId, onClose });
    rerender({ selectedId: 9, items, getId, onClose });
    expect(onClose).not.toHaveBeenCalled();
  });
});
