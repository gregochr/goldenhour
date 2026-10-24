import PropTypes from 'prop-types';
import { GLANCE_LABEL, GLANCE_SHORT_LABEL } from '../utils/tabLabels.js';

/**
 * The At a Glance tab's name as a pair of CSS-switched spans ("At a Glance" from 640px, "Glance"
 * below it), the house pattern from {@code AskPlanThis}. One span is `display: none` at a time, so
 * the accessible name is always the visible word (WCAG 2.5.3) and no JS width switch is needed.
 *
 * @param {Object} props
 * @param {string} [props.className] extra classes for the wrapping span
 */
export default function GlanceLabel({ className }) {
  return (
    <span className={className} data-testid="glance-label">
      <span className="hidden sm:inline" data-testid="glance-label-long">{GLANCE_LABEL}</span>
      <span className="sm:hidden" data-testid="glance-label-short">{GLANCE_SHORT_LABEL}</span>
    </span>
  );
}

GlanceLabel.propTypes = {
  className: PropTypes.string,
};

GlanceLabel.defaultProps = {
  className: undefined,
};
