/**
 * `utils/mapDoor.js` and `MapBreadcrumb` and `bigSeasCopy` on the daylight High-water window
 * (hot-topics-by-day-plan §3 M3). The row is never a door's window, but the `=== 'SUNRISE' ? … : sunset`
 * fallbacks in `ratingFor` and `solarTimeFor` would hand it the sunset's rating and clock time if a trigger
 * ever named it; the explicit guards pin that they do not.
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { resolveDoor } from '../utils/mapDoor.js';
import { bigSeasCopy } from '../utils/bigSeas.js';
import MapBreadcrumb from '../components/map/MapBreadcrumb.jsx';
import { FRIDAY_HW } from './bigSeasFixtures.js';

const DATE = '2026-07-04';

function loc(name, rating) {
  return {
    name,
    regionName: 'Tyne and Wear',
    lat: 54,
    lon: -1,
    enabled: true,
    locationType: ['LANDSCAPE'],
    bortleClass: null,
    forecastsByDate: new Map([[DATE, { sunset: { rating, solarEventTime: `${DATE}T20:49:00` } }]]),
  };
}

const ctx = (locations) => ({
  locations, briefingScores: new Map(), todayStr: DATE, tomorrowStr: '2026-07-05',
});

describe('mapDoor: HIGH_WATER is never a door window', () => {
  const trigger = (eventType) => ({
    kind: 'region', region: 'Tyne and Wear', date: DATE, eventType,
  });

  it('reads no rating and no clock time for it (control: SUNSET reads both)', () => {
    const locations = [loc('Low', 2), loc('Top', 5)];
    const sunset = resolveDoor(trigger('SUNSET'), ctx(locations));
    expect(sunset.handoff.locationName).toBe('Top');
    expect(sunset.subLine).toContain('21:49');
    const hw = resolveDoor(trigger('HIGH_WATER'), ctx(locations));
    // No sunset time is borrowed for it, and the sunset's 5★ does not pick the place.
    expect(hw.subLine).not.toContain('21:49');
    expect(hw.handoff.locationName).not.toBe('Top');
  });
});

describe('MapBreadcrumb: the window clause', () => {
  it('says "high water", not the raw enum', () => {
    render(
      <MapBreadcrumb
        carried={{ title: 'Big seas' }}
        minStars={3}
        driveTimeFilter={0}
        activeRow={{ dayLabel: 'Tomorrow', eventType: 'HIGH_WATER' }}
        onBack={() => {}}
        onClearRating={() => {}}
        onClearReach={() => {}}
        onClearScope={() => {}}
        onClearOrigin={() => {}}
      />,
    );
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('Tomorrow high water');
    expect(document.body.textContent).not.toMatch(/high_water/i);
  });
});

describe('bigSeasCopy: the High-water lead needs both served wave facts', () => {
  const sel = (day) => ({
    form: 'full', day, aligned: false, jumpRow: null,
  });

  it('prints the "with … waves from …" clause when both are served', () => {
    expect(bigSeasCopy(sel(FRIDAY_HW)).lead).toContain('with 3.4 m waves from NNE');
  });

  it.each([
    ['height', { waveHeightM: undefined }],
    ['direction', { waveDirectionDeg: undefined }],
    ['both', { waveHeightM: null, waveDirectionDeg: null }],
  ])('leaves the clause out whole when the %s is missing (never "NaN m")', (_what, missing) => {
    const lead = bigSeasCopy(sel({ ...FRIDAY_HW, ...missing })).lead;
    expect(lead).toBe(
      'High water at 12:40. Not a light window: this is for spray over the piers, not for colour.',
    );
    expect(lead).not.toMatch(/NaN|undefined|null/);
  });
});
