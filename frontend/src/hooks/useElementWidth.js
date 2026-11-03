import { useEffect, useState } from 'react';

/**
 * The width of an element, kept current with a {@code ResizeObserver}, never below {@code minWidth}.
 *
 * @param {{current: HTMLElement|null}} ref the element
 * @param {number} minWidth the smallest width reported, px (also the answer before the first measurement)
 * @returns {number} the element's content width in px, at least {@code minWidth}
 */
export default function useElementWidth(ref, minWidth) {
  const [width, setWidth] = useState(minWidth);

  useEffect(() => {
    const node = ref.current;
    if (!node) return undefined;
    const measure = (value) => setWidth(Math.max(minWidth, Math.floor(value)));
    measure(node.clientWidth);
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver((entries) => {
      const entry = entries[entries.length - 1];
      measure(entry?.contentRect?.width ?? node.clientWidth);
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [ref, minWidth]);

  return Math.max(minWidth, width);
}
