import React from 'react';
import PropTypes from 'prop-types';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { REACH_TIERS, formatLensCount, formatLensReadout } from '../utils/reachLens.js';
import { LENS_RATING_FLOORS } from '../utils/ratingLens.js';
import { PLAN_ORDERS } from '../utils/windowFirstOrder.js';

/**
 * One segmented control — a caption and its chips, wrapped together so a wrap can never orphan one
 * from the other.
 *
 * <p>{@code aria-labelledby} pointing at the visible caption rather than an {@code aria-label} of
 * its own, and that is load-bearing on a bar whose captions change with the viewport. An
 * {@code aria-label} of "Drive time" over a visible "How far tonight" fails WCAG 2.5.3, because the
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
 * rather than a silently emptied page. The prompt to fix that lives in the rail footer, where the
 * design reserves the slot.
 *
 * <h2>Two rows on a phone, and the label is what buys the room</h2>
 *
 * <p>The bar used to be a hidden-scrollbar horizontal scroller at every width, so on a 390px screen
 * the tiers past "1h 30min" were off the right edge with nothing saying so. The handoff's fix is
 * layout rather than affordance — "a fade would advertise a scroll that shouldn't need to exist" —
 * and the room comes from the caption: {@code HOW FAR TONIGHT} eats about a third of a phone
 * viewport inline, where {@code Drive} on its own does not. Each control then takes a full row with
 * {@code flex: 1} chips, and nothing overflows at 320px.
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
 * @param {object}   props.orderLens    the value from {@code usePlanOrder}
 * @param {number}   props.spotCount    spots drawn across every window, after both gates
 * @param {number}   props.reachedCount spots the rating floor chose from — the "of N" denominator
 * @param {number}   props.windowCount  windows drawn
 */
export default function WindowFirstLensBar({
  lens, ratingLens, orderLens, spotCount, reachedCount = spotCount, windowCount,
}) {
  const {
    tier, tierId, defaultTier, defaultTierId, weekend, overridden, locked, selectTier,
    resetToDefault,
  } = lens;
  const isMobile = useIsMobile();
  const count = formatLensCount({ spotCount, reachedCount, windowCount });

  return (
    <div data-testid="window-first-lens" className="wf-lens">
      {/* The greying stops HERE, and the upsell below is deliberately outside it. WCAG 1.4.3
          exempts an inactive control from the contrast floor, which is what lets the tiers sit
          at 0.45 — but the "Pro" pill is not inactive, it is the call to action, and inside the
          wrapper it rendered at 3.68:1 against a 4.5:1 floor (measured on the running app;
          12.59:1 outside it). `HotTopicStrip` already ships its upsell as a sibling of the
          blurred content for the same reason. */}
      <LensSegment
        id="window-first-lens-tiers"
        groupTestId="window-first-lens-controls"
        label={isMobile ? 'Drive' : 'How far tonight'}
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

      {/* The third axis, and the only one that is not a FILTER — which is why it sits last and
          takes its own accent. Reach and Rated remove spots; Order removes nothing and re-ranks the
          window cards. Putting it first would read as another gate, and the readout beside it
          counts what the two gates left, not what this one moved.

          ⚠️ It re-orders the CARDS and never the strip above. That is enforced by construction
          rather than by care here — the strip is built from `upcomingEvents` and this value never
          reaches it — but it is the design's own headline note about the control, so it is worth
          knowing at the site that offers it. */}
      <LensSegment
        id="window-first-lens-orders"
        groupTestId="window-first-lens-order"
        segClassName="wf-seg-order"
        label="Order"
        options={PLAN_ORDERS}
        value={orderLens.orderId}
        onSelect={orderLens.selectOrder}
        className="wf-lens-order"
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
              ratingLabel: ratingLens.floor?.label,
              spotCount,
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
  /** The value from {@code usePlanOrder} — the pane's ordering axis, not a filter. */
  orderLens: PropTypes.shape({
    orderId: PropTypes.string.isRequired,
    selectOrder: PropTypes.func.isRequired,
  }).isRequired,
  spotCount: PropTypes.number.isRequired,
  /** Defaults to {@code spotCount} — with no floor set the two are equal and no "of N" is drawn. */
  reachedCount: PropTypes.number,
  windowCount: PropTypes.number.isRequired,
};
