import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { limitAsWord } from '../../utils/subRegionCopy.js';
import { countWord } from '../../utils/pickGeography.js';

/**
 * The scores upsell's words (docs/engineering/pro-features-gate-plan.md §4). Placeholder copy until the
 * owner signs it off, as Ask's was. There is no upgrade button, no link and no "upgrade" text anywhere
 * (the App Store forbids pointing at a web purchase, and `/upgrade` is not a route).
 */
export const SCORES_UPSELL_LABEL = 'Scores and the why';
export const SCORES_UPSELL_HEADING = 'Fiery Sky and Golden Hour scores';
export const SCORES_UPSELL_BODY = 'See how strong the colour and the light should be at each location, with a note on why.';

export const BEST_BETS_UPSELL_LABEL = 'Best bets';
export const BEST_BETS_UPSELL_HEADING = 'Best bets';
export const BEST_BETS_UPSELL_BODY = "See the forecast's two best windows, and where to go for each.";

export const TIDE_LIGHT_UPSELL_LABEL = 'When high water meets the light';
export const TIDE_LIGHT_UPSELL_HEADING = 'High water at the light';
export const TIDE_LIGHT_UPSELL_BODY = 'Pro shows which morning or evening in a tide run puts high water at sunrise or sunset, and how the tide sits at the light at every coastal spot.';

export const AURORA_UPSELL_HEADING = 'Aurora forecasts';
export const AURORA_UPSELL_BODY = 'See where the aurora should show, rated at each dark-sky location, with how far south it should reach.';

/**
 * The sub-regions doors' words (sub-regions-plan §11 S5a-3, handoff task 7). Every number is served data, never a
 * literal: the places and sub-regions come from the roster, "your n sub-regions" from the picks the server applies
 * (never the limit), and the example places from the sub-region options. Singular at one, always.
 */
export const SUBREGIONS_ALL_LABEL = 'Every sub-region';
export const SUBREGIONS_ALL_HEADING = 'Ratings in every sub-region';

/**
 * The "Every sub-region" body.
 *
 * @param {{total: number, subRegionCount: number, allowed: number, picked: number}} facts the roster's places and
 *        sub-regions, the places the picks cover, and the sub-regions the server applies
 * @returns {string}
 */
export function subregionsAllBody({
  total, subRegionCount, allowed, picked,
}) {
  const across = subRegionCount > 0
    ? `Pro rates all ${total} places across the ${countWord(subRegionCount, 'sub-region', 'sub-regions')}. `
    : `Pro rates all ${total} places. `;
  const yours = picked > 0
    ? `You see ratings for ${countWord(allowed, 'place', 'places')} in your ${limitAsWord(picked)} ${picked === 1 ? 'sub-region' : 'sub-regions'}.`
    : '';
  return `${across}${yours}`.trim();
}

/** A region's name inside a sentence: a leading "The" goes lower case, a bare name stands bare. */
export function regionInSentence(name) {
  const text = String(name ?? '');
  return /^the\s/i.test(text) ? `the ${text.slice(4)}` : text;
}

/** The region door's trigger label, and its heading. */
export function subregionsRegionLabel(regionName) {
  return `Ratings across ${regionInSentence(regionName)}`;
}

/**
 * The region door's body: the places in the region and its sub-regions, with up to two example places.
 *
 * @param {{regionName: string, places: number, subRegionCount: number, examples: string[]}} facts
 * @returns {string}
 */
export function subregionsRegionBody({
  regionName, places, subRegionCount, examples = [],
}) {
  const named = examples.filter(Boolean).slice(0, 2);
  let among = '';
  if (named.length === 2) among = `, ${named[0]} and ${named[1]} among them`;
  else if (named.length === 1) among = `, ${named[0]} among them`;
  const subs = subRegionCount > 0
    ? ` across the ${limitAsWord(subRegionCount)} ${subRegionCount === 1 ? 'sub-region' : 'sub-regions'} of ${regionInSentence(regionName)}`
    : ` in ${regionInSentence(regionName)}`;
  return `Pro rates all ${countWord(places, 'place', 'places')}${subs}${among}, for every sunrise and sunset.`;
}

/** The place door's trigger label, and its heading. */
export function subregionsPlaceLabel(place) {
  return `Ratings at ${place}`;
}

/** The place door's body. */
export function subregionsPlaceBody(place) {
  return `Pro gives every place in every sub-region its star rating and verdict for each window, ${place} included.`;
}

/**
 * The aurora door's trigger label for a night row's label (docs/engineering/aurora-pro-door-plan.md §2.2):
 * {@code Tonight} becomes {@code Aurora tonight}, {@code Tomorrow night} becomes {@code Aurora tomorrow
 * night}, and {@code Tuesday night} becomes {@code Aurora Tuesday night} (a weekday keeps its capital).
 * The accessible name is this label, so the casing matters even though CSS uppercases the trigger.
 */
export function auroraUpsellLabel(nightLabel) {
  if (nightLabel === 'Tonight') return 'Aurora tonight';
  if (nightLabel === 'Tomorrow night') return 'Aurora tomorrow night';
  return `Aurora ${nightLabel}`;
}

/**
 * The generic upsell's content: the house pill, a heading and a body. No preview sketch: the two
 * existing sketches (tide, Ask) are feature drawings and a generic one would say nothing.
 * {@code headingRef} lets a popover move focus to the heading.
 */
export function ProUpsellContent({
  heading, body, headingId, headingRef = undefined, bodyId = undefined, testIdPrefix,
}) {
  return (
    <>
      <span className="wf-lens-pro" data-testid={`${testIdPrefix}-pill`}>Pro</span>
      <h3
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        className="wf-pro-upsell-heading"
        data-testid={`${testIdPrefix}-heading`}
      >
        {heading}
      </h3>
      <p id={bodyId} className="wf-pro-upsell-body" data-testid={`${testIdPrefix}-body`}>{body}</p>
    </>
  );
}

ProUpsellContent.propTypes = {
  heading: PropTypes.string.isRequired,
  body: PropTypes.string.isRequired,
  headingId: PropTypes.string.isRequired,
  headingRef: PropTypes.oneOfType([PropTypes.func, PropTypes.shape({ current: PropTypes.any })]),
  bodyId: PropTypes.string,
  testIdPrefix: PropTypes.string.isRequired,
};

/** Where focus goes if the door unmounts while focus is inside it: the host's nearest focusable ancestor. */
const FOCUSABLE_ANCESTOR = '[tabindex], a[href], button, input, select, textarea';

/**
 * A self-contained Pro upsell door: a trigger button (the label, with the {@code wf-lens-pro} pill INSIDE
 * it) and a non-modal popover, with the whole lifecycle owned here so a host only renders
 * {@code <ProUpsellDoor .../>} (plan §4):
 * <ol>
 *   <li>open state, {@code aria-expanded}, {@code aria-controls};</li>
 *   <li>an inline, absolutely positioned {@code role="dialog"} (no {@code aria-modal}) named by its heading
 *       and described by its body, the heading focused on open, and a "Not now" button;</li>
 *   <li>Escape on the popover or the trigger closes it, returns focus to the trigger and STOPS the key
 *       (propagation and default) so it never reaches a host {@code Modal}'s document listener;</li>
 *   <li>an outside {@code pointerdown} (capture) closes it (Safari gives a null {@code relatedTarget}, so
 *       blur alone is not enough);</li>
 *   <li>a {@code focusin} anywhere outside the door closes it, leaving focus where the reader put it;</li>
 *   <li>unmounting while open closes it and does not strand focus on {@code <body>}.</li>
 * </ol>
 *
 * <p>⚠️ No foreign-modal stand-down predicate. Such a predicate counts any dialog, including the sheet this
 * door lives in, so one Escape would close the whole sheet. The door is only ever the topmost layer while it
 * is open, so it handles its own keys unconditionally. It is not a {@code Modal} and adds no dialog to the
 * two-dialog count.
 */
export default function ProUpsellDoor({
  label, heading, body, testIdPrefix, className = '', unmountFocusRef = null, skipUnmountHandoff = false,
  note = null,
}) {
  const [open, setOpen] = useState(false);
  const uid = useId();
  const popId = `${uid}-pop`;
  const headingId = `${uid}-heading`;
  const bodyId = `${uid}-body`;
  const noteId = `${uid}-note`;
  const rootRef = useRef(null);
  const triggerRef = useRef(null);
  const headingRef = useRef(null);

  const popRef = useRef(null);

  useEffect(() => {
    if (!open) return;
    headingRef.current?.focus({ preventScroll: true });
    // On a short viewport the popover can open below the fold; jsdom has no scrollIntoView.
    popRef.current?.scrollIntoView?.({ block: 'nearest' });
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const inside = (node) => !!rootRef.current && rootRef.current.contains(node);
    const onPointerDown = (e) => { if (!inside(e.target)) setOpen(false); };
    const onFocusIn = (e) => { if (!inside(e.target)) setOpen(false); };
    document.addEventListener('pointerdown', onPointerDown, true);
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('focusin', onFocusIn);
    };
  }, [open]);

  // Unmount while open (the host re-rendered the door away): if focus is inside, hand it to the host's
  // nearest focusable ancestor before the nodes go, so it does not fall to <body>. A layout cleanup runs
  // while the nodes are still attached.
  // A host may name where focus goes instead ({@code unmountFocusRef}: the aurora door's window pill) or opt out
  // so focus falls to <body> and the host's own focus rescue runs ({@code skipUnmountHandoff}: the phone peek
  // sheet's fallback only acts on <body>). Read through a ref so the cleanup sees the latest values.
  const handoffRef = useRef({ unmountFocusRef, skipUnmountHandoff });
  useEffect(() => { handoffRef.current = { unmountFocusRef, skipUnmountHandoff }; });
  useLayoutEffect(() => () => {
    const root = rootRef.current;
    if (!root || !root.contains(document.activeElement)) return;
    const { unmountFocusRef: target, skipUnmountHandoff: skip } = handoffRef.current;
    if (skip) return;
    (target?.current ?? root.parentElement?.closest(FOCUSABLE_ANCESTOR))?.focus({ preventScroll: true });
  }, []);

  function close(returnFocus) {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus({ preventScroll: true });
  }

  function onKeyDown(e) {
    if (e.key !== 'Escape' || !open) return;
    e.stopPropagation();
    e.preventDefault();
    close(true);
  }

  return (
    // The wrapper owns Escape for both its children (trigger and popover); it is not itself interactive.
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      ref={rootRef}
      className={`wf-pro-upsell-door ${className}`.trim()}
      data-testid={`${testIdPrefix}-door`}
      onKeyDown={onKeyDown}
    >
      <button
        ref={triggerRef}
        type="button"
        className="wf-pro-upsell-trigger"
        data-testid={`${testIdPrefix}-trigger`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={popId}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="wf-pro-upsell-trigger-label">{label}</span>
        {' '}
        <span className="wf-lens-pro">Pro</span>
      </button>
      {open && (
        <div
          id={popId}
          ref={popRef}
          tabIndex={-1}
          role="dialog"
          aria-labelledby={headingId}
          aria-describedby={note ? `${bodyId} ${noteId}` : bodyId}
          className="wf-pro-upsell"
          data-testid={`${testIdPrefix}-popover`}
        >
          <ProUpsellContent
            heading={heading}
            body={body}
            headingId={headingId}
            headingRef={headingRef}
            bodyId={bodyId}
            testIdPrefix={testIdPrefix}
          />
          {note && (
            <p id={noteId} className="wf-pro-upsell-body" data-testid={`${testIdPrefix}-note`}>{note}</p>
          )}
          <button
            type="button"
            className="wf-pro-upsell-dismiss"
            data-testid={`${testIdPrefix}-dismiss`}
            onClick={() => close(true)}
          >
            Not now
          </button>
        </div>
      )}
    </div>
  );
}

ProUpsellDoor.propTypes = {
  label: PropTypes.string.isRequired,
  heading: PropTypes.string.isRequired,
  body: PropTypes.string.isRequired,
  testIdPrefix: PropTypes.string.isRequired,
  className: PropTypes.string,
  /** Where focus goes if the door unmounts while focused, instead of the nearest focusable ancestor. */
  unmountFocusRef: PropTypes.shape({ current: PropTypes.any }),
  /** Leave focus to fall to {@code <body>} on such an unmount, for a host whose own rescue acts on that. */
  skipUnmountHandoff: PropTypes.bool,
  /** One extra line under the body, described with it (`aria-describedby` names both). */
  note: PropTypes.string,
};
