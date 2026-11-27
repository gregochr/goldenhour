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

/** The heading word. This week's bar also carries the week's best and the help `?`, as siblings of the heading. */
export const THIS_WEEK_HEADING = 'This week';

/**
 * Scrolls a section to the top of the screen and puts focus on its heading, the one move every "go to this section"
 * door makes. The SECTION is scrolled, not the heading: the heading sits in a sticky bar, and scrolling a sticky
 * element aims at where it happens to be stuck now, not at where its section starts. The section's own
 * {@code scroll-margin-top} (index.css) keeps its top clear of the lens bar. {@code preventScroll} on the focus because
 * the scroll has been made.
 *
 * @param {string} headingId a heading id from this module
 * @returns {boolean} whether the heading was found
 */
export function focusSectionHeading(headingId) {
  const heading = document.getElementById(headingId);
  if (!heading) return false;
  (heading.closest('section') ?? heading).scrollIntoView?.({ block: 'start' });
  heading.focus({ preventScroll: true });
  return true;
}
