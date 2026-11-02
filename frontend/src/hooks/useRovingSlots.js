import { useCallback, useRef, useState } from 'react';

/**
 * A roving tab stop over the hit targets of a chart (a bar per day, a dot per run): only one is in the tab order, Left
 * and Right (and Home and End) move between them, so a 60-day chart is one stop, not sixty.
 *
 * @param {number} count the number of targets
 * @returns {{containerRef: {current: Element|null}, slotProps: (index: number) => object}} put {@code containerRef} on
 *   the chart's root and spread {@code slotProps(i)} on target {@code i}
 */
export default function useRovingSlots(count) {
  const containerRef = useRef(null);
  const [active, setActive] = useState(0);
  const current = Math.min(active, Math.max(0, count - 1));

  const move = useCallback((index) => {
    setActive(index);
    containerRef.current?.querySelector(`[data-slot="${index}"]`)?.focus();
  }, []);

  const slotProps = (index) => ({
    'data-slot': index,
    tabIndex: index === current ? 0 : -1,
    onFocus: () => setActive(index),
    onKeyDown: (event) => {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      let next = null;
      if (event.key === 'ArrowRight') next = Math.min(count - 1, index + 1);
      else if (event.key === 'ArrowLeft') next = Math.max(0, index - 1);
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = count - 1;
      if (next == null) return;
      // The Day view's own Left/Right stepping must not also fire (F1).
      event.preventDefault();
      move(next);
    },
  });

  return { containerRef, slotProps };
}
