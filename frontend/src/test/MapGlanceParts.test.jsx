import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import MapBestBetWhy, { MapBestBetPanel } from '../components/map/MapBestBetWhy.jsx';
import MapWarningChips, { MapWarningPanel } from '../components/map/MapWarningChips.jsx';
import RegionsJump from '../components/map/RegionsJump.jsx';
import WindowSpotSheet from '../components/WindowSpotSheet.jsx';
import { WindowWarningsBody } from '../components/WindowWarningRow.jsx';
import { ATTRIBUTION, response, warning } from './warningsFixture.js';

/**
 * The small parts of glance-to-map-plan.md G1 on their own: the reason pill, the warning toggle and panel, the
 * warning body both surfaces draw, the Regions row's second line and the list's phone frame.
 */
const DATE = '2026-06-15';
const EVENT = {
  kind: 'solar', eventType: 'SUNSET', date: DATE, past: false,
};
const LOCATIONS = [{
  id: 7,
  forecastsByDate: new Map([[DATE, { sunset: { solarEventTime: `${DATE}T20:10:00` } }]]),
}];
const span = { validFrom: `${DATE}T00:00:00Z`, validTo: `${DATE}T23:59:00Z` };
const YELLOW = warning({
  warningId: 'y', weatherTypes: ['WIND'], headline: 'Wind headline', ...span,
});
const RED = warning({
  warningId: 'r', level: 'RED', weatherTypes: ['RAIN'], updated: true, ...span,
});

describe('MapBestBetWhy', () => {
  it.each([null, undefined, { kind: 'best', headline: '' }])('prints nothing for %j', (pick) => {
    const { container } = render(<MapBestBetWhy pick={pick} onToggle={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
    const panel = render(<MapBestBetPanel pick={pick} onClose={vi.fn()} />);
    expect(panel.container).toBeEmptyDOMElement();
  });

  it('is the trigger only: it reports its own button and says "Also good" for an also pick', () => {
    const onToggle = vi.fn();
    render(<MapBestBetWhy pick={{ kind: 'also', headline: 'Fine too.' }} onToggle={onToggle} />);
    const pill = screen.getByTestId('map-best-bet-why');
    expect(pill).toHaveTextContent('Also good · why');
    expect(pill).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(pill);
    expect(onToggle).toHaveBeenCalledWith(pill);
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
  });

  it('names its panel only while open', () => {
    render(<MapBestBetWhy pick={{ kind: 'best', headline: 'H' }} open onToggle={vi.fn()} />);
    expect(screen.getByTestId('map-best-bet-why')).toHaveAttribute('aria-controls', 'wf-map-best-bet-panel');
    expect(screen.getByTestId('map-best-bet-why')).toHaveTextContent('Best bet · why');
  });

  it('the panel draws only the parts it was served', () => {
    render(<MapBestBetPanel pick={{ kind: 'also', headline: 'Fine too.' }} onClose={vi.fn()} />);
    expect(screen.getByTestId('map-best-bet-headline')).toHaveTextContent('Fine too.');
    expect(screen.queryByTestId('map-best-bet-detail')).toBeNull();
    expect(screen.queryByTestId('map-best-bet-region')).toBeNull();
    expect(screen.queryByTestId('map-best-bet-location')).toBeNull();
  });

  it('the panel draws the region, headline, detail and place of a best pick, and no star, and closes', () => {
    const onClose = vi.fn();
    render(
      <MapBestBetPanel
        pick={{
          kind: 'best', regionName: 'The Lakes', headline: 'H', detail: 'D', locationName: 'Wastwater',
        }}
        onClose={onClose}
      />,
    );
    expect(screen.getByTestId('map-best-bet-region')).toHaveTextContent('The Lakes');
    expect(screen.getByTestId('map-best-bet-detail')).toHaveTextContent('D');
    expect(screen.getByTestId('map-best-bet-location')).toHaveTextContent('Wastwater');
    const text = screen.getByTestId('map-best-bet-body').textContent;
    expect(text).toMatch(/The Lakes.*H.*D.*Wastwater/);
    expect(text).not.toMatch(/★/);
    fireEvent.click(screen.getByTestId('map-best-bet-close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('the warning expand', () => {
  beforeEach(() => {
    getWarnings.mockReset();
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(`${DATE}T10:00:00Z`));
  });
  afterEach(() => { vi.useRealTimers(); });

  async function mount(served, ui) {
    getWarnings.mockResolvedValue(served);
    await act(async () => { render(<WarningsProvider>{ui}</WarningsProvider>); });
  }

  it('draws no toggle when the parent offers none (the chips as they were)', async () => {
    await mount(response([YELLOW]), <MapWarningChips event={EVENT} locations={LOCATIONS} />);
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
    expect(screen.queryByTestId('map-warning-toggle')).toBeNull();
  });

  it('gives each warning its own toggle, named for it, beside a chip that stays a link', async () => {
    const onToggle = vi.fn();
    await mount(
      response([YELLOW, RED]),
      <MapWarningChips event={EVENT} locations={LOCATIONS} openId="r" onToggle={onToggle} />,
    );
    const toggles = screen.getAllByTestId('map-warning-toggle');
    expect(toggles).toHaveLength(2);
    expect(toggles[0]).toHaveAccessibleName(/Hide details of Red warning of rain/);
    expect(toggles[0]).toHaveAttribute('aria-expanded', 'true');
    expect(toggles[0]).toHaveAttribute('aria-controls', 'wf-map-warning-panel');
    expect(toggles[1]).toHaveAccessibleName(/Show details of Yellow warning of wind/);
    expect(toggles[1]).toHaveAttribute('aria-expanded', 'false');
    expect(toggles[1]).not.toHaveAttribute('aria-controls');
    fireEvent.click(toggles[1]);
    expect(onToggle).toHaveBeenCalledWith('y', toggles[1]);
    screen.getAllByTestId('warning-chip').forEach((chip) => expect(chip.tagName).toBe('A'));
  });

  it('draws no toggle on the stale-only line, and keeps the stale admission', async () => {
    await mount(
      response([], { stale: true, lastCheckedAt: `${DATE}T09:05:00Z` }),
      <MapWarningChips event={EVENT} locations={LOCATIONS} onToggle={vi.fn()} />,
    );
    expect(screen.queryByTestId('map-warning-toggle')).toBeNull();
    expect(screen.getByTestId('warning-stale')).toBeInTheDocument();
  });

  it('keeps the attribution on screen with the panel closed', async () => {
    await mount(
      response([YELLOW]),
      <MapWarningChips event={EVENT} locations={LOCATIONS} onToggle={vi.fn()} />,
    );
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
  });

  it('prints the panel for the open warning', async () => {
    await mount(
      response([YELLOW]),
      <MapWarningPanel event={EVENT} locations={LOCATIONS} openId="y" onClose={vi.fn()} />,
    );
    expect(screen.getByTestId('map-warning-panel')).toHaveTextContent('Wind headline');
    // The chips beside it print the attribution; the panel does not print it a second time.
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
  });

  it.each([
    ['no id open', { openId: null }, response([YELLOW])],
    ['an id that is not in force', { openId: 'gone' }, response([YELLOW])],
    ['the feature off', { openId: 'y' }, response([YELLOW], { enabled: false })],
    ['a night', { openId: 'y', event: { ...EVENT, kind: 'astro' } }, response([YELLOW])],
  ])('prints no panel for %s', async (_name, props, served) => {
    await mount(
      served,
      <MapWarningPanel event={EVENT} locations={LOCATIONS} onClose={vi.fn()} {...props} />,
    );
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
  });

  it('closes from its own button on desktop', async () => {
    const onClose = vi.fn();
    await mount(
      response([YELLOW]),
      <MapWarningPanel event={EVENT} locations={LOCATIONS} openId="y" onClose={onClose} />,
    );
    fireEvent.click(screen.getByTestId('map-warning-panel-close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('is the same body in a phone sheet', async () => {
    await mount(
      response([YELLOW]),
      <MapWarningPanel event={EVENT} locations={LOCATIONS} openId="y" onClose={vi.fn()} isMobile />,
    );
    expect(screen.getByTestId('map-warning-panel')).toHaveTextContent('Wind headline');
    expect(screen.queryByTestId('map-warning-panel-close')).toBeNull();
  });

  it('WindowWarningsBody draws every warning, the advice link and the footer', async () => {
    await mount(response([YELLOW, RED]), <WindowWarningsBody warnings={[YELLOW, RED]} />);
    expect(screen.getAllByTestId('window-warning')).toHaveLength(2);
    expect(screen.getByTestId('window-warning-advice-link')).toBeInTheDocument();
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('WindowWarningsBody can leave its footer to a surface that draws one', async () => {
    await mount(response([YELLOW]), <WindowWarningsBody warnings={[YELLOW]} showFooter={false} />);
    expect(screen.getByTestId('window-warning')).toBeInTheDocument();
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
  });

  it('WindowWarningsBody with no warning draws no advice link and no attribution, only the stale admission', async () => {
    await mount(
      response([], { stale: true, lastCheckedAt: `${DATE}T09:05:00Z` }),
      <WindowWarningsBody warnings={[]} />,
    );
    expect(screen.queryByTestId('window-warning')).toBeNull();
    expect(screen.queryByTestId('window-warning-advice-link')).toBeNull();
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
    expect(screen.getByTestId('warning-stale')).toBeInTheDocument();
  });
});

describe('RegionsJump rows', () => {
  const base = {
    open: true, onOpenChange: vi.fn(), onSelectRegion: vi.fn(),
  };

  it('draws a second line only for a row carrying a verdict, in the Map\'s own words', () => {
    render(
      <RegionsJump
        {...base}
        rows={[
          {
            name: 'Lakes',
            driveMinutes: 40,
            beyondArea: false,
            bestRating: 5,
            tier: 'WORTH_IT',
            verdictLabel: 'Worth it',
            atFourPlus: 2,
            placeCount: 5,
            showCounts: true,
          },
          {
            name: 'Thin',
            driveMinutes: null,
            beyondArea: false,
            bestRating: null,
            tier: 'UNRATED',
            verdictLabel: 'Too few rated',
            atFourPlus: 0,
            placeCount: 0,
            showCounts: false,
          },
          {
            name: 'Far', driveMinutes: 300, beyondArea: true, bestRating: 2,
          },
          {
            name: 'Elsewhere',
            driveMinutes: 90,
            beyondArea: false,
            bestRating: null,
            tier: 'MAYBE',
            verdictLabel: 'Maybe',
            atFourPlus: 0,
            placeCount: 0,
            showCounts: false,
            outside: true,
            outsideCount: 3,
          },
        ]}
      />,
    );
    const [lakes, thin, far, elsewhere] = screen.getAllByTestId('wf-jump-row');
    expect(lakes).toHaveTextContent('Worth it');
    expect(lakes).toHaveTextContent('2 of 5 at 4★+');
    expect(lakes.textContent).not.toMatch(/in reach/i);
    expect(thin).toHaveTextContent('Too few rated');
    expect(thin.querySelector('[data-testid="wf-jump-count"]')).toBeNull();
    expect(far.querySelector('[data-testid="wf-jump-sub"]')).toBeNull();
    // A shaped region with none of the reader's picks says what the drilldown says: the word, the outside count, Pro.
    expect(elsewhere).toHaveTextContent('Maybe');
    expect(elsewhere).toHaveTextContent('Outside your sub-regions · 3 places');
    expect(elsewhere.querySelector('[data-testid="wf-jump-pro"]')).not.toBeNull();
    expect(elsewhere.querySelector('[data-testid="wf-jump-count"]')).toBeNull();
  });
});

describe('WindowSpotSheet in a phone sheet', () => {
  const card = {
    when: 'Sunset',
    kicker: 'Tonight',
    time: '20:41',
    allSpots: [{
      key: '1', locationId: 1, locationName: 'Bamburgh', regionName: 'NE', rating: 4, driveMinutes: 30,
    }],
  };

  it('hosts the card in a bottom sheet without the centred modal\'s chrome', () => {
    render(<WindowSpotSheet card={card} barTierId="any" barFloorId="any" onClose={vi.fn()} presentation="sheet" />);
    expect(screen.getByTestId('bottom-sheet-root')).toBeInTheDocument();
    expect(screen.getByTestId('window-spot-sheet')).toHaveClass('wf-sheet-card--in-sheet');
    expect(screen.getByText('Bamburgh')).toBeInTheDocument();
  });

  it('is the centred modal by default', () => {
    render(<WindowSpotSheet card={card} barTierId="any" barFloorId="any" onClose={vi.fn()} />);
    expect(screen.queryByTestId('bottom-sheet-root')).toBeNull();
    expect(screen.getByTestId('window-spot-sheet')).not.toHaveClass('wf-sheet-card--in-sheet');
  });
});
