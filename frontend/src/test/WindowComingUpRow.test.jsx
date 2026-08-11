import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import WindowComingUpRow from '../components/WindowComingUpRow.jsx';

/** A row descriptor as `buildComingUpRows` produces one. */
const row = (over = {}) => ({
  key: 'spring-tide:2026-08-16:2026-08-18',
  type: 'spring-tide',
  title: 'Spring tide run',
  detail: 'The moon’s alignment pulls the tide further out and further in than usual.',
  kindLabel: null,
  lead: 'In 7 days',
  dates: '16–18 Aug',
  whenNote: 'Biggest 16 Aug',
  facts: [],
  attribution: null,
  figuresMissing: false,
  ...over,
});

const FACTS = [
  { segments: [{ text: 'range ', tone: 'base' }, { text: '4.6 m', tone: 'strong' }] },
  { segments: [{ text: 'alignment ', tone: 'base' }, { text: 'LW 05:44', tone: 'strong' }] },
];

const renderRow = (over) => render(<WindowComingUpRow row={row(over)} />);

describe('WindowComingUpRow', () => {
  it('states when it is, what it is, and what it means', () => {
    renderRow();

    expect(screen.getByTestId('coming-up-lead')).toHaveTextContent('In 7 days');
    expect(screen.getByTestId('coming-up-dates')).toHaveTextContent('16–18 Aug');
    expect(screen.getByTestId('coming-up-title')).toHaveTextContent('Spring tide run');
    expect(screen.getByTestId('coming-up-detail'))
      .toHaveTextContent('The moon’s alignment pulls the tide further out');
  });

  it('renders no qualifier element when the span has nothing to qualify', () => {
    renderRow({ whenNote: null });
    expect(screen.queryByTestId('coming-up-when-note')).toBeNull();
    // The rest of the "when" block survives it — a missing qualifier is not a missing date.
    expect(screen.getByTestId('coming-up-dates')).toHaveTextContent('16–18 Aug');
  });

  it('renders one queryable chip per fact, keeping each one whole', () => {
    // Per-chip test-ids, matching `WindowAttributeRow`'s own `window-attribute-fact`. Without them
    // the only way to reach a chip is `container.querySelectorAll`, which the standards ban and
    // which counts DOM nodes rather than facts — a builder that emitted one four-segment chip
    // instead of two two-segment ones would pass a node count and fail a reader.
    renderRow({ facts: FACTS });

    const chips = within(screen.getByTestId('coming-up-facts')).getAllByTestId('coming-up-fact');
    expect(chips).toHaveLength(2);
    expect(chips[0]).toHaveTextContent('range 4.6 m');
    expect(chips[1]).toHaveTextContent('alignment LW 05:44');
  });

  it('sets the label dim and the value full ink, which is what the segments are for', () => {
    // `.wf-facts [data-tone='strong']` is the only thing that makes "range" read as a label and
    // "4.6 m" as the answer, so a chip rendered entirely in one tone is a real defect that reads
    // as flat grey. The util owns which segment is which; this pins that the row emits the
    // attribute at all.
    renderRow({ facts: FACTS });

    const chip = within(screen.getByTestId('coming-up-facts')).getAllByTestId('coming-up-fact')[0];
    const tones = within(chip).getAllByText(/./).map((el) => el.getAttribute('data-tone'));
    expect(tones).toEqual(['base', 'strong']);
  });

  it('renders no fact line at all when there are no chips, rather than an empty one', () => {
    renderRow({ facts: [] });
    expect(screen.queryByTestId('coming-up-facts')).toBeNull();
  });

  it('names whose figures these are when there are figures', () => {
    renderRow({ facts: FACTS, attribution: 'Figures for Bamburgh Beach' });
    expect(screen.getByTestId('coming-up-attribution'))
      .toHaveTextContent('Figures for Bamburgh Beach');
  });

  it('says the figures are missing, and keeps everything else on the row', () => {
    // The degrade rule on screen. It must read as "we know when, not how big" — not as a broken
    // row — so the dates, the title and the explanation all stay and only the numbers are absent.
    renderRow({ figuresMissing: true, facts: [], attribution: null });

    expect(screen.getByTestId('coming-up-figures-missing'))
      .toHaveTextContent('Dates only — no heights or clock times for this run.');
    expect(screen.getByTestId('coming-up-dates')).toHaveTextContent('16–18 Aug');
    expect(screen.getByTestId('coming-up-title')).toHaveTextContent('Spring tide run');
    expect(screen.getByTestId('coming-up-detail')).toBeInTheDocument();
  });

  it('never renders a placeholder figure in place of one it does not have', () => {
    // The rule the whole degrade path exists for: nothing is synthesised to fill the gap. A dash,
    // an "—" or a "0.0 m" would each be a measurement the backend never made.
    renderRow({ figuresMissing: true, facts: [], attribution: null });
    const text = screen.getByTestId('coming-up-row').textContent;
    expect(text).not.toMatch(/\d+(\.\d+)?\s*m\b/);
    expect(screen.queryByTestId('coming-up-facts')).toBeNull();
    expect(screen.queryByTestId('coming-up-attribution')).toBeNull();
  });

  it('says nothing about missing figures on a row that never had any to miss', () => {
    // An unclipped NLC season and an equinox both carry no chips and are perfectly healthy. The
    // caveat is gated on the type upstream, and this pins that the component honours it rather than
    // deriving its own from an empty fact list.
    renderRow({ type: 'nlc-season', figuresMissing: false, facts: [] });
    expect(screen.queryByTestId('coming-up-figures-missing')).toBeNull();
  });

  it('marks a row whose kind is not almanac', () => {
    renderRow({ kindLabel: 'Forecast' });
    expect(screen.getByTestId('coming-up-kind')).toHaveTextContent('Forecast');
  });

  it('leaves an almanac row unmarked, because the footer states that default once', () => {
    // A chip every row carries is a word that never varies. Rendering it would also put the same
    // claim on screen fifteen times over a footer that already makes it.
    renderRow({ kindLabel: null });
    expect(screen.queryByTestId('coming-up-kind')).toBeNull();
  });

  it('offers nothing to click, because the feed carries nowhere to go', () => {
    // The mock gives `.ev` a pointer cursor and never says what a click does. This payload has no
    // location, no rating and no region, so a click could only land on a map with nothing to
    // centre on — §6 bans a control that cannot act.
    renderRow({ facts: FACTS });
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('carries its type as a data attribute, so a later phase can style by channel', () => {
    renderRow({ type: 'nlc-season' });
    expect(screen.getByTestId('coming-up-row')).toHaveAttribute('data-type', 'nlc-season');
  });
});
