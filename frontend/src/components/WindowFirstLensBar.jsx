import React from 'react';
import PropTypes from 'prop-types';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { REACH_TIERS, formatLensCount, formatLensReadout } from '../utils/reachLens.js';
import { LENS_RATING_FLOORS } from '../utils/ratingLens.js';

/**
 * One segmented control — a caption and its chips, wrapped together so a wrap can never orphan one
 * from the other.
 *
 * <p>{@code aria-labelledby} pointing at the visible caption rather than an {@code aria-label} of
 * its own, and that is load-bearing on a bar whose captions change with the viewport. An
 * {@code aria-label} of "Drive time" over a visible "Drive from home" fails WCAG 2.5.3, because the
 * visible words are not in the accessible name; pointing at the caption makes the two the same
 * string by construction, at every width.
 *
 * <p><b>{@code aria-pressed} toggle buttons in a named group, not a radiogroup.</b> The handoff asks
 * for {@code role="radiogroup"} / {@code role="radio"}; this arm has shipped the toggle-group idiom
 * on this bar and on the drill-down's three controls since P8, and a radiogroup carries an APG
 * obligation the handoff does not mention — arrow-key roving with a single tab stop — which a
 * half-built one would announce and then not honour. What the handoff's bullet actually asks for is
 * satisfied either way: the group is named, the selection is exposed, and it does not rest on colour
 * alone (the 600 weight carries it). Recorded rather than silently skipped.
 */
function LensSegment({
  id, groupTestId, label, options, value, disabled, onSelect, className, segClassName = '',
}) {
  return (
    <div className={`wf-lens-group ${className}`} data-testid={groupTestId}>
      <span className="wf-lens-k" id={`${id}-label`}>{label}</span>
      <div
        className={`wf-seg${segClassName ? ` ${segClassName}` : ''}`}
        role="group"
        aria-labelledby={`${id}-label`}
        data-testid={id}
      >
        {options.map((option) => (
          <button
            key={option.id}
            type="button"
            data-testid={`${id}-option`}
            data-option={option.id}
            aria-pressed={option.id === value}
            disabled={disabled}
            onClick={() => onSelect(option.id)}
            className={`wf-seg-btn${option.id === value ? ' on' : ''}`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}

LensSegment.propTypes = {
  id: PropTypes.string.isRequired,
  groupTestId: PropTypes.string.isRequired,
  label: PropTypes.string.isRequired,
  options: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
  })).isRequired,
  value: PropTypes.string.isRequired,
  disabled: PropTypes.bool,
  onSelect: PropTypes.func.isRequired,
  className: PropTypes.string,
  /** Marks the axis, so the selected chip can take that axis's own colour. */
  segClassName: PropTypes.string,
};

/**
 * The global lens bar — three controls, applied to every window on the page.
 *
 * <h2>It is sticky, and it is never suppressed</h2>
 *
 * <p>Plan §2.5: "Do not suppress the lens bar — that makes a {@code position: sticky} element appear
 * per-user." A user with no home postcode gets the bar, the tiers and the readout; the gate simply
 * finds no drive time to act on and passes everything, which is the visible no-op rule 1 asks for
 * rather than a silently emptied page. The prompt to fix that lives in the masthead's tick line,
 * as the origin button's empty state since M3 — it was the rail footer's until that row went, and
 * design reserves the slot.
 *
 * <h2>The drive caption names its origin, never a time</h2>
 *
 * <p>It read {@code HOW FAR TONIGHT} until 2026-09-05, which is a claim this control does not
 * honour. The gate runs over <em>every</em> window the shell draws — up to six of them
 * ({@code PlanRenderLimits.MAX_VISIBLE_EVENTS}), so sunrise and sunset across three or four days —
 * and the readout on this same row says so out loud, in the "N spots across M windows" clause.
 * Tomorrow's sunrise card is gated by these chips exactly as tonight's sunset is.
 * {@code reachLens.defaultTierIdFor} had already written the mismatch down while explaining why
 * Friday counts as a weekday: "the bar is one control over up to six windows spanning four days".
 *
 * <p><b>What "tonight" was reaching for is the setting's lifetime, not the gate's scope.</b> Two
 * things here really are day-bound — the default tier is a pure function of today's date, and a
 * manual choice is discarded at the day roll — and for an unlocked reader the bar states both
 * already: the readout's default clause ({@code weekday default} / {@code weekend default}, or
 * {@code default here} away) and the amber {@code today only} pill. A LITE reader sees neither,
 * because {@code useReachLens} pins them to "Any" so nothing is in force to expire — which is why
 * the caption is the wrong place to carry the lifetime even for them. So it was spending its words
 * restating what the readout says, and getting the filter's reach wrong in exchange. Plan §6's
 * sweep polices the second half one element over — "no count describes a set that was never
 * filtered" — and §2.7 states the first half for the confidence channel; neither clause is written
 * about captions, so read this as their family rather than as either rule quoted.
 *
 * <p><b>The away caption never made the claim</b> ({@code Drive from Keswick}), so one control was
 * carrying two captions that disagreed about what it did, and the time claim vanished the moment a
 * reader picked an origin — without the gate changing at all. Naming the origin at both ends makes
 * them one sentence shape, and {@code WindowSpotSheet}'s inherited reach control was recaptioned
 * {@code Drive} in the same commit for the same reason: it seeds from this bar's tier and gates the
 * same axis through the same {@code gateSpotsByReach}, so leaving it on {@code How far} would have
 * made it the only reach control on the tab still speaking the retired vocabulary. That is
 * {@code Adversarial Review.html} charge c6 ("Reach is stated in three places") <em>extended</em>,
 * not applied: c6 ruled on where the reach <em>value</em> lives, and its "one source" remedy reads
 * as one source only while the two ends sound like one control. Its own clause about the drilldown
 * saying "widened for browsing" is unaffected — that sentence lives in the sheet's header, not in
 * this caption.
 *
 * <p><b>⚠️ The home arm is a literal where the away arm is data-driven, and that asymmetry is
 * deliberate — do not "fix" it with a third arm keyed on {@code reachMeasured}.</b> The away branch
 * renders only when an origin exists, so a reader with no saved postcode gets {@code DRIVE FROM
 * HOME} one row under the masthead's own {@code Set a postcode for light and drive times} nudge —
 * a from-point named by the caption while the page above asks them to create it. That looks like
 * the honesty rule CLAUDE.md states for reach, and is not it: that rule governs saying <em>"within
 * reach"</em>, a claim that a gate ran and produced a count, which is why {@code formatLensCount}
 * drops those two words when {@code reachMeasured} is false. A caption naming the origin a
 * measurement would be taken from asserts no result and no filtering — {@code Rated} makes no claim
 * that anything is rated either. The two rows agree rather than argue: both point at the same
 * missing input, where {@code HOW FAR TONIGHT} named something the reader already had and gave no
 * hint that a postcode was the piece in the way. Shortening to a bare {@code Drive} in that state
 * would take the hint back out and leave the chips inert with nothing on the bar explaining why —
 * which is, to be exact about it, what the phone already does at every width below 640px, postcode
 * or not. That is the room argument below winning over this one where the two collide, and it is
 * survivable because the masthead's nudge renders at both widths; it is not a reason to give up the
 * hint on the width that has room for it.
 *
 * <p>The swap costs no width: {@code .wf-lens-k} is {@code --font-mono} at a fixed letter-spacing
 * and both captions are fifteen characters, so the phone reasoning in the next section holds
 * exactly rather than approximately — and the phone caption itself is unchanged. Measured in the
 * browser against the real stylesheet on 2026-09-05: both render at 105.313px, and the bar's height
 * (53.5px) and overflow are unchanged at 1440px and in the 640–781px band the CSS comment flags.
 *
 * <h2>Two rows on a phone, and the label is what buys the room</h2>
 *
 * <p>The bar used to be a hidden-scrollbar horizontal scroller at every width, so on a 390px screen
 * the tiers past "1h 30min" were off the right edge with nothing saying so. The handoff's fix is
 * layout rather than affordance — "a fade would advertise a scroll that shouldn't need to exist" —
 * and the room comes from the caption: the full fifteen-character label — {@code HOW FAR TONIGHT}
 * when P14 measured this, {@code DRIVE FROM HOME} since, identical in width for the reason the
 * section above gives — eats about a third of a phone viewport inline, where {@code Drive} on its
 * own does not. Each control then takes a full row with {@code flex: 1} chips, and nothing
 * overflows at 320px.
 *
 * <p>The caption text switches in JS rather than by rendering both and hiding one, because
 * {@code aria-labelledby} resolves through {@code display: none} — two captions would put both
 * strings in one accessible name at every width. {@code useIsMobile} is the app's own 639px
 * boundary; the handoff says 620px, and a second breakpoint 19px away would be one more thing to
 * keep in step with {@code .wf-spots}, {@code .wf-lens} and the sheet for no gain.
 *
 * <h2>The readout says what both controls are set to, and the phone gets only the count</h2>
 *
 * <p>One element, two strings. The full summary restates the two chips and would be the widest thing
 * on a phone bar; the count line is the half that is not already legible from the pressed chips —
 * and it is the half that matters, because a short list has to read as "I asked for this" rather
 * than "tonight is bad".
 *
 * <h2>The override is marked twice, and both marks are cheap</h2>
 *
 * <p>An amber "today only" pill states the policy — the choice is discarded at the day roll — and a
 * reset button named for the default it returns to makes it one click to undo. Both key on the same
 * {@code overridden} flag the hook derives, so they cannot disagree, and both are absent while the
 * bar sits on its default. That is deliberately not the "marking the same fact twice" §2.7 warns
 * about: the pill is a statement and the button is an action, and a statement with no route back is
 * the shape this project has already had to fix once ("the setting appeared to do nothing").
 *
 * <p>Both belong to <em>reach</em> and neither has a rating twin, because nothing about the floor
 * expires: the {@code Any rating} chip is its own reset and is on screen whenever the floor is. They
 * sit between the two groups rather than inside the first, so that on a phone they wrap onto their
 * own row directly beneath the control they describe instead of squeezing four chips into a third of
 * a row.
 *
 * <h2>Role gating stops at reach</h2>
 *
 * <p>Plan §7 makes the reach bar a PRO control taking CLAUDE.md's LITE treatment. The rating floor
 * is <b>not</b> gated — it needs no per-user data and withholds nothing a role could not already
 * see, which is the same call {@code WindowSpotSheet} made for it at P11 and is what CLAUDE.md's
 * "breadcrumbs not paywalls" asks for. So a LITE reader gets a greyed drive row above a live rating
 * row, which is the honest picture of what they have.
 *
 * @param {object}   props
 * @param {object}   props.lens         the value from {@code useReachLens}
 * @param {object}   props.ratingLens   the value from {@code useRatingLens}
 * @param {number}   props.spotCount    spots drawn across every window, after both gates
 * @param {number}   props.reachedCount spots the rating floor chose from — the "of N" denominator
 * @param {number}   props.windowCount  windows drawn
 * @param {?string}  [props.originBase] the away origin's base town. Present, the drive caption
 *        names it — the design's {@code DRIVE FROM KESWICK} — because every figure the control
 *        gates is measured from there, and a caption still naming home would be the one thing this
 *        bar must not say. Null is home, and the caption names it.
 */
export default function WindowFirstLensBar({
  lens, ratingLens, spotCount, reachedCount = spotCount, windowCount, reachMeasured = true,
  originBase = null, stuck = false,
}) {
  const {
    tier, tierId, defaultTier, defaultTierId, weekend, overridden, locked, selectTier,
    resetToDefault, defaultFromOrigin,
  } = lens;
  const isMobile = useIsMobile();
  const count = formatLensCount({ spotCount, reachedCount, windowCount, reachMeasured });

  return (
    <div
      data-testid="window-first-lens"
      // The stuck treatment is a class rather than a `data-` attribute so it can be written as one
      // more rule in the `.wf-lens` block beside the geometry it modifies, which is where every
      // other state on this bar lives.
      className={`wf-lens${stuck ? ' wf-lens-stuck' : ''}`}
    >
      {/* The greying stops HERE, and the upsell below is deliberately outside it. WCAG 1.4.3
          exempts an inactive control from the contrast floor, which is what lets the tiers sit
          at 0.45 — but the "Pro" pill is not inactive, it is the call to action, and inside the
          wrapper it rendered at 3.68:1 against a 4.5:1 floor (measured on the running app;
          12.59:1 outside it). The now-deleted `HotTopicStrip` shipped its own upsell as a sibling
          of the blurred content for the same reason. */}
      <LensSegment
        id="window-first-lens-tiers"
        groupTestId="window-first-lens-controls"
        // Phone and desktop both name the base, because the base is the load-bearing half — a
        // phone caption reading "Drive" over figures measured from somewhere the reader has just
        // chosen would be the one width where the change is invisible. The switch stays in JS for
        // the reason the class comment gives: `aria-labelledby` resolves through `display: none`,
        // so rendering both and hiding one would put both strings in the accessible name.
        label={originBase
          ? (isMobile ? `From ${originBase}` : `Drive from ${originBase}`)
          : (isMobile ? 'Drive' : 'Drive from home')}
        options={REACH_TIERS}
        value={tierId}
        // A real `disabled` as well as the wrapper's `pointer-events: none`, because pointer-events
        // does not stop a keyboard.
        disabled={locked}
        onSelect={selectTier}
        className={`wf-lens-controls${locked ? ' wf-lens-locked' : ''}`}
      />

      {overridden && (
        <span data-testid="window-first-lens-temporary" className="wf-lens-stick">today only</span>
      )}
      {locked && (
        <span data-testid="window-first-lens-upsell" className="wf-lens-pro">Pro</span>
      )}
      {overridden && (
        <button
          type="button"
          data-testid="window-first-lens-reset"
          className="wf-lens-reset"
          onClick={resetToDefault}
        >
          {`Back to ${defaultTier.label}`}
        </button>
      )}

      {/* Decorative, and it is the one element the phone layout loses: with each control on its own
          full-width row there is no seam left for a rule to mark. */}
      <span className="wf-lens-rule" aria-hidden="true" />

      <LensSegment
        id="window-first-lens-floors"
        groupTestId="window-first-lens-rating"
        segClassName="wf-seg-rating"
        label="Rated"
        options={LENS_RATING_FLOORS}
        value={ratingLens.floorId}
        onSelect={ratingLens.selectFloor}
        className="wf-lens-rating"
      />

      <span
        data-testid="window-first-lens-readout"
        data-variant={isMobile ? 'count' : 'summary'}
        className={isMobile ? 'wf-lens-count' : 'wf-lens-res'}
      >
        {isMobile ? (
          count && <><b>{count.lead}</b>{` ${count.rest}`}</>
        ) : (
          // Wrapped, because `.wf-lens-res` is the flex BOX that right-aligns and this span is what
          // ellipsises — see the stylesheet for why those cannot be one element.
          <span>
            {formatLensReadout({
              tierLabel: tier.label,
              // The predicate itself, not `!overridden` — those differ for a locked control, which
              // is pinned to Any and has overridden false while sitting nowhere near the default.
              isDefault: tierId === defaultTierId,
              weekend,
              // Away the default is the ORIGIN's, so the readout must not call it the day's.
              originDefault: Boolean(defaultFromOrigin),
              ratingLabel: ratingLens.floor?.label,
              spotCount,
              reachMeasured,
              reachedCount,
              windowCount,
            })}
          </span>
        )}
      </span>
    </div>
  );
}

WindowFirstLensBar.propTypes = {
  lens: PropTypes.shape({
    tier: PropTypes.shape({ id: PropTypes.string, label: PropTypes.string }).isRequired,
    tierId: PropTypes.string.isRequired,
    defaultTier: PropTypes.shape({ label: PropTypes.string }).isRequired,
    defaultTierId: PropTypes.string.isRequired,
    weekend: PropTypes.bool.isRequired,
    /** True while the default on show is the origin's rather than the day's. */
    defaultFromOrigin: PropTypes.bool,
    overridden: PropTypes.bool.isRequired,
    locked: PropTypes.bool.isRequired,
    selectTier: PropTypes.func.isRequired,
    resetToDefault: PropTypes.func.isRequired,
  }).isRequired,
  ratingLens: PropTypes.shape({
    floor: PropTypes.shape({ id: PropTypes.string, label: PropTypes.string }),
    floorId: PropTypes.string.isRequired,
    selectFloor: PropTypes.func.isRequired,
  }).isRequired,
  /** The away origin's base town, or null at home. */
  originBase: PropTypes.string,
  spotCount: PropTypes.number.isRequired,
  /**
   * Whether ANY drive time exists for the reach tier to gate on — false for a reader with no home
   * postcode. Withholds the "within reach" wording from the readout, never the numbers. §6 clause 7:
   * "no count describes a set that was never filtered". Defaults true, the pre-M5 wording.
   */
  reachMeasured: PropTypes.bool,
  /** Defaults to {@code spotCount} — with no floor set the two are equal and no "of N" is drawn. */
  reachedCount: PropTypes.number,
  windowCount: PropTypes.number.isRequired,
  /** Whether the bar has left its resting place — the shell's sentinel answers it (M3). */
  stuck: PropTypes.bool,
};
