/**
 * `components/map/WindowControl.jsx` — the PAST pill (outcome-rating-plan.md §2 #2, §3 O3a step 4).
 *
 * A past window (`row.past === true`, built by `utils/mapEvents.js`) is `served: false` and so has no
 * verdict, tint or tick. The pill states what it IS with a `Past` tag where the verdict word would
 * sit — on the dropdown pill and on the phone override pill alike, since both are the one element —
 * and the steppers name a past neighbour "past" in words, as they name a served one by its verdict.
 *
 * jsdom applies no stylesheet, so the tag's look is pinned by reading the real `index.css` rule.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import WindowControl from '../components/map/WindowControl.jsx';

function row(overrides = {}) {
  return {
    id: 'solar:2026-10-15:SUNSET',
    kind: 'solar',
    eventType: 'SUNSET',
    date: '2026-10-15',
    label: 'Today sunset',
    dayLabel: 'Today',
    time: '17:42',
    bestRating: 4,
    scored: true,
    served: true,
    badges: [],
    ...overrides,
  };
}

const PAST_YESTERDAY = row({
  id: 'solar:2026-10-14:SUNSET', date: '2026-10-14', past: true, served: false, scored: false,
  bestRating: null, label: 'Yesterday sunset', dayLabel: 'Yesterday', time: '17:44',
});
const PAST_TODAY_SUNRISE = row({
  id: 'solar:2026-10-15:SUNRISE', eventType: 'SUNRISE', past: true, served: false, scored: false,
  bestRating: null, label: 'Today sunrise', dayLabel: 'Today', time: '07:12',
});
const LIVE = row();
const EVENTS = [PAST_YESTERDAY, PAST_TODAY_SUNRISE, LIVE];

// A STALE verdict is deliberately supplied for the past rows too (a real list never has one — `buildEvVerdicts`
// skips `served: false` — but this is what makes the replacement observable: if the Past branch were
// \`<Past/><VerdictCell/>\`, or the pill still took its tier, these would show).
const VERDICTS = new Map([
  [LIVE.id, { tier: 'WORTH_IT' }],
  [PAST_YESTERDAY.id, { tier: 'MAYBE' }],
  [PAST_TODAY_SUNRISE.id, { tier: 'MAYBE' }],
]);

function renderControl(overrides = {}) {
  return render(
    <WindowControl
      events={EVENTS}
      activeIndex={1}
      onSelect={vi.fn()}
      verdicts={VERDICTS}
      {...overrides}
    />,
  );
}

describe('WindowControl — a PAST window', () => {
  it('wears a Past tag where a verdict would be, and draws no verdict cell', () => {
    renderControl({ activeIndex: 1 });
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toContainElement(screen.getByTestId('wf-win-past'));
    expect(screen.getByTestId('wf-win-past')).toHaveTextContent('Past');
    expect(screen.getByTestId('wf-win-past')).toHaveClass('wf-win-past');
    expect(screen.queryByTestId('wf-win-verdict')).not.toBeInTheDocument();
  });

  it('names the window "past" in words in the pill\'s accessible name', () => {
    renderControl({ activeIndex: 1 });
    expect(screen.getByRole('button', { name: /Sunrise\s*Today\s*07:12\s*Past/ }))
      .toBe(screen.getByTestId('wf-win-pill'));
  });

  it('reads the past label and time of the row, not a served one\'s', () => {
    renderControl({ activeIndex: 0 });
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toHaveTextContent('Yesterday');
    expect(pill).toHaveTextContent('17:44');
    expect(pill).toHaveTextContent('Past');
  });

  it('control: a served row still shows its verdict and no Past tag', () => {
    renderControl({ activeIndex: 2 });
    expect(screen.getByTestId('wf-win-verdict')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-win-past')).not.toBeInTheDocument();
  });

  it('draws the same tag on the phone override pill (it is the one pill element)', () => {
    renderControl({
      activeIndex: 0,
      pillOverride: { onPress: vi.fn(), expanded: false, controlsId: 'peek-windows' },
    });
    expect(screen.getByTestId('wf-win-pill')).toContainElement(screen.getByTestId('wf-win-past'));
  });

  it('draws no tint on the pill — a past window has no tier', () => {
    renderControl({ activeIndex: 1 });
    expect(screen.getByTestId('wf-win-pill')).not.toHaveAttribute('data-tier');
  });
});

describe('WindowControl — steppers beside a past window', () => {
  it('names a past previous neighbour "past" in the stepper\'s accessible name', () => {
    renderControl({ activeIndex: 1 });
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Previous event, past');
  });

  it('names a served next neighbour by its verdict as before', () => {
    renderControl({ activeIndex: 1 });
    expect(screen.getByTestId('wf-win-next')).toHaveAccessibleName('Next event, Worth it');
  });

  it('names a past NEXT neighbour "past" too', () => {
    renderControl({ events: [LIVE, PAST_TODAY_SUNRISE], activeIndex: 0, verdicts: VERDICTS });
    expect(screen.getByTestId('wf-win-next')).toHaveAccessibleName('Next event, past');
  });

  it('a disabled stepper keeps its bare name even beside a past row', () => {
    renderControl({ activeIndex: 0 });
    expect(screen.getByTestId('wf-win-prev')).toBeDisabled();
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Previous event');
  });
});

describe('WindowControl — a past row in the open dropdown', () => {
  it('says "Past" in the score cell instead of the unscored dash, so the row is not an unmarked filler', () => {
    renderControl({ activeIndex: 2 });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-win-row');
    const pastRow = rows.find((r) => r.getAttribute('data-ev-id') === PAST_YESTERDAY.id);
    const liveRow = rows.find((r) => r.getAttribute('data-ev-id') === LIVE.id);
    expect(within(pastRow).getByTestId('wf-win-row-past')).toHaveTextContent('Past');
    expect(pastRow.textContent).not.toMatch(/—/);
    expect(within(liveRow).queryByTestId('wf-win-row-past')).not.toBeInTheDocument();
  });

  it('draws no verdict tick on the stepper beside a past window, even when a verdict is held for it', () => {
    renderControl({ activeIndex: 2 });
    expect(screen.queryByTestId('wf-win-tick')).not.toBeInTheDocument();
  });
});

describe('.wf-win-past — the design\'s .wpast on existing tokens', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '');
  const match = css.match(/\.wf-win-past\s*\{([^}]*)\}/);
  const body = match ? match[1] : '';
  const decl = (prop) => {
    const m = body.match(new RegExp(`(?:^|;|\\s)${prop}\\s*:\\s*([^;]+);`));
    return m ? m[1].trim() : null;
  };

  it('has a rule', () => {
    expect(match).not.toBeNull();
  });

  it('is mono 9.5px, uppercase, .08em tracking', () => {
    expect(decl('font-family')).toBe('var(--font-mono)');
    expect(decl('font-size')).toBe('9.5px');
    expect(decl('letter-spacing')).toBe('0.08em');
    expect(decl('text-transform')).toBe('uppercase');
  });

  it('is ink-3 with a 1px border-light, radius 4, padding 1px 5px — each a token where one exists', () => {
    expect(decl('color')).toBe('var(--color-plex-text-muted)');
    expect(decl('border')).toBe('1px solid var(--color-plex-border-light)');
    expect(decl('border-radius')).toBe('4px');
    expect(decl('padding')).toBe('1px 5px');
  });

  it('does not shrink: a flex item beside a label that yields must not clip to "PA"', () => {
    expect(decl('flex')).toBe('none');
    expect(decl('white-space')).toBe('nowrap');
  });
});
