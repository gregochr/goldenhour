/**
 * The element ids of the forecast screen's two sections (docs/engineering/one-screen-plan.md O2), shared by the shell
 * that renders This week, {@code FurtherAhead} and the doors that scroll to or focus them. Each section is a
 * {@code <section aria-labelledby>} under a sticky {@code <h2>}; the heading is also the section's focus target
 * ({@code tabIndex=-1}), where a retired tab used to take focus.
 */
export const THIS_WEEK_ID = 'wf-this-week';
export const THIS_WEEK_HEADING_ID = 'wf-this-week-h';
export const FURTHER_AHEAD_ID = 'wf-further-ahead';
export const FURTHER_AHEAD_HEADING_ID = 'wf-further-ahead-h';

/** The heading words. This week reads "forecast", Further ahead "dates known in advance". */
export const THIS_WEEK_HEADING = 'This week';
export const THIS_WEEK_SUBHEADING = 'forecast';

/**
 * Scrolls a section heading to the top of the screen and puts focus on it, the one move every "go to this section"
 * door makes. {@code preventScroll} on the focus because {@code scrollIntoView} has just placed it, and the heading's
 * {@code scroll-margin-top} (index.css) keeps it clear of the sticky stack.
 *
 * @param {string} headingId a heading id from this module
 * @returns {boolean} whether the heading was found
 */
export function focusSectionHeading(headingId) {
  const heading = document.getElementById(headingId);
  if (!heading) return false;
  heading.scrollIntoView?.({ block: 'start' });
  heading.focus({ preventScroll: true });
  return true;
}
