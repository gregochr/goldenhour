import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within, fireEvent, cleanup } from '@testing-library/react';
import WindowComingUpEntry from '../components/WindowComingUpEntry.jsx';

/** A view as `buildEntryView` produces one. */
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
  interactive: true,
  tide: null,
  coincidence: null,
  joinNote: null,
  ...over,
});

const FACTS = [
  { segments: [{ text: 'range ', tone: 'base' }, { text: '4.6 m', tone: 'strong' }] },
  { segments: [{ text: 'tide ', tone: 'base' }, { text: 'HW 05:44 · 34m before sunrise', tone: 'accent' }] },
];

const renderEntry = (over, onGoToPlan = vi.fn(), onShowOnMap = vi.fn()) => {
  const result = render(
    <WindowComingUpEntry entry={view(over)} onGoToPlan={onGoToPlan} onShowOnMap={onShowOnMap} />,
  );
  return { ...result, onGoToPlan, onShowOnMap };
};

describe('WindowComingUpEntry — the rail', () => {
  it('renders the day-of-week only when the rail carries one', () => {
    renderEntry({ rail: { dow: 'Wed', day: '2', month: 'Sept', isRange: false, countdown: 'in 5 days' } });
    expect(screen.getByTestId('coming-up-rail')).toHaveTextContent('Wed');
  });

  it('renders no day-of-week for a span', () => {
    renderEntry();
    expect(screen.getByTestId('coming-up-rail')).not.toHaveTextContent('Wed');
  });

  it('renders the countdown when there is one', () => {
    renderEntry();
    expect(screen.getByTestId('coming-up-countdown')).toHaveTextContent('in 7 days');
  });

  it('renders no countdown line when there is nothing to count from', () => {
    renderEntry({ rail: { dow: null, day: '16–18', month: 'Aug', isRange: true, countdown: null } });
    expect(screen.queryByTestId('coming-up-countdown')).toBeNull();
  });
});

describe('WindowComingUpEntry — the card', () => {
  it('states the title and the kind tag', () => {
    renderEntry();
    expect(screen.getByTestId('coming-up-title')).toHaveTextContent('Spring tide run');
    expect(screen.getByTestId('coming-up-kindtag')).toHaveTextContent('Almanac');
  });

  it('always renders the kind tag, unlike the marker-on-exception the old row used', () => {
    // P2 gives every entry a real kindTag; the footer's old "every date here is fixed" job moved
    // onto this chip, so it can no longer be suppressed for the almanac default.
    renderEntry({ kindTag: 'Almanac' });
    expect(screen.getByTestId('coming-up-kindtag')).toHaveTextContent('Almanac');
  });

  it('shows the forecast tag when the entry is forecast-driven', () => {
    renderEntry({ kindTag: 'Forecast · peak', isForecast: true });
    expect(screen.getByTestId('coming-up-kindtag')).toHaveTextContent('Forecast · peak');
  });

  it('renders no superlative tag when there is none', () => {
    renderEntry();
    expect(screen.queryByTestId('coming-up-superlative')).toBeNull();
  });

  it('renders the superlative tag when the server sent one', () => {
    renderEntry({ superlative: 'biggest until November' });
    expect(screen.getByTestId('coming-up-superlative')).toHaveTextContent('biggest until November');
  });

  it('renders the headline metric when there is one', () => {
    renderEntry({ metric: '~20/hr' });
    expect(screen.getByTestId('coming-up-metric')).toHaveTextContent('~20/hr');
  });

  it('renders no prose paragraph when there is none', () => {
    renderEntry();
    expect(screen.queryByTestId('coming-up-prose')).toBeNull();
  });

  it('renders the prose paragraph on a feature card', () => {
    renderEntry({ prose: 'The moon’s alignment pulls the tide further out.', isFeature: true });
    expect(screen.getByTestId('coming-up-prose'))
      .toHaveTextContent('The moon’s alignment pulls the tide further out.');
  });

  it('renders no fact line at all when there are no facts', () => {
    renderEntry();
    expect(screen.queryByTestId('coming-up-facts')).toBeNull();
  });

  it('renders one queryable chip per fact, keeping each one whole', () => {
    renderEntry({ facts: FACTS });
    const chips = within(screen.getByTestId('coming-up-facts')).getAllByTestId('coming-up-fact');
    expect(chips).toHaveLength(2);
    expect(chips[0]).toHaveTextContent('range 4.6 m');
    expect(chips[1]).toHaveTextContent('tide HW 05:44 · 34m before sunrise');
  });

  it('renders all three tones — base, strong and accent — from the served segments', () => {
    renderEntry({ facts: FACTS });
    const [chip1, chip2] = within(screen.getByTestId('coming-up-facts'))
      .getAllByTestId('coming-up-fact');
    const tonesOf = (chip) => within(chip).getAllByText(/./).map((el) => el.getAttribute('data-tone'));
    expect(tonesOf(chip1)).toEqual(['base', 'strong']);
    expect(tonesOf(chip2)).toEqual(['base', 'accent']);
  });

  it('renders no threshold line when the server sent none — a lone tide run, per §11.21', () => {
    renderEntry({ threshold: null });
    expect(screen.queryByTestId('coming-up-threshold')).toBeNull();
  });

  it('renders the threshold line when the server sent one', () => {
    renderEntry({ threshold: 'The other 2 runs in this window ranged 4.1–4.9 m.' });
    expect(screen.getByTestId('coming-up-threshold'))
      .toHaveTextContent('The other 2 runs in this window ranged 4.1–4.9 m.');
  });

  it('always renders the single action’s label', () => {
    renderEntry();
    expect(screen.getByTestId('coming-up-action')).toHaveTextContent('Show coastal spots for 16 Aug →');
  });
});

describe('WindowComingUpEntry — the dashed rule', () => {
  it('marks a forecast entry\'s card as dashed', () => {
    renderEntry({ isForecast: true, action: { label: 'See the plan for 2 Sept →', kind: 'plan', date: '2026-09-02' }, interactive: true });
    expect(screen.getByTestId('coming-up-card')).toHaveClass('wf-cu-card-fc');
  });

  it('leaves an almanac entry\'s card solid', () => {
    renderEntry({ isForecast: false });
    expect(screen.getByTestId('coming-up-card')).not.toHaveClass('wf-cu-card-fc');
  });
});

describe('WindowComingUpEntry — the click seam (plan §11.5, D8)', () => {
  it('wires a plan-kind action to a real, keyboard-operable button', () => {
    const { onGoToPlan } = renderEntry({
      title: 'Spring tide run',
      action: { label: 'See the plan for 2 Sept →', kind: 'plan', date: '2026-09-02' },
      interactive: true,
    });
    const button = screen.getByRole('button', { name: 'Spring tide run Almanac See the plan for 2 Sept →' });
    fireEvent.click(button);
    expect(onGoToPlan).toHaveBeenCalledWith('2026-09-02');
  });

  it('never dispatches onGoToPlan for a served kind other than plan — the dispatch reads '
      + 'action.kind itself, not the interactive flag', () => {
    // Guards the P3b seam from `comingUpFeed.js`'s own doc comment: dispatch must not fall through
    // to `onGoToPlan` by default when a served kind other than `plan` is wired via `interactive`.
    const { onGoToPlan } = renderEntry({
      action: { label: 'Show coastal spots for 16 Aug →', kind: 'coastal-spots', date: '2026-08-16' },
      interactive: true,
    });
    fireEvent.click(screen.getByRole('button'));
    expect(onGoToPlan).not.toHaveBeenCalled();
  });

  it('dispatches a coastal-spots action through onShowOnMap with the SEASCAPE filter (D8)', () => {
    const { onShowOnMap } = renderEntry({
      title: 'Spring tide run',
      action: { label: 'Show coastal spots for 16 Aug →', kind: 'coastal-spots', date: '2026-08-16' },
      interactive: true,
    });
    fireEvent.click(screen.getByRole('button'));
    expect(onShowOnMap).toHaveBeenCalledWith({
      kind: 'coming-up', filterAction: 'SEASCAPE', label: 'Spring tide run', date: '2026-08-16',
    });
  });

  it('dispatches a dark-sky-spots action through onShowOnMap with the darkSky flag (D8)', () => {
    const { onShowOnMap } = renderEntry({
      title: 'Perseids',
      action: { label: 'Show dark-sky spots →', kind: 'dark-sky-spots', date: '2026-08-16' },
      interactive: true,
    });
    fireEvent.click(screen.getByRole('button'));
    expect(onShowOnMap).toHaveBeenCalledWith({
      kind: 'coming-up', darkSky: true, label: 'Perseids', date: '2026-08-16',
    });
  });

  it('renders an entry with no interactive served kind as inert — no button, no click promise', () => {
    // `interactive` is now true for all three served kinds, but the served view can still arrive
    // non-interactive (e.g. `comingUpFeed.js`'s `{ kind: null }` fallback for a missing action) —
    // that branch still needs its own honest, un-clickable render.
    const { onGoToPlan, onShowOnMap } = renderEntry({
      action: { label: '', kind: null, date: '2026-08-16' },
      interactive: false,
    });
    expect(screen.queryByRole('button')).toBeNull();
    const card = screen.getByTestId('coming-up-card');
    expect(card).toHaveClass('wf-cu-card-inert');
    fireEvent.click(card);
    expect(onGoToPlan).not.toHaveBeenCalled();
    expect(onShowOnMap).not.toHaveBeenCalled();
  });

  it('computes the button’s accessible name from its own content, never an aria-label override', () => {
    // A corrected defect, not a style choice: `button` is an ARIA role with `childrenPresentational
    // : true`, so an explicit `aria-label` here would not just set the name — it would throw away
    // every fact, the prose and the threshold line too, leaving a screen-reader user with only the
    // action's own sentence and none of the card's actual content. The full text must survive as
    // the computed name instead, exactly as `WindowFirstComingUpHandoff`'s own button already does.
    renderEntry({
      title: 'Spring tide run',
      superlative: 'biggest until November',
      facts: FACTS,
      threshold: 'The other 2 runs in this window ranged 4.1–4.9 m.',
      action: { label: 'See the plan for 2 Sept →', kind: 'plan', date: '2026-09-02' },
      interactive: true,
    });
    expect(screen.getByRole('button')).toHaveAccessibleName(
      'Spring tide run Almanac biggest until November range 4.6 m tide HW 05:44 · 34m before '
      + 'sunrise The other 2 runs in this window ranged 4.1–4.9 m. See the plan for 2 Sept →',
    );
  });

  it('never glues the kind tag straight into the action label when nothing sits between them', () => {
    // The narrowest case the fix guards: an entry with no superlative, metric, prose, facts or
    // threshold leaves the kind tag directly followed by the action link in the DOM, with nothing
    // rendered between them — exactly where a missing `{' '}` text-node sibling would glue two
    // words together in the computed name.
    renderEntry({
      title: 'Autumn equinox',
      action: { label: 'See the plan for 22 Sept →', kind: 'plan', date: '2026-09-22' },
      interactive: true,
    });
    expect(screen.getByRole('button'))
      .toHaveAccessibleName('Autumn equinox Almanac See the plan for 22 Sept →');
  });
});

describe('WindowComingUpEntry — the feature title', () => {
  it('carries the feature class when the view says so', () => {
    renderEntry({ isFeature: true });
    expect(screen.getByTestId('coming-up-card')).toHaveClass('wf-cu-card-feat');
  });

  it('carries no feature class on a plain card', () => {
    renderEntry({ isFeature: false });
    expect(screen.getByTestId('coming-up-card')).not.toHaveClass('wf-cu-card-feat');
  });
});

describe('WindowComingUpEntry — structure', () => {
  it('renders as a list item, so the enclosing list gets a boundary', () => {
    renderEntry();
    expect(screen.getByRole('listitem')).toBe(screen.getByTestId('coming-up-entry'));
  });

  it('carries the family on the card, driving its topic colour', () => {
    renderEntry({ family: 'dust' });
    expect(screen.getByTestId('coming-up-card')).toHaveAttribute('data-family', 'dust');
  });
});

describe('WindowComingUpEntry — the tide sparkline (design README §4, plan §6b)', () => {
  it('renders no sparkline when the entry carries no tide field', () => {
    renderEntry({ tide: null });
    expect(screen.queryByTestId('coming-up-tide-sparkline')).toBeNull();
  });

  it('renders the sparkline on a tide entry, as a fact-row item', () => {
    renderEntry({ tide: { range: 5.2, delta: 1.9, phase: 'HW' } });
    const sparkline = screen.getByTestId('coming-up-tide-sparkline');
    expect(within(screen.getByTestId('coming-up-facts')).getByTestId('coming-up-tide-sparkline'))
      .toBe(sparkline);
  });

  it('states the range and the delta in words beside the picture — the accessible answer', () => {
    renderEntry({ tide: { range: 5.2, delta: 1.9, phase: 'HW' } });
    expect(screen.getByTestId('coming-up-tide-sparkline-label')).toHaveTextContent('5.2 m +1.9 vs avg');
  });

  it('signs a negative delta without a leading plus', () => {
    renderEntry({ tide: { range: 3.1, delta: -0.4, phase: 'HW' } });
    expect(screen.getByTestId('coming-up-tide-sparkline-label')).toHaveTextContent('3.1 m -0.4 vs avg');
  });

  it('signs an exactly-average delta without a leading plus', () => {
    renderEntry({ tide: { range: 3.3, delta: 0, phase: 'HW' } });
    expect(screen.getByTestId('coming-up-tide-sparkline-label')).toHaveTextContent('3.3 m 0.0 vs avg');
  });

  it('hides the SVG from the accessibility tree — the label carries the answer', () => {
    renderEntry({ tide: { range: 5.2, delta: 1.9, phase: 'HW' } });
    expect(screen.getByTestId('coming-up-tide-sparkline')).toHaveAttribute('aria-hidden', 'true');
  });

  it('renders the sparkline alongside served facts, not instead of them', () => {
    renderEntry({ tide: { range: 5.2, delta: 1.9, phase: 'HW' }, facts: FACTS });
    const facts = screen.getByTestId('coming-up-facts');
    expect(within(facts).getByTestId('coming-up-tide-sparkline')).toBeInTheDocument();
    expect(within(facts).getAllByTestId('coming-up-fact')).toHaveLength(2);
  });

  it('marks a HIGH water above the axis and a LOW water below it — the geometry, not just the '
      + 'label text', () => {
    // A mutation that swapped `phase === 'LW'` for `phase === 'HW'` (or vice versa) inside
    // ComingUpTideSparkline would leave every test above green, since none of them read the
    // marker's own position — only this one does.
    renderEntry({ tide: { range: 5.2, delta: 1.9, phase: 'HW' } });
    const highCy = Number(screen.getByTestId('coming-up-tide-sparkline-marker').getAttribute('cy'));
    cleanup();
    renderEntry({ tide: { range: 5.2, delta: 1.9, phase: 'LW' } });
    const lowCy = Number(screen.getByTestId('coming-up-tide-sparkline-marker').getAttribute('cy'));
    expect(highCy).toBeLessThan(12); // above the axis (SVG y grows downward)
    expect(lowCy).toBeGreaterThan(12); // below the axis
  });
});

describe('WindowComingUpEntry — the coincidence card (D10, plan §6b)', () => {
  const COINCIDENCE = [
    { family: 'sun-moon', name: 'Supermoon', factsLabel: 'Mon 26 Oct · moonrise 17:22' },
  ];

  it('renders no coincidence card when the entry did not merge', () => {
    renderEntry({ coincidence: null });
    expect(screen.queryByTestId('coming-up-coincidence')).toBeNull();
  });

  it('renders only the served line for the merged topic — never a synthesized self line', () => {
    // A first draft re-printed `entry.title` as a "self" line here, which duplicated the title row
    // verbatim (the served title is not a combined name the way the design's own `nm` is) — see
    // the class doc. The card's own identity is the title row; this renders only what was served.
    renderEntry({
      title: 'Spring tide run',
      family: 'coastal',
      coincidence: COINCIDENCE,
      joinNote: 'Same cause, two effects.',
    });
    const card = screen.getByTestId('coming-up-coincidence');
    expect(within(card).queryByText('Spring tide run')).toBeNull();
    expect(screen.getAllByTestId('coming-up-coincidence-line')).toHaveLength(1);
    expect(within(card).getByText('Supermoon')).toBeInTheDocument();
  });

  it('keys each line’s swatch colour on that LINE’s own served family, not the card’s', () => {
    renderEntry({ family: 'coastal', coincidence: COINCIDENCE, joinNote: 'Same cause.' });
    expect(screen.getByTestId('coming-up-coincidence-line')).toHaveAttribute('data-family', 'sun-moon');
  });

  it('renders the served factsLabel verbatim — the absorbed run’s range is load-bearing (P2 log)', () => {
    renderEntry({ coincidence: COINCIDENCE, joinNote: 'Same cause.' });
    expect(screen.getByTestId('coming-up-coincidence-facts'))
      .toHaveTextContent('Mon 26 Oct · moonrise 17:22');
  });

  it('renders the joining sentence below the coincidence lines', () => {
    renderEntry({
      coincidence: COINCIDENCE,
      joinNote: 'One perigee causes both, so the pair scores as the maximum of the two: 9.0 bits.',
    });
    expect(screen.getByTestId('coming-up-join-note')).toHaveTextContent(
      'One perigee causes both, so the pair scores as the maximum of the two: 9.0 bits.',
    );
  });

  it('renders BOTH prose and the coincidence card when the backend serves both — a corrected first '
      + 'attempt (see the class doc)', () => {
    // ComingUpAssembler.assemble runs markFirstOfType AFTER mergeCoincidences, so a merged winner
    // that is also first-of-its-type in the window legitimately carries both fields. An earlier
    // ternary treated them as exclusive and silently dropped the prose whenever both were served.
    renderEntry({
      prose: 'The moon’s alignment pulls the tide further out than usual.',
      coincidence: COINCIDENCE,
      joinNote: 'Same cause.',
      isFeature: true,
    });
    expect(screen.getByTestId('coming-up-prose')).toHaveTextContent(
      'The moon’s alignment pulls the tide further out than usual.',
    );
    expect(screen.getByTestId('coming-up-coincidence')).toBeInTheDocument();
  });

  it('renders no join note when the server sent none', () => {
    renderEntry({ coincidence: COINCIDENCE, joinNote: null });
    expect(screen.queryByTestId('coming-up-join-note')).toBeNull();
  });
});
