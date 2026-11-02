import { useEffect, useRef, useState } from 'react';

/**
 * The pixel width of an element, kept current with a {@code ResizeObserver}, so a chart's {@code viewBox} can be as
 * wide as its card (1:1 with the screen: text and strokes stay real pixels, as in the Day strip). Returns 0 until the
 * first measure and wherever nothing measures (jsdom); a chart then falls back to its own default width.
 *
 * @returns {[{current: HTMLElement|null}, number]} the ref to put on the element, and its width
 */
export default function useMeasuredWidth() {
  const ref = useRef(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const node = ref.current;
    if (!node || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}
