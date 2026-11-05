import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import AllModelsTable from '../components/skyeval/AllModelsTable.jsx';

const NOW = new Date('2026-11-04T12:00:00Z');

const LANES = [
  { runType: 'BATCH_NEAR_TERM', label: 'Near-term forecasts' },
  { runType: 'BATCH_FAR_TERM', label: 'Far-term forecasts' },
];

const model = (name, over = {}) => ({
  model: name,
  fresh: true,
  lanes: [],
  last4WeeklyPct: 98,
  onHeadPct: 97.5,
  last4Ratings: 40,
  last4Below: 1,
  last4Above: 2,
  costPerRunGbp: 0.28,
  lastTestedAt: '2026-11-03T12:00:00Z',
  ...over,
});

function setup(models, props = {}) {
  const onToggle = vi.fn();
  render(
    <AllModelsTable
      models={models}
      lanes={LANES}
      head={{ shortSha: 'abc1234' }}
      highlighted={[]}
      onToggle={onToggle}
      now={NOW}
      {...props}
    />,
  );
  return { onToggle };
}

const row = (name) => screen.getByTestId(`sky-eval-model-row-${name}`);
// The bar is an aria-hidden SVG with no role or test id (colour is its only content), so the fill colour class is
// the one thing a test can read: the accuracy fill is the second <rect> of the row's <svg>.
const barRects = (name) => Array.from(row(name).getElementsByTagName('rect'));
const barClass = (name) => barRects(name)[1].getAttribute('class');

describe('AllModelsTable', () => {
  it('names the head sha in the column header, falling back to HEAD', () => {
    setup([model('HAIKU')]);
    expect(screen.getByText('On abc1234')).toBeInTheDocument();
  });

  it('falls back to HEAD when no build is known', () => {
    setup([model('HAIKU')], { head: null });
    expect(screen.getByText('On HEAD')).toBeInTheDocument();
  });

  it('shows accuracy, head pass rate, misses, cost and age for a fresh model', () => {
    setup([model('HAIKU')]);
    const r = row('HAIKU');
    expect(r).toHaveAttribute('data-fresh', 'true');
    expect(r).toHaveTextContent('98%');
    expect(r).toHaveTextContent('97.5%');
    expect(r).toHaveTextContent('1 / 2');
    expect(r).toHaveTextContent('£0.28');
    expect(r).toHaveTextContent('1d ago');
    expect(r).not.toHaveTextContent('older version');
    expect(r.className).not.toContain('opacity-70');
  });

  it('colours the bar green from 97, amber from 92 and coral below', () => {
    setup([
      model('GREEN', { last4WeeklyPct: 97 }),
      model('AMBER', { last4WeeklyPct: 92 }),
      model('CORAL', { last4WeeklyPct: 91.9 }),
    ]);
    expect(barClass('GREEN')).toContain('fill-verdict-go');
    expect(barClass('AMBER')).toContain('fill-verdict-marginal');
    expect(barClass('CORAL')).toContain('fill-plex-coral');
  });

  it('shows a dash instead of a bar when there is no weekly accuracy', () => {
    setup([model('HAIKU', { last4WeeklyPct: null })]);
    expect(barRects('HAIKU')).toHaveLength(0);
    expect(row('HAIKU')).toHaveTextContent('—');
  });

  it('names the production lanes a model serves, joined with a middle dot', () => {
    setup([model('SONNET', { lanes: ['BATCH_NEAR_TERM', 'BATCH_FAR_TERM'] })]);
    expect(row('SONNET')).toHaveTextContent('Near-term · Far-term');
  });

  it('falls back to the run type when a lane is not in the served list', () => {
    setup([model('SONNET', { lanes: ['MYSTERY_RUN'] })]);
    expect(row('SONNET')).toHaveTextContent('MYSTERY_RUN');
  });

  it('says eval only for a model with no lane', () => {
    setup([model('OPUS', { lanes: [] })]);
    expect(row('OPUS')).toHaveTextContent('eval only');
  });

  it('dashes the misses column and age when nothing was rated or tested', () => {
    setup([model('OPUS', { last4Ratings: 0, lastTestedAt: null, costPerRunGbp: null })]);
    const r = row('OPUS');
    expect(r).not.toHaveTextContent('/');
    expect(r).not.toHaveTextContent('ago');
    expect(r).not.toHaveTextContent('£');
  });

  it('dims a stale model and labels its age as an older version', () => {
    setup([model('OLD', { fresh: false })]);
    const r = row('OLD');
    expect(r).toHaveAttribute('data-fresh', 'false');
    expect(r.className).toContain('opacity-70');
    expect(r).toHaveTextContent('older version');
  });

  it('does not say older version for a stale model that was never tested', () => {
    setup([model('OLD', { fresh: false, lastTestedAt: null })]);
    expect(row('OLD')).not.toHaveTextContent('older version');
  });

  it('marks every compared model as pressed and the others not', () => {
    setup([model('HAIKU'), model('SONNET'), model('OPUS')], { highlighted: ['SONNET', 'OPUS'] });
    expect(row('SONNET')).toHaveAttribute('aria-pressed', 'true');
    expect(row('OPUS')).toHaveAttribute('aria-pressed', 'true');
    expect(row('HAIKU')).toHaveAttribute('aria-pressed', 'false');
    expect(row('SONNET').className).toContain('bg-[rgba(242,231,211,0.05)]');
    expect(row('HAIKU').className).not.toContain('bg-[rgba(242,231,211,0.05)]');
  });

  it('presses nothing when no model is selected', () => {
    setup([model('HAIKU')]);
    expect(row('HAIKU')).toHaveAttribute('aria-pressed', 'false');
  });

  it('reports the clicked model to toggle, selected or not, leaving the choice to the parent', () => {
    const { onToggle } = setup([model('HAIKU'), model('SONNET')], { highlighted: ['SONNET'] });
    fireEvent.click(row('HAIKU'));
    expect(onToggle).toHaveBeenLastCalledWith('HAIKU');
    fireEvent.click(row('SONNET'));
    expect(onToggle).toHaveBeenLastCalledWith('SONNET');
    expect(onToggle).toHaveBeenCalledTimes(2);
  });
});
