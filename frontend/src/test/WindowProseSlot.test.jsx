import React from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import WindowProseSlot from '../components/WindowProseSlot.jsx';

/**
 * The popup's prose slot — the one place on the window that explains rather than measures.
 *
 * <h2>What this file inherits, and what it drops</h2>
 *
 * <p>It replaces {@code WindowRegionBand.test.jsx}, whose component was the accordion row's
 * drill-down band and died with the row at M2. Salvaged by behaviour: the narrative is the
 * payload's own sentence <b>verbatim</b>, its absence is a <b>stated</b> state rather than an empty
 * box, and the null line points at the region's own best window only when that is a different one.
 * The band's served figures and its six-dot window strip are not here and are not lost — the region
 * cards a few pixels above carry {@code best 5★ · 10 in reach}, and stepping windows is the popup's
 * own {@code ‹ n/6 ›} nav. Restating either under the prose is the "quality said four times" this
 * redesign exists to cure.
 *
 * <h2>The rule this file exists for</h2>
 *
 * <p>⚠️ A21. There is <b>no served window-level prose</b>: the design's fixture has one, the
 * codebase's {@code card.lead} is an unrelated boolean, and inventing a sentence is the thing plan
 * §3 rule 6 forbids. So the unpicked slot renders the TOP REGION's prose and labels it as that
 * region's — the assertions below are what stops a later refactor presenting one region's sentence
 * as a statement about six.
 */

afterEach(cleanup);

const NARRATIVE = 'A clean eastern horizon with high cloud to catch the light.';

function row(overrides = {}) {
  return {
    name: 'Northumberland & Tyneside',
    verdict: 'WORTH_IT',
    summary: NARRATIVE,
    glossHeadline: null,
    glossDetail: null,
    bestRating: 5,
    ...overrides,
  };
}

const renderSlot = (props = {}) => render(
  <WindowProseSlot row={row()} picked={false} {...props} />,
);

describe('WindowProseSlot — whose words these are', () => {
  it('⚠️ labels the unpicked state as the TOP REGION’s, never as the window’s', () => {
    // A21 in one assertion. The window's verdict IS its top region's, so the region's gloss is the
    // honest nearest-served prose — but presenting it as a statement about every region in scope
    // would be a claim the payload never made.
    renderSlot();
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Northumberland & Tyneside');
    expect(screen.getByTestId('wf-prose-kicker')).toHaveTextContent('the window’s top region');
  });

  it('drops the label once a region is picked, because the name then means what it says', () => {
    renderSlot({ picked: true });
    expect(screen.queryByTestId('wf-prose-kicker')).toBeNull();
  });

  it('carries the region’s verdict as an attribute, which is what the left border hangs off', () => {
    renderSlot({ row: row({ verdict: 'MAYBE' }) });
    expect(screen.getByTestId('wf-prose')).toHaveAttribute('data-verdict', 'MAYBE');
  });
});

describe('WindowProseSlot — the prose, in one order', () => {
  it('renders the served summary verbatim, unshortened', () => {
    renderSlot();
    expect(screen.getByTestId('wf-prose-body')).toHaveTextContent(NARRATIVE);
  });

  it('prefers Claude’s own gloss to the summary, and leads with the headline', () => {
    // The pair `HeatmapGrid` already prefers in its hover tip, so both flag arms read one payload
    // the same way round.
    renderSlot({
      row: row({ glossHeadline: 'Cirrus canvas', glossDetail: 'Thin high cloud at 40%.' }),
    });
    const body = screen.getByTestId('wf-prose-body');
    expect(body).toHaveTextContent('Cirrus canvas Thin high cloud at 40%.');
    expect(screen.getByTestId('wf-prose-lead')).toHaveTextContent('Cirrus canvas');
    expect(body).not.toHaveTextContent(NARRATIVE);
  });

  it('renders a lone headline as the prose rather than as a lead with nothing under it', () => {
    renderSlot({ row: row({ glossHeadline: 'Cirrus canvas', summary: null }) });
    expect(screen.getByTestId('wf-prose-body')).toHaveTextContent('Cirrus canvas');
    expect(screen.queryByTestId('wf-prose-lead')).toBeNull();
  });

  it('renders a lone detail as the prose, on the same rule', () => {
    renderSlot({ row: row({ glossDetail: 'Thin high cloud at 40%.', summary: null }) });
    expect(screen.getByTestId('wf-prose-body')).toHaveTextContent('Thin high cloud at 40%.');
    expect(screen.queryByTestId('wf-prose-lead')).toBeNull();
  });
});

describe('WindowProseSlot — the null path is a named state, never blankness', () => {
  it('states the absence rather than leaving the box empty', () => {
    // An empty box reads as a rendering failure, and a reader cannot tell it from prose that failed
    // to load — which is why the design specifies a line for it.
    renderSlot({ row: row({ summary: null }) });
    expect(screen.queryByTestId('wf-prose-body')).toBeNull();
    expect(screen.getByTestId('wf-prose-none'))
      .toHaveTextContent('Nothing written about this one yet.');
  });

  it('points at the region’s own best window when a different one is better', () => {
    renderSlot({
      row: row({ summary: null }),
      picked: true,
      bestWindow: { key: '2026-08-05:SUNRISE', label: 'Tomorrow sunrise', time: '05:20' },
      isCurrentWindow: false,
    });
    expect(screen.getByTestId('wf-prose-none'))
      .toHaveTextContent('This region’s own best is tomorrow sunrise 05:20.');
  });

  it('points at no other window when THIS one is the region’s best', () => {
    // "Its own best is tonight" on the window already open is a sentence pointing at itself.
    renderSlot({
      row: row({ summary: null }),
      picked: true,
      bestWindow: { key: '2026-08-04:SUNSET', label: 'Tonight Sunset', time: '21:11' },
      isCurrentWindow: true,
    });
    expect(screen.getByTestId('wf-prose-none')).not.toHaveTextContent('own best');
  });

  it('points at no other window when the region is scored in none of them', () => {
    renderSlot({ row: row({ summary: null }), picked: true, bestWindow: null });
    expect(screen.getByTestId('wf-prose-none')).not.toHaveTextContent('own best');
  });
});

describe('WindowProseSlot — what the picked header adds', () => {
  it('states this region’s own movement, with its direction spelled out', () => {
    // The glyph is the whole of the visible value, so the spoken form carries the meaning — and the
    // verb is "at last run", never "since": the delta is measured from the build BEFORE the last.
    renderSlot({ picked: true, row: row({ meanRatingDelta: 0.6 }) });
    const chip = screen.getByTestId('wf-prose-moved');
    expect(screen.getByTestId('wf-prose-moved-mark')).toHaveTextContent('▲0.6');
    expect(screen.getByTestId('wf-prose-moved-mark')).toHaveAttribute('data-tone', 'up');
    expect(chip).toHaveTextContent('up 0.6 stars at last run');
    expect(chip).not.toHaveTextContent('since');
  });

  it('states a MEASURED zero as the flat mark', () => {
    renderSlot({ picked: true, row: row({ meanRatingDelta: 0 }) });
    expect(screen.getByTestId('wf-prose-moved-mark')).toHaveTextContent('—');
    expect(screen.getByTestId('wf-prose-moved-mark')).toHaveAttribute('data-tone', 'flat');
  });

  it('⚠️ omits the movement entirely when the payload carries no delta', () => {
    // Null is silence, never a `—`: a dash would claim a measured stillness where there is no basis.
    renderSlot({ picked: true, row: row({ meanRatingDelta: null }) });
    expect(screen.queryByTestId('wf-prose-moved')).toBeNull();
  });

  it('never shows movement in the unpicked state, where the header names a region nobody chose', () => {
    renderSlot({ picked: false, row: row({ meanRatingDelta: 0.6 }) });
    expect(screen.queryByTestId('wf-prose-moved')).toBeNull();
  });

  it('⚠️ states no count of the region’s own locations, in EITHER state', () => {
    // Deleted at M5's copy sweep — a recorded deviation from the plan's §5, not a trim, and the
    // component header carries the measurement. In short: with a region picked the popup printed the
    // same integer three times inside ~250px against three different wholes (`9 of its locations
    // below` here, `across 69 locations` in the served prose under it, `9 of 209` in the strip's
    // footer), and this was the only one of the three whose denominator a reader had to guess.
    //
    // Asserted as an absence of the WORDS rather than of the testid: a testid check passes just as
    // well against a chip that was renamed as against one that was removed.
    // ⚠️ Asserted as "no count of this region's places AT ALL", not as the absence of one phrase.
    // Keyed on `/of its location/` alone, a re-add worded "9 locations below" or "9 of 69" would
    // pass — and the rule being defended is that this slot states no such count in any wording,
    // because the strip's footer states one 250px lower against a different whole.
    const noCount = (node) => {
      expect(node.textContent).not.toMatch(/of its location/i);
      expect(node.textContent).not.toMatch(/\d+\s*(of|locations?|places?|below)/i);
    };
    const picked = renderSlot({ picked: true });
    noCount(screen.getByTestId('wf-prose'));
    picked.unmount();
    renderSlot({ picked: false });
    noCount(screen.getByTestId('wf-prose'));
  });
});

describe('WindowProseSlot — the box never moves', () => {
  // ⚠️ The whole reason the element exists: picking a region swaps words and repaints the field,
  // and if the box changed height the tide row and the ranked locations below would move on every
  // pick and every clear. The min-height itself is a CSS claim (`.wf-prose`, browser-verified);
  // what a jsdom test CAN pin is that all three states render the SAME node with the same class, so
  // no state can be given a different box by accident.
  it('renders one node, one class, in every state', () => {
    const states = [
      { picked: false },
      { picked: true, row: row({ meanRatingDelta: -0.3 }) },
      { picked: true, row: row({ summary: null }) },
    ];
    for (const props of states) {
      const view = render(<WindowProseSlot row={row()} {...props} />);
      const slot = screen.getByTestId('wf-prose');
      expect(slot).toHaveClass('wf-prose');
      expect(slot.tagName).toBe('DIV');
      view.unmount();
    }
  });
});
