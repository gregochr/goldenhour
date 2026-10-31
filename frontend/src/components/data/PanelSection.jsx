import { useId } from 'react';
import PropTypes from 'prop-types';
import { sectionLabelClass } from '../users/userFormClasses.js';

/**
 * One section of a Data panel: a mono uppercase label, an optional action on its right (Edit, Rename), then the
 * body. Padding is 14x18, 16 on a phone. Sections separate with a hairline.
 *
 * @param {object} props
 * @param {string} props.label the section's label (also its accessible name)
 * @param {import('react').ReactNode} [props.action] a control right-aligned on the label's row
 * @param {import('react').ReactNode} [props.children] the body
 * @param {string} [props.testId] the section's test id
 */
export default function PanelSection({ label, action = null, children = null, testId = undefined }) {
  const headingId = useId();
  return (
    <section
      aria-labelledby={headingId}
      className="border-b border-plex-border px-[18px] py-[14px] last:border-b-0 max-[639px]:px-4"
      data-testid={testId}
    >
      <div className="mb-2.5 flex min-h-5 items-center justify-between gap-3">
        <h3 id={headingId} className={sectionLabelClass}>{label}</h3>
        {action}
      </div>
      {children}
    </section>
  );
}

PanelSection.propTypes = {
  label: PropTypes.string.isRequired,
  action: PropTypes.node,
  children: PropTypes.node,
  testId: PropTypes.string,
};
