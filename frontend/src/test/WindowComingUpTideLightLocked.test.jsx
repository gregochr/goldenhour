import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';
import WindowComingUpEntry from '../components/WindowComingUpEntry.jsx';

let mockTideLocked = true;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => ({ tideLocked: mockTideLocked }),
}));

/**
 * The Lite locked row on Coming up (coming-up-tide-light-locked-row-plan.md §4).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader's tide card closes over the gap where Pro's sunrise and
 * sunset high-water rows sit and nothing says what is missing, the row promises an aligned morning a withheld
 * run may not have, the row leaks served tide, a stale session draws a silent gap, or the row becomes a
 * focusable control inside a card button.
 */

const TODAY = '2026-08-09';
const LABEL = 'High water at sunrise and sunset';
const BODY = 'Pro shows where high water falls against sunrise and sunset on this run.';

const tide = (id, start, over = {}) => ({
  id, type: 'spring-tide', startDate: start, endDate: start, kind: 'ALMANAC', family: 'coastal',
  title: `Run ${id}`, kindTag: 'Almanac', superlative: null, metric: null, prose: null,
  facts: [{ segments: [{ text: 'range', tone: 'base' }, { text: '4.6 m', tone: 'strong' }] }],
  threshold: null,
  action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: start },
  ...over,
});

const COUNTS = { fixed: 3, forecast: 0, byFamily: { coastal: 3 } };

const renderPane = (entries) => render(
  <WindowFirstComingUp
    id="p"
    labelledBy="t"
    status="ready"
    events={{ entries, counts: COUNTS }}
    todayStr={TODAY}
    onRetry={vi.fn()}
    onGoToPlan={vi.fn()}
    onShowOnMap={vi.fn()}
    onMarkSeen={vi.fn()}
  />,
);

/** A view as `buildEntryView` produces one, for the entry-level cases. */
const view = (over = {}) => ({
  id: 'spring-tide:2026-08-16:2026-08-18',
  type: 'spring-tide',
  family: 'coastal',
  isForecast: false,
  rail: { dow: null, day: '16–18', month: 'Aug', isRange: true, countdown: 'in 7 days' },
  title: 'Spring tide run',
  kindTag: 'Almanac',
  superlative: null,
  metric: null,
  prose: null,
  isFeature: false,
  facts: [],
  threshold: null,
  action: { label: 'Show coastal spots for 16 Aug →', kind: 'coastal-spots', date: '2026-08-16' },
  interactive: false,
  tide: null,
  coincidence: null,
  joinNote: null,
  isNew: false,
  ...over,
});

const renderEntry = (over) => render(
  <WindowComingUpEntry entry={view(over)} onGoToPlan={vi.fn()} onShowOnMap={vi.fn()} />,
);

beforeEach(() => { mockTideLocked = true; });

describe('Coming up — the Lite locked tide-light row', () => {
  it('draws the row inside the facts block, after the last fact, with the exact words', () => {
    renderPane([tide('a', '2026-08-12', { lightRowsWithheld: true })]);
    const facts = screen.getByTestId('coming-up-facts');
    const row = within(facts).getByTestId('coming-up-tide-light-locked');
    const fact = within(facts).getByTestId('coming-up-fact');
    expect(fact.compareDocumentPosition(row)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(row.parentElement).toBe(facts);
    expect(within(row).getByTestId('coming-up-tide-light-locked-label')).toHaveTextContent(LABEL);
    expect(within(row).getByTestId('coming-up-tide-light-locked-pill')).toHaveTextContent('Pro');
    expect(within(row).getByTestId('coming-up-tide-light-locked-body')).toHaveTextContent(BODY);
    expect(within(row).getByTestId('wf-cu-locked-sketch')).toHaveAttribute('aria-hidden', 'true');
  });

  it('is static: no role, tabindex, title or focusable descendant', () => {
    renderPane([tide('a', '2026-08-12', { lightRowsWithheld: true })]);
    const row = screen.getByTestId('coming-up-tide-light-locked');
    expect(row).not.toHaveAttribute('role');
    expect(row).not.toHaveAttribute('tabindex');
    expect(row).not.toHaveAttribute('title');
    expect(within(row).queryByRole('button')).toBeNull();
    expect(within(row).queryByRole('link')).toBeNull();
    expect(within(row).getByTestId('wf-cu-locked-sketch')).toHaveAttribute('focusable', 'false');
    expect(row).not.toHaveTextContent(/upgrade/i);
  });

  it('draws the row on EVERY flagged card, not only the first', () => {
    renderPane([
      tide('a', '2026-08-12', { lightRowsWithheld: true }),
      tide('b', '2026-08-20', { lightRowsWithheld: true }),
    ]);
    const rows = screen.getAllByTestId('coming-up-entry');
    expect(within(rows[0]).getByTestId('coming-up-tide-light-locked')).toBeInTheDocument();
    expect(within(rows[1]).getByTestId('coming-up-tide-light-locked')).toBeInTheDocument();
  });

  it('draws no row on an unflagged tide card', () => {
    renderPane([tide('a', '2026-08-12'), tide('b', '2026-08-20', { lightRowsWithheld: true })]);
    const rows = screen.getAllByTestId('coming-up-entry');
    expect(within(rows[0]).queryByTestId('coming-up-tide-light-locked')).toBeNull();
    expect(within(rows[1]).getByTestId('coming-up-tide-light-locked')).toBeInTheDocument();
  });

  it('draws no row when the flag is absent, as on a Pro or Admin body', () => {
    mockTideLocked = false;
    renderPane([tide('a', '2026-08-12'), tide('b', '2026-08-20')]);
    expect(screen.queryByTestId('coming-up-tide-light-locked')).toBeNull();
  });

  it('draws the row from the wire flag alone, even when the client still thinks the reader is Pro', () => {
    // A demoted Pro session: the server sends a Lite body, the client role is stale.
    mockTideLocked = false;
    renderPane([tide('a', '2026-08-12', { lightRowsWithheld: true })]);
    expect(screen.getByTestId('coming-up-tide-light-locked')).toHaveTextContent(LABEL);
  });

  it('draws the row on a supermoon-hosted merged card, whatever its family', () => {
    renderPane([tide('a', '2026-08-12', {
      type: 'supermoon', family: 'sun-moon', lightRowsWithheld: true,
      coincidence: [{ family: 'coastal', name: 'Spring tide', factsLabel: 'Wed 12 Aug' }],
    })]);
    expect(screen.getByTestId('coming-up-card')).toHaveAttribute('data-family', 'sun-moon');
    expect(screen.getByTestId('coming-up-tide-light-locked')).toHaveTextContent(LABEL);
  });

  it('keeps the neutral sentence on a miss-only card, which may have no aligned morning at all', () => {
    // Both event rows were misses and were withheld; nothing is left but the flag.
    renderPane([tide('a', '2026-08-12', { facts: [], lightRowsWithheld: true })]);
    const body = screen.getByTestId('coming-up-tide-light-locked-body');
    expect(body).toHaveTextContent(BODY);
    expect(body).not.toHaveTextContent(/which morning|which evening/i);
  });

  it('never draws a tide-light door, whatever the role', () => {
    renderPane([tide('a', '2026-08-12', { lightRowsWithheld: true })]);
    expect(screen.queryByTestId('coming-up-tide-light-upsell-door')).toBeNull();
    expect(screen.queryByTestId('coming-up-tide-light-upsell-trigger')).toBeNull();
  });

  it('never draws a tide-light door for a Pro-looking client either', () => {
    mockTideLocked = false;
    renderPane([tide('b', '2026-08-20', { lightRowsWithheld: true })]);
    expect(screen.queryByTestId('coming-up-tide-light-upsell-door')).toBeNull();
  });
});

describe('WindowComingUpEntry — the locked row in isolation', () => {
  it('renders the facts block for a flagged entry with no tide and no facts left', () => {
    renderEntry({ lightRowsWithheld: true });
    const facts = screen.getByTestId('coming-up-facts');
    expect(within(facts).getByTestId('coming-up-tide-light-locked')).toHaveTextContent(LABEL);
    expect(within(facts).queryByTestId('coming-up-fact')).toBeNull();
  });

  it('renders no facts block for an unflagged entry with no tide and no facts', () => {
    renderEntry({ lightRowsWithheld: false });
    expect(screen.queryByTestId('coming-up-facts')).toBeNull();
  });

  it('is a direct child of the facts block beside a sparkline and a single fact, so the scoped CSS applies', () => {
    renderEntry({
      tide: { range: 4.1, delta: 0.6, phase: 'HW' },
      facts: [{ segments: [{ text: 'range', tone: 'base' }, { text: '4.1 m', tone: 'strong' }] }],
      lightRowsWithheld: true,
    });
    const facts = screen.getByTestId('coming-up-facts');
    const row = within(facts).getByTestId('coming-up-tide-light-locked');
    // The CSS hangs on `.wf-cu-card .wf-facts > .wf-cu-locked`; jsdom cannot lay it out.
    expect(row).toHaveClass('wf-cu-locked');
    expect(row.parentElement).toBe(facts);
    expect(within(facts).getByTestId('coming-up-tide-sparkline')).toBeInTheDocument();
  });

  it('inside a card button holds no focusable element and keeps its words apart in the accessible name', () => {
    renderEntry({
      interactive: true,
      tide: { range: 4.1, delta: 0.6, phase: 'HW' },
      lightRowsWithheld: true,
    });
    const button = screen.getByRole('button');
    const row = within(button).getByTestId('coming-up-tide-light-locked');
    expect(within(row).queryByRole('button')).toBeNull();
    expect(row.querySelectorAll('a, button, [tabindex]')).toHaveLength(0);
    expect(button).toHaveAccessibleName(expect.stringContaining(`${LABEL} Pro ${BODY}`));
    expect(button).toHaveAccessibleName(expect.stringContaining('+0.6 vs avg High water'));
  });

  it('draws an identical sketch for different tide data and carries none of the fixture digits', () => {
    const first = renderEntry({ tide: { range: 4.1, delta: 0.6, phase: 'HW' }, lightRowsWithheld: true });
    const sketchA = within(first.container).getByTestId('wf-cu-locked-sketch').outerHTML;
    first.unmount();
    const second = renderEntry({ tide: { range: 7.9, delta: -1.3, phase: 'LW' }, lightRowsWithheld: true });
    const sketchB = within(second.container).getByTestId('wf-cu-locked-sketch').outerHTML;
    expect(sketchB).toBe(sketchA);
    expect(sketchA).not.toContain('4.1');
    expect(sketchA).not.toContain('7.9');
  });
});
