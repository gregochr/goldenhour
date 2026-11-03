import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';

/** The tooltip's widest, px, and the gap between the anchor and the box. */
const TIP_MAX_WIDTH = 260;
const TIP_GAP = 14;
const TIP_EDGE = 4;
const TIP_BASE_HEIGHT = 28;
const TIP_LINE_HEIGHT = 16;

/** The tone of a status word in the tooltip. Whole literals so Tailwind keeps them. */
const TONE_TEXT = {
  go: 'text-plex-ok',
  amber: 'text-verdict-marginal',
  coral: 'text-plex-coral',
  tide: 'text-tide',
  off: 'text-plex-text-muted',
};

/** The element id the tooltip carries, which a focused row points at with {@code aria-describedby}. */
export const DAY_TOOLTIP_ID = 'scheduler-day-tooltip';

/**
 * The picture's one open tooltip: which thing it is for, where it is anchored (px in the plot's own space) and what it
 * says. Hover and keyboard focus show it for the thing under them; leaving or blurring hides it only if it is still
 * that thing's; Escape hides it before anything else sees the key, so it never also closes the job panel.
 *
 * <p>The Escape rule: the listener is on the document in the capture phase and calls {@code preventDefault}, so
 * {@code usePanelEscape} (which ignores a {@code defaultPrevented} key) leaves the panel open. It exists only while a
 * tooltip is showing.
 *
 * @returns {{tip: {id: string, x: number, y: number, content: object}|null, show: Function, hide: Function}}
 */
export function useDayTooltip() {
  const [tip, setTip] = useState(null);
  const show = useCallback((id, x, y, content) => setTip({ id, x, y, content }), []);
  const hide = useCallback((id) => setTip((current) => (id == null || current?.id === id ? null : current)), []);
  const open = tip != null;

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      event.preventDefault();
      setTip(null);
    };
    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, [open]);

  return { tip, show, hide };
}

/** Which blocks the tooltip prints, in order (used to estimate its height before it is measured). */
function linesOf(content) {
  const lines = [];
  const meta = [content.time, content.duration].filter(Boolean).join(' · ');
  if (content.status || meta) lines.push('status');
  if (content.note) lines.push('note');
  if (content.watchdog) lines.push('watchdog');
  if (content.alias) lines.push('alias');
  return lines;
}

/**
 * The tooltip, an HTML layer over the plot (never inside the {@code aria-hidden} SVG): name, status, time and duration,
 * the one-line note, the watchdog window and its event, and the plain alias. {@code role="tooltip"}; a focused row
 * names it with {@code aria-describedby}. It is positioned from the anchor and the plot's size (never measured with
 * {@code getBBox}): to the right of and below the anchor, flipped above near the bottom, kept inside the plot.
 *
 * @param {object} props
 * @param {{id: string, x: number, y: number, content: object}} props.tip the open tooltip
 * @param {number} props.width the plot's width, px
 * @param {number} props.height the plot's height, px
 */
export default function DayTooltip({ tip, width, height }) {
  const ref = useRef(null);
  const { content } = tip;
  const lines = linesOf(content);
  const estimate = TIP_BASE_HEIGHT + TIP_LINE_HEIGHT * (lines.length + (content.watchdogEvent ? 1 : 0));
  const left = Math.max(TIP_EDGE, Math.min(tip.x + 12, width - TIP_MAX_WIDTH - TIP_EDGE));
  const below = tip.y + TIP_GAP;
  const top = below + estimate <= height ? below : Math.max(0, tip.y - TIP_GAP - estimate);

  // The position rides two CSS variables (the classes read them), so the markup carries no inline style prop.
  useLayoutEffect(() => {
    ref.current?.style.setProperty('--tip-x', `${left}px`);
    ref.current?.style.setProperty('--tip-y', `${top}px`);
  }, [left, top]);

  const meta = [content.time, content.duration].filter(Boolean).join(' · ');
  return (
    <div
      ref={ref}
      id={DAY_TOOLTIP_ID}
      role="tooltip"
      className="pointer-events-none absolute left-(--tip-x) top-(--tip-y) z-20 w-max max-w-[260px] rounded-lg border border-plex-border bg-plex-panel px-3 py-2 text-[12px] leading-4 text-plex-text shadow-lg"
      data-testid="scheduler-day-tooltip"
    >
      <div className="font-medium" data-testid="scheduler-tip-name">{content.name}</div>
      {(content.status || meta) && (
        <div className="font-mono text-[11px] text-plex-text-secondary" data-testid="scheduler-tip-status">
          {content.status && <span className={TONE_TEXT[content.status.tone] ?? TONE_TEXT.off}>{content.status.label}</span>}
          {content.status && meta ? ' · ' : ''}
          {meta}
        </div>
      )}
      {content.note && <div className="text-plex-text-secondary" data-testid="scheduler-tip-note">{content.note}</div>}
      {content.watchdog && (
        <div className="text-plex-coral" data-testid="scheduler-tip-watchdog">
          {content.watchdog}
          {content.watchdogEvent && <div className="text-plex-text-secondary">{content.watchdogEvent}</div>}
        </div>
      )}
      {content.alias && <div className="text-plex-text-muted" data-testid="scheduler-tip-alias">{content.alias}</div>}
    </div>
  );
}

DayTooltip.propTypes = {
  tip: PropTypes.shape({
    id: PropTypes.string.isRequired,
    x: PropTypes.number.isRequired,
    y: PropTypes.number.isRequired,
    content: PropTypes.object.isRequired,
  }).isRequired,
  width: PropTypes.number.isRequired,
  height: PropTypes.number.isRequired,
};
