import React, { useState, useRef, useEffect } from 'react';
import PropTypes from 'prop-types';

/**
 * Tap/click-to-reveal info popover. Replaces hover-only title attributes
 * so the help text is accessible on touch devices.
 *
 * @param {object} props
 * @param {string} props.text - The help text to display.
 * @param {string} [props.className] - Optional extra classes on the wrapper.
 */
export default function InfoTip({ text, className = '' }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  useEffect(() => {
    if (!open) return;
    function handleClickOutside(e) {
      if (ref.current && !ref.current.contains(e.target)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('touchstart', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('touchstart', handleClickOutside);
    };
  }, [open]);

  return (
    <span ref={ref} className={`relative inline-flex items-center ${className}`}>
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
          className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 px-2.5 py-1.5 text-xs rounded shadow-lg whitespace-normal max-w-[220px] z-50 bg-plex-surface text-plex-text border border-plex-border"
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
};
