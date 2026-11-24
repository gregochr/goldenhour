import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  FURTHER_AHEAD_HEADING_ID, FURTHER_AHEAD_ID, THIS_WEEK_HEADING, THIS_WEEK_HEADING_ID, THIS_WEEK_ID,
  THIS_WEEK_SUBHEADING, focusSectionHeading,
} from '../utils/screenSections.js';

/** The two sections' ids and the one "go to this heading" move every door makes (one-screen-plan.md O2, D11). */
afterEach(() => {
  document.body.innerHTML = '';
  delete Element.prototype.scrollIntoView;
});

describe('screenSections', () => {
  it('has four distinct ids and the two heading words', () => {
    expect(new Set([THIS_WEEK_ID, THIS_WEEK_HEADING_ID, FURTHER_AHEAD_ID, FURTHER_AHEAD_HEADING_ID]).size).toBe(4);
    expect(`${THIS_WEEK_HEADING} · ${THIS_WEEK_SUBHEADING}`).toBe('This week · forecast');
  });

  it('scrolls the heading to the top and focuses it without a second scroll', () => {
    document.body.innerHTML = `<h2 id="${FURTHER_AHEAD_HEADING_ID}" tabindex="-1">Further ahead</h2>`;
    const heading = document.getElementById(FURTHER_AHEAD_HEADING_ID);
    Element.prototype.scrollIntoView = vi.fn();
    const focus = vi.spyOn(heading, 'focus');

    expect(focusSectionHeading(FURTHER_AHEAD_HEADING_ID)).toBe(true);

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'start' });
    expect(focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(heading).toHaveFocus();
  });

  it('still focuses where there is no scrollIntoView (jsdom, an old engine)', () => {
    document.body.innerHTML = `<h2 id="${THIS_WEEK_HEADING_ID}" tabindex="-1">This week</h2>`;
    expect(focusSectionHeading(THIS_WEEK_HEADING_ID)).toBe(true);
    expect(document.getElementById(THIS_WEEK_HEADING_ID)).toHaveFocus();
  });

  it('reports a heading that is not on the page and does nothing', () => {
    expect(focusSectionHeading('no-such-heading')).toBe(false);
  });
});
