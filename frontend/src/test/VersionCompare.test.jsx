import { render, screen, fireEvent, within } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import VersionCompare from '../components/skyeval/VersionCompare.jsx';

const NOW = new Date('2026-11-04T12:00:00Z');

const comparison = (sha, rows, over = {}) => ({
  version: {
    sha: `${sha}full`,
    shortSha: sha,
    message: `Change ${sha}`,
    committedAt: '2026-11-02T10:00:00Z',
    ...over,
  },
  rows,
});

const row = (model, over = {}) => ({
  model,
  lanes: [],
  notRun: false,
  beforePct: 95,
  afterPct: 96,
  deltaPts: 1,
  moved: [],
  ...over,
});

const HEAD = comparison('aaa1111', [
  row('HAIKU', { lanes: ['BATCH_FAR_TERM'], deltaPts: -5.2, beforePct: 98, afterPct: 92.8 }),
  row('SONNET', { deltaPts: 4, beforePct: 90, afterPct: 94 }),
  row('OPUS', { deltaPts: 0.5 }),
  row('LAZY', { notRun: true }),
]);
const OLDER = comparison('bbb2222', [row('HAIKU', { deltaPts: 2 })], { message: null, committedAt: null });

describe('VersionCompare', () => {
  it('says so when there is nothing to compare', () => {
    render(<VersionCompare comparisons={[]} now={NOW} />);
    expect(screen.getByTestId('sky-eval-compare-empty')).toHaveTextContent(
      'No version has runs both on it and before it yet.',
    );
    expect(screen.queryByTestId('sky-eval-compare-rows')).toBeNull();
  });

  it('opens on the first (HEAD) version with its message and date', () => {
    render(<VersionCompare comparisons={[HEAD, OLDER]} now={NOW} />);
    expect(screen.getByTestId('sky-eval-version-chip-aaa1111')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('sky-eval-version-chip-bbb2222')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('sky-eval-version-chip-aaa1111')).toHaveTextContent('2d ago');
    const header = screen.getByTestId('sky-eval-compare-version');
    expect(header).toHaveTextContent('Change aaa1111');
    expect(header).toHaveTextContent('Mon 2 Nov');
  });

  it('switches to the picked version and drops the message and date when it has none', () => {
    render(<VersionCompare comparisons={[HEAD, OLDER]} now={NOW} />);
    fireEvent.click(screen.getByTestId('sky-eval-version-chip-bbb2222'));
    expect(screen.getByTestId('sky-eval-version-chip-bbb2222')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('sky-eval-version-chip-aaa1111')).toHaveAttribute('aria-pressed', 'false');
    const header = screen.getByTestId('sky-eval-compare-version');
    expect(header).not.toHaveTextContent('Change');
    expect(header).not.toHaveTextContent('·');
    expect(screen.queryByTestId('sky-eval-compare-row-SONNET')).toBeNull();
    expect(screen.getByTestId('sky-eval-compare-row-HAIKU')).toBeInTheDocument();
  });

  it('shows before, after and the change, toned by size', () => {
    render(<VersionCompare comparisons={[HEAD]} now={NOW} />);
    const haiku = screen.getByTestId('sky-eval-compare-row-HAIKU');
    expect(haiku).toHaveTextContent('98%');
    expect(haiku).toHaveTextContent('92.8%');
    const change = (name) => within(screen.getByTestId(`sky-eval-compare-row-${name}`)).getByTestId('sky-eval-compare-change');
    expect(change('HAIKU')).toHaveTextContent('−5.2 pts');
    expect(change('HAIKU')).toHaveAttribute('data-tone', 'bad');
    expect(change('SONNET')).toHaveTextContent('+4 pts');
    expect(change('SONNET')).toHaveAttribute('data-tone', 'good');
    expect(change('OPUS')).toHaveTextContent('+0.5 pts');
    expect(change('OPUS')).toHaveAttribute('data-tone', 'neutral');
  });

  it('tags production models and only those', () => {
    render(<VersionCompare comparisons={[HEAD]} now={NOW} />);
    expect(within(screen.getByTestId('sky-eval-compare-row-HAIKU')).getByTestId('sky-eval-prod-tag')).toBeInTheDocument();
    expect(within(screen.getByTestId('sky-eval-compare-row-SONNET')).queryByTestId('sky-eval-prod-tag')).toBeNull();
  });

  it('shows a not-run row without a change figure', () => {
    render(<VersionCompare comparisons={[HEAD]} now={NOW} />);
    const lazy = screen.getByTestId('sky-eval-compare-row-LAZY');
    expect(within(lazy).getByTestId('sky-eval-compare-notrun')).toHaveTextContent('Not run on this version');
    expect(within(lazy).queryByTestId('sky-eval-compare-change')).toBeNull();
  });

  it('says no fixture moved when none did', () => {
    render(<VersionCompare comparisons={[HEAD]} now={NOW} />);
    expect(screen.getByTestId('sky-eval-compare-row-OPUS')).toHaveTextContent('No fixture moved by 20 pts or more');
  });

  it('lists moved fixtures, with the miss side only for worse ones', () => {
    const moved = comparison('ccc3333', [
      row('HAIKU', {
        deltaPts: -3,
        moved: [
          { fixture: 'low-sun', direction: 'WORSE', beforePct: 80.4, afterPct: 40.6, missSide: 'BELOW' },
          { fixture: 'high-cloud', direction: 'WORSE', beforePct: 70, afterPct: 30, missSide: 'ABOVE' },
          { fixture: 'mist', direction: 'WORSE', beforePct: 70, afterPct: 30, missSide: null },
          { fixture: 'clear', direction: 'BETTER', beforePct: 20, afterPct: 60, missSide: 'BELOW' },
        ],
      }),
    ]);
    render(<VersionCompare comparisons={[moved]} now={NOW} />);
    const [low, high, mist, clear] = screen.getAllByTestId('sky-eval-compare-moved');
    expect(low).toHaveTextContent('worse');
    expect(low).toHaveTextContent('low-sun');
    expect(low).toHaveTextContent('80% → 41% in band · rated too low');
    expect(high).toHaveTextContent('70% → 30% in band · rated too high');
    expect(mist.textContent).not.toContain('rated too');
    expect(clear).toHaveTextContent('better');
    expect(clear).toHaveTextContent('20% → 60% in band');
    expect(clear.textContent).not.toContain('rated too');
  });
});
