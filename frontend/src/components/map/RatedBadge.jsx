import PropTypes from 'prop-types';
import { badgeText } from '../../utils/outcomeRatingJoin.js';

/**
 * The "you rated this" corner badge on a pin or a chip (outcome-rating-plan.md §2 #6, O4b):
 * {@code 3★}, or {@code –} for "didn't see it", drawn top-right on a PAST window only.
 *
 * <p>{@code aria-hidden}: its meaning joins the host button's accessible name
 * ({@code outcomeRatingJoin.badgeAccessibleClause}), because an {@code aria-label} replaces the
 * button's content and a screen reader would otherwise never hear it. It overhangs its host (top
 * -7px, right -9px, {@code .wf-rated-badge}), which is why a chip's measured rect grows by that
 * overhang when one is drawn ({@code utils/mapLabels.js#withBadgeOverhang}).
 *
 * @param {object} props
 * @param {number|string} props.value 1..5, or {@code 'NOT_SEEN'}
 */
export default function RatedBadge({ value }) {
  return (
    <span className="wf-rated-badge" data-testid="wf-rated-badge" aria-hidden="true">
      {badgeText(value)}
    </span>
  );
}

RatedBadge.propTypes = {
  value: PropTypes.oneOfType([PropTypes.number, PropTypes.string]).isRequired,
};
