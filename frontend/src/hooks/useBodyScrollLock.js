import { useLayoutEffect } from 'react';

/** How many locks are held: the first one applies the lock and the last one to go lifts it. */
let holders = 0;
/** What the first holder replaced, restored by the last. */
let saved = null;

/**
 * Stops the page behind a full-screen view from scrolling while it is open (`docs/engineering/one-screen-plan.md`
 * D11). No scroll lock existed in the app before the map view: this is new code.
 *
 * <p>`overflow: hidden` on the root element freezes the document scroller without moving it, so the reader's place
 * is exactly where it was on close (`window.scrollY` is untouched). Removing the scrollbar would widen the page by
 * its own width, so that width is handed back as right padding on `<body>` for the duration. Reference counted, so two
 * holders (a view and a dialog that also locks) never fight over the restore.
 *
 * @param {boolean} locked whether the lock is held
 */
export default function useBodyScrollLock(locked) {
  useLayoutEffect(() => {
    if (!locked || typeof document === 'undefined') return undefined;
    const root = document.documentElement;
    const { body } = document;
    if (holders === 0) {
      const scrollbar = Math.max(0, window.innerWidth - root.clientWidth);
      saved = { overflow: root.style.overflow, paddingRight: body.style.paddingRight };
      root.style.overflow = 'hidden';
      if (scrollbar > 0) {
        const current = Number.parseFloat(window.getComputedStyle(body).paddingRight) || 0;
        body.style.paddingRight = `${current + scrollbar}px`;
      }
    }
    holders += 1;
    return () => {
      holders -= 1;
      if (holders === 0 && saved) {
        root.style.overflow = saved.overflow;
        body.style.paddingRight = saved.paddingRight;
        saved = null;
      }
    };
  }, [locked]);
}
