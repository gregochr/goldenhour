import React, { useEffect, useLayoutEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import BoltIcon from './BoltIcon.jsx';
import { skyWideNotice } from '../../utils/mapTopics.js';

/** No locked types: every chip is an ordinary toggle (PRO, Admin, and every bare render). */
const NO_LOCKED_TYPES = [];

/** The empty-state words (desktop and tablet only; the phone renders nothing, Q11). */
const EMPTY_TEXT = 'No hot topics on this window';

/**
 * The Map tab's hot-topic chips (hot-topics-by-day-plan §3 M1; handoff §2A).
 *
 * <p>A row INSIDE {@code .wf-map-chrome-tl}, under the window control and the big-seas line, so it
 * shares the control's 504px bound and rides in the same label-placement obstacle. The row is
 * {@code pointer-events: none} and the chips {@code auto} (the {@code .wf-map-toolbar-row}
 * precedent): on a phone the gaps between chips would otherwise swallow map drags.
 *
 * <p>Each chip is a toggle {@code <button aria-pressed>} keyed by topic type. Its accessible name is
 * state-independent ("King tide, 66 coastal places"): {@code aria-pressed} carries the state, so the
 * name never has to change on a press. The glyph is decorative.
 *
 * <p>The row is a labelled group. If a focused chip unmounts (a briefing poll or a scope change took
 * its topic off the window, or the whole row went away), focus moves to the group, or to
 * {@code fallbackFocus} (the map pane) when there is no group, rather than falling to
 * {@code <body>}, where the pane's Escape rule cannot hear it. ⚠️ The decision is NOT made from a
 * blur: Chromium fires {@code blur} on a removed element while it is still connected, which made a
 * blur-clearing record forget the chip it should have parked from. The component keeps the focused
 * chip's DOM NODE (set on {@code focusin}, replaced by any later {@code focusin}) and parks when
 * that node is no longer connected and nothing else holds focus. The toggle's outcome is announced
 * by {@code MapView} through its existing status region, not here: there is no live region in this
 * component.
 *
 * <p>A whole-sky selection draws no rings; its one-line notice sits in this row instead, never as a
 * centred float, which would be a new label obstacle in the middle of the field.
 *
 * @param {object} props
 * @param {Array<object>} props.chips {@code topicsOnRow}'s result
 * @param {?string} props.selectedType the selected chip's type, or null
 * @param {Function} props.onToggle called with a chip's type when it is pressed
 * @param {boolean} [props.isMobile] the phone layout: no empty state
 * @param {boolean} [props.hasWindow] whether a window is on screen (the empty state needs one)
 * @param {boolean} [props.suppressed] draw nothing (the phone's rule while the big-seas line shows:
 *   Big seas is already the line there); the focus rule still runs
 * @param {?Function} [props.fallbackFocus] where focus goes when a focused chip is removed and there
 *   is no group to take it
 * @param {Array<string>} [props.lockedTopicTypes] the types that are a Pro map view for this reader (the
 *   tide topics, for LITE; docs/engineering/tide-pro-gate-plan.md §6.2). A locked chip is a popover door,
 *   not a toggle: it drops {@code aria-pressed}, gains {@code aria-haspopup="dialog"} and
 *   {@code aria-expanded}, and {@code onToggle} is called with the chip's element as a second argument
 *   so focus can return to it when the upsell closes
 * @param {boolean} [props.upsellOpen] whether the upsell popover is open (a locked chip's
 *   {@code aria-expanded})
 */
export default function MapTopicChips({
  chips, selectedType, onToggle, isMobile = false, hasWindow = true, suppressed = false,
  fallbackFocus = null, lockedTopicTypes = NO_LOCKED_TYPES, upsellOpen = false,
}) {
  const groupRef = useRef(null);
  const focusedNodeRef = useRef(null);

  // The focused chip's node: replaced by any later focusin, so focus moving elsewhere forgets it.
  useEffect(() => {
    function onFocusIn(e) {
      const chip = e.target?.closest?.('[data-topic-type]');
      focusedNodeRef.current = chip && groupRef.current?.contains(chip) ? chip : null;
    }
    document.addEventListener('focusin', onFocusIn);
    return () => document.removeEventListener('focusin', onFocusIn);
  }, []);

  // If the chip that held focus is gone after this commit and nothing else took focus, park it.
  useLayoutEffect(() => {
    const node = focusedNodeRef.current;
    if (!node || node.isConnected) return;
    focusedNodeRef.current = null;
    const active = document.activeElement;
    if (active && active !== document.body) return;
    if (groupRef.current) groupRef.current.focus({ preventScroll: true });
    else fallbackFocus?.();
  });

  const selected = chips.find((chip) => chip.type === selectedType) ?? null;
  const notice = skyWideNotice(selected);

  if (suppressed) return null;
  if (chips.length === 0) {
    if (isMobile || !hasWindow) return null;
    return (
      <div
        ref={groupRef}
        tabIndex={-1}
        role="group"
        aria-label="Hot topics on this window"
        className="wf-topics wf-topics-empty"
        data-testid="wf-topics"
      >
        <span className="wf-topics-none" data-testid="wf-topics-empty">{EMPTY_TEXT}</span>
      </div>
    );
  }

  return (
    <div
      ref={groupRef}
      tabIndex={-1}
      role="group"
      aria-label="Hot topics on this window"
      className="wf-topics"
      data-testid="wf-topics"
    >
      {chips.map((chip) => {
        const isLocked = lockedTopicTypes.includes(chip.type);
        const isSelected = !isLocked && chip.type === selectedType;
        return (
          <button
            key={chip.type}
            type="button"
            className="wf-tch"
            aria-pressed={isLocked ? undefined : isSelected}
            aria-haspopup={isLocked ? 'dialog' : undefined}
            aria-expanded={isLocked ? upsellOpen : undefined}
            aria-label={chip.label}
            data-testid={`wf-topic-chip-${chip.type}`}
            data-topic-type={chip.type}
            data-selected={isSelected ? 'true' : undefined}
            style={{ '--wf-tc': chip.colour }}
            onClick={(e) => (isLocked ? onToggle(chip.type, e.currentTarget) : onToggle(chip.type))}
          >
            <span className="wf-tch-g" aria-hidden="true">
              {chip.bigSeas ? <BoltIcon /> : chip.glyph}
            </span>
            <span className="wf-tch-n" aria-hidden="true">{chip.name}</span>
            {chip.where && (
              <span className="wf-tch-w" aria-hidden="true" data-testid="wf-topic-where">
                {chip.where}
              </span>
            )}
          </button>
        );
      })}
      {notice && (
        <p className="wf-topics-sky" data-testid="wf-topic-skywide">
          <span aria-hidden="true">{selected.glyph}</span>
          {' '}
          {notice}
        </p>
      )}
    </div>
  );
}

MapTopicChips.propTypes = {
  chips: PropTypes.arrayOf(PropTypes.shape({
    type: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    glyph: PropTypes.string,
    colour: PropTypes.string.isRequired,
    where: PropTypes.string,
    wholeSky: PropTypes.bool,
    bigSeas: PropTypes.bool,
  })).isRequired,
  selectedType: PropTypes.string,
  onToggle: PropTypes.func.isRequired,
  isMobile: PropTypes.bool,
  hasWindow: PropTypes.bool,
  suppressed: PropTypes.bool,
  fallbackFocus: PropTypes.func,
  lockedTopicTypes: PropTypes.arrayOf(PropTypes.string),
  upsellOpen: PropTypes.bool,
};
