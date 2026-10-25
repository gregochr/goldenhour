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

  it('prints no header subtitle naming the horizon', () => {
    renderPane();
    expect(screen.queryByTestId('coming-up-subtitle')).toBeNull();
    expect(screen.getByTestId('window-first-coming-up')).not.toHaveTextContent('past the four-day forecast');
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
      .toHaveTextContent('No dates coming up in the next 90 days beyond the four-day forecast.');
  });

  it('⚠️ scopes the empty note to the DATED list, so it never denies the four-day strip '
      + 'rendering right below it', () => {
    // `totalEntries` counts `events.entries` alone, and the "Next four days" strip reads
    // `hotTopics`, a separate source — so an empty chronology beside live four-day content is
    // reachable, and a broader sentence would print a denial directly above rows the reader can
    // see. Caught by a Codex review of #748, where the copy pass had dropped the scoping word.
    renderPane({
      status: 'ready',
      events: { entries: [], counts: { fixed: 0, forecast: 0, byFamily: {} } },
      hotTopics: [{ type: 'DUST', label: 'Saharan dust', date: TODAY, eventType: 'SUNSET' }],
    });

    const empty = screen.getByTestId('coming-up-empty');
    expect(empty).toHaveTextContent('No dates coming up');
    const lanes = screen.getByTestId('coming-up-fourdays');
    expect(empty.compareDocumentPosition(lanes)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
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
      expect(screen.getByTestId('coming-up-legend')).toBeInTheDocument();
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

  it('renders the kind tag on every entry, not marker-on-exception', () => {
    // The footer's old "every date here is fixed" vocabulary job moved onto the per-card tag.
    renderPane();
    expect(screen.getAllByTestId('coming-up-kindtag')).toHaveLength(2);
    expect(screen.getAllByTestId('coming-up-kindtag')[0]).toHaveTextContent('Almanac');
  });
});

describe('WindowFirstComingUp — the footer is one fixed sentence in every status', () => {
  it('reads the same sentence in loading, error and ready, whatever the counts', () => {
    for (const [status, events] of [
      ['loading', null],
      ['error', null],
      ['ready', { entries: ENTRIES, counts: COUNTS }],
      ['ready', { entries: ENTRIES, counts: { fixed: 1, forecast: 1, byFamily: {} } }],
    ]) {
      const { unmount } = renderPane({ status, events });
      expect(screen.getByTestId('coming-up-footer')).toHaveTextContent(
        'This list starts where the four-day forecast stops and shows dates fixed in advance. '
        + "Weather-driven topics can't be known that far out, so they appear only in Next four "
        + 'days.',
      );
      unmount();
    }
  });
});

describe('WindowFirstComingUp — the Recurring conditions panel is gone (coming-up-glance P2)', () => {
  it('renders no .wf-cond element, even when the payload still carries conditions', () => {
    const { container } = renderPane({
      events: {
        entries: ENTRIES,
        counts: COUNTS,
        conditions: [{
          type: 'COASTAL_TIDES', name: 'Coastal tides', cadence: 'deterministic', interim: false,
          rateLabel: 'a run every 14.8 days', quantLabel: 'about one a fortnight', peak: null,
          occurrences: [],
        }],
      },
    });
    expect(container.querySelector('[class*="wf-cond"]')).toBeNull();
    expect(screen.queryByTestId('coming-up-conditions')).toBeNull();
  });
});

describe('WindowFirstComingUp — the "Next four days" tiles (coming-up-glance-plan T1)', () => {
  // TODAY is Sun 9 Aug 2026, so the four days are Sun 9, Mon 10, Tue 11 and Wed 12.
  const DUST = { type: 'DUST', label: 'Saharan dust', date: '2026-08-10', eventType: 'SUNSET' };
  const SPRING = (date, over = {}) => ({
    type: 'SPRING_TIDE', label: 'Spring tide', date, eventType: null,
    tideRun: { dayNumber: 1, dayCount: 3, peak: false },
    ...over,
  });
  const MIST = {
    type: 'MIST', label: 'Valley mist', date: '2026-08-11', eventType: 'SUNRISE',
    glance: { value: 'dawn · likely at 3 sites', shortValue: 'likely', weather: true },
  };
  const chipFor = (type) => screen.getAllByTestId('coming-up-fourdays-chip')
    .find((c) => c.getAttribute('data-topic') === type);

  it('renders the header and one chip per topic, after the chips and before the chronology', () => {
    renderPane({ hotTopics: [DUST] });
    const tiles = screen.getByTestId('coming-up-fourdays');
    expect(screen.getByTestId('coming-up-chips').compareDocumentPosition(tiles))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(tiles).toHaveTextContent('Next four days');
    expect(screen.getAllByTestId('coming-up-fourdays-chip')).toHaveLength(1);
    expect(chipFor('DUST')).toHaveTextContent('Dust');
    expect(screen.getAllByTestId('coming-up-fourdays-day')).toHaveLength(4);
    expect(tiles.compareDocumentPosition(screen.getAllByTestId('coming-up-entry')[0]))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('"At a Glance" is its own button and calls onGoToPlan with no date', () => {
    const { onGoToPlan } = renderPane({ hotTopics: [DUST] });
    const button = screen.getByRole('button', { name: /^At a Glance/ });
    fireEvent.click(button);
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
    expect(onGoToPlan).toHaveBeenCalledWith();
  });

  it('a tap on a chip with a Plan window card opens that window, with the topic', () => {
    const onGoToPlanWindow = vi.fn();
    const { onGoToPlan } = renderPane({
      hotTopics: [DUST],
      openableKeys: new Set(['2026-08-10:SUNSET']),
      onGoToPlanWindow,
    });
    fireEvent.click(chipFor('DUST'));
    expect(onGoToPlanWindow).toHaveBeenCalledWith('2026-08-10:SUNSET', 'DUST');
    expect(onGoToPlan).not.toHaveBeenCalled();
  });

  it('a tap on a chip with no Plan window card is plain goToPlan', () => {
    const onGoToPlanWindow = vi.fn();
    const { onGoToPlan } = renderPane({
      hotTopics: [DUST], openableKeys: new Set(), onGoToPlanWindow,
    });
    fireEvent.click(chipFor('DUST'));
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
    expect(onGoToPlanWindow).not.toHaveBeenCalled();
  });

  it('hands a weather chip the pane\'s Map door: onShowOnMap with that morning\'s date and event', () => {
    const { onShowOnMap } = renderPane({ hotTopics: [MIST] });
    fireEvent.click(chipFor('MIST'));
    expect(onShowOnMap).toHaveBeenCalledWith({
      kind: 'event', date: '2026-08-11', eventType: 'SUNRISE', topicType: 'MIST',
    });
  });

  it('withholds the Map door for a date outside the reader\'s known forecast dates', () => {
    const onGoToPlanWindow = vi.fn();
    const { onShowOnMap, onGoToPlan } = renderPane({
      hotTopics: [MIST], forecastDates: ['2026-08-09'], onGoToPlanWindow,
    });
    fireEvent.click(chipFor('MIST'));
    expect(onShowOnMap).not.toHaveBeenCalled();
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
  });

  it('degrades to the header and four empty tiles when the briefing has not supplied hotTopics',
    () => {
      renderPane({ hotTopics: undefined });
      expect(screen.getByTestId('coming-up-fourdays')).toHaveTextContent('Next four days');
      expect(screen.getByRole('button', { name: /^At a Glance/ })).toBeInTheDocument();
      expect(screen.getAllByTestId('coming-up-fourdays-day')).toHaveLength(4);
      expect(screen.queryByTestId('coming-up-fourdays-chip')).toBeNull();
      expect(screen.queryByTestId('coming-up-fourdays-empty')).toBeNull();
    });

  it('says explicitly that nothing is live once hotTopics has arrived empty, keeping the tiles', () => {
    renderPane({ hotTopics: [] });
    expect(screen.getByTestId('coming-up-fourdays-empty'))
      .toHaveTextContent('Nothing on those four days');
    expect(screen.getAllByTestId('coming-up-fourdays-day')).toHaveLength(4);
  });

  it('scrolls to the list entry for a chip whose run covers its day, and only while that entry is '
      + 'in the rendered list', () => {
    renderPane({ hotTopics: [SPRING('2026-08-12')] });
    expect(chipFor('SPRING_TIDE')).toHaveAccessibleName(/shown in the list below$/);

    // The coastal chip keeps the spring-tide card; the other chip hides it, and a jump must never
    // aim at a card the filter has removed.
    const chip = (label) => screen.getAllByTestId('coming-up-chip')
      .find((c) => within(c).queryByText(label));
    fireEvent.click(chip('Coastal'));
    expect(chipFor('SPRING_TIDE')).toHaveAccessibleName(/shown in the list below$/);
    fireEvent.click(chip('Night sky'));
    expect(chipFor('SPRING_TIDE')).toHaveAccessibleName(/opens At a Glance$/);
  });
});

describe('WindowFirstComingUp — a chip\'s jump never aims at a list that is not on screen', () => {
  it('names no jump while a retry is loading, even with stale events still held', () => {
    const run = [{
      type: 'SPRING_TIDE', label: 'Spring tide', date: '2026-08-12', eventType: null,
      tideRun: { dayNumber: 1, dayCount: 1, peak: false },
    }];
    const spring = () => screen.getAllByTestId('coming-up-fourdays-chip')
      .find((c) => c.getAttribute('data-topic') === 'SPRING_TIDE');
    // The same props, once ready: the chip jumps ...
    const ready = renderPane({ hotTopics: run, status: 'ready' });
    expect(spring()).toHaveAccessibleName(/shown in the list below$/);
    ready.unmount();
    // ... and not while loading or failed, because the month list is not rendered and the card the
    // chip would scroll to is not in the DOM.
    for (const status of ['loading', 'error']) {
      const { unmount } = renderPane({ hotTopics: run, status });
      expect(screen.queryByTestId('coming-up-entry')).toBeNull();
      expect(spring()).toHaveAccessibleName(/opens At a Glance$/);
      unmount();
    }
  });
});

describe('WindowFirstComingUp — card-click fires the entry’s action', () => {
  it('invokes onGoToPlan with the action’s own date on a plan-kind card', () => {
    const { onGoToPlan } = renderPane({
      events: {
        // Over the day-chip cap: a short or one-day plan entry draws chips instead (§11.26), so
        // only a long one still has the single Plan line this test is about.
        entries: [wireEntry({
          startDate: '2026-08-12',
          endDate: '2026-12-31',
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

describe('WindowFirstComingUp — the tiles do not wait on the almanac feed', () => {
  it('the tiles still render while the almanac feed is loading — they read only hotTopics', () => {
    renderPane({ status: 'loading', events: undefined });
    expect(screen.getByTestId('coming-up-fourdays')).toBeInTheDocument();
  });
});

describe('WindowFirstComingUp — map doors follow the reader’s forecast dates (plan §11.24)', () => {
  // Both fixtures are OVER-CAP spans (a months-long season's shape): a single-day or short entry gets
  // day chips instead (§11.25, §11.26), so only an over-cap one still takes the single, gated door.
  // Different action dates let one list split them.
  const SPLIT_ENTRIES = [
    wireEntry({
      id: 'meteor:2026-08-12:2026-09-30', startDate: '2026-08-12', endDate: '2026-09-30',
      action: { label: 'Show dark-sky spots for 12 Aug →', kind: 'dark-sky-spots', date: '2026-08-12' },
    }),
    wireEntry({
      id: 'spring-tide:2026-10-11:2026-12-31', type: 'spring-tide', family: 'coastal',
      title: 'Spring tide run', metric: null, prose: null,
      startDate: '2026-10-11', endDate: '2026-12-31',
      action: { label: 'Show coastal spots for 11 Oct →', kind: 'coastal-spots', date: '2026-10-11' },
    }),
  ];
  // One object held across every render below, so a `forecastDates` change is the ONLY thing that
  // can make the chronology recompute — a fresh `events` literal would recompute it regardless.
  const SPLIT_EVENTS = { entries: SPLIT_ENTRIES, counts: COUNTS };
  const renderSplit = (props = {}) => renderPane({ events: SPLIT_EVENTS, ...props });

  it('makes a button of the entry whose date is listed and a plain card of the one that is not', () => {
    const { onShowOnMap } = renderSplit({ forecastDates: ['2026-08-12'] });
    const perseids = screen.getByRole('button', { name: /Perseids/ });
    expect(screen.queryByRole('button', { name: /Spring tide run/ })).toBeNull();

    const spring = screen.getAllByTestId('coming-up-card')[1];
    expect(spring.tagName).toBe('DIV');
    expect(within(spring).getByTestId('coming-up-action'))
      .toHaveTextContent('Coastal spots: no forecast for 11 Oct');

    fireEvent.click(spring);
    expect(onShowOnMap).not.toHaveBeenCalled();
    fireEvent.click(perseids);
    expect(onShowOnMap).toHaveBeenCalledWith(expect.objectContaining({ kind: 'coming-up', date: '2026-08-12' }));
  });

  it('withholds every map door when the list is known to be empty, and draws no button', () => {
    renderSplit({ forecastDates: [] });
    expect(screen.queryByRole('button', { name: /Perseids/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Spring tide run/ })).toBeNull();
    for (const action of screen.getAllByTestId('coming-up-action')) {
      expect(action).toHaveAttribute('data-withheld', 'true');
    }
  });

  it('leaves every map door live while the forecast dates are not known yet (none passed)', () => {
    renderSplit();
    expect(screen.getByRole('button', { name: /Perseids/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Spring tide run/ })).toBeInTheDocument();
    for (const action of screen.getAllByTestId('coming-up-action')) {
      expect(action).not.toHaveAttribute('data-withheld');
    }
  });

  it('re-decides the doors when only the list changes under a mounted pane', () => {
    const { rerender, onShowOnMap, onRetry, onGoToPlan } = renderSplit({ forecastDates: [] });
    expect(screen.getAllByTestId('coming-up-card').map((c) => c.tagName)).toEqual(['DIV', 'DIV']);
    rerender(
      <WindowFirstComingUp
        id="window-first-panel-coming-up"
        labelledBy="window-first-tab-coming-up"
        status="ready"
        events={SPLIT_EVENTS}
        todayStr={TODAY}
        onRetry={onRetry}
        onGoToPlan={onGoToPlan}
        onShowOnMap={onShowOnMap}
        forecastDates={['2026-08-12', '2026-10-11']}
      />,
    );
    expect(screen.getAllByTestId('coming-up-card').map((c) => c.tagName)).toEqual(['BUTTON', 'BUTTON']);
  });
});

describe('WindowFirstComingUp — a run’s day chips follow the reader’s forecast dates (plan §11.25)', () => {
  const RUN_EVENTS = {
    entries: [wireEntry({
      id: 'spring-tide:2026-10-09:2026-10-14', type: 'spring-tide', family: 'coastal',
      title: 'Spring tide run', metric: null, prose: null,
      startDate: '2026-10-09', endDate: '2026-10-14',
      action: { label: 'Show coastal spots for 11 Oct →', kind: 'coastal-spots', date: '2026-10-11' },
    })],
    counts: COUNTS,
  };
  const dayChip = (label) => screen.getByRole('button', { name: label });

  it('enables the chips for held dates, dims the rest and opens the map on the chip’s date', () => {
    const { onShowOnMap } = renderPane({
      events: RUN_EVENTS, todayStr: '2026-10-07', forecastDates: ['2026-10-09', '2026-10-10'],
    });
    expect(screen.getAllByTestId('coming-up-day-chip')).toHaveLength(6);
    expect(screen.getByTestId('coming-up-card').tagName).toBe('DIV');
    expect(dayChip('Sun 11 Oct, peak day — no forecast yet')).toBeDisabled();
    fireEvent.click(dayChip('Show coastal spots for Sat 10 Oct'));
    expect(onShowOnMap).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'coming-up', filterAction: 'SEASCAPE', date: '2026-10-10' }),
    );
  });

  it('leaves every chip live while the forecast dates are not known yet', () => {
    renderPane({ events: RUN_EVENTS, todayStr: '2026-10-07' });
    for (const chip of screen.getAllByTestId('coming-up-day-chip')) {
      expect(chip).toBeEnabled();
    }
    expect(screen.queryByTestId('coming-up-day-chips-note')).toBeNull();
  });

  it('re-decides the chips when only the list changes under a mounted pane', () => {
    const { rerender, onShowOnMap, onRetry, onGoToPlan } = renderPane({
      events: RUN_EVENTS, todayStr: '2026-10-07', forecastDates: [],
    });
    for (const chip of screen.getAllByTestId('coming-up-day-chip')) {
      expect(chip).toBeDisabled();
    }
    rerender(
      <WindowFirstComingUp
        id="window-first-panel-coming-up"
        labelledBy="window-first-tab-coming-up"
        status="ready"
        events={RUN_EVENTS}
        todayStr="2026-10-07"
        onRetry={onRetry}
        onGoToPlan={onGoToPlan}
        onShowOnMap={onShowOnMap}
        forecastDates={['2026-10-09', '2026-10-11']}
      />,
    );
    expect(dayChip('Show coastal spots for Fri 9 Oct')).toBeEnabled();
    expect(dayChip('Show coastal spots for Sun 11 Oct, peak day')).toBeEnabled();
    expect(dayChip('Sat 10 Oct — no forecast yet')).toBeDisabled();
  });

  it('keeps an over-cap span on its single gated door — chips are for short runs only', () => {
    // 9–19 Oct is eleven days: past the cap, so the entry keeps ONE door, and the gate still applies.
    const overCap = {
      entries: [wireEntry({
        id: 'season:2026-10-09:2026-10-19', type: 'nlc', family: 'night-sky',
        title: 'Long season', metric: null, prose: null,
        startDate: '2026-10-09', endDate: '2026-10-19',
        action: { label: 'Show dark-sky spots for 14 Oct →', kind: 'dark-sky-spots', date: '2026-10-14' },
      })],
      counts: COUNTS,
    };
    const { onShowOnMap } = renderPane({
      events: overCap, todayStr: '2026-10-07', forecastDates: ['2026-10-08'],
    });
    expect(screen.queryByTestId('coming-up-day-chips')).toBeNull();
    expect(screen.getByTestId('coming-up-action'))
      .toHaveTextContent('Dark-sky spots: no forecast for 14 Oct');
    expect(screen.queryByRole('button', { name: /Long season/ })).toBeNull();
    fireEvent.click(screen.getByTestId('coming-up-card'));
    expect(onShowOnMap).not.toHaveBeenCalled();
  });
});

describe('WindowFirstComingUp — a single-day map entry draws a one-box row (plan §11.26)', () => {
  const METEOR_EVENTS = {
    entries: [wireEntry({
      id: 'meteor:2026-10-21:2026-10-21', title: 'Orionids',
      startDate: '2026-10-21', endDate: '2026-10-21',
      action: { label: 'Show dark-sky spots for 21 Oct →', kind: 'dark-sky-spots', date: '2026-10-21' },
    })],
    counts: COUNTS,
  };

  it('draws the lead, one chip and the singular caption when its day has no forecast', () => {
    renderPane({ events: METEOR_EVENTS, todayStr: '2026-10-07', forecastDates: ['2026-10-08'] });
    expect(screen.getByRole('group', { name: 'Dark-sky spots' })).toBeInTheDocument();
    expect(screen.getAllByTestId('coming-up-day-chip')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Wed 21 Oct, peak day — no forecast yet' })).toBeDisabled();
    expect(screen.getByTestId('coming-up-day-chips-note')).toHaveTextContent('no forecast for this day yet');
    expect(screen.queryByTestId('coming-up-action')).toBeNull();
  });

  it('opens the map on the entry’s date from its one live chip', () => {
    const { onShowOnMap } = renderPane({
      events: METEOR_EVENTS, todayStr: '2026-10-07', forecastDates: ['2026-10-21'],
    });
    fireEvent.click(screen.getByRole('button', { name: 'Show dark-sky spots for Wed 21 Oct, peak day' }));
    expect(onShowOnMap).toHaveBeenCalledWith({
      kind: 'coming-up', filterAction: null, darkSky: true, label: 'Orionids · Wed 21 Oct', date: '2026-10-21',
    });
  });
});

describe('WindowFirstComingUp — a plan entry’s chips open the map, not the Plan tab (plan §11.26)', () => {
  const SOLSTICE = {
    entries: [wireEntry({
      id: 'solstice:2026-12-18:2026-12-24', type: 'solstice', family: 'sun-moon',
      title: 'Winter solstice', metric: null, prose: null,
      startDate: '2026-12-18', endDate: '2026-12-24',
      action: { label: 'See the plan for 21 Dec →', kind: 'plan', date: '2026-12-21' },
    })],
    counts: COUNTS,
  };

  it('draws the All spots row and sends an unfiltered handoff with the chip’s date', () => {
    const { onShowOnMap, onGoToPlan } = renderPane({
      events: SOLSTICE, todayStr: '2026-12-10', forecastDates: ['2026-12-19'],
    });
    expect(screen.getByRole('group', { name: 'All spots by day' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show all spots for Sat 19 Dec' }));
    expect(onShowOnMap).toHaveBeenCalledWith({
      kind: 'coming-up', filterAction: null, darkSky: false,
      label: 'Winter solstice · Sat 19 Dec', date: '2026-12-19',
    });
    expect(onGoToPlan).not.toHaveBeenCalled();
    expect(screen.queryByText(/See the plan/)).toBeNull();
  });
});

describe('WindowFirstComingUp — a single-day plan entry draws a one-box All spots row (plan §11.26)', () => {
  const ECLIPSE = {
    entries: [wireEntry({
      id: 'eclipse:2026-10-21:2026-10-21', type: 'eclipse', family: 'eclipse', title: 'Partial eclipse',
      metric: null, prose: null, startDate: '2026-10-21', endDate: '2026-10-21',
      action: { label: 'See the plan for 21 Oct →', kind: 'plan', date: '2026-10-21' },
    })],
    counts: COUNTS,
  };

  it('opens the map unfiltered on its date from the one live chip, never the Plan tab', () => {
    const { onShowOnMap, onGoToPlan } = renderPane({
      events: ECLIPSE, todayStr: '2026-10-07', forecastDates: ['2026-10-21'],
    });
    expect(screen.getByRole('group', { name: 'All spots' })).toBeInTheDocument();
    expect(screen.queryByText(/See the plan/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show all spots for Wed 21 Oct, peak day' }));
    expect(onShowOnMap).toHaveBeenCalledWith({
      kind: 'coming-up', filterAction: null, darkSky: false,
      label: 'Partial eclipse · Wed 21 Oct', date: '2026-10-21',
    });
    expect(onGoToPlan).not.toHaveBeenCalled();
  });

  it('dims the chip with the singular caption when its day has no forecast', () => {
    renderPane({ events: ECLIPSE, todayStr: '2026-10-07', forecastDates: ['2026-10-08'] });
    expect(screen.getByRole('button', { name: 'Wed 21 Oct, peak day — no forecast yet' })).toBeDisabled();
    expect(screen.getByTestId('coming-up-day-chips-note')).toHaveTextContent('no forecast for this day yet');
  });
});

