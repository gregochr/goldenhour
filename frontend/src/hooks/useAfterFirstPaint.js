import { useEffect, useState } from 'react';

/**
 * Returns `false` on the first render, then flips to `true` shortly after the first paint
 * (on the browser's idle callback, or a short timeout fallback).
 *
 * Used to defer non-critical work — e.g. opening long-lived SSE connections — until after the
 * page is interactive, so those connections don't compete with the critical first-load fetches.
 *
 * @param {number} [timeoutMs=1500] - maximum wait before flipping true even if the browser never idles
 * @returns {boolean} whether the first paint has completed
 */
export default function useAfterFirstPaint(timeoutMs = 1500) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (typeof window !== 'undefined' && typeof window.requestIdleCallback === 'function') {
      const id = window.requestIdleCallback(() => setReady(true), { timeout: timeoutMs });
      return () => window.cancelIdleCallback?.(id);
    }
    const id = setTimeout(() => setReady(true), 200);
    return () => clearTimeout(id);
  }, [timeoutMs]);

  return ready;
}
