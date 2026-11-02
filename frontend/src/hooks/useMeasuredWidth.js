import { useEffect, useState } from 'react';

/**
 * The pixel width of an element, kept current with a {@code ResizeObserver}, so a chart's {@code viewBox} can be as
 * wide as its card (1:1 with the screen: text and strokes stay real pixels, as in the Day strip). Returns 0 until the
 * first measure and wherever nothing measures (jsdom); a chart then falls back to its own default width.
 *
 * <p>The element is held in state (a callback ref), so one that mounts later (a chart that only renders once there
 * are runs) is measured when it appears, not only if it was there on the first render.
 *
 * @returns {[(node: HTMLElement|null) => void, number]} the callback ref to put on the element, and its width
 */
export default function useMeasuredWidth() {
  const [node, setNode] = useState(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    if (!node || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(node);
    return () => observer.disconnect();
  }, [node]);
  return [setNode, width];
}
