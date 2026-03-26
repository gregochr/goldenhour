import React, { useState, useRef, useEffect, useCallback } from 'react';
import PropTypes from 'prop-types';

/**
 * Tap/click-to-reveal info popover. Replaces hover-only title attributes
 * so the help text is accessible on touch devices.
 *
 * The popover uses `position: fixed` so it escapes any `overflow: hidden`
 * ancestor (e.g. collapsible filter panels).
 *
 * @param {object} props
 * @param {string} props.text - The help text to display.
 * @param {string} [props.className] - Optional extra classes on the wrapper.
 * @param {'above'|'below'} [props.position='above'] - Preferred placement.
 */
export default function InfoTip({ text, className = '', position = 'above' }) {
  const [open, setOpen] = useState(false);
  const [coords, setCoords] = useState({ top: 0, left: 0 });
  const triggerRef = useRef(null);
  const popoverRef = useRef(null);

  const computePosition = useCallback(() => {
    if (!triggerRef.current) return;
    const rect = triggerRef.current.getBoundingClientRect();
    const GAP = 6;
    const popoverWidth = 320; // max-w-xs equivalent estimate; clamped below

    // Horizontal: align left edge of trigger, then clamp to viewport
    const rawLeft = rect.left;
    const clampedLeft = Math.min(
      Math.max(rawLeft, 8),
      window.innerWidth - popoverWidth - 8,
    );

    const top = position === 'below'
      ? rect.bottom + GAP
      : rect.top - GAP; // bottom edge; subtract popover height after paint

    setCoords({ top, left: clampedLeft, above: position !== 'below', triggerBottom: rect.bottom, triggerTop: rect.top });
  }, [position]);

  useEffect(() => {
    if (!open) return;
    computePosition();

    function handleClickOutside(e) {
      if (
        triggerRef.current && !triggerRef.current.contains(e.target)
        && popoverRef.current && !popoverRef.current.contains(e.target)
      ) {
        setOpen(false);
      }
    }
    function handleScroll() { computePosition(); }

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('touchstart', handleClickOutside);
    window.addEventListener('scroll', handleScroll, true);
    window.addEventListener('resize', computePosition);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('touchstart', handleClickOutside);
      window.removeEventListener('scroll', handleScroll, true);
      window.removeEventListener('resize', computePosition);
    };
  }, [open, computePosition]);

  // After paint, shift 'above' popover up by its own height
  const popoverStyle = coords.above
    ? { position: 'fixed', top: 'auto', bottom: window.innerHeight - coords.triggerTop + 6, left: coords.left, zIndex: 9999 }
    : { position: 'fixed', top: coords.triggerBottom + 6, left: coords.left, zIndex: 9999 };

  return (
    <span ref={triggerRef} className={`relative inline-flex items-center ${className}`}>
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        className="inline-flex items-center justify-center w-4 h-4 rounded-full text-[10px] leading-none font-bold border border-current opacity-50 hover:opacity-80 transition-opacity cursor-pointer"
        aria-label="More info"
        data-testid="infotip-trigger"
      >
        i
      </button>
      {open && (
        <span
          ref={popoverRef}
          style={popoverStyle}
          className="w-max max-w-[min(24rem,calc(100vw-2rem))] px-2.5 py-1.5 text-xs rounded shadow-lg whitespace-pre-line bg-plex-surface text-plex-text border border-plex-border"
          data-testid="infotip-popover"
        >
          {text}
        </span>
      )}
    </span>
  );
}

InfoTip.propTypes = {
  text: PropTypes.string.isRequired,
  className: PropTypes.string,
  position: PropTypes.oneOf(['above', 'below']),
};
