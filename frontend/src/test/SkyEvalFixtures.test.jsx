import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import FixturesTab from '../components/skyeval/FixturesTab.jsx';
import {
  HEAD, HEAD_SHA, PREV, PREV_SHA, modelStats, overview, run, runFixture, session,
} from './skyEvalFixtures.js';

const FIXTURES = [
  { name: 'alpha', bandLo: 1, bandHi: 2, firstSeenAt: '2026-07-01T04:00:00Z' },
  { name: 'bravo', bandLo: 3, bandHi: 5, firstSeenAt: '2026-07-01T04:00:00Z' },
  { name: 'charlie', bandLo: 2, bandHi: 3, firstSeenAt: '2026-10-30T04:00:00Z' },
];

/**
 * Haiku 5.5 has three runs: 10 and 11 on the previous version (11 a manual run), 12 on HEAD. Sonnet 5.5 has one
 * (20). Charlie was added after run 10, so run 10 has no row for it.
 */
function gridOverview(extra = {}) {
  return overview({
    fixtures: FIXTURES,
    versions: [HEAD, PREV],
    models: [
      modelStats({ model: 'SONNET_55', label: 'Sonnet 5.5', lanes: ['BATCH_NEAR_TERM'], status: 'HELD' }),
      modelStats({ model: 'HAIKU_55', label: 'Haiku 5.5', lanes: [] }),
      modelStats({ model: 'OPUS', label: 'Opus', lanes: [] }),
    ],
    sessions: [
      session({
        id: 3, trigger: 'SCHEDULED', version: HEAD_SHA, startedAt: '2026-11-02T04:00:00Z',
        runs: [run({ id: 12, model: 'HAIKU_55' })],
      }),
      session({
        id: 2, trigger: 'MANUAL', version: PREV_SHA, startedAt: '2026-10-28T10:00:00Z',
        runs: [run({ id: 11, model: 'HAIKU_55' }), run({ id: 20, model: 'SONNET_55' })],
      }),
      session({
        id: 1, trigger: 'SCHEDULED', version: PREV_SHA, startedAt: '2026-10-26T04:00:00Z',
        runs: [run({ id: 10, model: 'HAIKU_55' })],
      }),
    ],
    runFixtures: [
      runFixture(10, 'alpha'),
      runFixture(10, 'bravo', { inBand: 5, below: 3, mean: 2.6, min: 1, max: 4 }),
      runFixture(11, 'alpha', { inBand: 6, above: 2, mean: 2.9, min: 2, max: 4 }),
      runFixture(11, 'bravo'),
      runFixture(11, 'charlie', { inBand: 0, below: 3, above: 5, mean: 3.4, min: 2, max: 5 }),
      runFixture(12, 'alpha', { inBand: 4, below: 1, above: 3 }),
      runFixture(12, 'bravo', { inBand: 7, below: 1 }),
      runFixture(12, 'charlie'),
      runFixture(20, 'alpha'),
    ],
    modelFixtures: [
      { model: 'HAIKU_55', fixture: 'alpha', last4Misses: 6, last4Pct: 75 },
      { model: 'HAIKU_55', fixture: 'bravo', last4Misses: 7, last4Pct: 70.8 },
      { model: 'HAIKU_55', fixture: 'charlie', last4Misses: 0, last4Pct: 100 },
      { model: 'SONNET_55', fixture: 'alpha', last4Misses: 0, last4Pct: 100 },
    ],
    ...extra,
  });
}

const renderTab = (ov = gridOverview(), preselected = null) => render(<FixturesTab overview={ov} preselected={preselected} />);

const rowOrder = () => screen.getAllByTestId(/^sky-eval-fixture-row-/).map((el) => el.dataset.testid.replace('sky-eval-fixture-row-', ''));
const cell = (fixture, runId) => screen.getByTestId(`sky-eval-cell-${fixture}-${runId}`);

describe('FixturesTab: model choice', () => {
  it('opens on the near-term lane model when it has runs, listing only models with grid data', () => {
    renderTab();
    expect(screen.getByTestId('sky-eval-fixtures-tab')).toHaveAttribute('data-model', 'SONNET_55');
    expect(screen.getByTestId('sky-eval-fixtures-model-SONNET_55')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('sky-eval-fixtures-model-HAIKU_55')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByTestId('sky-eval-fixtures-model-OPUS')).not.toBeInTheDocument();
  });

  it('opens on a preselected model, and ignores one with no data', () => {
    const { unmount } = renderTab(gridOverview(), 'HAIKU_55');
    expect(screen.getByTestId('sky-eval-fixtures-tab')).toHaveAttribute('data-model', 'HAIKU_55');
    unmount();
    renderTab(gridOverview(), 'OPUS');
    expect(screen.getByTestId('sky-eval-fixtures-tab')).toHaveAttribute('data-model', 'SONNET_55');
  });

  it('switches model: new columns, new row order, last-4 from that model, open row closed', () => {
    renderTab();
    expect(screen.getAllByTestId(/^sky-eval-column-\d+$/)).toHaveLength(1);
    fireEvent.click(screen.getByTestId('sky-eval-fixture-toggle-alpha'));
    expect(screen.getByTestId('sky-eval-fixture-history-alpha')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('sky-eval-fixtures-model-HAIKU_55'));
    expect(screen.getByTestId('sky-eval-fixtures-tab')).toHaveAttribute('data-model', 'HAIKU_55');
    expect(screen.getAllByTestId(/^sky-eval-column-\d+$/)).toHaveLength(3);
    expect(screen.queryByTestId('sky-eval-fixture-history-alpha')).not.toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-fixtures-caption')).toHaveTextContent('last 3 runs of Haiku 5.5');
    expect(screen.getByTestId('sky-eval-fixture-last4-bravo')).toHaveTextContent('70.8%');
  });

  it('says so when no model has a completed run', () => {
    renderTab(gridOverview({ runFixtures: [] }));
    expect(screen.getByTestId('sky-eval-fixtures-empty')).toBeInTheDocument();
  });
});

describe('FixturesTab: the grid', () => {
  it('sorts fixtures by last-4 misses, worst first, with the fixture id and band in the row header', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    expect(rowOrder()).toEqual(['bravo', 'alpha', 'charlie']);
    const header = screen.getByTestId('sky-eval-fixture-toggle-bravo');
    expect(header).toHaveTextContent('bravo');
    expect(header).toHaveTextContent('band 3–5');
  });

  it('shows the last-4 percentage, coral below 90', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    expect(screen.getByTestId('sky-eval-fixture-last4-bravo')).toHaveAttribute('data-tone', 'coral');
    expect(screen.getByTestId('sky-eval-fixture-last4-alpha')).toHaveAttribute('data-tone', 'coral');
    expect(screen.getByTestId('sky-eval-fixture-last4-charlie')).toHaveAttribute('data-tone', 'plain');
    expect(screen.getByTestId('sky-eval-fixture-last4-charlie')).toHaveTextContent('100%');
  });

  it('draws an all-in-band cell soft green at .2 with no text', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    const c = cell('alpha', 10);
    expect(c).toHaveAttribute('data-kind', 'inband');
    const fill = within(c).getByTestId('sky-eval-cell-fill');
    expect(fill).toHaveAttribute('fill', '#8AAE72');
    expect(fill).toHaveAttribute('fill-opacity', '0.2');
    expect(c).not.toHaveTextContent('↓');
    expect(c).not.toHaveTextContent('↑');
  });

  it('draws a mostly-below cell tide with ↓n and .18 + .6 x the miss ratio', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    const c = cell('bravo', 10);
    expect(c).toHaveAttribute('data-kind', 'below');
    expect(c).toHaveTextContent('↓3');
    const fill = within(c).getByTestId('sky-eval-cell-fill');
    expect(fill).toHaveAttribute('fill', '#6FA8B0');
    expect(Number(fill.getAttribute('fill-opacity'))).toBeCloseTo(0.18 + 0.6 * (3 / 8), 10);
  });

  it('draws a mostly-above cell coral with ↑n, and both arrows when mixed', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    const above = cell('alpha', 11);
    expect(above).toHaveAttribute('data-kind', 'above');
    expect(above).toHaveTextContent('↑2');
    expect(within(above).getByTestId('sky-eval-cell-fill')).toHaveAttribute('fill', '#E8593F');
    expect(Number(within(above).getByTestId('sky-eval-cell-fill').getAttribute('fill-opacity'))).toBeCloseTo(0.18 + 0.6 * (2 / 8), 10);
    const mixed = cell('alpha', 12);
    expect(mixed).toHaveAttribute('data-kind', 'above');
    expect(mixed).toHaveTextContent('↓1↑3');
    expect(Number(within(mixed).getByTestId('sky-eval-cell-fill').getAttribute('fill-opacity'))).toBeCloseTo(0.18 + 0.6 * (4 / 8), 10);
  });

  it('does not draw a run with failed calls as all in band: a grey ✕k cell with the failure in its label', () => {
    renderTab(gridOverview({ runFixtures: [...gridOverview().runFixtures, runFixture(12, 'charlie', { inBand: 5 })].filter((r) => !(r.runId === 12 && r.fixture === 'charlie' && r.inBand === 8)) }), 'HAIKU_55');
    const c = cell('charlie', 12);
    expect(c).toHaveAttribute('data-kind', 'failed');
    expect(c).toHaveTextContent('✕3');
    expect(within(c).getByTestId('sky-eval-cell-fill')).toHaveAttribute('fill', '#9A9086');
    expect(c).toHaveAttribute('aria-label', expect.stringContaining('3 failed'));
  });

  it('draws a fixture not yet in a run as a dot with no fill', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    const c = cell('charlie', 10);
    expect(c).toHaveAttribute('data-kind', 'absent');
    expect(c).toHaveTextContent('·');
    expect(within(c).queryByTestId('sky-eval-cell-fill')).not.toBeInTheDocument();
    expect(c).toHaveAttribute('aria-label', expect.stringContaining('not in this run'));
  });

  it('gives each cell the tooltip facts as its accessible name and its SVG title', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    const c = cell('bravo', 10);
    const text = 'bravo, Mon 26 Oct, c41e2d8: 5/8 in band, 3 below, mean rating 2.6, band 3–5';
    expect(c).toHaveAttribute('aria-label', text);
    expect(c.querySelector('title')).toHaveTextContent(text);
    expect(cell('alpha', 11)).toHaveAttribute('aria-label', expect.stringContaining('(manual)'));
  });

  it('puts the date vertically in the column header, with ·m for a manual run', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    expect(within(screen.getByTestId('sky-eval-column-10')).getByTestId('sky-eval-column-date')).toHaveTextContent(/^26 Oct$/);
    expect(within(screen.getByTestId('sky-eval-column-11')).getByTestId('sky-eval-column-date')).toHaveTextContent(/^28 Oct ·m$/);
    expect(within(screen.getByTestId('sky-eval-column-12')).getByTestId('sky-eval-column-date')).toHaveTextContent(/^2 Nov$/);
  });

  it('marks a version change with a gold left border and the 4-character id above the column, and only there', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    expect(screen.getByTestId('sky-eval-column-10')).toHaveAttribute('data-version-changed', 'false');
    expect(screen.getByTestId('sky-eval-column-11')).toHaveAttribute('data-version-changed', 'false');
    const changed = screen.getByTestId('sky-eval-column-12');
    expect(changed).toHaveAttribute('data-version-changed', 'true');
    expect(changed).toHaveClass('border-skyeval-version');
    expect(screen.getByTestId('sky-eval-column-10')).not.toHaveClass('border-skyeval-version');
    expect(within(changed).getByTestId('sky-eval-column-version')).toHaveTextContent('a91b');
    expect(within(screen.getByTestId('sky-eval-column-11')).queryByTestId('sky-eval-column-version')).not.toBeInTheDocument();
    expect(within(cell('alpha', 12)).getByTestId('sky-eval-cell-version-border')).toHaveAttribute('width', '2');
    expect(within(cell('alpha', 11)).queryByTestId('sky-eval-cell-version-border')).not.toBeInTheDocument();
  });

  it('labels a change from a known version to an unknown one with "?"', () => {
    const sessions = gridOverview().sessions.map((s) => (s.id === 3 ? { ...s, version: null } : s));
    renderTab(gridOverview({ sessions }), 'HAIKU_55');
    expect(screen.getByTestId('sky-eval-column-12')).toHaveAttribute('data-version-changed', 'true');
    expect(within(screen.getByTestId('sky-eval-column-12')).getByTestId('sky-eval-column-version')).toHaveTextContent('?');
  });

  it('sorts a fixture with failed calls above one with no misses even with equal miss counts', () => {
    renderTab(gridOverview({
      modelFixtures: [
        { model: 'HAIKU_55', fixture: 'alpha', last4Misses: 0, last4Pct: 100 },
        { model: 'HAIKU_55', fixture: 'bravo', last4Misses: 0, last4Pct: 62.5 },
        { model: 'HAIKU_55', fixture: 'charlie', last4Misses: 0, last4Pct: 100 },
      ],
    }), 'HAIKU_55');
    expect(rowOrder()[0]).toBe('bravo');
  });

  it('shows the legend, and keeps the horizontal scroll inside the grid', () => {
    renderTab();
    expect(screen.getByTestId('sky-eval-fixtures-legend')).toHaveTextContent('all ratings in band');
    expect(screen.getByTestId('sky-eval-fixtures-legend')).toHaveTextContent('new version');
    expect(screen.getByTestId('sky-eval-fixtures-legend')).toHaveTextContent('✕ = failed calls');
    expect(screen.getByTestId('sky-eval-fixtures-scroll')).toHaveClass('overflow-x-auto');
  });

  it('renders 30 fixtures against 13 runs', () => {
    const fixtures = Array.from({ length: 30 }, (_, i) => ({
      name: `fx-${String(i).padStart(2, '0')}`, bandLo: 1, bandHi: 2, firstSeenAt: '2026-07-01T04:00:00Z',
    }));
    const sessions = Array.from({ length: 15 }, (_, i) => session({
      id: i + 1, trigger: 'SCHEDULED', version: HEAD_SHA,
      startedAt: `2026-10-${String(i + 10).padStart(2, '0')}T04:00:00Z`,
      runs: [run({ id: 100 + i, model: 'SONNET_55' })],
    }));
    const runFixtures = sessions.flatMap((s) => fixtures.map((f) => runFixture(s.runs[0].id, f.name)));
    renderTab(gridOverview({ fixtures, sessions, runFixtures, modelFixtures: [] }));
    expect(screen.getAllByTestId(/^sky-eval-fixture-row-/)).toHaveLength(30);
    expect(screen.getAllByTestId(/^sky-eval-column-\d+$/)).toHaveLength(13);
    expect(screen.getAllByTestId(/^sky-eval-cell-/).filter((el) => el.tagName === 'svg')).toHaveLength(30 * 13);
  });
});

describe('FixturesTab: rating history', () => {
  it('expands a fixture row with a real button, and collapses it again', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    const toggle = screen.getByTestId('sky-eval-fixture-toggle-bravo');
    expect(toggle.tagName).toBe('BUTTON');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('sky-eval-fixture-history-bravo')).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const history = screen.getByTestId('sky-eval-fixture-history-bravo');
    expect(toggle.contains(history)).toBe(false);
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('sky-eval-fixture-history-bravo')).not.toBeInTheDocument();
  });

  it('opens one fixture at a time', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    fireEvent.click(screen.getByTestId('sky-eval-fixture-toggle-bravo'));
    fireEvent.click(screen.getByTestId('sky-eval-fixture-toggle-alpha'));
    expect(screen.queryByTestId('sky-eval-fixture-history-bravo')).not.toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-fixture-history-alpha')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-fixture-toggle-bravo')).toHaveAttribute('aria-expanded', 'false');
  });

  it('draws the band, a dot per run the fixture is in coloured by side, and the version line', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    fireEvent.click(screen.getByTestId('sky-eval-fixture-toggle-bravo'));
    const history = screen.getByTestId('sky-eval-fixture-history-bravo');
    expect(within(history).getByTestId('sky-eval-history-band')).toBeInTheDocument();
    expect(history).toHaveTextContent('band 3–5');
    expect(within(history).getByTestId('sky-eval-history-point-10')).toHaveAttribute('data-kind', 'below');
    expect(within(history).getByTestId('sky-eval-history-point-11')).toHaveAttribute('data-kind', 'model');
    expect(within(history).getByTestId('sky-eval-history-point-12')).toHaveAttribute('data-kind', 'below');
    expect(within(history).getAllByTestId('sky-eval-history-version-line').length).toBeGreaterThan(0);
    expect(within(history).getByTestId('sky-eval-fixture-history-svg')).toHaveAttribute('viewBox', '0 0 700 190');
  });

  it('says so, instead of drawing a chart, when the model has never scored the fixture', () => {
    renderTab(gridOverview({ runFixtures: gridOverview().runFixtures.filter((r) => r.fixture !== 'charlie') }), 'HAIKU_55');
    fireEvent.click(screen.getByTestId('sky-eval-fixture-toggle-charlie'));
    expect(screen.getByTestId('sky-eval-fixture-history-charlie')).toHaveTextContent('Not yet scored on Haiku 5.5.');
    expect(screen.queryByTestId('sky-eval-fixture-history-svg')).not.toBeInTheDocument();
  });

  it('leaves out runs the fixture was not in', () => {
    renderTab(gridOverview(), 'HAIKU_55');
    fireEvent.click(screen.getByTestId('sky-eval-fixture-toggle-charlie'));
    const history = screen.getByTestId('sky-eval-fixture-history-charlie');
    expect(within(history).queryByTestId('sky-eval-history-point-10')).not.toBeInTheDocument();
    expect(within(history).getByTestId('sky-eval-history-point-11')).toBeInTheDocument();
  });
});
