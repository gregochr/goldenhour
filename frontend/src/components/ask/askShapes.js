import PropTypes from 'prop-types';

/**
 * PropTypes shapes the Ask surfaces share — one spelling each, so a field added to a pick card or a
 * door added to the plan view is a one-place edit and the hosts cannot drift apart
 * (`docs/engineering/ask-photocast-plan.md` §2.5–2.8).
 */

/** A DOM ref a surface hands down: a callback ref or a ref object (React 19 accepts either). */
export const refShape = PropTypes.oneOfType([
  PropTypes.func,
  PropTypes.shape({ current: PropTypes.any }),
]);

/** A ref OBJECT only — for a node the receiver reads {@code .current} from and cannot take as a callback. */
export const objectRefShape = PropTypes.shape({ current: PropTypes.any });

/**
 * The doors out of "Plan this" (F5), which the SURFACE owns: "Open in Plan ›" and the postcode
 * nudge. A control whose door is absent is not drawn.
 */
export const planActionsShape = PropTypes.shape({
  openInPlan: PropTypes.func,
  setPostcode: PropTypes.func,
});

/**
 * One element of {@code buildPickCards}' result — the pick card's and the plan view's input. Only the
 * fields a view reads are listed; the rest of the card rides along untouched.
 */
export const pickCardShape = PropTypes.shape({
  rank: PropTypes.number.isRequired,
  name: PropTypes.string.isRequired,
  targetType: PropTypes.string.isRequired,
  dayWord: PropTypes.string.isRequired,
  eventTime: PropTypes.string,
  // Null on a night card: a night has no verdict word (the kind and the stars stand in its place).
  verdict: PropTypes.string,
  verdictLabel: PropTypes.string,
  rating: PropTypes.number,
  driveLabel: PropTypes.string,
  why: PropTypes.string,
  summary: PropTypes.string,
  label: PropTypes.string.isRequired,
  tide: PropTypes.shape({
    tier: PropTypes.oneOf(['match', 'miss']).isRequired,
    state: PropTypes.string,
    shortfall: PropTypes.string,
    clause: PropTypes.string,
  }),
  // A night card's own facts (`docs/engineering/ask-night-plan.md` §2.6); absent on a solar one.
  night: PropTypes.bool,
  kind: PropTypes.oneOf(['astro', 'aurora']),
  nightWord: PropTypes.string,
  nightWindow: PropTypes.string,
  astroStars: PropTypes.number,
  auroraStars: PropTypes.number,
  bortle: PropTypes.number,
  moon: PropTypes.string,
  clearSky: PropTypes.string,
});
