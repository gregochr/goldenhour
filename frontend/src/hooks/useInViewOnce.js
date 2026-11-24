import { useCallback, useEffect, useState } from 'react';

/**
 * Whether an element has ever been on screen: false until the first time it intersects the viewport, then true for
 * good. Gates a fetch that should wait until its reader could see the result (Your places' area outlook).
 *
 * <p>The node arrives through a callback ref held in state, not a {@code useRef}, for the reason
 * {@code useStuckSentinel} records: the element is mounted conditionally, so an effect keyed on a ref would run once
 * against nothing and never again.
 *
 * <p><b>Fails open.</b> With no {@code IntersectionObserver} (jsdom, a very old browser) it reports {@code true} at
 * once: the fetch it gates is cheap and a reader who never gets it would be worse off than one who gets it early.
 *
 * @returns {[Function, boolean]} a ref callback for the element, and whether it has been in view
 */
export default function useInViewOnce() {
  const [node, setNode] = useState(null);
  const [seen, setSeen] = useState(false);
  const ref = useCallback((el) => setNode(el ?? null), []);
  const observable = typeof IntersectionObserver !== 'undefined';

  useEffect(() => {
    if (!node || !observable) return undefined;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setSeen(true);
        observer.disconnect();
      }
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [node, observable]);

  return [ref, seen || !observable];
}
