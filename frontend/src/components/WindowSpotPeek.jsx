import React from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import ScoreBar from './ScoreBar.jsx';

/** Filled/hollow glyph row — `★★★★☆`. Ratings are integers, so no half star exists. */
function starGlyphs(rating) {
  const filled = Math.max(0, Math.min(5, Math.round(rating)));
  return '★'.repeat(filled) + '☆'.repeat(5 - filled);
}

/**
 * The window-first spot peek — what a pointer resting on a film-strip card is shown.
 *
 * <p>Every test id here is {@code wf-} prefixed, distinct from this panel's now-retired v1
 * predecessor's {@code cth-hover-*} ids.
 *
 * <h2>Portalled to the body, which removes three questions rather than one</h2>
 *
 * <p>{@code position: fixed} escapes a scroller's overflow, which is necessary and not sufficient —
 * a {@code transform} on any ancestor re-bases fixed positioning onto that ancestor. All three
 * hazards are live on this exact surface: {@code .wf-spots} is {@code overflow-x: auto} (which
 * computes {@code overflow-y} to auto, so it clips on both axes), the window card sets
 * {@code overflow: hidden} as an <em>inline</em> style that no stylesheet rule can outrank, and
 * {@code .wf-spot:hover} applies {@code translateY(-2px)} to the very button this panel hangs off.
 * Portalling to {@code document.body} takes the ancestor chain out of the question entirely, which was
 * the argument a shared popover host made for itself, and the reason its approach is copied here.
 *
 * <p><b>That shared popover host (since deleted as dead code) was never used by this caller</b>,
 * and the reason was placement rather than portalling: its placement function anchored above the
 * trigger and never flipped, and the host took only {@code {popover, className}} — no slot for the
 * pointer handlers the 120ms panel grace needs, and no way to express an arrow offset. Serving this
 * caller would have meant widening two shared files to change behaviour the day rail relied on, for
 * the sake of the one part (the portal) that is four lines. What is taken is the <em>reasoning</em>,
 * cited: portal rather than merely fix, and dismiss on capture-phase scroll.
 *
 * <h2>What it shows, and what it deliberately leaves out</h2>
 *
 * <ul>
 *   <li><b>Score bars.</b> In this arm the peek is the only route to the scores, and it fires off a
 *       strip the reader is already scanning, so the weight is earned. If the pilot reports the
 *       panel feeling like a glitch, the bars are the first thing back out.</li>
 *   <li><b>The generated-at timestamp — out.</b> The rail footer states the forecast's age once for
 *       the whole screen, and §2.7's rule against marking one fact twice binds.</li>
 *   <li><b>The region line — out.</b> The spot card prints the region itself, one element away.</li>
 *   <li><b>Tide detail — out.</b> P7's attribute row carries the window's tide directly above the
 *       strip, and the payload has no per-spot tide to state anyway.</li>
 *   <li><b>The header bar and the ✕ — out.</b> Both are modal furniture, and this must not read as
 *       modal weight. There is nothing for a header bar to hold: the panel closes on pointer-leave
 *       and on Escape, so a close button would be a control no assistive technology can reach and no
 *       pointer user needs.</li>
 *   <li><b>The footer bar — out; the prompt it held stays as prose.</b> The line is worded as the
 *       gain rather than the destination. The card underneath already says {@code ◍ Open on map →},
 *       which names <em>where</em> the click goes; this says what it adds over the panel being read
 *       — the whole generated paragraph, not one clause.</li>
 *   <li><b>The location name — out.</b> The arrow tethers the panel to the card that just named it,
 *       and at this strip's geometry the panel is within a few px of one card's width, so it sits
 *       squarely under the name it would repeat.</li>
 * </ul>
 *
 * <h2>{@code aria-hidden}, and therefore never the only route to anything</h2>
 *
 * <p>Hover has no touch equivalent, so the strip gates this on viewport and pointer type, and the
 * card stays a real button one tab stop away. Nothing here is a destination the card does not also
 * reach: the click duplicates the card's own activation, which is what licenses a handler on a
 * non-interactive element. The <em>content</em> is reachable too — the map overlay the click opens
 * renders the same scores and the whole summary — so a screen reader loses a shortcut, not a fact.
 * {@code frontend-test-standards.md:225} names this component as the example of the rule, and
 * {@code WindowSpotStrip.test.jsx} pins it.
 *
 * @param {object}    props
 * @param {?number}   props.rating       1–5 stars, or null when unrated
 * @param {?number}   props.driveMinutes the caller's drive time, or null
 * @param {?string}   props.leaveBy      when to leave, `HH:mm` on the UK clock, or null when
 *        either the drive time or this slot's event time is unknown
 * @param {?number}   props.fierySky     Fiery Sky potential 0–100, or null
 * @param {?number}   props.goldenHour   Golden Hour potential 0–100, or null
 * @param {?string}   props.clause       one clause of Claude's sentence, already truncated —
 *        {@code resolveSpotPeek} truncates so that the gate is provably the thing rendered
 * @param {Object}    props.position     fixed-position coordinates, `{left, top}` or `{left, bottom}`
 * @param {string}    props.placement    'below' | 'above' — which side of the card it sits on
 * @param {number}    props.arrowLeft    arrow offset from the panel's left edge, in px
 * @param {Function}  props.onOpen       what the prompt does — the map, or (M4) this place's own
 *                                        four-day sheet. The caller decides, and names it below
 * @param {string}    [props.openPrompt] the prompt's words. Defaults to the map wording, so a
 *                                       caller written before M4 renders exactly what it did
 * @param {Function}  props.onPointerEnter keeps the panel open while the pointer rests on it
 * @param {Function}  props.onPointerLeave starts the dismissal grace
 */
export default function WindowSpotPeek({
  rating = null,
  driveMinutes = null,
  leaveBy = null,
  fierySky = null,
  goldenHour = null,
  clause = null,
  position,
  placement = 'below',
  arrowLeft = 24,
  onOpen,
  openPrompt = 'Click for the full read + map →',
  onPointerEnter,
  onPointerLeave,
}) {
  const drive = formatDriveDuration(driveMinutes);
  const hasScores = fierySky != null || goldenHour != null;

  return createPortal(
    // Not a <button>: the panel is aria-hidden and pointer-only, and wrapping four lines of content
    // in a control would put a duplicate of the card's own action into the accessibility tree for
    // anyone the panel is invisible to. Keyboard users reach the same action on the card.
    <div
      data-testid="wf-peek"
      aria-hidden="true"
      className="wf-peek"
      data-placement={placement}
      onClick={onOpen}
      onMouseEnter={onPointerEnter}
      onMouseLeave={onPointerLeave}
      style={{
        left: `${position.left}px`,
        ...(placement === 'above'
          ? { bottom: `${position.bottom}px` }
          : { top: `${position.top}px` }),
        '--wf-peek-arrow-left': `${arrowLeft}px`,
      }}
    >
      {/* 1 · the rating, and what it costs to get there.

          `flex-wrap`, and it is load-bearing rather than defensive. Measured in the browser at
          1280×900 on a 5★ spot with a 41-minute drive: stars 80px + `5.0/5` 32px + the drive chip
          71px + the new leave chip 96px + three 8px gaps = 303px of content in this panel's 252px
          box. Nothing in the row can shrink — `.wf-peek-chip` is `white-space: nowrap` (its own
          comment records why) and a flex item's automatic minimum size is its min-content width —
          and `.wf-peek` declares no `overflow`, so before this the leave chip painted ~50px
          outside the panel's own border, over the page. The row-gap keeps the wrapped line off the
          score bars; the wrap costs ~24px, which `PEEK_ESTIMATED_HEIGHT`'s 220 already covers (it
          was measured at 163–202). */}
      <div className="flex flex-wrap items-center" style={{ gap: '6px 8px' }}>
        {/* Omitted entirely when the spot is unrated, never drawn as an empty scale. `☆☆☆☆☆` is the
            glyph for ZERO, and zero is a claim: the card this panel hangs off refuses to make it —
            `WindowSpotStrip.jsx` omits the badge rather than showing a dash, "an unrated spot is one
            nothing has looked at, which is a different statement from a poor one" — and every other
            field here already follows that rule (the drive chip below, each score bar). The state is
            reachable rather than theoretical: the score index is fetched once and the briefing polls,
            so a slot can carry a score with no usable rating, which is exactly the divergence
            `resolveSpotPeek` is written to expect.

            `--color-verdict-marginal` — the verdict token, named directly rather than through an
            alias. Measured on the running app: #E0A542 on this panel's #2A2019 is 7.30:1. */}
        {rating != null && (
          <span
            data-testid="wf-peek-stars"
            style={{ color: 'var(--color-verdict-marginal)', letterSpacing: '2px', fontSize: '14px' }}
          >
            {starGlyphs(rating)}
          </span>
        )}
        {/* Secondary, not muted: recomputed over this panel's own #2A2019, muted is 3.46:1 at
            10.5px and fails AA. (An earlier draft of this comment said 3.9:1, which is not a figure
            any backdrop in the palette produces — the conclusion held, the number did not.) */}
        {rating != null && (
          <span className="font-mono text-plex-text-secondary" style={{ fontSize: '10.5px' }}>
            {rating.toFixed(1)}/5
          </span>
        )}
        {/* Secondary again, and measured on the chip's OWN backdrop rather than the panel's: the
            chip carries `rgba(255,255,255,0.05)` of its own, which lifts the surface under the text
            to rgb(53,43,37) and the ratio to 5.82:1 — not the 6.44:1 the bare panel gives. Still
            clears AA. This project has now got that wrong often enough to be worth the sentence. */}
        {drive && (
          <span data-testid="wf-peek-drive" className="wf-peek-chip font-mono">
            🚗 {drive}
          </span>
        )}
        {/* The departure time, beside what it is derived from. Conditional on its own value like
            every other field here: `leaveBy` is null whenever the drive time or the slot's event
            time is unknown, and this row states what it knows rather than reserving space for what
            it does not. The chip is the drive chip's own class, so the two read as one pair — this
            is the "what it costs to get there" row, and a leave time is the same statement said in
            the form a reader can act on. */}
        {leaveBy && (
          <span data-testid="wf-peek-leave" className="wf-peek-chip font-mono">
            ↰ leave {leaveBy}
          </span>
        )}
      </div>

      {/* 2 · the two scores — the part the card cannot show and the reason this panel is new.
          Each bar is conditional on its own value, so a slot scored on one axis and not the other
          draws one bar rather than a bar and a zero. */}
      {hasScores && (
        <div data-testid="wf-peek-scores" style={{ marginTop: '8px' }}>
          {fierySky != null && (
            <ScoreBar
              label="Fiery Sky"
              score={fierySky}
              metric="fiery"
              testId="wf-peek-fiery"
              dense
            />
          )}
          {goldenHour != null && (
            <ScoreBar
              label="Golden Hour"
              score={goldenHour}
              metric="golden"
              testId="wf-peek-golden"
              dense
            />
          )}
        </div>
      )}

      {/* 3 · one clause of the why, in Claude's voice.
          Serif italic is this app's typographic mark for generated prose — the drill-down gloss, the
          map overlay's summary, the InfoTip card body and the tide run's phrase all use it, and this
          fragment is the same kind of thing: an argued sentence, not a measurement. */}
      {clause && (
        <p
          data-testid="wf-peek-summary"
          className="text-plex-text-secondary"
          style={{
            fontFamily: 'var(--font-serif)',
            fontStyle: 'italic',
            fontSize: '12.5px',
            lineHeight: 1.55,
            margin: '8px 0 0',
          }}
        >
          {clause}
        </p>
      )}

      {/* 4 · where the rest of it lives */}
      <p
        data-testid="wf-peek-prompt"
        style={{
          fontSize: '11.5px',
          fontWeight: 600,
          color: 'var(--color-tide)',
          margin: '8px 0 0',
        }}
      >
        {openPrompt}
      </p>
    </div>,
    document.body,
  );
}

WindowSpotPeek.propTypes = {
  rating: PropTypes.number,
  driveMinutes: PropTypes.number,
  leaveBy: PropTypes.string,
  fierySky: PropTypes.number,
  goldenHour: PropTypes.number,
  clause: PropTypes.string,
  position: PropTypes.shape({
    left: PropTypes.number.isRequired,
    top: PropTypes.number,
    bottom: PropTypes.number,
  }).isRequired,
  placement: PropTypes.oneOf(['above', 'below']),
  arrowLeft: PropTypes.number,
  onOpen: PropTypes.func.isRequired,
  openPrompt: PropTypes.string,
  onPointerEnter: PropTypes.func.isRequired,
  onPointerLeave: PropTypes.func.isRequired,
};
