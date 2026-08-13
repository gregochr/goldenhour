/**
 * Tests for which chip the date strip calls "Today".
 *
 * The strip labels a chip, marks it `data-today`, and draws the past/future divider from it — all
 * from one date. That date used to be the UTC one while the rest of the map read the browser's
 * local date, so for the hour after UK midnight under BST the strip named yesterday's chip "Today"
 * and dimmed the real one as past.
 *
 * ⚠️ TIMEZONE AND CLOCK ARE BOTH PINNED. Nothing in this repo pins TZ (this Mac is Europe/London,
 * CI runners are UTC) and the disagreement being asserted exists only under BST, so on an unpinned
 * runner these would pass while proving nothing. Verified to survive `TZ=UTC` in the environment.
 */
process.env.TZ = 'Europe/London';

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import DateStrip from '../components/DateStrip.jsx';

/** 00:30 UK on 14 Aug under BST. UTC still reads 2026-08-13; the UK reads 2026-08-14. */
const BST_AFTER_UK_MIDNIGHT = '2026-08-13T23:30:00Z';
/** The same clock reading in GMT, where both calendars agree — the control. */
const GMT_AFTER_UK_MIDNIGHT = '2026-01-13T23:30:00Z';

const DATES = ['2026-08-12', '2026-08-13', '2026-08-14', '2026-08-15'];
const WINTER_DATES = ['2026-01-12', '2026-01-13', '2026-01-14'];

function freeze(iso) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

const chipFor = (date) =>
  screen.getAllByTestId('date-chip').find((c) => c.getAttribute('data-date') === date);

/**
 * jsdom implements no layout and therefore no `scrollIntoView`, which `DateStrip` calls unguarded
 * on mount. Stubbed locally rather than in `setup.js`, matching the convention and reasoning in
 * `WindowFirstMapPane.test.jsx` — a global stub would hide the absence from every other file.
 */
const realScrollIntoView = Element.prototype.scrollIntoView;
beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  Element.prototype.scrollIntoView = realScrollIntoView;
  vi.useRealTimers();
});

describe('DateStrip "Today" chip', () => {
  it('marks the UK date as today in the hour after UK midnight, not the UTC date', () => {
    freeze(BST_AFTER_UK_MIDNIGHT);
    render(<DateStrip dates={DATES} selectedDate="2026-08-14" onSelect={vi.fn()} />);

    expect(chipFor('2026-08-14')).toHaveAttribute('data-today', 'true');
    expect(chipFor('2026-08-13')).not.toHaveAttribute('data-today');
  });

  it('labels that chip "Today" and the next one "Tomorrow"', () => {
    freeze(BST_AFTER_UK_MIDNIGHT);
    render(<DateStrip dates={DATES} selectedDate="2026-08-14" onSelect={vi.fn()} />);

    expect(chipFor('2026-08-14')).toHaveTextContent(/^Today ·/);
    expect(chipFor('2026-08-15')).toHaveTextContent(/^Tomorrow ·/);
  });

  it('does not label yesterday "Today", which the UTC basis did', () => {
    // The assertion that fails if the basis regresses: on UTC this chip read "Today · Thu 13 Aug".
    freeze(BST_AFTER_UK_MIDNIGHT);
    render(<DateStrip dates={DATES} selectedDate="2026-08-14" onSelect={vi.fn()} />);

    expect(chipFor('2026-08-13')).not.toHaveTextContent('Today');
    expect(chipFor('2026-08-13')).not.toHaveTextContent('Tomorrow');
  });

  it('agrees with UTC in GMT, which is why the old basis looked correct half the year', () => {
    freeze(GMT_AFTER_UK_MIDNIGHT);
    render(<DateStrip dates={WINTER_DATES} selectedDate="2026-01-13" onSelect={vi.fn()} />);

    expect(chipFor('2026-01-13')).toHaveAttribute('data-today', 'true');
  });

  it('marks no chip today when the strip does not carry today', () => {
    // Absence: a strip built entirely from future dates has no "today" to mark, and must not
    // arbitrarily promote its first chip.
    freeze(BST_AFTER_UK_MIDNIGHT);
    render(<DateStrip dates={['2026-08-20', '2026-08-21']} selectedDate="2026-08-20" onSelect={vi.fn()} />);

    expect(screen.getAllByTestId('date-chip').every((c) => !c.hasAttribute('data-today'))).toBe(true);
  });
});
