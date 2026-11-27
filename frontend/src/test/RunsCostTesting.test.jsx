import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import RunsCostView from '../components/runs/cost/RunsCostView.jsx';
import DailySpendChart from '../components/runs/cost/DailySpendChart.jsx';
import CostChangeTable from '../components/runs/cost/CostChangeTable.jsx';
import PromptTestRuns from '../components/runs/day/PromptTestRuns.jsx';
import DayRunList from '../components/runs/day/DayRunList.jsx';
import { dayResponse, dayRow } from './runsDayFixtures.js';
import {
  costAllTime, costResponse, promptTestRun, testingBlock, testingType,
} from './runsCostTimingFixtures.js';

// CI prompt-regression spend on Runs > Cost trends and the Day view (ci-spend-plan.md C3). With no testing block the
// page is today's page but for "All app types"; with one it gains a headline line, an off-by-default hatched layer,
// a Testing group in the table and the Day view's Prompt test runs list.

const measured = vi.hoisted(() => ({ width: 0 }));
vi.mock('../hooks/useMeasuredWidth.js', () => ({ default: () => [null, measured.width] }));

beforeEach(() => { measured.width = 0; });

/** The 14-day response with spend on the last four days and a matching testing block. */
function withTesting(over = {}) {
  const data = costResponse(over);
  const dates = data.days.map((d) => d.date);
  const last = dates.slice(-4);
  data.testing = testingBlock(data, {
    [last[0]]: { SKY: 0.42 },
    [last[2]]: { SKY: 0.95, ASK: 0.2 },
  });
  return data;
}

function renderView(data, props = {}) {
  const view = {
    cost: { data, loading: false, error: null, refresh: vi.fn() },
    range: 14,
    onRangeChange: vi.fn(),
    selected: null,
    onSelect: vi.fn(),
    onOpenDay: vi.fn(),
    ...props,
  };
  return render(<RunsCostView {...view} />);
}

const barsWithTesting = () => document.querySelectorAll('[data-testing]');

describe('with no testing block the page is today\'s page', () => {
  it('shows "All app types" and none of the testing parts', () => {
    renderView(costResponse());
    expect(screen.getByTestId('runs-cost-iso-all')).toHaveTextContent('All app types');
    expect(screen.queryByTestId('runs-cost-iso-testing')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runs-cost-testing-line')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runs-cost-testing-note')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runs-cost-app-subtotal')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runs-cost-testing-group')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runs-cost-testing-row-sky')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runs-cost-testing-subtotal')).not.toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('Yes. Spend is up 14%');
    expect(screen.getByTestId('runs-cost-total')).toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-chart-card').querySelector('pattern')).toBeNull();
  });

  it('a null or empty testing block is the same as none', () => {
    renderView({ ...costResponse(), testing: null });
    expect(screen.queryByTestId('runs-cost-testing-line')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runs-cost-testing-divider')).not.toBeInTheDocument();
  });

  it('a block with no types takes the null path: no divider, chip, line, note or testing rows', () => {
    const data = costResponse();
    data.testing = { types: [], days: [], totals: { ...testingBlock(data).totals } };
    renderView(data);
    for (const id of ['runs-cost-testing-divider', 'runs-cost-iso-testing', 'runs-cost-testing-line',
      'runs-cost-testing-note', 'runs-cost-app-subtotal', 'runs-cost-testing-group']) {
      expect(screen.queryByTestId(id)).not.toBeInTheDocument();
    }
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('Yes. Spend is up 14%');
    expect(screen.getByTestId('runs-cost-iso-all')).toHaveTextContent('All app types');
  });
});

describe('with a testing block', () => {
  it('flags the headline as app spend and adds the line outside the app sentence', () => {
    renderView(withTesting());
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('Yes. App spend is up 14% on the previous 14 days.');
    const line = screen.getByTestId('runs-cost-testing-line');
    expect(line).toHaveTextContent('Plus £3.40 on prompt tests (14 runs, was £1.62)');
    expect(line).toHaveTextContent('show in chart');
    expect(screen.getByTestId('runs-cost-body')).not.toHaveTextContent('prompt tests');
  });

  it('shows the line even when the app spent nothing in either period', () => {
    const data = withTesting();
    data.totals = { ...data.totals, lastGbp: 0, priorGbp: 0, deltaGbp: 0, deltaPercent: null };
    renderView(data);
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('No app spend recorded in the last 14 days.');
    expect(screen.queryByTestId('runs-cost-body')).not.toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-testing-line')).toBeInTheDocument();
  });

  it('mentions unknown usage in the line', () => {
    const data = withTesting();
    data.testing.totals.unknownRuns = 2;
    renderView(data);
    expect(screen.getByTestId('runs-cost-testing-line')).toHaveTextContent('2 usage unknown');
  });

  it('the layer is off by default, with a divider, a pressed-state chip and the footnote', () => {
    renderView(withTesting());
    const chip = screen.getByTestId('runs-cost-iso-testing');
    expect(chip).toHaveAttribute('aria-pressed', 'false');
    expect(chip).toHaveTextContent('Prompt tests');
    expect(barsWithTesting()).toHaveLength(0);
    expect(screen.getByTestId('runs-cost-testing-divider')).toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-svg').getAttribute('aria-label')).not.toContain('prompt tests');
    expect(screen.getByTestId('runs-cost-testing-note'))
      .toHaveTextContent('Hatched = prompt tests (CI), counted separately from the app. Off by default.');
  });

  it('the chip and the headline link drive the same flag', () => {
    renderView(withTesting());
    const link = screen.getByTestId('runs-cost-testing-toggle');
    const chip = screen.getByTestId('runs-cost-iso-testing');
    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-pressed', 'true');
    expect(link).toHaveTextContent('hide in chart');
    expect(barsWithTesting().length).toBeGreaterThan(0);
    expect(screen.getByTestId('runs-cost-svg').getAttribute('aria-label')).toMatch(/, including prompt tests$/);
    fireEvent.click(link);
    expect(chip).toHaveAttribute('aria-pressed', 'false');
    expect(link).toHaveTextContent('show in chart');
    expect(barsWithTesting()).toHaveLength(0);
    fireEvent.click(link);
    expect(chip).toHaveAttribute('aria-pressed', 'true');
  });

  it('draws sky then ask on top of the day\'s whole app stack, at full strength, with unique pattern ids', () => {
    renderView(withTesting(), { selected: 'OVERNIGHT' });
    fireEvent.click(screen.getByTestId('runs-cost-testing-toggle'));
    const dates = withTesting().days.map((d) => d.date).slice(-4);
    const bar = screen.getByTestId(`runs-cost-bar-${dates[2]}`);
    const layers = [...bar.querySelectorAll('rect')];
    const testingRects = layers.filter((r) => r.hasAttribute('data-testing'));
    expect(testingRects.map((r) => r.getAttribute('data-testing'))).toEqual(['SKY', 'ASK']);
    // They come after every app rect, none is dimmed, and the app-type selection does not touch them.
    expect(layers.slice(-2)).toEqual(testingRects);
    expect(testingRects.every((r) => !r.hasAttribute('opacity'))).toBe(true);
    const appRects = layers.filter((r) => !r.hasAttribute('data-testing'));
    expect(appRects.some((r) => r.getAttribute('opacity') !== '1')).toBe(true);
    const ids = [...document.querySelectorAll('pattern')].map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(testingRects[0].getAttribute('fill')).toBe(`url(#${ids.find((id) => id.endsWith('-sky'))})`);
    expect(testingRects[1].getAttribute('fill')).toBe(`url(#${ids.find((id) => id.endsWith('-ask'))})`);
  });

  it('two charts on a page do not share pattern ids', () => {
    const data = withTesting();
    const props = {
      data, selected: null, onSelect: vi.fn(), range: 14, onRangeChange: vi.fn(), onOpenDay: vi.fn(), showTesting: true,
    };
    render(<><DailySpendChart {...props} /><DailySpendChart {...props} /></>);
    const ids = [...document.querySelectorAll('pattern')].map((p) => p.id);
    expect(ids).toHaveLength(4);
    expect(new Set(ids).size).toBe(4);
  });

  it('only includes testing in the y-axis peak while it is shown', () => {
    const data = withTesting();
    data.testing.days = data.testing.days.map((d, i) => (i === data.testing.days.length - 1
      ? { ...d, byType: { SKY: 30, ASK: 0 }, totalGbp: 30, appPlusTestingGbp: 35 } : d));
    const { container } = renderView(data);
    const ticks = () => [...container.querySelectorAll('[data-testid="runs-cost-svg"] text')]
      .map((t) => t.textContent).filter((t) => /^£\d/.test(t));
    const before = ticks();
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    const after = ticks();
    expect(Number(after.at(-1).slice(1))).toBeGreaterThan(Number(before.at(-1).slice(1)));
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    expect(ticks()).toEqual(before);
  });

  it('clicking the chip leaves the 7-day average line exactly as the plain page draws it', () => {
    const plain = renderView(costResponse());
    const plainPoints = screen.getByTestId('runs-cost-average').getAttribute('points');
    plain.unmount();
    renderView(withTesting());
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    expect(screen.getByTestId('runs-cost-average').getAttribute('points')).toBe(plainPoints);
  });

  it('shown or not, the headline percent, app subtotal and y axis are what an app-only page gives', () => {
    const plain = costResponse();
    const withBlock = withTesting();
    const a = renderView(plain);
    const plainHeadline = screen.getByTestId('runs-cost-headline').textContent.replace('App spend', 'Spend');
    const plainAxis = [...a.container.querySelectorAll('[data-testid="runs-cost-svg"] text')].map((t) => t.textContent);
    const plainAverage = screen.getByTestId('runs-cost-average').getAttribute('points');
    const plainTotal = screen.getByTestId('runs-cost-total-last').textContent;
    a.unmount();
    const b = renderView(withBlock);
    expect(screen.getByTestId('runs-cost-headline').textContent.replace('App spend', 'Spend')).toBe(plainHeadline);
    expect([...b.container.querySelectorAll('[data-testid="runs-cost-svg"] text')].map((t) => t.textContent)).toEqual(plainAxis);
    expect(screen.getByTestId('runs-cost-average').getAttribute('points')).toBe(plainAverage);
    expect(screen.getByTestId('runs-cost-app-subtotal-last').textContent).toBe(plainTotal);
  });

  it('an empty app period with the layer shown is not "empty" when testing spent', () => {
    const data = withTesting();
    data.days = data.days.map((d) => ({ ...d, byType: {}, totalGbp: 0 }));
    renderView(data);
    expect(screen.getByTestId('runs-cost-empty')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    expect(screen.queryByTestId('runs-cost-empty')).not.toBeInTheDocument();
  });

  it('the tooltip gains the suite lines, App and Total only while the layer is shown', () => {
    const data = withTesting();
    renderView(data);
    const date = data.days.at(-2).date;
    const day = data.days.at(-2);
    fireEvent.mouseEnter(screen.getByTestId(`runs-cost-day-${date}`));
    expect(screen.queryByTestId('runs-cost-tooltip-app')).not.toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-tooltip-total')).toHaveTextContent(`£${Number(day.totalGbp).toFixed(2)}`);
    fireEvent.mouseLeave(screen.getByTestId(`runs-cost-day-${date}`));
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    fireEvent.mouseEnter(screen.getByTestId(`runs-cost-day-${date}`));
    const tip = screen.getByTestId('runs-cost-tooltip');
    expect(within(tip).getByTestId('runs-cost-tooltip-sky')).toHaveTextContent('Prompt tests · sky');
    expect(within(tip).getByTestId('runs-cost-tooltip-sky')).toHaveTextContent('£0.95');
    expect(within(tip).getByTestId('runs-cost-tooltip-ask')).toHaveTextContent('£0.20');
    expect(within(tip).getByTestId('runs-cost-tooltip-app')).toHaveTextContent(`£${Number(day.totalGbp).toFixed(2)}`);
    expect(within(tip).getByTestId('runs-cost-tooltip-total'))
      .toHaveTextContent(`£${(Number(day.totalGbp) + 1.15).toFixed(2)}`);
  });

  it('keeps the Selected line above App and Total when two types are picked', () => {
    const data = withTesting();
    renderView(data, { selected: 'OVERNIGHT,EVENING' });
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    fireEvent.mouseEnter(screen.getByTestId(`runs-cost-day-${data.days.at(-2).date}`));
    const tip = screen.getByTestId('runs-cost-tooltip');
    expect(within(tip).getByTestId('runs-cost-tooltip-selected')).toBeInTheDocument();
    expect(within(tip).getByTestId('runs-cost-tooltip-app')).toBeInTheDocument();
  });

  it('a day on which testing spent nothing has no suite lines but still has App and Total', () => {
    const data = withTesting();
    renderView(data);
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    fireEvent.mouseEnter(screen.getByTestId(`runs-cost-day-${data.days.at(-14).date}`));
    const tip = screen.getByTestId('runs-cost-tooltip');
    expect(within(tip).queryByTestId('runs-cost-tooltip-sky')).not.toBeInTheDocument();
    expect(within(tip).getByTestId('runs-cost-tooltip-total')).toBeInTheDocument();
  });

  it('the day\'s aria-label gains the suite lines, App and the total while shown, and is unchanged otherwise', () => {
    const data = withTesting();
    renderView(data);
    const date = data.days.at(-2).date;
    const slot = screen.getByTestId(`runs-cost-day-${date}`);
    const plain = slot.getAttribute('aria-label');
    expect(plain).toMatch(/^.*: £\d+\.\d\d\. Open this day\.$/);
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    const label = slot.getAttribute('aria-label');
    expect(label).toContain(`£${(Number(data.days.at(-2).totalGbp) + 1.15).toFixed(2)} in total`);
    expect(label).toContain(`App £${Number(data.days.at(-2).totalGbp).toFixed(2)}`);
    expect(label).toContain('Prompt tests · sky £0.95');
    expect(label).toContain('Prompt tests · ask £0.20');
    expect(label.endsWith('Open this day.')).toBe(true);
  });

  it('a day still opens on click with the layer shown', () => {
    const data = withTesting();
    const onOpenDay = vi.fn();
    renderView(data, { onOpenDay });
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    fireEvent.click(screen.getByTestId(`runs-cost-day-${data.days.at(-1).date}`));
    expect(onOpenDay).toHaveBeenCalledWith(data.days.at(-1).date);
  });

  it('marks an incomplete day "so far" in the total and the label', () => {
    const data = withTesting();
    data.days = data.days.map((d, i) => (i === data.days.length - 2 ? { ...d, costComplete: false } : d));
    renderView(data);
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    const date = data.days.at(-2).date;
    expect(screen.getByTestId(`runs-cost-day-${date}`).getAttribute('aria-label')).toContain(' so far.');
    fireEvent.mouseEnter(screen.getByTestId(`runs-cost-day-${date}`));
    expect(screen.getByTestId('runs-cost-tooltip-total')).toHaveTextContent('so far');
  });
});

describe('the table with a testing block', () => {
  it('lists App subtotal, the group row, the suites, the Testing subtotal and the Total, in that order', () => {
    renderView(withTesting());
    const table = screen.getByTestId('runs-cost-table');
    const ids = [...table.querySelectorAll('[role="row"][data-testid]')].map((r) => r.getAttribute('data-testid'));
    const tail = ids.slice(-6);
    expect(tail).toEqual([
      'runs-cost-app-subtotal', 'runs-cost-testing-group', 'runs-cost-testing-row-sky', 'runs-cost-testing-row-ask',
      'runs-cost-testing-subtotal', 'runs-cost-total',
    ]);
    expect(screen.getByTestId('runs-cost-app-subtotal')).toHaveTextContent('App subtotal');
    expect(screen.getByTestId('runs-cost-app-subtotal-last')).toHaveTextContent('£130.73');
  });

  it('the group row is one cell that spans the whole grid', () => {
    renderView(withTesting());
    const cells = within(screen.getByTestId('runs-cost-testing-group')).getAllByRole('cell');
    expect(cells).toHaveLength(1);
    expect(cells[0]).toHaveClass('col-span-full');
    expect(cells[0]).toHaveTextContent('Testing · CI prompt regression');
  });

  it('the suite rows show last, prior, change, why and a hatched sparkline', () => {
    renderView(withTesting());
    const sky = screen.getByTestId('runs-cost-testing-row-sky');
    expect(sky).toHaveTextContent('Prompt tests · sky');
    expect(screen.getByTestId('runs-cost-testing-row-sky-last')).toHaveTextContent('£2.64');
    expect(sky).toHaveTextContent('£1.10');
    expect(screen.getByTestId('runs-cost-testing-row-sky-change')).toHaveTextContent('+£1.54');
    expect(screen.getByTestId('runs-cost-testing-row-sky-change')).toHaveTextContent('+140%');
    expect(screen.getByTestId('runs-cost-testing-row-sky-why')).toHaveTextContent('Runs 9, was 4 · £/run +7%');
    expect(within(sky).getByTestId('runs-cost-testing-spark-sky').querySelector('pattern')).not.toBeNull();
    expect(screen.getByTestId('runs-cost-testing-row-ask-why')).toHaveTextContent('Runs 5, was 3 · £/run −12%');
  });

  it('the suite rows are inert: no button, no aria-pressed, no hover background, and a click changes nothing', () => {
    const onSelect = vi.fn();
    renderView(withTesting(), { onSelect });
    for (const key of ['sky', 'ask']) {
      const row = screen.getByTestId(`runs-cost-testing-row-${key}`);
      expect(within(row).queryByRole('button')).toBeNull();
      expect(row.querySelector('[aria-pressed]')).toBeNull();
      expect(row.className).not.toMatch(/hover:/);
      fireEvent.click(row);
    }
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('the Testing subtotal carries the spend-cap note and its change', () => {
    renderView(withTesting());
    const sub = screen.getByTestId('runs-cost-testing-subtotal');
    expect(sub).toHaveTextContent('Testing subtotal');
    expect(screen.getByTestId('runs-cost-testing-subtotal-last')).toHaveTextContent('£3.40');
    expect(screen.getByTestId('runs-cost-testing-subtotal-change')).toHaveTextContent('+£1.78');
    expect(sub).toHaveTextContent('the CI key has its own spend cap');
  });

  it('the Total is app + testing with the combined dollars and the existing rate', () => {
    renderView(withTesting());
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('£134.13');
    expect(screen.getByTestId('runs-cost-total-change')).toHaveTextContent('+£17.68');
    expect(screen.getByTestId('runs-cost-total-usd')).toHaveTextContent('$179.71');
    expect(screen.getByTestId('runs-cost-total-usd')).toHaveTextContent('£1 = $1.33');
  });

  it('a suite with unknown runs says so, and an incomplete figure says "so far"', () => {
    const data = withTesting();
    data.testing.types = [testingType('SKY', { unknownRuns: 2, costComplete: false }), testingType('ASK')];
    renderView(data);
    expect(screen.getByTestId('runs-cost-testing-row-sky-why')).toHaveTextContent('2 usage unknown');
    expect(screen.getByTestId('runs-cost-testing-row-sky-last')).toHaveTextContent('so far');
    expect(screen.getByTestId('runs-cost-testing-subtotal-last')).toHaveTextContent('so far');
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('so far');
  });

  it('only one suite ran: only its row is listed', () => {
    const data = withTesting();
    data.testing.types = [testingType('ASK')];
    renderView(data);
    expect(screen.queryByTestId('runs-cost-testing-row-sky')).not.toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-testing-row-ask')).toBeInTheDocument();
  });

  it('an incomplete app total shows "so far" on the App subtotal', () => {
    const data = withTesting();
    data.totals.costComplete = false;
    renderView(data);
    expect(screen.getByTestId('runs-cost-app-subtotal-last')).toHaveTextContent('so far');
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('so far');
  });

  it('with no testing block the Total row is today\'s', () => {
    render(<CostChangeTable data={costResponse()} selected={null} onSelect={vi.fn()} />);
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('£130.73');
    expect(screen.getByTestId('runs-cost-total-change')).toHaveTextContent('+£15.91');
    expect(screen.getByTestId('runs-cost-total-change')).toHaveTextContent('+14%');
  });
});

describe('all time with a testing block', () => {
  function allTimeWithTesting() {
    const data = costAllTime(10);
    data.testing = testingBlock(data, { [data.days.at(-1).date]: { SKY: 1 } }, {
      types: [testingType('SKY', { priorRuns: 0, unknownRuns: 1, costComplete: false }), testingType('ASK')],
      totals: {
        lastGbp: 3.4, priorGbp: 0, deltaGbp: 3.4, deltaPercent: null, runs: 14,
        priorRuns: 0,
        unknownRuns: 1,
        lastUsd: 4.53,
        combined: { lastGbp: 603.4, priorGbp: 0, deltaGbp: 603.4, lastUsd: 804.53 },
      },
    });
    return data;
  }

  it('flags the headline, adds the "since" line and has no "was"', () => {
    renderView(allTimeWithTesting(), { range: 'all' });
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('£600.00 of app spend since 23 Oct 2026.');
    const line = screen.getByTestId('runs-cost-testing-line');
    expect(line).toHaveTextContent('Plus £3.40 on prompt tests since 23 Oct 2026');
    expect(line).not.toHaveTextContent('was');
    expect(line).toHaveTextContent('1 usage unknown');
  });

  it('the table has the same structure on its Spend and sparkline grid, with no prior or change', () => {
    renderView(allTimeWithTesting(), { range: 'all' });
    const table = screen.getByTestId('runs-cost-table');
    const ids = [...table.querySelectorAll('[role="row"][data-testid]')].map((r) => r.getAttribute('data-testid'));
    expect(ids.slice(-6)).toEqual([
      'runs-cost-app-subtotal', 'runs-cost-testing-group', 'runs-cost-testing-row-sky', 'runs-cost-testing-row-ask',
      'runs-cost-testing-subtotal', 'runs-cost-total',
    ]);
    expect(screen.queryByTestId('runs-cost-testing-row-sky-change')).not.toBeInTheDocument();
    expect(screen.queryByTestId('runs-cost-testing-row-sky-why')).not.toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-testing-row-sky-unknown')).toHaveTextContent('1 usage unknown');
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('£603.40');
    expect(screen.getByTestId('runs-cost-total-usd')).toHaveTextContent('$804.53');
    expect(screen.getByTestId('runs-cost-testing-subtotal')).toHaveTextContent('the CI key has its own spend cap');
    expect(screen.getByTestId('runs-cost-app-subtotal-last')).toHaveTextContent('£600.00');
  });

  it('the chip and the layer work in all time', () => {
    renderView(allTimeWithTesting(), { range: 'all' });
    fireEvent.click(screen.getByTestId('runs-cost-iso-testing'));
    expect(barsWithTesting().length).toBeGreaterThan(0);
  });

  it('with no block the all-time table keeps its single Total row', () => {
    renderView(costAllTime(10), { range: 'all' });
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('£600.00');
    expect(screen.queryByTestId('runs-cost-app-subtotal')).not.toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('£600.00 spent since');
  });
});

describe('the Day view\'s Prompt test runs', () => {
  it('renders nothing for no runs', () => {
    const { container, rerender } = render(<PromptTestRuns runs={[]} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<PromptTestRuns />);
    expect(container).toBeEmptyDOMElement();
  });

  it('lists a row with the PR, title, suite, outcome, cost, meta and link, plus the footnote', () => {
    render(<PromptTestRuns runs={[promptTestRun()]} />);
    expect(screen.getByRole('heading', { name: 'Prompt test runs' })).toBeInTheDocument();
    expect(screen.getByTestId('runs-day-prompt-test-0-pr')).toHaveTextContent('PR #1912');
    const row = screen.getByTestId('runs-day-prompt-test-0');
    expect(row).toHaveTextContent('fix: valley mist wording');
    expect(row).toHaveTextContent('sky');
    expect(screen.getByTestId('runs-day-prompt-test-0-outcome')).toHaveTextContent('passed');
    expect(screen.getByTestId('runs-day-prompt-test-0-cost')).toHaveTextContent('£0.41');
    expect(screen.getByTestId('runs-day-prompt-test-0-meta'))
      .toHaveTextContent('42 cases · 0 failed · 612k in · 38k out · 71% cached · attempt 1');
    const link = screen.getByTestId('runs-day-prompt-test-0-link');
    expect(link).toHaveAttribute('href', 'https://github.com/gregochr/goldenhour/actions/runs/9/attempts/1');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByTestId('runs-day-prompt-tests-note'))
      .toHaveTextContent('Priced by the server with the same CostCalculator rates as the app, from the token counts the run reported.');
  });

  it('shows "N failed", "failed" and "cancelled" and a manual run with no PR', () => {
    render(<PromptTestRuns runs={[
      promptTestRun({ suite: 'ASK', conclusion: 'failure', failed: 1 }),
      promptTestRun({ conclusion: 'failure', failed: 0, prNumber: null, prTitle: null }),
      promptTestRun({ conclusion: 'cancelled' }),
    ]}
    />);
    expect(screen.getByTestId('runs-day-prompt-test-0-outcome')).toHaveTextContent('1 failed');
    expect(screen.getByTestId('runs-day-prompt-test-1-outcome')).toHaveTextContent(/^failed$/);
    expect(screen.getByTestId('runs-day-prompt-test-1-pr')).toHaveTextContent('Manual run');
    expect(screen.getByTestId('runs-day-prompt-test-1')).not.toHaveTextContent('valley mist');
    expect(screen.getByTestId('runs-day-prompt-test-2-outcome')).toHaveTextContent('cancelled');
    expect(screen.getByTestId('runs-day-prompt-test-0')).toHaveTextContent('ask');
  });

  it('an unknown or unpriced run reads so and never £0; a re-run is its own attempt', () => {
    render(<PromptTestRuns runs={[
      promptTestRun({ usageState: 'UNKNOWN', costGbp: null, costUsd: null, input: null, output: null, cacheRead: null, inputTotal: null, cachedPercent: null }),
      promptTestRun({ usageState: 'UNPRICED', costGbp: null, costUsd: null, attempt: 2 }),
    ]}
    />);
    expect(screen.getByTestId('runs-day-prompt-test-0-cost')).toHaveTextContent('usage unknown');
    expect(screen.getByTestId('runs-day-prompt-test-0-cost')).not.toHaveTextContent('£');
    expect(screen.getByTestId('runs-day-prompt-test-0-meta')).not.toHaveTextContent('in ');
    expect(screen.getByTestId('runs-day-prompt-test-1-cost')).toHaveTextContent('unpriced');
    expect(screen.getByTestId('runs-day-prompt-test-1-meta')).toHaveTextContent('attempt 2');
  });

  it('renders the link only for an https://github.com/ address', () => {
    render(<PromptTestRuns runs={[
      promptTestRun({ htmlUrl: null }),
      promptTestRun({ htmlUrl: 'https://evil.example/x' }),
      promptTestRun({ htmlUrl: 'javascript:alert(1)' }),
    ]}
    />);
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });
});

describe('the Day view\'s empty app list', () => {
  const props = (day) => ({
    day, filter: 'all', onFilterChange: vi.fn(), openId: null, onToggle: vi.fn(), onOpenDetail: vi.fn(),
  });

  it('says "No app runs on this day." when only CI ran, and "No runs on this day." otherwise', () => {
    const { rerender } = render(<DayRunList {...props(dayResponse([], { promptTestRuns: [promptTestRun()] }))} />);
    expect(screen.getByTestId('runs-day-empty')).toHaveTextContent('No app runs on this day.');
    rerender(<DayRunList {...props(dayResponse([], { promptTestRuns: [] }))} />);
    expect(screen.getByTestId('runs-day-empty')).toHaveTextContent('No runs on this day.');
    rerender(<DayRunList {...props(dayResponse([]))} />);
    expect(screen.getByTestId('runs-day-empty')).toHaveTextContent('No runs on this day.');
  });

  it('the run filters count only app rows; the prompt test runs are not among them', () => {
    const day = dayResponse([dayRow()], { promptTestRuns: [promptTestRun()] });
    render(<DayRunList {...props(day)} />);
    expect(screen.getByTestId('runs-chip-all-count')).toHaveTextContent('1');
  });
});
