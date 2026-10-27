import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The shell's wiring of the Plan tab's "show this on the map" producers (overlay convergence C4,
 * `docs/engineering/overlay-convergence-plan.md` section 3 C4): the Coming up cards and day chips.
 * Each used to be handed `App`'s overlay trigger (`onShowOnMap`); the shell now builds ONE function,
 * over its `openMapTab`, and hands that to every producer - so each sends the normalised trigger and
 * carries the Plan's live lens.
 *
 * <p>The pane is a stub exposing exactly the prop under test as buttons: what each real
 * producer CALLS it with is pinned by its own suite (`SlotLocationName`,
 * `WindowComingUpEntry`), and what a trigger resolves to by `mapDoor.test.js`; what only the shell
 * can be wrong about is the join between them.
 */

vi.mock('../components/WindowFirstComingUp.jsx', () => ({
  default: ({ onShowOnMap }) => (
    <div data-testid="stub-coming-up">
      <button
        type="button"
        data-testid="coming-up-coastal"
        onClick={() => onShowOnMap({
          kind: 'coming-up', filterAction: 'SEASCAPE', label: 'Spring tide run', date: '2026-08-10',
        })}
      >
        coastal
      </button>
      <button
        type="button"
        data-testid="coming-up-dark-sky"
        onClick={() => onShowOnMap({
          kind: 'coming-up', darkSky: true, label: 'Meteor shower', date: '2026-08-11',
        })}
      >
        dark sky
      </button>
    </div>
  ),
}));

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => ([lng, lat]) => [lng * 10, lat * 10]),
  };
});

const TODAY = '2026-08-08';

const ctx = (briefing = { generatedAt: `${TODAY}T12:00:00`, hotTopics: [], days: [] }) => ({
  briefing,
  loading: false,
  heatStripCards: [],
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
    floor: { id: '4', min: 4, label: '4★+' }, floorId: '4', minRating: 4, selectFloor: vi.fn(),
  },
});

async function renderShell(props = {}, briefing = undefined) {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(briefing));
  const onOpenMapTab = vi.fn();
  await act(async () => {
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onOpenMapTab={onOpenMapTab}
        locations={[]}
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

describe('WindowFirstShell — the Plan tab\'s map producers share one structured door (C4)', () => {
  it('a Coming up coastal card sends a `coming-up` trigger carrying its subject filter and headline', async () => {
    const { onOpenMapTab } = await renderShell();

    await act(async () => { fireEvent.click(screen.getByTestId('coming-up-coastal')); });

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith({
      kind: 'coming-up', filterAction: 'SEASCAPE', darkSky: false, label: 'Spring tide run',
      date: '2026-08-10', inPlace: false, from: 'plan', minRating: 4, limitMinutes: 60, outlierKeys: expect.any(Set),
    });
  });

  it('a Coming up dark-sky card sends the dark-sky lens, not a filter action', async () => {
    const { onOpenMapTab } = await renderShell();

    await act(async () => { fireEvent.click(screen.getByTestId('coming-up-dark-sky')); });

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      kind: 'coming-up', darkSky: true, filterAction: null, date: '2026-08-11',
      minRating: 4, limitMinutes: 60,
    }));
  });

  it('the overlay\'s legacy `onShowOnMap` prop is gone: a stray one is never called', async () => {
    const onShowOnMap = vi.fn();
    const { onOpenMapTab } = await renderShell({ onShowOnMap });

    await act(async () => { fireEvent.click(screen.getByTestId('coming-up-coastal')); });

    expect(onShowOnMap).not.toHaveBeenCalled();
    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
  });
});

describe('WindowFirstShell — the served briefing\'s neighbour-outlier marks ride the trigger', () => {
  it('builds the Set from the flagged slot, in the key shape resolveDoor reads', async () => {
    const briefing = {
      generatedAt: `${TODAY}T12:00:00`,
      hotTopics: [],
      days: [{
        date: '2026-08-09',
        eventSummaries: [{
          targetType: 'SUNRISE',
          regions: [{ slots: [
            { locationName: 'Bamburgh', neighbourOutlier: true },
            { locationName: 'Seahouses' },
          ] }],
        }],
      }],
    };
    const { onOpenMapTab } = await renderShell({}, briefing);
    await act(async () => { fireEvent.click(screen.getByTestId('coming-up-coastal')); });
    const sent = onOpenMapTab.mock.calls[0][0];
    expect([...sent.outlierKeys]).toEqual(['Bamburgh|2026-08-09|SUNRISE']);
  });
});
