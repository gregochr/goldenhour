import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * This week's bar (docs/engineering/screen-spec.md D3): the heading, the help `?` moved here from the strip's retired heading, and
 * the week's best as a sibling button. The strip and Further ahead are stubs, so what is pinned is what only the shell
 * can be wrong about: which served card the button names, the words and the accessible name, and the door it opens.
 */

vi.mock('../components/FurtherAhead.jsx', () => ({ default: () => <div data-testid="stub-further-ahead" /> }));
vi.mock('../components/WindowFirstHeatStrip.jsx', () => ({
  default: ({ quietOpen, onQuietToggle }) => (
    <div data-testid="stub-strip">
      <output data-testid="stub-quiet-open">{[...quietOpen].join(',')}</output>
      <button type="button" data-testid="stub-toggle" onClick={() => onQuietToggle('2026-08-10')}>toggle</button>
    </div>
  ),
}));

const TODAY = '2026-08-08';

const stripCard = (over = {}) => ({
  key: `${TODAY}:SUNSET`,
  date: TODAY,
  targetType: 'SUNSET',
  dow: 'Sat',
  away: false,
  bestRating: 3,
  pickKind: null,
  pickRegion: null,
  pickLocationId: null,
  pickLocationName: null,
  pickRating: null,
  ...over,
});

const BEST = stripCard({
  key: '2026-08-10:SUNRISE',
  date: '2026-08-10',
  targetType: 'SUNRISE',
  dow: 'Mon',
  pickKind: 'best',
  pickRegion: 'North East',
  pickLocationId: 12,
  pickLocationName: 'Crimdon Beach',
  pickRating: 4,
});

const ctx = (heatStripCards) => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [], days: [] },
  loading: false,
  heatStripCards,
  heatSpots: [],
  heatPointSets: new Map(),
  regionSeries: new Map(),
  windowCards: [],
  paneItems: [],
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  reachById: new Map(),
  todayStr: TODAY,
  tomorrowStr: '2026-08-09',
  homePlace: 'Newcastle',
  isPro: true,
  isLiteUser: false,
  reachLens: {
    tier: { id: '60', label: '1h', limitMinutes: 60 },
    tierId: '60',
    defaultTier: { id: '60', label: '1h', limitMinutes: 60 },
    defaultTierId: '60',
    weekend: false,
    overridden: false,
    locked: false,
    selectTier: vi.fn(),
    resetToDefault: vi.fn(),
  },
  ratingLens: {
    floor: { id: 'any', min: null, label: 'Any rating' }, floorId: 'any', minRating: null, selectFloor: vi.fn(),
  },
});

async function renderShell(cards, props = {}) {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(cards));
  const onOpenMapTab = vi.fn();
  await act(async () => {
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onOpenMapTab={onOpenMapTab}
        locations={[]}
        forecastDates={['2026-08-08', '2026-08-09', '2026-08-10', '2026-08-11']}
        {...props}
      />,
    );
  });
  return { onOpenMapTab };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('This week\'s bar: the heading and the help ?', () => {
  it('names the section "This week" alone, with the guide ? in the bar and no "· forecast"', async () => {
    await renderShell([stripCard()]);
    const bar = screen.getByTestId('this-week-bar');
    expect(screen.getByTestId('this-week-heading')).toHaveAccessibleName('This week');
    expect(screen.getByTestId('this-week-heading')).toHaveAttribute('id', 'wf-this-week-h');
    expect(within(bar).getByTestId('guide-link')).toHaveAccessibleName('What fixed and could still move mean');
    expect(bar).not.toHaveTextContent('forecast');
  });
});

describe('This week\'s bar: the week\'s best (screen-spec.md D3)', () => {
  it('shows the served Best bet in words, decoration aside, and names it fully', async () => {
    await renderShell([stripCard(), BEST]);
    const best = screen.getByTestId('this-week-best');
    expect(best).toHaveTextContent('best Mon sunrise, Crimdon Beach 4★ →');
    expect(best).toHaveAccessibleName('Best this week: Monday sunrise, Crimdon Beach, 4 stars. Open on the map.');
    // A sibling of the heading, never inside it.
    expect(screen.getByTestId('this-week-heading')).not.toContainElement(best);
    expect(screen.getByTestId('this-week-heading')).toHaveAccessibleName('This week');
  });

  it('shows no stars when the pick place has no served slot rating', async () => {
    await renderShell([{ ...BEST, pickRating: null }]);
    expect(screen.getByTestId('this-week-best')).toHaveTextContent('best Mon sunrise, Crimdon Beach →');
  });

  it('opens the map view on that window, framing home and the place, from the button', async () => {
    const { onOpenMapTab } = await renderShell([stripCard(), BEST]);
    const best = screen.getByTestId('this-week-best');

    await act(async () => { fireEvent.click(best); });

    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'location', date: '2026-08-10', eventType: 'SUNRISE', locationName: 'Crimdon Beach',
      frame: 'home-and-place', inPlace: false,
    }));
  });

  it('opens on the window alone when the pick names no place id', async () => {
    const { onOpenMapTab } = await renderShell([{ ...BEST, pickLocationId: null, pickLocationName: null }]);
    expect(screen.getByTestId('this-week-best')).toHaveTextContent('best Mon sunrise, North East');

    await act(async () => { fireEvent.click(screen.getByTestId('this-week-best')); });

    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      date: '2026-08-10', eventType: 'SUNRISE', frame: 'home-and-place',
    }));
  });

  it('draws no button with no served Best bet (Lite, an away-scoped origin, none served)', async () => {
    await renderShell([stripCard(), { ...BEST, pickKind: 'also' }, { ...BEST, pickKind: null }]);
    expect(screen.queryByTestId('this-week-best')).toBeNull();
    expect(screen.getByTestId('this-week-heading')).toBeInTheDocument();
  });

  it('draws no button when the best is outside the days the screen shows', async () => {
    await renderShell([BEST], { forecastDates: ['2026-08-08', '2026-08-09'] });
    expect(screen.queryByTestId('this-week-best')).toBeNull();
  });

  it('draws no button with no map door to open', async () => {
    await renderShell([BEST], { onOpenMapTab: undefined });
    expect(screen.queryByTestId('this-week-best')).toBeNull();
  });
});

describe('quiet days: the open set lives in the shell', () => {
  it('hands the strip an empty open set, and a press adds then removes the date', async () => {
    await renderShell([stripCard()]);
    expect(screen.getByTestId('stub-quiet-open')).toHaveTextContent('');

    await act(async () => { fireEvent.click(screen.getByTestId('stub-toggle')); });
    expect(screen.getByTestId('stub-quiet-open')).toHaveTextContent('2026-08-10');

    await act(async () => { fireEvent.click(screen.getByTestId('stub-toggle')); });
    expect(screen.getByTestId('stub-quiet-open')).toHaveTextContent('');
  });
});
