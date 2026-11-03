import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { PANEL_EDIT_FORM_ATTR, visibleDialogOpen } from '../../../hooks/usePanelEscape.js';

/** The tooltip's widest, px, and the gap between the anchor and the box. */
const TIP_MAX_WIDTH = 260;
const TIP_MAX_WIDTH_PHONE = 200;
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

/** Whether an Escape belongs to something else: a text field, a visible dialog, or the panel's edit form. */
function escapeBelongsElsewhere(target) {
  if (visibleDialogOpen() || document.querySelector(`[${PANEL_EDIT_FORM_ATTR}]`)) return true;
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') return true;
  return target.tagName === 'INPUT' && !['checkbox', 'radio', 'button'].includes(target.type);
}

/**
 * The picture's one open tooltip, as the id of the thing it is for. Hover and keyboard focus show it for the thing
 * under them; leaving or blurring hides it only if it is still that thing's. Its content is never kept: the caller
 * looks the id up in what it is drawing now, so a poll that replaces the data cannot leave a stale tooltip.
 *
 * <p>{@code scope} is whatever must not change while a tooltip is open (the selected job, the zoom, the served day):
 * when any of it changes the tooltip is closed.
 *
 * <p>Escape closes it first, so it never also closes the job panel: a capture-phase document listener (present only
 * while a tooltip is open) calls {@code preventDefault}, which {@code usePanelEscape} honours. It stands aside when the
 * key belongs to something else: focus in a text field, a visible dialog, or the panel's edit form being open.
 *
 * @param {...unknown} scope values compared by identity
 * @returns {{id: string|null, anchorX: number|null, show: (id: string, anchorX?: number) => void,
 *   hide: (id?: string) => void}} {@code anchorX} is an x the shower fixed at that moment (a row label's, which depends
 *   on how far the scroller is scrolled), else null to use the mark's own
 */
export function useDayTooltip(...scope) {
  const [state, setState] = useState(null);
  const current = state != null && state.scope.length === scope.length && state.scope.every((v, i) => v === scope[i]);
  const id = current ? state.id : null;
  const anchorX = current ? state.anchorX : null;
  const show = (next, anchor = null) => setState({ id: next, anchorX: anchor, scope });
  const hide = (which) => setState((held) => (which == null || held?.id === which ? null : held));
  const open = id != null;

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.defaultPrevented || escapeBelongsElsewhere(event.target)) return;
      event.preventDefault();
      setState(null);
    };
    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, [open]);

  return { id, anchorX, show, hide };
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
 * @param {boolean} [props.compact] a phone: the box is narrower, so it fits beside the sticky label column
 */
export default function DayTooltip({ tip, width, height, compact = false }) {
  const ref = useRef(null);
  const { content } = tip;
  const lines = linesOf(content);
  const estimate = TIP_BASE_HEIGHT + TIP_LINE_HEIGHT * (lines.length + (content.watchdogEvent ? 1 : 0));
  const maxWidth = compact ? TIP_MAX_WIDTH_PHONE : TIP_MAX_WIDTH;
  const left = Math.max(TIP_EDGE, Math.min(tip.x + 12, width - maxWidth - TIP_EDGE));
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
      className="pointer-events-none absolute left-(--tip-x) top-(--tip-y) z-20 w-max max-w-[260px] max-[639px]:max-w-[200px] rounded-lg border border-plex-border bg-plex-panel px-3 py-2 text-[12px] leading-4 text-plex-text shadow-lg"
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
  compact: PropTypes.bool,
};
