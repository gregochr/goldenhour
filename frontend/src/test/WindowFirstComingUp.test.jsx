import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';

const TODAY = '2026-08-09';

/** A `ComingUpEntry` as P2 actually serves one. */
const wireEntry = (over = {}) => ({
  id: 'meteor:2026-08-12:2026-08-12',
  type: 'meteor',
  startDate: '2026-08-12',
  endDate: '2026-08-12',
  kind: 'ALMANAC',
  family: 'night-sky',
  title: 'Perseids',
  kindTag: 'Almanac',
  superlative: null,
  metric: '~100/hr',
  prose: 'Perseids peaks, around 100 meteors an hour at best under a dark sky.',
  facts: [],
  threshold: null,
  action: { label: 'Show dark-sky spots for 12 Aug →', kind: 'dark-sky-spots', date: '2026-08-12' },
  ...over,
});

const COUNTS = { fixed: 2, forecast: 0, byFamily: { 'night-sky': 1, coastal: 1 } };

const ENTRIES = [
  wireEntry(),
  wireEntry({
    id: 'spring-tide:2026-08-12:2026-08-13', type: 'spring-tide', family: 'coastal',
    title: 'Spring tide run', metric: null, prose: null,
    startDate: '2026-08-12', endDate: '2026-08-13',
    action: { label: 'Show coastal spots for 12 Aug →', kind: 'coastal-spots', date: '2026-08-12' },
  }),
];

const renderPane = (props = {}) => {
  const onRetry = vi.fn();
  const onGoToPlan = vi.fn();
  const onShowOnMap = vi.fn();
  const result = render(
    <WindowFirstComingUp
      id="window-first-panel-coming-up"
      labelledBy="window-first-tab-coming-up"
      status="ready"
      events={{ entries: ENTRIES, counts: COUNTS }}
      todayStr={TODAY}
      onRetry={onRetry}
      onGoToPlan={onGoToPlan}
      onShowOnMap={onShowOnMap}
      {...props}
    />,
  );
  return { ...result, onRetry, onGoToPlan, onShowOnMap };
};

describe('WindowFirstComingUp — the panel contract', () => {
  it('is a tab panel tied to the tab that controls it', () => {
    renderPane();
    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveAttribute('id', 'window-first-panel-coming-up');
    expect(panel).toHaveAttribute('aria-labelledby', 'window-first-tab-coming-up');
  });

  it('is focusable, because everything inside it is text', () => {
    renderPane();
    expect(screen.getByRole('tabpanel')).toHaveAttribute('tabindex', '0');
  });

  it('names the horizon it covers', () => {
    renderPane();
    expect(screen.getByTestId('coming-up-subtitle')).toHaveTextContent('next 90 days');
  });

  it('renders the legend unconditionally — not gated on entries, filters or status', () => {
    for (const [status, events] of [
      ['ready', { entries: ENTRIES, counts: COUNTS }],
      ['idle', null],
      ['loading', null],
      ['error', null],
      ['ready', { entries: [], counts: { fixed: 0, forecast: 0, byFamily: {} } }],
    ]) {
      const { unmount } = renderPane({ status, events });
      expect(screen.getByTestId('coming-up-legend')).toHaveTextContent('fixed');
      expect(screen.getByTestId('coming-up-legend')).toHaveTextContent('could still move');
      unmount();
    }
  });

  it('keeps the legend on screen after filtering down to an all-solid subset', () => {
    renderPane();
    const coastalChip = screen.getAllByTestId('coming-up-chip')
      .find((c) => within(c).queryByText('Coastal'));
    fireEvent.click(coastalChip);
    expect(screen.getByTestId('coming-up-legend')).toBeInTheDocument();
  });
});

describe('WindowFirstComingUp — the four states', () => {
  it('renders one entry per served row, in the payload’s order', () => {
    renderPane();
    const entries = screen.getAllByTestId('coming-up-entry');
    expect(entries).toHaveLength(2);
    expect(entries[0]).toHaveTextContent('Perseids');
    expect(entries[1]).toHaveTextContent('Spring tide run');
  });

  it('says it is looking while the request is in flight', () => {
    renderPane({ status: 'loading', events: null });
    expect(screen.getByTestId('coming-up-loading')).toBeInTheDocument();
  });

  it('never claims there is nothing coming up while the request is still in flight', () => {
    renderPane({ status: 'loading', events: null });
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
    expect(screen.queryByTestId('coming-up-entry')).toBeNull();
  });

  it('says nothing is coming up only once the feed has actually answered with nothing', () => {
    renderPane({ status: 'ready', events: { entries: [], counts: { fixed: 0, forecast: 0, byFamily: {} } } });
    expect(screen.getByTestId('coming-up-empty'))
      .toHaveTextContent('Nothing coming up in the next 90 days beyond the four-day forecast.');
  });

  it('renders nothing but its frame before the tab has ever been opened', () => {
    renderPane({ status: 'idle', events: null });
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
    expect(screen.queryByTestId('coming-up-loading')).toBeNull();
    expect(screen.queryByTestId('coming-up-entry')).toBeNull();
    expect(screen.queryByTestId('coming-up-chips')).toBeNull();
    expect(screen.getByTestId('coming-up-footer')).toBeInTheDocument();
  });

  it('says the load failed, and does not pretend the sky is empty', () => {
    renderPane({ status: 'error', events: null });
    expect(screen.getByTestId('coming-up-error')).toHaveTextContent('Could not load what is coming up.');
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
  });

  it('announces the load and the failure through one always-mounted live region', () => {
    const { unmount } = renderPane({ status: 'loading', events: null });
    const live = screen.getByTestId('coming-up-status');
    expect(live).toHaveAttribute('role', 'status');
    expect(live).toHaveTextContent('Looking ahead…');
    unmount();

    renderPane({ status: 'error', events: null });
    expect(screen.getByTestId('coming-up-status')).toHaveTextContent('Could not load what is coming up.');
  });

  it('groups the entries as a list, so a screen reader gets a boundary and a count', () => {
    // Both fixture entries fall in August, so this is exactly one month section — one list.
    renderPane();
    const list = screen.getByRole('list');
    expect(list).toBe(screen.getByTestId('coming-up-list'));
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('draws no list container when there is nothing to list', () => {
    renderPane({ status: 'ready', events: { entries: [], counts: { fixed: 0, forecast: 0, byFamily: {} } } });
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('puts focus on the panel when a retry succeeds, rather than dropping it on the body', () => {
    const { rerender, onRetry, onGoToPlan } = renderPane({ status: 'error', events: null });
    const button = screen.getByRole('button', { name: 'Try again' });
    button.focus();
    fireEvent.click(button);
    expect(onRetry).toHaveBeenCalledTimes(1);

    rerender(
      <WindowFirstComingUp
        id="window-first-panel-coming-up"
        labelledBy="window-first-tab-coming-up"
        status="ready"
        events={{ entries: ENTRIES, counts: COUNTS }}
        todayStr={TODAY}
        onRetry={onRetry}
        onGoToPlan={onGoToPlan}
        onShowOnMap={vi.fn()}
      />,
    );
    expect(screen.getByRole('tabpanel')).toHaveFocus();
  });

  it('offers a retry that actually retries', () => {
    const { onRetry } = renderPane({ status: 'error', events: null });
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('keeps its heading and footer in every state, so the pane never looks broken', () => {
    for (const status of ['idle', 'loading', 'error', 'ready']) {
      const { unmount } = renderPane({
        status,
        events: status === 'ready' ? { entries: [], counts: { fixed: 0, forecast: 0, byFamily: {} } } : null,
      });
      expect(screen.getByTestId('coming-up-subtitle')).toBeInTheDocument();
      expect(screen.getByTestId('coming-up-footer')).toBeInTheDocument();
      unmount();
    }
  });
});

describe('WindowFirstComingUp — month rules', () => {
  it('renders one month rule for a feed spanning one month', () => {
    renderPane();
    expect(screen.getAllByTestId('coming-up-month')).toHaveLength(1);
    expect(screen.getByTestId('coming-up-month')).toHaveTextContent('Aug');
    expect(screen.getByTestId('coming-up-month')).toHaveTextContent('2026');
  });

  it('renders one month rule per month when the feed spans more than one', () => {
    renderPane({
      events: {
        entries: [...ENTRIES, wireEntry({
          id: 'equinox:2026-09-22:2026-09-22', type: 'equinox', family: 'sun-moon',
          title: 'Autumn equinox', metric: 'twice a year', prose: 'The sun rises due east.',
          startDate: '2026-09-22', endDate: '2026-09-22',
          action: { label: 'See the plan for 22 Sept →', kind: 'plan', date: '2026-09-22' },
        })],
        counts: { ...COUNTS, byFamily: { ...COUNTS.byFamily, 'sun-moon': 1 } },
      },
    });
    const months = screen.getAllByTestId('coming-up-month');
    expect(months.map((m) => m.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('Aug'), expect.stringContaining('Sept')]),
    );
    expect(screen.getAllByRole('list')).toHaveLength(2);
  });
});

describe('WindowFirstComingUp — filter chips (plan D6)', () => {
  it('renders one chip per family plus All', () => {
    renderPane();
    const chips = screen.getAllByTestId('coming-up-chip');
    expect(chips.map((c) => c.textContent)).toEqual([
      expect.stringContaining('All'),
      expect.stringContaining('Coastal'),
      expect.stringContaining('Night sky'),
      expect.stringContaining('Sun & moon'),
      expect.stringContaining('Air & dust'),
    ]);
  });

  it('renders the served count on each chip, not the family name alone', () => {
    // COUNTS.byFamily = { 'night-sky': 1, coastal: 1 }, so All sums to 2; the two named families
    // read their own count and the two absent ones read 0. A chip that renders `chip.id` or a
    // hardcoded `0` for every family, or that drops the count element outright, would still pass
    // the "one chip per family" test above — this pins the number itself reaching the screen.
    renderPane();
    const chips = screen.getAllByTestId('coming-up-chip');
    expect(within(chips[0]).getByText('2')).toBeInTheDocument(); // All
    expect(within(chips[1]).getByText('1')).toBeInTheDocument(); // Coastal
    expect(within(chips[4]).getByText('0')).toBeInTheDocument(); // Air & dust
  });

  it('marks the All chip pressed by default, and no other', () => {
    renderPane();
    const [all, coastal] = screen.getAllByTestId('coming-up-chip');
    expect(all).toHaveAttribute('aria-pressed', 'true');
    expect(coastal).toHaveAttribute('aria-pressed', 'false');
  });

  it('filters the visible entries when a chip is pressed, flipping aria-pressed both ways', () => {
    renderPane();
    const [allChip] = screen.getAllByTestId('coming-up-chip');
    const coastalChip = screen.getAllByTestId('coming-up-chip')
      .find((c) => within(c).queryByText('Coastal'));
    fireEvent.click(coastalChip);
    const entries = screen.getAllByTestId('coming-up-entry');
    expect(entries).toHaveLength(1);
    expect(entries[0]).toHaveTextContent('Spring tide run');
    expect(coastalChip).toHaveAttribute('aria-pressed', 'true');
    // The boundary the standards ask for: the flip away from All must also be asserted, not just
    // the flip onto the newly-pressed chip.
    expect(allChip).toHaveAttribute('aria-pressed', 'false');
  });

  it('never changes a chip’s own count when a DIFFERENT chip is selected', () => {
    renderPane();
    const before = screen.getAllByTestId('coming-up-chip').map((c) => c.textContent);
    const coastalChip = screen.getAllByTestId('coming-up-chip')
      .find((c) => within(c).queryByText('Coastal'));
    fireEvent.click(coastalChip);
    const after = screen.getAllByTestId('coming-up-chip').map((c) => c.textContent);
    expect(after).toEqual(before);
  });

  it('names the filter, not just "nothing here", when the active filter matches no entry', () => {
    // Distinct from the feed-is-genuinely-empty state: the feed has two entries, so silently
    // showing nothing (as a zero-count family chip like Air & dust does at first ship, D9) would
    // read as the pane having broken rather than as the filter being the reason.
    renderPane();
    const dustChip = screen.getAllByTestId('coming-up-chip')
      .find((c) => within(c).queryByText('Air & dust'));
    fireEvent.click(dustChip);
    expect(screen.queryByTestId('coming-up-entry')).toBeNull();
    expect(screen.queryByRole('list')).toBeNull();
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
    expect(screen.getByTestId('coming-up-filter-empty'))
      .toHaveTextContent('Nothing matches the Air & dust filter.');
  });
});

describe('WindowFirstComingUp — filter chip glyphs (G4, plan §4.2)', () => {
  it('renders a glyph on every family chip, after the existing dot', () => {
    renderPane();
    const chips = screen.getAllByTestId('coming-up-chip');
    const [, coastal, nightSky, sunMoon, airDust] = chips;
    expect(within(coastal).getByTestId('coming-up-chip-glyph')).toHaveTextContent('🌊');
    expect(within(nightSky).getByTestId('coming-up-chip-glyph')).toHaveTextContent('🌌');
    expect(within(sunMoon).getByTestId('coming-up-chip-glyph')).toHaveTextContent('☀️');
    expect(within(airDust).getByTestId('coming-up-chip-glyph')).toHaveTextContent('🏜️');

    const dot = coastal.querySelector('.wf-cu-chip-dot');
    const glyph = within(coastal).getByTestId('coming-up-chip-glyph');
    expect(dot.compareDocumentPosition(glyph) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('renders the All chip bare — no dot, no glyph', () => {
    renderPane();
    const [all] = screen.getAllByTestId('coming-up-chip');
    expect(all.querySelector('.wf-cu-chip-dot')).toBeNull();
    expect(within(all).queryByTestId('coming-up-chip-glyph')).toBeNull();
  });

  it('hides every chip glyph from the accessibility tree', () => {
    renderPane();
    const chips = screen.getAllByTestId('coming-up-chip');
    for (const chip of chips) {
      const glyph = within(chip).queryByTestId('coming-up-chip-glyph');
      if (glyph) expect(glyph).toHaveAttribute('aria-hidden', 'true');
    }
  });
});

describe('WindowFirstComingUp — the vocabulary now lives on the card', () => {
  it('does not claim the dates come from orbital mechanics, because two sources compute none', () => {
    renderPane();
    expect(screen.getByTestId('coming-up-footer').textContent).not.toMatch(/orbital/i);
  });

  it('states the served fixed/forecast counts in the footer, not a count of rendered rows', () => {
    renderPane();
    expect(screen.getByTestId('coming-up-footer')).toHaveTextContent('Every date here is fixed in advance');
  });

  it('switches the footer copy once the served counts include a forecast entry', () => {
    renderPane({ events: { entries: ENTRIES, counts: { fixed: 1, forecast: 1, byFamily: {} } } });
    const footer = screen.getByTestId('coming-up-footer');
    expect(footer).toHaveTextContent('1 is a forecast peak');
    expect(footer.textContent).not.toContain('Every date here is fixed');
  });

  it('renders the kind tag on every entry, not marker-on-exception', () => {
    // The footer's old "every date here is fixed" vocabulary job moved onto the per-card tag.
    renderPane();
    expect(screen.getAllByTestId('coming-up-kindtag')).toHaveLength(2);
    expect(screen.getAllByTestId('coming-up-kindtag')[0]).toHaveTextContent('Almanac');
  });
});

describe('WindowFirstComingUp — the footer never states a count it does not have', () => {
  it('shows only the general rule, with no count, before the feed has answered', () => {
    // The defect this guards: the old footer's copy was static and count-free, so gating it on
    // status was never necessary — the new copy names actual numbers, and stating them beneath
    // "Looking ahead…" or "Could not load…" would be a claim about data that has not arrived.
    for (const [status, events] of [['idle', null], ['loading', null], ['error', null]]) {
      const { unmount } = renderPane({ status, events });
      const footer = screen.getByTestId('coming-up-footer');
      expect(footer).toHaveTextContent(
        'This list starts where the four-day forecast stops. It shows two things: dates fixed '
        + 'in advance, and the forecast peak of a recurring condition.',
      );
      expect(footer.textContent).not.toContain('Every date here is fixed in advance');
      expect(footer.textContent).not.toMatch(/of these dates (is|are) fixed/);
      unmount();
    }
  });

  it('states the real counts once the feed answers', () => {
    renderPane();
    expect(screen.getByTestId('coming-up-footer')).toHaveTextContent('Every date here is fixed in advance');
  });
});

describe('WindowFirstComingUp — the handoff row (plan D14)', () => {
  it('renders above the chronology, stating the boundary with Plan', () => {
    renderPane({ hotTopics: [{ type: 'DUST', label: 'Saharan dust', date: TODAY }] });
    const handoff = screen.getByTestId('coming-up-handoff');
    expect(handoff).toHaveTextContent('Now —');
    expect(handoff).toHaveTextContent('One topic on those four days');
    expect(handoff).toHaveTextContent('Saharan dust');
    expect(handoff).toHaveTextContent('On Plan →');
  });

  it('gives each phrase its own word boundary in the accessible name, rather than gluing them '
      + 'into one run-on string', () => {
    // JSX drops whitespace-only text between sibling tags — it does not collapse it to a space —
    // so without an explicit `{' '}` between every span the accessible name (the button's whole
    // text content) reads "...four daysSaharan dustAurora possibleOn Plan" with no boundaries.
    // This pins `WindowFirstComingUpHandoff` itself (unchanged by this phase) through the ROLE
    // this pane renders it with, so a future edit to either file cannot silently reintroduce it.
    renderPane({
      hotTopics: [
        { type: 'DUST', label: 'Saharan dust', date: TODAY },
        { type: 'AURORA', label: 'Aurora possible', date: TODAY },
      ],
    });

    const handoff = screen.getByRole('button', {
      name: 'Now — Wed 12 Two topics on those four days Saharan dust Aurora possible '
        + 'On Plan →',
    });
    expect(handoff).toBeInTheDocument();
  });

  it('calls onGoToPlan when clicked', () => {
    const { onGoToPlan } = renderPane();
    fireEvent.click(screen.getByTestId('coming-up-handoff'));
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
  });

  it('degrades to the label-only row when the briefing has not supplied hotTopics yet', () => {
    renderPane({ hotTopics: undefined });
    const handoff = screen.getByTestId('coming-up-handoff');
    expect(handoff).toHaveTextContent('Now —');
    expect(handoff).toHaveTextContent('On Plan →');
    expect(screen.queryByTestId('coming-up-handoff-summary')).toBeNull();
  });

  it('says explicitly that nothing is live once hotTopics has arrived empty', () => {
    renderPane({ hotTopics: [] });
    expect(screen.getByTestId('coming-up-handoff-summary'))
      .toHaveTextContent('Nothing on those four days');
  });
});

describe('WindowFirstComingUp — card-click fires the entry’s action', () => {
  it('invokes onGoToPlan with the action’s own date on a plan-kind card', () => {
    const { onGoToPlan } = renderPane({
      events: {
        entries: [wireEntry({
          action: { label: 'See the plan for 12 Aug →', kind: 'plan', date: '2026-08-12' },
        })],
        counts: { fixed: 1, forecast: 0, byFamily: { 'night-sky': 1 } },
      },
    });
    // The accessible-name contract for this button is pinned in `WindowComingUpEntry.test.jsx`;
    // this test is about dispatch, so it targets the card directly.
    fireEvent.click(screen.getByTestId('coming-up-card'));
    expect(onGoToPlan).toHaveBeenCalledWith('2026-08-12');
  });
});

describe('WindowFirstComingUp — recurring conditions strip (plan §7 P4)', () => {
  const CONDITION = {
    type: 'COASTAL_TIDES',
    name: 'Coastal tides',
    cadence: 'deterministic',
    interim: false,
    rateLabel: 'a run every 14.8 days · fixed by the ephemeris',
    quantLabel: 'rarity 3.9 · 7 runs in 90 days',
    peak: null,
    occurrences: [],
  };

  it('renders the strip from events.conditions, between the chips and the handoff row — the '
      + 'design of record\'s own DOM order', () => {
    renderPane({ events: { entries: ENTRIES, counts: COUNTS, conditions: [CONDITION] } });

    const chips = screen.getByTestId('coming-up-chips');
    const strip = screen.getByTestId('coming-up-conditions');
    const handoff = screen.getByTestId('coming-up-handoff');

    expect(chips.compareDocumentPosition(strip)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(strip.compareDocumentPosition(handoff)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('renders nothing for the strip when conditions is empty or absent, without breaking the pane', () => {
    renderPane({ events: { entries: ENTRIES, counts: COUNTS, conditions: [] } });
    expect(screen.queryByTestId('coming-up-conditions')).toBeNull();

    renderPane({ events: { entries: ENTRIES, counts: COUNTS } });
    expect(screen.queryByTestId('coming-up-conditions')).toBeNull();
  });

  it('the handoff row still renders while the almanac feed is loading or has failed — it reads '
      + 'only hotTopics, not the almanac status', () => {
    renderPane({ status: 'loading', events: undefined });
    expect(screen.getByTestId('coming-up-handoff')).toBeInTheDocument();
    expect(screen.queryByTestId('coming-up-conditions')).toBeNull();
  });

  it('the STRIP\'s own header sub-line gains a quiet "scores are provisional" suffix while any visible '
      + 'condition is interim — never the chronology pane\'s own sub-line (plan §7)', () => {
    renderPane({
      events: { entries: ENTRIES, counts: COUNTS, conditions: [{ ...CONDITION, interim: true }] },
    });
    const marker = screen.getByTestId('coming-up-provisional');
    expect(marker).toHaveTextContent('scores are provisional');
    expect(screen.getByTestId('coming-up-conditions')).toContainElement(marker);
    expect(screen.getByTestId('coming-up-subtitle')).not.toContainElement(marker);
  });

  it('the suffix is absent once every visible condition is mature', () => {
    renderPane({ events: { entries: ENTRIES, counts: COUNTS, conditions: [CONDITION] } });
    expect(screen.queryByTestId('coming-up-provisional')).toBeNull();
  });
});
