import React from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import { formatDriveDuration } from '../utils/briefingDisplay.js';

/** Filled/hollow glyph row — `★★★★☆`. Ratings are integers, so no half star exists. */
function starGlyphs(rating) {
  const filled = Math.max(0, Math.min(5, Math.round(rating)));
  return '★'.repeat(filled) + '☆'.repeat(5 - filled);
}

/**
 * One score bar — the label, the number, and the filled track.
 *
 * <p>The two gradients are quoted from {@code MarkerPopupContent.jsx:260-261} rather than invented,
 * because the map overlay this panel's prompt points at draws the same two measurements: a reader
 * who follows the click must recognise the bar they just looked at. Copied rather than imported —
 * they are module-private there, and importing that 1,300-line component into the Plan pane would
 * pull the map popup's whole module graph into the pane's chunk to fetch two strings.
 *
 * <p><b>The number is not tinted</b>, where the popup ramps it through a three-stop scale. One
 * deviation, deliberate: this panel is 280px of tooltip that opens on a pointer resting, so a third
 * colour channel competing with the rating badge and the bar is weight it has not earned. The bar's
 * length carries the value and the number states it, so nothing here is encoded by colour alone
 * (SC 1.4.1).
 *
 * @param {object}  props
 * @param {string}  props.label the measurement's own name, as the map popup prints it
 * @param {number}  props.score 0–100
 * @param {string}  props.testId per-bar test id
 * @param {string}  props.fill  the track gradient
 */
function PeekScoreBar({ label, score, testId, fill }) {
  const pct = Math.min(100, Math.max(0, score));
  return (
    <div data-testid={testId} data-score={score} style={{ marginTop: '6px' }}>
      <div
        className="flex items-center justify-between font-mono"
        style={{ fontSize: '10px', marginBottom: '3px' }}
      >
        <span className="text-plex-text-secondary">{label}</span>
        <span className="text-plex-text" style={{ fontWeight: 600 }}>{pct}</span>
      </div>
      <div className="wf-peek-bar" style={{ background: fill }}>
        <span className="wf-peek-bar-rest" style={{ width: `${100 - pct}%` }} />
      </div>
    </div>
  );
}

PeekScoreBar.propTypes = {
  label: PropTypes.string.isRequired,
  score: PropTypes.number.isRequired,
  testId: PropTypes.string.isRequired,
  fill: PropTypes.string.isRequired,
};

/** Quoted from `MarkerPopupContent`'s `FIERY_FILL` / `GOLDEN_FILL` — see {@link PeekScoreBar}. */
const FIERY_FILL = 'linear-gradient(90deg, #B5A06A, #E0A542 45%, #C8452F)';
const GOLDEN_FILL = 'linear-gradient(90deg, #6B6453, #C88E2E 45%, #F5C518)';

/**
 * The window-first spot peek — what a pointer resting on a film-strip card is shown.
 *
 * <h2>Why this is a new component and not {@code CardHoverPreview}</h2>
 *
 * <p>Plan §5a`:688-691`. That panel hard-codes {@code cth-} class names, a {@code --cth-arrow-left}
 * custom property and five {@code cth-hover-*} test ids, takes no {@code className} and has no
 * content slot — so it could not be restyled or extended from outside even if the v1 arm were not
 * deliberately frozen for the flag comparison. Its own {@code CloseToHome.test.jsx:497,509} asserts
 * {@code cth-hover-scores} and {@code cth-hover-upsell} are <b>absent</b>, which is why every test id
 * here is {@code wf-} prefixed: reusing the old names would fail a test on a component this phase
 * never touched.
 *
 * <h2>Portalled to the body, which removes three questions rather than one</h2>
 *
 * <p>{@code position: fixed} escapes a scroller's overflow, which is necessary and not sufficient —
 * a {@code transform} on any ancestor re-bases fixed positioning onto that ancestor. All three
 * hazards are live on this exact surface: {@code .wf-spots} is {@code overflow-x: auto} (which
 * computes {@code overflow-y} to auto, so it clips on both axes), the window card sets
 * {@code overflow: hidden} as an <em>inline</em> style that no stylesheet rule can outrank, and
 * {@code .wf-spot:hover} applies {@code translateY(-2px)} to the very button this panel hangs off.
 * Portalling to {@code document.body} takes the ancestor chain out of the question entirely, which is
 * the argument {@code PopoverHost} makes for itself and the reason it is copied here.
 *
 * <p><b>{@code PopoverHost} itself is not used</b>, and the reason is placement rather than
 * portalling. {@code computePopoverPlacement} anchors above the trigger and never flips, and the host
 * takes only {@code {popover, className}} — no slot for the pointer handlers the 120ms panel grace
 * needs, and no way to express an arrow offset. Serving this caller would mean widening two shared
 * files to change behaviour the day rail relies on, for the sake of the one part (the portal) that is
 * four lines. What is taken is the <em>reasoning</em>, cited: portal rather than merely fix, and
 * dismiss on capture-phase scroll.
 *
 * <h2>What it shows, and what the mock's peek asks for that it does not</h2>
 *
 * <p>{@code CardHoverPreview}'s Javadoc lists <b>eight</b> things it deliberately dropped. Plan §7
 * reconciles only the first. The rest, decided here:
 *
 * <ul>
 *   <li><b>Score bars — restored.</b> §7: in this arm the peek is the only route to the scores, and
 *       it fires off a strip the reader is already scanning, so the weight is earned where it was
 *       not before. §7's own tripwire stands: if the pilot reports the panel feeling like a glitch,
 *       the bars are the first thing back out.</li>
 *   <li><b>The generated-at timestamp — still out.</b> Stronger here than in the v1 arm: the rail
 *       footer states the forecast's age once for the whole screen, and §2.7's rule against marking
 *       one fact twice binds.</li>
 *   <li><b>The region line — still out.</b> The spot card prints the region itself, one element
 *       away.</li>
 *   <li><b>Tide detail — still out.</b> P7's attribute row carries the window's tide directly above
 *       the strip, and the payload has no per-spot tide to state anyway.</li>
 *   <li><b>The header bar and the ✕ — still out.</b> Both are modal furniture, and §7's tripwire is
 *       precisely that this must not read as modal weight. There is nothing for a header bar to hold:
 *       the panel closes on pointer-leave and on Escape, so a close button would be a control no
 *       assistive technology can reach and no pointer user needs.</li>
 *   <li><b>The footer bar — out; the prompt it held stays as prose.</b> Same call the v1 peek made,
 *       and the line is worded as the gain rather than the destination. The card underneath already
 *       says {@code ◍ Open on map →}, which names <em>where</em> the click goes; this says what it
 *       adds over the panel being read — the whole generated paragraph, not one clause.</li>
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
 * {@code frontend-test-standards.md:156} names this component's v1 sibling by name as the example of
 * the rule, and {@code WindowSpotStrip.test.jsx} pins it.
 *
 * @param {object}    props
 * @param {?number}   props.rating       1–5 stars, or null when unrated
 * @param {?number}   props.driveMinutes the caller's drive time, or null
 * @param {?number}   props.fierySky     Fiery Sky potential 0–100, or null
 * @param {?number}   props.goldenHour   Golden Hour potential 0–100, or null
 * @param {?string}   props.clause       one clause of Claude's sentence, already truncated —
 *        {@code resolveSpotPeek} truncates so that the gate is provably the thing rendered
 * @param {Object}    props.position     fixed-position coordinates, `{left, top}` or `{left, bottom}`
 * @param {string}    props.placement    'below' | 'above' — which side of the card it sits on
 * @param {number}    props.arrowLeft    arrow offset from the panel's left edge, in px
 * @param {Function}  props.onOpen       opens the map for this spot — the prompt's action
 * @param {Function}  props.onPointerEnter keeps the panel open while the pointer rests on it
 * @param {Function}  props.onPointerLeave starts the dismissal grace
 */
export default function WindowSpotPeek({
  rating = null,
  driveMinutes = null,
  fierySky = null,
  goldenHour = null,
  clause = null,
  position,
  placement = 'below',
  arrowLeft = 24,
  onOpen,
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
      {/* 1 · the rating, and what it costs to get there */}
      <div className="flex items-center" style={{ gap: '8px' }}>
        {/* Omitted entirely when the spot is unrated, never drawn as an empty scale. `☆☆☆☆☆` is the
            glyph for ZERO, and zero is a claim: the card this panel hangs off refuses to make it —
            `WindowSpotStrip.jsx` omits the badge rather than showing a dash, "an unrated spot is one
            nothing has looked at, which is a different statement from a poor one" — and every other
            field here already follows that rule (the drive chip below, each score bar). The state is
            reachable rather than theoretical: the score index is fetched once and the briefing polls,
            so a slot can carry a score with no usable rating, which is exactly the divergence
            `resolveSpotPeek` is written to expect.

            `--color-verdict-marginal`, NOT the `--color-marginal` the v1 peek names. That token is
            declared nowhere in `index.css` — grep finds it used and never defined — so
            `CardHoverPreview`'s stars have never been amber; they have always fallen back to
            inherited body ink, and looked plausible enough that nobody noticed. Caught by reading
            `getComputedStyle` on the running app, which returned the empty string. The v1 arm is
            frozen for the flag comparison so it is left alone and handed to P15; copying its bug into
            the new component is the one thing that was not an option. Measured on the running app:
            #E0A542 on this panel's #2A2019 is 7.30:1. */}
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
      </div>

      {/* 2 · the two scores — the part the card cannot show and the reason this panel is new.
          Each bar is conditional on its own value, so a slot scored on one axis and not the other
          draws one bar rather than a bar and a zero. */}
      {hasScores && (
        <div data-testid="wf-peek-scores" style={{ marginTop: '8px' }}>
          {fierySky != null && (
            <PeekScoreBar
              label="Fiery Sky"
              score={fierySky}
              testId="wf-peek-fiery"
              fill={FIERY_FILL}
            />
          )}
          {goldenHour != null && (
            <PeekScoreBar
              label="Golden Hour"
              score={goldenHour}
              testId="wf-peek-golden"
              fill={GOLDEN_FILL}
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
        Click for the full read + map →
      </p>
    </div>,
    document.body,
  );
}

WindowSpotPeek.propTypes = {
  rating: PropTypes.number,
  driveMinutes: PropTypes.number,
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
  onPointerEnter: PropTypes.func.isRequired,
  onPointerLeave: PropTypes.func.isRequired,
};
