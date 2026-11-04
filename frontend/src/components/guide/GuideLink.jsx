import { useRef } from 'react';
import PropTypes from 'prop-types';
import { useGuide } from '../../context/GuideContext.jsx';
import { GUIDE_PAGES, GUIDE_SECTIONS } from '../../utils/guide.js';

/**
 * Resolves what a door opens: a {@link GUIDE_SECTIONS} key, or (for a page with no section) a
 * {@link GUIDE_PAGES} id. An unknown key opens nothing, so a typo can never invent a page.
 *
 * @param {?string} section a GUIDE_SECTIONS key
 * @param {?string} page a GUIDE_PAGES id, used when there is no section
 * @returns {?{page: string, section: string}}
 */
function resolveTarget(section, page) {
  if (section && Object.prototype.hasOwnProperty.call(GUIDE_SECTIONS, section)) return GUIDE_SECTIONS[section];
  if (page && Object.prototype.hasOwnProperty.call(GUIDE_PAGES, page)) return { page, section: '' };
  return null;
}

/**
 * Opens the guide at a target. Shared by {@link GuideLink} and {@link GuideWhy}: stops the press (both live in
 * clickable rows and cards) and names the button as the focus-return target, since Safari does not focus a
 * clicked button and the default (the element focused at press time) would be {@code <body>}.
 */
function useGuideDoor(section, page, returnFocus) {
  const { openGuide } = useGuide();
  const ref = useRef(null);
  const target = resolveTarget(section, page);
  function onClick(e) {
    e.stopPropagation();
    if (!target) return;
    openGuide(target.page, target.section, { returnFocus: returnFocus ?? (() => ref.current) });
  }
  return { ref, onClick };
}

/**
 * The in-context guide door: a 24px round {@code ?} with a 44px hit area (docs/engineering/user-guide-plan.md,
 * G3). It opens the guide at {@code GUIDE_SECTIONS[section]}. The click stops propagating because the door
 * lives inside clickable rows and cards, and inside Map panels whose own outside-press rules must not see it
 * as a press on the map.
 *
 * @param {object} props
 * @param {string} props.section a GUIDE_SECTIONS key
 * @param {string} props.label the accessible name and tooltip (what the door explains)
 * @param {string} [props.className] extra classes for placement
 * @param {string} [props.testId] overrides the default {@code guide-link} test id
 */
export default function GuideLink({
  section, label, className = '', testId = 'guide-link',
}) {
  const { ref, onClick } = useGuideDoor(section, null, null);
  return (
    <button
      ref={ref}
      type="button"
      className={`wf-guide-link ${className}`.trim()}
      aria-label={label}
      title={label}
      data-testid={testId}
      onClick={onClick}
    >
      <span aria-hidden="true">?</span>
    </button>
  );
}

GuideLink.propTypes = {
  section: PropTypes.string.isRequired,
  label: PropTypes.string.isRequired,
  className: PropTypes.string,
  testId: PropTypes.string,
};

/**
 * The text twin of {@link GuideLink} for the Lite locks: "What does this do?", the same colour, underlined on
 * hover. It is rendered only where the reader is locked out (the caller's existing {@code locked} or
 * {@code !isPro} term), never from a role read of its own.
 *
 * @param {object} props
 * @param {string} [props.section] a GUIDE_SECTIONS key
 * @param {string} [props.page] a GUIDE_PAGES id, for a target with no section (Ask)
 * @param {function(): ?HTMLElement} [props.returnFocus] where focus goes when the guide closes; an upsell popover
 *        passes its trigger, because the popover (and this button) is gone by then
 * @param {string} [props.className] extra classes for placement
 * @param {string} [props.testId] overrides the default {@code guide-why} test id
 */
export function GuideWhy({
  section = undefined, page = undefined, returnFocus = undefined, className = '', testId = 'guide-why',
}) {
  const { ref, onClick } = useGuideDoor(section, page, returnFocus);
  return (
    <button
      ref={ref}
      type="button"
      className={`wf-guide-why ${className}`.trim()}
      data-testid={testId}
      onClick={onClick}
    >
      What does this do?
    </button>
  );
}

GuideWhy.propTypes = {
  section: PropTypes.string,
  page: PropTypes.string,
  returnFocus: PropTypes.func,
  className: PropTypes.string,
  testId: PropTypes.string,
};
