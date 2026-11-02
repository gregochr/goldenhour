import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import SkyEvalView from '../components/skyeval/SkyEvalView.jsx';
import {
  AVAILABLE, NOW_ISO, PREV, modelStats, overview, run, runFixture, session,
} from './skyEvalFixtures.js';

vi.mock('../api/skyRatingEvalApi', () => ({
  getSkyEvalOverview: vi.fn(),
  startSkyEvalCheck: vi.fn(),
  getSkyRatingEvalSchedule: vi.fn(),
  setSkyRatingEvalScheduled: vi.fn(),
}));
vi.mock('../api/modelsApi', () => ({ getAvailableModels: vi.fn() }));

import { getSkyEvalOverview, startSkyEvalCheck } from '../api/skyRatingEvalApi';
import { getAvailableModels } from '../api/modelsApi';

/** Settles pending promise chains inside an awaited act (the fake clock freezes Testing Library's own wait). */
const settle = () => act(async () => {});

/** A request held open by the test, resolved later inside an awaited act. */
function deferred() {
  const d = {};
  d.promise = new Promise((resolve, reject) => { d.resolve = resolve; d.reject = reject; });
  return d;
}

async function mountView(ov = overview(), tab = null) {
  getSkyEvalOverview.mockResolvedValue({ data: ov });
  if (tab) window.localStorage.setItem('skyEval.tab', tab);
  const utils = render(<SkyEvalView />);
  await screen.findByTestId('sky-eval-subline');
  return utils;
}

const click = (testId) => fireEvent.click(screen.getByTestId(testId));
const pressed = (testId) => screen.getByTestId(testId).getAttribute('aria-pressed');

/** A lane model that is not checked on HEAD yet. */
const unchecked = (model, label, lane) => modelStats({
  model, label, lanes: [lane], onHeadPct: null, onHeadRuns: 0, previousVersionPct: null, deltaPts: null,
  status: 'UNCHECKED',
});

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
  vi.setSystemTime(new Date(NOW_ISO));
  window.localStorage.clear();
  getAvailableModels.mockResolvedValue(AVAILABLE);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
  window.localStorage.clear();
});

describe('SkyEvalView: header and tabs', () => {
  it('titles the page and builds the sub-line from the served schedule, fixtures and models', async () => {
    await mountView();
    expect(screen.getByRole('heading', { level: 2, name: 'Sky Eval' })).toBeInTheDocument();
    const line = screen.getByTestId('sky-eval-subline');
    expect(line).toHaveTextContent('Sky-rating calibration');
    expect(screen.getByTestId('sky-eval-fixture-count')).toHaveTextContent('2 fixtures');
    expect(line).toHaveTextContent('weekly, Sun 04:00, on Sonnet 5.5, Haiku 4.5, Haiku 5.5');
    expect(line).toHaveTextContent('Edit in Scheduler');
    expect(screen.getByTestId('sky-eval-edit-scheduler')).toHaveAttribute('href', '#manage/scheduler/sky_rating_eval');
  });

  it('drops the model list from the sub-line when the weekly run is paused', async () => {
    await mountView(overview({
      schedule: { label: 'weekly run paused', jobStatus: 'PAUSED', models: ['SONNET_55'] },
    }));
    const line = screen.getByTestId('sky-eval-subline');
    expect(line).toHaveTextContent('weekly run paused');
    expect(line).not.toHaveTextContent('Sonnet 5.5');
  });

  it('counts fixtures and sessions on the tabs', async () => {
    await mountView();
    expect(screen.getByTestId('sky-eval-tab-fixtures')).toHaveTextContent('Fixtures2');
    expect(screen.getByTestId('sky-eval-tab-runs')).toHaveTextContent('Runs2 sessions');
  });

  it('opens on Models and remembers the chosen tab in storage', async () => {
    await mountView();
    expect(pressed('sky-eval-tab-models')).toBe('true');
    click('sky-eval-tab-runs');
    expect(window.localStorage.getItem('skyEval.tab')).toBe('runs');
    expect(screen.getByTestId('sky-eval-runs-tab')).toBeInTheDocument();
  });

  it('reopens on the remembered tab', async () => {
    await mountView(overview(), 'runs');
    expect(screen.getByTestId('sky-eval-runs-tab')).toBeInTheDocument();
    expect(screen.queryByTestId('sky-eval-models-tab')).not.toBeInTheDocument();
  });

  it('ignores a stored value that is not one of the tabs', async () => {
    await mountView(overview(), 'javascript:alert(1)');
    expect(screen.getByTestId('sky-eval-models-tab')).toBeInTheDocument();
  });

  it('still works when storage throws on both read and write', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    await mountView();
    expect(screen.getByTestId('sky-eval-models-tab')).toBeInTheDocument();
    click('sky-eval-tab-runs');
    expect(screen.getByTestId('sky-eval-runs-tab')).toBeInTheDocument();
  });

  it('leaves the old chart-hidden keys alone', async () => {
    window.localStorage.setItem('skyEval.chartHidden.OPUS', '1');
    await mountView();
    expect(window.localStorage.getItem('skyEval.chartHidden.OPUS')).toBe('1');
  });

  it('shows the Fixtures grid on the Fixtures tab, defaulting to the near-term lane model', async () => {
    await mountView(overview({ runFixtures: [runFixture(56, 'copt-hill-11mar-false-positive')] }), 'fixtures');
    expect(screen.queryByTestId('sky-eval-fixtures-placeholder')).not.toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-fixtures-tab')).toHaveAttribute('data-model', 'HAIKU_55');
  });

  it('shows the served error when the overview fails to load', async () => {
    getSkyEvalOverview.mockRejectedValue(Object.assign(new Error('Request failed with status code 500'), {
      response: { status: 500, data: { error: 'Overview unavailable' } },
    }));
    render(<SkyEvalView />);
    await settle();
    expect(screen.getByTestId('sky-eval-load-error')).toHaveTextContent('Overview unavailable');
    expect(screen.getByTestId('sky-eval-open-check')).toBeDisabled();
  });
});

describe('SkyEvalView: verdict card', () => {
  it('says production held, with the commit message, age and before and after', async () => {
    await mountView();
    expect(screen.getByTestId('sky-eval-verdict-headline')).toHaveTextContent("Both production models held up on today's change.");
    expect(screen.getByTestId('sky-eval-verdict-body')).toHaveTextContent(
      "Prompt: stricter cirrus penalty (a91b0e2, 2h ago). Sonnet 5.5 97.5% → 99.4%, Haiku 4.5 93.8% → 91.9%. "
      + "Haiku 5.5 dropped from 95.6% to 90.4%, but it isn't used in production.",
    );
  });

  it('leaves out the not-in-production note when the server did not flag one', async () => {
    const ov = overview();
    await mountView({ ...ov, verdict: { ...ov.verdict, droppedNonProduction: false } });
    expect(screen.getByTestId('sky-eval-verdict-body')).not.toHaveTextContent("isn't used in production");
  });

  it.each([
    ['DROPPED', 'a91b0e2 made Haiku 4.5 worse.'],
    ['UNCHECKED', "a91b0e2 hasn't been checked on Sonnet 5.5 yet."],
    ['NO_HEAD', 'This build has no version recorded.'],
    ['NO_BASELINE', 'Nothing to compare a91b0e2 with yet.'],
  ])('shows the served %s headline', async (kind, headline) => {
    const ov = overview();
    await mountView({ ...ov, verdict: { ...ov.verdict, kind, headline } });
    expect(screen.getByTestId('sky-eval-verdict')).toHaveAttribute('data-kind', kind);
    expect(screen.getByTestId('sky-eval-verdict-headline')).toHaveTextContent(headline);
  });

  it('does not name a commit in the body when the build has no version', async () => {
    const ov = overview();
    await mountView({
      ...ov,
      head: { sha: null, shortSha: null, message: null, committedAt: null },
      verdict: { ...ov.verdict, kind: 'NO_HEAD', headline: 'This build has no version recorded.', lines: [] },
    });
    expect(screen.getByTestId('sky-eval-verdict-body')).not.toHaveTextContent('Prompt');
  });
});

describe('SkyEvalView: lane cards', () => {
  it('shows each lane with its label, model, purpose and stat strip', async () => {
    await mountView();
    const lane = screen.getByTestId('sky-eval-lane-BATCH_NEAR_TERM');
    expect(lane).toHaveTextContent('T and T+1 forecasts');
    expect(lane).toHaveTextContent('Sonnet 5.5');
    expect(lane).toHaveTextContent('Must be as accurate as possible');
    expect(within(lane).getByTestId('sky-eval-lane-stat-head')).toHaveTextContent("Today's version99.4%2 runs · a91b0e2");
    expect(within(lane).getByTestId('sky-eval-lane-stat-weekly')).toHaveTextContent('Last 4 weekly98.8%in band');
    expect(within(lane).getByTestId('sky-eval-lane-stat-misses')).toHaveTextContent('1 below 3 above');
    expect(within(lane).getByTestId('sky-eval-lane-stat-misses')).toHaveTextContent('of 320 ratings');
    expect(within(lane).getByTestId('sky-eval-lane-stat-cost')).toHaveTextContent('£0.28');
    expect(screen.getByTestId('sky-eval-lane-BATCH_FAR_TERM')).toHaveTextContent('Haiku 4.5');
  });

  it('stacks the lanes under 1000 px and the stat strip two by two under 560 px', async () => {
    await mountView();
    expect(screen.getByTestId('sky-eval-lanes').className).toContain('max-[999px]:grid-cols-[minmax(0,1fr)]');
    expect(screen.getAllByTestId('sky-eval-lane-stats')[0].className).toContain('max-[559px]:grid-cols-2');
  });

  it('shows a held lane as green with the before and after and the run count', async () => {
    await mountView();
    const bar = within(screen.getByTestId('sky-eval-lane-BATCH_NEAR_TERM')).getByTestId('sky-eval-lane-status');
    expect(bar).toHaveAttribute('data-state', 'held');
    expect(bar).toHaveTextContent('Held after a91b0e2: 97.5% → 99.4% over 2 runs.');
  });

  it('shows a dropped lane in coral with the drop and a See which fixtures link', async () => {
    await mountView(overview({
      models: [
        modelStats({
          model: 'SONNET_55', lanes: ['BATCH_NEAR_TERM'], previousVersionPct: 95.6, onHeadPct: 90.4, deltaPts: -5.2,
          status: 'DROPPED',
        }),
        modelStats({ model: 'HAIKU', lanes: ['BATCH_FAR_TERM'], status: 'HELD' }),
      ],
    }));
    const bar = within(screen.getByTestId('sky-eval-lane-BATCH_NEAR_TERM')).getByTestId('sky-eval-lane-status');
    expect(bar).toHaveAttribute('data-state', 'dropped');
    expect(bar).toHaveTextContent('Dropped −5.2 pts after a91b0e2 (95.6% → 90.4%).');
    expect(within(bar).getByTestId('sky-eval-lane-see-fixtures')).toHaveTextContent('See which fixtures');
  });

  it('opens the Fixtures tab on the dropped lane model from See which fixtures', async () => {
    await mountView(overview({
      models: [
        modelStats({
          model: 'SONNET_55', lanes: ['BATCH_NEAR_TERM'], previousVersionPct: 95.6, onHeadPct: 90.4, deltaPts: -5.2,
          status: 'DROPPED',
        }),
        modelStats({ model: 'HAIKU', lanes: ['BATCH_FAR_TERM'], status: 'DROPPED', previousVersionPct: 95, onHeadPct: 90, deltaPts: -5 }),
      ],
      sessions: [
        session({ runs: [run({ id: 56, model: 'SONNET_55' }), run({ id: 57, model: 'HAIKU' })] }),
      ],
      runFixtures: [runFixture(56, 'copt-hill-11mar-false-positive'), runFixture(57, 'copt-hill-11mar-false-positive')],
    }));
    click('sky-eval-tab-models');
    const farLane = screen.getByTestId('sky-eval-lane-BATCH_FAR_TERM');
    fireEvent.click(within(farLane).getByTestId('sky-eval-lane-see-fixtures'));
    expect(screen.getByTestId('sky-eval-tab-fixtures')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('sky-eval-fixtures-tab')).toHaveAttribute('data-model', 'HAIKU');
    expect(window.localStorage.getItem('skyEval.tab')).toBe('fixtures');
  });

  it('shows an unchecked lane in amber with a Run a check link that opens the panel with that model only', async () => {
    await mountView(overview({
      models: [unchecked('SONNET_55', 'Sonnet 5.5', 'BATCH_NEAR_TERM'), modelStats({ model: 'HAIKU', lanes: ['BATCH_FAR_TERM'], status: 'HELD' })],
    }));
    const lane = screen.getByTestId('sky-eval-lane-BATCH_NEAR_TERM');
    expect(within(lane).getByTestId('sky-eval-lane-status')).toHaveTextContent('Not checked on a91b0e2 yet.');
    expect(within(lane).getByTestId('sky-eval-lane-stat-head')).toHaveTextContent('not run yet');

    fireEvent.click(within(lane).getByTestId('sky-eval-lane-run-check'));

    expect(screen.getByTestId('sky-eval-check-panel')).toBeInTheDocument();
    expect(pressed('sky-eval-check-model-SONNET_55')).toBe('true');
    expect(pressed('sky-eval-check-model-HAIKU')).toBe('false');
  });

  it('says there is nothing to compare when the model has a run on HEAD and none before', async () => {
    await mountView(overview({
      models: [
        modelStats({ model: 'SONNET_55', lanes: ['BATCH_NEAR_TERM'], previousVersionPct: null, deltaPts: null, status: 'NO_BASELINE' }),
        modelStats({ model: 'HAIKU', lanes: ['BATCH_FAR_TERM'], status: 'HELD' }),
      ],
    }));
    const bar = within(screen.getByTestId('sky-eval-lane-BATCH_NEAR_TERM')).getByTestId('sky-eval-lane-status');
    expect(bar).toHaveAttribute('data-state', 'baseline');
    expect(bar).toHaveTextContent('Nothing to compare a91b0e2 with yet');
  });

  it('lists the compared models with points, cost multiple and a PROD tag where relevant', async () => {
    await mountView();
    const haiku = screen.getByTestId('sky-eval-compared-BATCH_NEAR_TERM-HAIKU');
    expect(haiku).toHaveTextContent('Haiku 4.5');
    expect(haiku).toHaveTextContent('93.4%');
    expect(haiku).toHaveTextContent('−5.3 pts');
    expect(haiku).toHaveTextContent('0.30× cost');
    expect(within(haiku).getByTestId('sky-eval-prod-tag')).toBeInTheDocument();
    const opus = screen.getByTestId('sky-eval-compared-BATCH_NEAR_TERM-OPUS');
    expect(within(opus).queryByTestId('sky-eval-prod-tag')).not.toBeInTheDocument();
    expect(opus).toHaveTextContent('2.2× cost');
  });

  it('colours the difference green when better and amber when worse', async () => {
    await mountView();
    const worse = within(screen.getByTestId('sky-eval-compared-BATCH_NEAR_TERM-HAIKU')).getByTestId('sky-eval-compared-pts');
    const better = within(screen.getByTestId('sky-eval-compared-BATCH_FAR_TERM-SONNET_55')).getByTestId('sky-eval-compared-pts');
    expect(worse.className).toContain('text-verdict-marginal');
    expect(better.className).toContain('text-verdict-go');
  });

  it('adds a "Fell to" line only for a model the server says fell on HEAD', async () => {
    await mountView();
    expect(screen.getByTestId('sky-eval-compared-BATCH_NEAR_TERM-HAIKU_55')).toHaveTextContent('Fell to 90.4% on a91b0e2');
    expect(screen.getByTestId('sky-eval-compared-BATCH_NEAR_TERM-HAIKU')).not.toHaveTextContent('Fell to');
  });

  it('footnotes stale models and opens the panel with them selected', async () => {
    await mountView();
    const note = within(screen.getByTestId('sky-eval-lane-BATCH_NEAR_TERM')).getByTestId('sky-eval-lane-stale');
    expect(note).toHaveTextContent('Opus last tested 29d ago.');
    fireEvent.click(within(note).getByTestId('sky-eval-lane-check-stale'));
    expect(pressed('sky-eval-check-model-OPUS')).toBe('true');
    expect(pressed('sky-eval-check-model-SONNET_55')).toBe('false');
  });
});

describe('SkyEvalView: accuracy chart', () => {
  it('draws one chip per model that has a session, and none for a model with no points', async () => {
    await mountView();
    expect(screen.getByTestId('sky-eval-chart-chip-SONNET_55')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-chart-chip-HAIKU_55')).toBeInTheDocument();
    expect(screen.queryByTestId('sky-eval-chart-chip-OPUS')).not.toBeInTheDocument();
  });

  it('colours production models and greys the rest', async () => {
    await mountView();
    expect(screen.getByTestId('sky-eval-series-SONNET_55')).toHaveAttribute('data-emphasised', 'true');
    expect(screen.getByTestId('sky-eval-series-HAIKU')).toHaveAttribute('data-emphasised', 'true');
    expect(screen.getByTestId('sky-eval-series-HAIKU_55')).toHaveAttribute('data-emphasised', 'false');
    const line = screen.getByTestId('sky-eval-series-SONNET_55').querySelector('polyline');
    expect(line).toHaveAttribute('stroke', 'oklch(.74 .14 340)');
    expect(line).toHaveAttribute('stroke-width', '2');
    const grey = screen.getByTestId('sky-eval-series-HAIKU_55').querySelector('polyline');
    expect(grey).toHaveAttribute('stroke-width', '1');
    expect(grey).toHaveAttribute('opacity', '0.6');
  });

  it('draws manual points hollow and weekly points filled, with a tooltip on each', async () => {
    await mountView();
    const points = [...screen.getByTestId('sky-eval-series-SONNET_55').querySelectorAll('circle')];
    expect(points.map((p) => p.getAttribute('data-manual'))).toEqual(['false', 'true']);
    expect(points[1].getAttribute('fill')).toBe('#221A15');
    expect(points[0].getAttribute('fill')).toBe('oklch(.74 .14 340)');
    expect(points[1].querySelector('title').textContent).toBe(
      'Sonnet 5.5 · 99.4%\nMon 2 Nov · manual · 2 runs\nversion a91b0e2',
    );
  });

  it('draws a gold line per version with a four-character label and the detail in its title', async () => {
    await mountView();
    const lines = screen.getAllByTestId('sky-eval-version-line');
    expect(lines.map((l) => l.querySelector('text').lastChild.textContent)).toEqual(['c41e', 'a91b']);
    expect(lines[1].querySelector('title').textContent).toBe('a91b0e2 · Prompt: stricter cirrus penalty · Mon 2 Nov');
  });

  it('scales with its card: a fixed viewBox and a full-width class, no measured size', async () => {
    await mountView();
    const svg = screen.getByTestId('sky-eval-accuracy-svg');
    expect(svg).toHaveAttribute('viewBox', '0 0 700 250');
    expect(svg.className.baseVal).toContain('w-full');
    expect(svg).not.toHaveAttribute('width');
  });

  it('highlights a model from its chip, then clears it on a second press', async () => {
    await mountView();
    click('sky-eval-chart-chip-HAIKU_55');
    expect(pressed('sky-eval-chart-chip-HAIKU_55')).toBe('true');
    expect(screen.getByTestId('sky-eval-series-HAIKU_55')).toHaveAttribute('data-emphasised', 'true');
    expect(screen.getByTestId('sky-eval-series-SONNET_55')).toHaveAttribute('data-emphasised', 'false');
    click('sky-eval-chart-chip-HAIKU_55');
    expect(screen.getByTestId('sky-eval-series-SONNET_55')).toHaveAttribute('data-emphasised', 'true');
  });
});

describe('SkyEvalView: did a change make ratings worse', () => {
  it('opens on HEAD with each model before, after and the change', async () => {
    await mountView();
    expect(pressed('sky-eval-version-chip-a91b0e2')).toBe('true');
    expect(screen.getByTestId('sky-eval-compare-version')).toHaveTextContent('a91b0e2Prompt: stricter cirrus penalty· Mon 2 Nov');
    const sonnet = screen.getByTestId('sky-eval-compare-row-SONNET_55');
    expect(sonnet).toHaveTextContent('97.5%');
    expect(sonnet).toHaveTextContent('99.4%');
    expect(within(sonnet).getByTestId('sky-eval-compare-change')).toHaveTextContent('+1.9 pts');
    expect(sonnet).toHaveTextContent('No fixture moved by 20 pts or more');
    expect(within(sonnet).getByTestId('sky-eval-prod-tag')).toBeInTheDocument();
  });

  it('colours a change of -3 or worse coral and a change inside 3 points neutral', async () => {
    await mountView();
    const bad = within(screen.getByTestId('sky-eval-compare-row-HAIKU_55')).getByTestId('sky-eval-compare-change');
    const flat = within(screen.getByTestId('sky-eval-compare-row-SONNET_55')).getByTestId('sky-eval-compare-change');
    expect(bad).toHaveAttribute('data-tone', 'bad');
    expect(bad.className).toContain('text-plex-coral');
    expect(flat).toHaveAttribute('data-tone', 'neutral');
  });

  it('lists a fixture that moved, worse, with its side', async () => {
    await mountView();
    const moved = within(screen.getByTestId('sky-eval-compare-row-HAIKU_55')).getByTestId('sky-eval-compare-moved');
    expect(moved).toHaveTextContent('worsecopt-hill-16mar-horizon-strip94% → 67% in band · rated too low');
  });

  it('says "Not run on this version" for a model with no runs on it', async () => {
    await mountView();
    expect(within(screen.getByTestId('sky-eval-compare-row-OPUS')).getByTestId('sky-eval-compare-notrun'))
      .toHaveTextContent('Not run on this version');
  });

  it('switches the comparison when another version chip is pressed, and shows a better mover without a side', async () => {
    await mountView();
    click(`sky-eval-version-chip-${PREV.shortSha}`);
    expect(pressed(`sky-eval-version-chip-${PREV.shortSha}`)).toBe('true');
    expect(pressed('sky-eval-version-chip-a91b0e2')).toBe('false');
    expect(screen.getByTestId('sky-eval-compare-version')).toHaveTextContent('Scorer: per-fixture band tolerance');
    expect(screen.queryByTestId('sky-eval-compare-row-HAIKU_55')).not.toBeInTheDocument();
    const moved = within(screen.getByTestId('sky-eval-compare-row-SONNET_55')).getByTestId('sky-eval-compare-moved');
    expect(moved).toHaveTextContent('betterseaham-28sep-low-sun-cirrus40% → 80% in band');
    expect(moved).not.toHaveTextContent('rated too');
  });

  it('says so when no version has runs both on it and before it', async () => {
    await mountView(overview({ comparisons: [] }));
    expect(screen.getByTestId('sky-eval-compare-empty')).toBeInTheDocument();
  });

  it('hides the arrow column and wraps the movers full-width below 760 px', async () => {
    await mountView();
    const row = screen.getByTestId('sky-eval-compare-row-SONNET_55');
    expect(row.className).toContain('max-[759px]:grid-cols-[minmax(0,1fr)_48px_48px_64px]');
    expect(row.querySelector('.max-\\[759px\\]\\:hidden')).not.toBeNull();
  });
});

describe('SkyEvalView: all models', () => {
  const rowOrder = () => screen.getAllByTestId(/^sky-eval-model-row-/).map((r) => r.dataset.testid.replace('sky-eval-model-row-', ''));

  it('lists the models in the order the server sent them', async () => {
    await mountView();
    expect(rowOrder()).toEqual(['SONNET_55', 'OPUS', 'HAIKU_55', 'HAIKU']);
  });

  it('shows the lane tag for a production model and "eval only" for the rest', async () => {
    await mountView();
    expect(screen.getByTestId('sky-eval-model-row-SONNET_55')).toHaveTextContent('T and T+1');
    expect(screen.getByTestId('sky-eval-model-row-HAIKU')).toHaveTextContent('T+2 and T+3');
    expect(screen.getByTestId('sky-eval-model-row-HAIKU_55')).toHaveTextContent('eval only');
  });

  it('shows last 4 weekly, on HEAD, misses and cost, with an em dash where there is none', async () => {
    await mountView();
    const sonnet = screen.getByTestId('sky-eval-model-row-SONNET_55');
    expect(sonnet).toHaveTextContent('98.8%');
    expect(sonnet).toHaveTextContent('99.4%');
    expect(sonnet).toHaveTextContent('1 / 3');
    expect(sonnet).toHaveTextContent('£0.28');
    expect(screen.getByTestId('sky-eval-model-row-OPUS')).toHaveTextContent('—');
  });

  it('dims a stale model and marks it older version', async () => {
    await mountView();
    const opus = screen.getByTestId('sky-eval-model-row-OPUS');
    expect(opus).toHaveAttribute('data-fresh', 'false');
    expect(opus.className).toContain('opacity-70');
    expect(opus).toHaveTextContent('older version');
    const fresh = screen.getByTestId('sky-eval-model-row-SONNET_55');
    expect(fresh).toHaveAttribute('data-fresh', 'true');
    expect(fresh).not.toHaveTextContent('older version');
  });

  it('highlights a model in the chart when its row is pressed', async () => {
    await mountView();
    click('sky-eval-model-row-HAIKU_55');
    expect(pressed('sky-eval-model-row-HAIKU_55')).toBe('true');
    expect(pressed('sky-eval-chart-chip-HAIKU_55')).toBe('true');
    expect(screen.getByTestId('sky-eval-series-HAIKU_55')).toHaveAttribute('data-emphasised', 'true');
    expect(screen.getByTestId('sky-eval-series-SONNET_55')).toHaveAttribute('data-emphasised', 'false');
  });

  it('makes each row a real button', async () => {
    await mountView();
    expect(screen.getByTestId('sky-eval-model-row-OPUS').tagName).toBe('BUTTON');
  });
});

describe('SkyEvalView: Run a check panel', () => {
  const open = async (ov) => {
    await mountView(ov);
    click('sky-eval-open-check');
  };

  it('opens on the production pair, three runs and all fixtures, with PROD tags on the lane models', async () => {
    await open();
    expect(pressed('sky-eval-check-model-SONNET_55')).toBe('true');
    expect(pressed('sky-eval-check-model-HAIKU')).toBe('true');
    expect(pressed('sky-eval-check-model-HAIKU_55')).toBe('false');
    expect(pressed('sky-eval-check-runs-3')).toBe('true');
    expect(pressed('sky-eval-check-fixtures-all')).toBe('true');
    expect(within(screen.getByTestId('sky-eval-check-model-SONNET_55')).getByTestId('sky-eval-prod-tag')).toBeInTheDocument();
    expect(within(screen.getByTestId('sky-eval-check-model-OPUS')).queryByTestId('sky-eval-prod-tag')).not.toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-check-runs-3')).toHaveTextContent('3 · quick');
    expect(screen.getByTestId('sky-eval-check-runs-8')).toHaveTextContent('8 · same as weekly');
    expect(screen.getByTestId('sky-eval-check-fixtures-all')).toHaveTextContent('All 2');
  });

  it('shows the version: short sha, message and when it was pushed', async () => {
    await open();
    expect(screen.getByTestId('sky-eval-check-version')).toHaveTextContent('a91b0e2Prompt: stricter cirrus penaltycurrent, pushed 2h ago');
  });

  it('says "version unknown" and still allows the run when HEAD is unknown', async () => {
    await open(overview({ head: { sha: null, shortSha: null, message: null, committedAt: null } }));
    expect(screen.getByTestId('sky-eval-check-version')).toHaveTextContent('version unknown');
    expect(screen.getByTestId('sky-eval-check-run')).not.toBeDisabled();
  });

  it('computes the estimate from the served per-rating cost and time x fixtures x runs', async () => {
    await open();
    // Two fixtures x 3 runs = 6 ratings per model: (0.0035 + 0.001) x 6 = £0.027; slowest 1.4 s x 6 = 8.4 s.
    expect(screen.getByTestId('sky-eval-check-cost')).toHaveTextContent('~£0.03');
    expect(screen.getByTestId('sky-eval-check-time')).toHaveTextContent('~8s');
    expect(screen.getByTestId('sky-eval-check-ratings')).toHaveTextContent('12 ratings');
    click('sky-eval-check-runs-8');
    // 16 ratings per model: £0.072 and 22.4 s; 32 ratings.
    expect(screen.getByTestId('sky-eval-check-cost')).toHaveTextContent('~£0.07');
    expect(screen.getByTestId('sky-eval-check-time')).toHaveTextContent('~22s');
    expect(screen.getByTestId('sky-eval-check-ratings')).toHaveTextContent('32 ratings');
  });

  it('says "no estimate" for a model with no history', async () => {
    await open();
    click('sky-eval-check-model-SONNET');
    expect(screen.getByTestId('sky-eval-check-cost')).toHaveTextContent('no estimate');
    expect(screen.getByTestId('sky-eval-check-time')).toHaveTextContent('no estimate');
    expect(screen.getByTestId('sky-eval-check-ratings')).toHaveTextContent('18 ratings');
  });

  it('toggles models and the Production pair shortcut restores the lane models only', async () => {
    await open();
    click('sky-eval-check-model-HAIKU_55');
    click('sky-eval-check-model-OPUS');
    click('sky-eval-check-model-SONNET_55');
    expect(pressed('sky-eval-check-model-SONNET_55')).toBe('false');
    expect(pressed('sky-eval-check-model-OPUS')).toBe('true');
    click('sky-eval-check-production-pair');
    expect(pressed('sky-eval-check-model-SONNET_55')).toBe('true');
    expect(pressed('sky-eval-check-model-HAIKU')).toBe('true');
    expect(pressed('sky-eval-check-model-HAIKU_55')).toBe('false');
    expect(pressed('sky-eval-check-model-OPUS')).toBe('false');
  });

  it('counts the fixtures missed last time, scopes the estimate to them and sends their names', async () => {
    startSkyEvalCheck.mockResolvedValue({ data: { runIds: [70, 71] } });
    await open();
    expect(screen.getByTestId('sky-eval-check-fixtures-missed')).toHaveTextContent('Missed last time · 1');
    click('sky-eval-check-fixtures-missed');
    expect(screen.getByTestId('sky-eval-check-ratings')).toHaveTextContent('6 ratings');
    await act(async () => { click('sky-eval-check-run'); });
    expect(startSkyEvalCheck).toHaveBeenCalledWith({
      models: ['SONNET_55', 'HAIKU'], runsPerFixture: 3, fixtureNames: ['copt-hill-11mar-false-positive'],
    });
  });

  it('disables "Missed last time" when nothing was missed', async () => {
    const ov = overview();
    await open({ ...ov, checkDefaults: { ...ov.checkDefaults, missedLastTime: [] } });
    expect(screen.getByTestId('sky-eval-check-fixtures-missed')).toBeDisabled();
    expect(screen.getByTestId('sky-eval-check-fixtures-missed')).toHaveTextContent('Missed last time · 0');
  });

  it('disables Run check with no model chosen', async () => {
    await open();
    click('sky-eval-check-model-SONNET_55');
    click('sky-eval-check-model-HAIKU');
    expect(screen.getByTestId('sky-eval-check-run')).toBeDisabled();
  });

  it('posts all fixtures as null, with the chosen repeats', async () => {
    startSkyEvalCheck.mockResolvedValue({ data: { runIds: [70, 71] } });
    await open();
    click('sky-eval-check-runs-1');
    await act(async () => { click('sky-eval-check-run'); });
    expect(startSkyEvalCheck).toHaveBeenCalledWith({
      models: ['SONNET_55', 'HAIKU'], runsPerFixture: 1, fixtureNames: null,
    });
  });

  it('closes on Cancel and on Close', async () => {
    await open();
    click('sky-eval-check-cancel');
    expect(screen.queryByTestId('sky-eval-check-panel')).not.toBeInTheDocument();
    click('sky-eval-open-check');
    click('sky-eval-check-close');
    expect(screen.queryByTestId('sky-eval-check-panel')).not.toBeInTheDocument();
  });

  it('after submit closes the panel, switches to Runs and expands the new session', async () => {
    startSkyEvalCheck.mockResolvedValue({ data: { runIds: [70, 71] } });
    const after = overview({
      sessions: [session({ id: 70, runs: [run({ id: 70 }), run({ id: 71, model: 'HAIKU' })] }), ...overview().sessions],
    });
    await open();
    getSkyEvalOverview.mockResolvedValue({ data: after });
    await act(async () => { click('sky-eval-check-run'); });
    expect(screen.queryByTestId('sky-eval-check-panel')).not.toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-runs-tab')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-session-toggle-70')).toHaveAttribute('aria-expanded', 'true');
    expect(window.localStorage.getItem('skyEval.tab')).toBe('runs');
  });

  it('expands the session that holds the first returned run when the check merged into an existing one', async () => {
    startSkyEvalCheck.mockResolvedValue({ data: { runIds: [70, 71] } });
    const merged = overview({
      sessions: [session({ id: 56, runs: [run({ id: 56 }), run({ id: 70 }), run({ id: 71, model: 'HAIKU' })] })],
    });
    await open();
    getSkyEvalOverview.mockResolvedValue({ data: merged });
    await act(async () => { click('sky-eval-check-run'); });
    expect(screen.getByTestId('sky-eval-session-toggle-56')).toHaveAttribute('aria-expanded', 'true');
  });

  it('guards the billing call: one request while the POST is in flight, the button disabled', async () => {
    const d = deferred();
    startSkyEvalCheck.mockReturnValue(d.promise);
    await open();
    await act(async () => { click('sky-eval-check-run'); });
    expect(screen.getByTestId('sky-eval-check-run')).toBeDisabled();
    expect(screen.getByTestId('sky-eval-check-run')).toHaveTextContent('Starting…');
    fireEvent.click(screen.getByTestId('sky-eval-check-run'));
    expect(startSkyEvalCheck).toHaveBeenCalledTimes(1);
    await act(async () => { d.resolve({ data: { runIds: [70] } }); });
    expect(screen.queryByTestId('sky-eval-check-panel')).not.toBeInTheDocument();
  });

  it('keeps the panel open and shows the error when the POST fails', async () => {
    startSkyEvalCheck.mockRejectedValue(Object.assign(new Error('Request failed with status code 400'), {
      response: { status: 400, data: { error: 'Unknown fixture' } },
    }));
    await open();
    await act(async () => { click('sky-eval-check-run'); });
    expect(screen.getByTestId('sky-eval-check-panel')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-check-error')).toHaveTextContent('Unknown fixture');
    expect(screen.getByTestId('sky-eval-check-run')).not.toBeDisabled();
    expect(screen.queryByTestId('sky-eval-runs-tab')).not.toBeInTheDocument();
  });

  it('falls back to the models the overview names when the models endpoint fails', async () => {
    getAvailableModels.mockRejectedValue(new Error('down'));
    await open();
    expect(screen.getByTestId('sky-eval-check-model-SONNET_55')).toBeInTheDocument();
    expect(screen.queryByTestId('sky-eval-check-model-SONNET')).not.toBeInTheDocument();
  });
});

describe('SkyEvalView: Runs tab', () => {
  const runsTab = async (ov = overview()) => mountView(ov, 'runs');

  it('lists sessions with when, trigger (WEEKLY for SCHEDULED), version and status', async () => {
    await runsTab();
    const manual = screen.getByTestId('sky-eval-session-56');
    expect(manual).toHaveTextContent('50m ago');
    expect(manual).toHaveTextContent('Mon 2 Nov 08:36');
    expect(within(manual).getByTestId('sky-eval-session-trigger')).toHaveTextContent('MANUAL');
    expect(manual).toHaveTextContent('a91b0e2');
    expect(manual).toHaveTextContent('Prompt: stricter cirrus penalty');
    expect(manual).toHaveTextContent('£0.60');
    expect(within(manual).getByTestId('sky-eval-session-status')).toHaveTextContent('Done');
    const weekly = screen.getByTestId('sky-eval-session-40');
    expect(within(weekly).getByTestId('sky-eval-session-trigger')).toHaveTextContent('WEEKLY');
    expect(weekly).toHaveTextContent('c41e2d8');
  });

  it('shows "unknown version" for a session with no version', async () => {
    await runsTab(overview({ sessions: [session({ version: null })] }));
    expect(screen.getByTestId('sky-eval-session-56')).toHaveTextContent('unknown version');
  });

  it('shows a chip per model with its repeats and a pass rate coloured at 97 and 92', async () => {
    await runsTab(overview({
      sessions: [session({
        byModel: [
          { model: 'SONNET_55', runCount: 2, inBandPct: 97 },
          { model: 'HAIKU', runCount: 1, inBandPct: 92 },
          { model: 'HAIKU_55', runCount: 1, inBandPct: 91.9 },
          { model: 'OPUS', runCount: 1, inBandPct: null },
        ],
      })],
    }));
    const chips = within(screen.getByTestId('sky-eval-session-models'));
    expect(screen.getByTestId('sky-eval-session-models')).toHaveTextContent('Sonnet 5.5×297%');
    const tones = [...screen.getByTestId('sky-eval-session-models').querySelectorAll('b')].map((b) => b.dataset.tone);
    expect(tones).toEqual(['ink', 'amber', 'coral', 'none']);
    expect(chips.getByText('—')).toBeInTheDocument();
  });

  it('expands a session into its runs with the button as a sibling, not the parent', async () => {
    await runsTab();
    const toggle = screen.getByTestId('sky-eval-session-toggle-56');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('sky-eval-session-runs-56')).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const table = screen.getByTestId('sky-eval-session-runs-56');
    expect(toggle.contains(table)).toBe(false);
    expect(within(table).getByTestId('sky-eval-run-56')).toHaveTextContent('Haiku 5.5#56 · 10×8');
    expect(within(table).getByTestId('sky-eval-run-56')).toHaveTextContent('96.3%');
    expect(within(table).getByTestId('sky-eval-run-56')).toHaveTextContent('2 / 2');
    expect(within(table).getByTestId('sky-eval-run-56')).toHaveTextContent('£0.09');
    expect(within(table).getByTestId('sky-eval-run-56')).toHaveTextContent('2m 15s');
    fireEvent.click(toggle);
    expect(screen.queryByTestId('sky-eval-session-runs-56')).not.toBeInTheDocument();
  });

  it('links each completed run to the Fixtures tab with its model preselected', async () => {
    await runsTab(overview({
      runFixtures: [runFixture(56, 'copt-hill-11mar-false-positive'), runFixture(57, 'copt-hill-11mar-false-positive')],
    }));
    fireEvent.click(screen.getByTestId('sky-eval-session-toggle-56'));
    expect(within(screen.getByTestId('sky-eval-run-56')).getByTestId('sky-eval-run-fixtures-56')).toHaveTextContent('Fixtures →');
    fireEvent.click(screen.getByTestId('sky-eval-run-fixtures-57'));
    expect(screen.getByTestId('sky-eval-tab-fixtures')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('sky-eval-fixtures-tab')).toHaveAttribute('data-model', 'SONNET_55');
  });

  it('has no Fixtures link on a failed or running run', async () => {
    await runsTab(overview({
      sessions: [session({
        status: 'RUNNING',
        runs: [run({ id: 56, model: 'HAIKU_55', status: 'RUNNING', progress: 0.5 }), run({ id: 57, model: 'OPUS', status: 'FAILED', errorMessage: 'x' })],
      })],
    }));
    fireEvent.click(screen.getByTestId('sky-eval-session-toggle-56'));
    expect(screen.queryByText(/Fixtures →/)).not.toBeInTheDocument();
  });

  it('shows a progress bar and an approximate cost while running', async () => {
    await runsTab(overview({
      sessions: [session({
        status: 'RUNNING', costGbp: 0.1,
        runs: [run({ id: 56, status: 'RUNNING', progress: 0.4, inBandPct: null, costGbp: 0.05, durationMs: null })],
      })],
    }));
    expect(within(screen.getByTestId('sky-eval-session-56')).getByTestId('sky-eval-session-status')).toHaveTextContent('Running');
    expect(screen.getByTestId('sky-eval-session-56')).toHaveTextContent('~£0.10');
    fireEvent.click(screen.getByTestId('sky-eval-session-toggle-56'));
    const bar = screen.getByTestId('sky-eval-run-progress-56').querySelector('rect:last-child');
    expect(bar).toHaveAttribute('width', '40');
  });

  it('says "Running…" instead of a bar for a batch run with no progress figure', async () => {
    await runsTab(overview({
      sessions: [session({ status: 'RUNNING', runs: [run({ id: 56, status: 'RUNNING', progress: null })] })],
    }));
    fireEvent.click(screen.getByTestId('sky-eval-session-toggle-56'));
    expect(screen.getByTestId('sky-eval-run-progress-56')).toHaveTextContent('Running…');
  });

  it('pulses the running dot only while motion is allowed', async () => {
    await runsTab(overview({ sessions: [session({ status: 'RUNNING', runs: [run({ status: 'RUNNING' })] })] }));
    const dot = screen.getByTestId('sky-eval-session-status').firstElementChild;
    expect(dot.className).toContain('animate-pulse');
    expect(dot.className).toContain('motion-reduce:animate-none');
  });

  it('shows the error text of a failed run and marks the session Failed', async () => {
    await runsTab(overview({
      sessions: [session({
        status: 'FAILED', costGbp: 0,
        runs: [run({ id: 56, status: 'FAILED', errorMessage: 'Batch API timed out', inBandPct: null, costGbp: 0, durationMs: null })],
      })],
    }));
    expect(within(screen.getByTestId('sky-eval-session-56')).getByTestId('sky-eval-session-status')).toHaveTextContent('Failed');
    fireEvent.click(screen.getByTestId('sky-eval-session-toggle-56'));
    expect(screen.getByTestId('sky-eval-run-error-56')).toHaveTextContent('Batch API timed out');
  });

  it('shows ten sessions, then the rest behind a "Show n older sessions" button', async () => {
    const many = Array.from({ length: 12 }, (_, i) => session({
      id: 100 - i, startedAt: `2026-10-${String(28 - i).padStart(2, '0')}T04:00:00Z`,
      runs: [run({ id: 100 - i })],
    }));
    await runsTab(overview({ sessions: many }));
    expect(screen.getAllByTestId(/^sky-eval-session-toggle-/)).toHaveLength(10);
    expect(screen.getByTestId('sky-eval-sessions-more')).toHaveTextContent('Show 2 older sessions');
    click('sky-eval-sessions-more');
    expect(screen.getAllByTestId(/^sky-eval-session-toggle-/)).toHaveLength(12);
    expect(screen.queryByTestId('sky-eval-sessions-more')).not.toBeInTheDocument();
  });

  it('hides version and cost below 900 px and stacks the models under the row', async () => {
    await runsTab();
    const toggle = screen.getByTestId('sky-eval-session-toggle-56');
    expect(toggle.className).toContain('max-[899px]:grid-cols-[minmax(0,1fr)_auto_auto]');
    expect(screen.getAllByTestId('sky-eval-session-models')[0].className).toContain('max-[899px]:col-span-3');
    expect(toggle.querySelectorAll('.max-\\[899px\\]\\:hidden').length).toBeGreaterThanOrEqual(2);
  });

  it('says so when there are no runs yet', async () => {
    await runsTab(overview({ sessions: [] }));
    expect(screen.getByTestId('sky-eval-runs-empty')).toBeInTheDocument();
  });
});

describe('SkyEvalView: polling', () => {
  const running = (extra = {}) => overview({
    sessions: [session({ id: 56, status: 'RUNNING', runs: [run({ id: 56, status: 'RUNNING', progress: 0.2 })] })],
    ...extra,
  });
  const tick = () => act(async () => { vi.advanceTimersByTime(4000); });

  it('refetches every four seconds while a session is running', async () => {
    await mountView(running());
    await settle();
    const before = getSkyEvalOverview.mock.calls.length;
    await tick();
    expect(getSkyEvalOverview.mock.calls.length).toBe(before + 1);
    await tick();
    expect(getSkyEvalOverview.mock.calls.length).toBe(before + 2);
  });

  it('never polls when nothing is running', async () => {
    await mountView();
    await settle();
    expect(getSkyEvalOverview).toHaveBeenCalledTimes(1);
    await tick();
    await tick();
    expect(getSkyEvalOverview).toHaveBeenCalledTimes(1);
  });

  it('stops polling once the answer says nothing is running', async () => {
    await mountView(running());
    await settle();
    getSkyEvalOverview.mockResolvedValue({ data: overview() });
    await tick();
    const stopped = getSkyEvalOverview.mock.calls.length;
    await tick();
    await tick();
    expect(getSkyEvalOverview.mock.calls.length).toBe(stopped);
  });

  it('skips a tick while the previous request is still in flight', async () => {
    await mountView(running());
    await settle();
    const d = deferred();
    getSkyEvalOverview.mockReturnValue(d.promise);
    await tick();
    const inFlight = getSkyEvalOverview.mock.calls.length;
    await tick();
    await tick();
    expect(getSkyEvalOverview.mock.calls.length).toBe(inFlight);
    await act(async () => { d.resolve({ data: running() }); });
  });

  it('drops an older response that arrives after a newer one', async () => {
    await mountView(running());
    await settle();
    const stale = deferred();
    getSkyEvalOverview.mockReturnValueOnce(stale.promise);
    await tick();
    // A check is submitted while that poll is still open; its forced reload answers first.
    startSkyEvalCheck.mockResolvedValue({ data: { runIds: [56] } });
    click('sky-eval-open-check');
    getSkyEvalOverview.mockResolvedValue({ data: running({ fixtures: overview().fixtures.concat(overview().fixtures) }) });
    await act(async () => { click('sky-eval-check-run'); });
    expect(screen.getByTestId('sky-eval-fixture-count')).toHaveTextContent('4 fixtures');
    await act(async () => { stale.resolve({ data: running({ fixtures: overview().fixtures.slice(0, 1) }) }); });
    expect(screen.getByTestId('sky-eval-fixture-count')).toHaveTextContent('4 fixtures');
  });
});


describe('SkyEvalView: review fixes', () => {
  const tick = () => act(async () => { vi.advanceTimersByTime(4000); });
  const runningOverview = (extra = {}) => overview({
    sessions: [session({ id: 56, status: 'RUNNING', runs: [run({ id: 56, status: 'RUNNING', progress: 0.2 })] })],
    ...extra,
  });

  it('shows a failed run\'s cost so the run rows add up to the session cost', async () => {
    await mountView(overview({
      sessions: [session({
        status: 'FAILED', costGbp: 0.05,
        runs: [run({ id: 56, status: 'FAILED', errorMessage: 'Batch API timed out', inBandPct: null, costGbp: 0.05 })],
      })],
    }), 'runs');
    fireEvent.click(screen.getByTestId('sky-eval-session-toggle-56'));
    expect(screen.getByTestId('sky-eval-run-56')).toHaveTextContent('£0.05');
  });

  it('shows the error of a completed run that partly failed, on its own line', async () => {
    await mountView(overview({
      sessions: [session({ runs: [run({ id: 56, status: 'COMPLETED', errorMessage: '3 of 80 ratings failed' })] })],
    }), 'runs');
    fireEvent.click(screen.getByTestId('sky-eval-session-toggle-56'));
    const err = screen.getByTestId('sky-eval-run-error-56');
    expect(err).toHaveTextContent('3 of 80 ratings failed');
    expect(err.className).toContain('col-span-full');
  });

  it('shows no error line on a clean completed run', async () => {
    await mountView(overview(), 'runs');
    fireEvent.click(screen.getByTestId('sky-eval-session-toggle-56'));
    expect(screen.queryByTestId('sky-eval-run-error-56')).not.toBeInTheDocument();
  });

  it('disables Cancel, Close and the header button while submitting, and the error lands on an open panel', async () => {
    const d = deferred();
    startSkyEvalCheck.mockReturnValue(d.promise);
    await mountView();
    click('sky-eval-open-check');
    await act(async () => { click('sky-eval-check-run'); });
    expect(screen.getByTestId('sky-eval-check-cancel')).toBeDisabled();
    expect(screen.getByTestId('sky-eval-check-close')).toBeDisabled();
    expect(screen.getByTestId('sky-eval-open-check')).toBeDisabled();
    await act(async () => {
      d.reject(Object.assign(new Error('x'), { response: { status: 400, data: { error: 'Unknown fixture' } } }));
    });
    expect(screen.getByTestId('sky-eval-check-error')).toHaveTextContent('Unknown fixture');
    expect(screen.getByTestId('sky-eval-check-cancel')).not.toBeDisabled();
    expect(screen.getByTestId('sky-eval-open-check')).not.toBeDisabled();
  });

  it('does not fetch twice when a running session turns polling on', async () => {
    await mountView(runningOverview());
    await settle();
    expect(getSkyEvalOverview).toHaveBeenCalledTimes(1);
    await tick();
    expect(getSkyEvalOverview).toHaveBeenCalledTimes(2);
  });

  it('keeps polling after a submit whose follow-up load failed, until the new run is listed and expanded', async () => {
    startSkyEvalCheck.mockResolvedValue({ data: { runIds: [70] } });
    await mountView();
    click('sky-eval-open-check');
    getSkyEvalOverview.mockRejectedValueOnce(new Error('blip'));
    await act(async () => { click('sky-eval-check-run'); });
    expect(screen.getByTestId('sky-eval-load-error')).toHaveTextContent('blip');
    const calls = getSkyEvalOverview.mock.calls.length;
    getSkyEvalOverview.mockResolvedValue({
      data: overview({ sessions: [session({ id: 70, runs: [run({ id: 70 })] }), ...overview().sessions] }),
    });
    await tick();
    expect(getSkyEvalOverview.mock.calls.length).toBe(calls + 1);
    expect(screen.getByTestId('sky-eval-session-toggle-70')).toHaveAttribute('aria-expanded', 'true');
    await tick();
    await tick();
    expect(getSkyEvalOverview.mock.calls.length).toBe(calls + 1);
  });

  it('states each stale model\'s own age in the footnote', async () => {
    const ov = overview();
    await mountView({
      ...ov,
      models: [
        ...ov.models,
        modelStats({ model: 'SONNET', label: 'Sonnet 4.6', fresh: false, lastTestedAt: '2026-09-22T05:00:00Z', lanes: [] }),
      ],
    });
    expect(within(screen.getByTestId('sky-eval-lane-BATCH_NEAR_TERM')).getByTestId('sky-eval-lane-stale'))
      .toHaveTextContent('Opus last tested 29d ago, Sonnet 4.6 last tested 41d ago.');
  });
});
