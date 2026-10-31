import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import useCloseWhenFilteredOut from '../hooks/useCloseWhenFilteredOut.js';

const getId = (item) => item.id;
const A = { id: 1 };
const B = { id: 2 };

function setup(initial) {
  const onClose = vi.fn();
  const view = renderHook((props) => useCloseWhenFilteredOut(props), {
    initialProps: { selectedId: 1, items: [A, B], getId, filterKey: 'all', onClose, ...initial },
  });
  return { onClose, ...view };
}

describe('useCloseWhenFilteredOut', () => {
  it('closes when a filter change leaves the selection out of the set', () => {
    const { onClose, rerender } = setup();

    rerender({ selectedId: 1, items: [B], getId, filterKey: 'search:b', onClose });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('stays open when the filter changes but the selection is still in the set', () => {
    const { onClose, rerender } = setup();

    rerender({ selectedId: 1, items: [A], getId, filterKey: 'search:a', onClose });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('never closes on a data refresh: same filters, a new items array without the selection', () => {
    const { onClose, rerender } = setup();

    rerender({ selectedId: 1, items: [B], getId, filterKey: 'all', onClose });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('does not check a selection that changes (Back, Forward, a new reader) under unchanged filters', () => {
    const { onClose, rerender } = setup();

    rerender({ selectedId: 9, items: [A, B], getId, filterKey: 'all', onClose });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('does nothing with no selection', () => {
    const { onClose, rerender } = setup({ selectedId: null });

    rerender({ selectedId: null, items: [], getId, filterKey: 'search:zzz', onClose });

    expect(onClose).not.toHaveBeenCalled();
  });
});
