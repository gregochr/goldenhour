/**
 * Parity between the location sheet's forecast-detail facts and `MarkerPopupContent`'s, rendered
 * from the SAME forecast row (overlay convergence C2). The markup differs by design (the sheet never
 * imports the popup's inline styles), so what is compared is every badge's rendered text,
 * whitespace-normalised, in the popup's order, for both tiers.
 *
 * What breaks if this fails: a fact reads differently on the sheet than on the popup, or a badge
 * the popup shows (or hides) for a tier is hidden (or shown) on the sheet. Kept after the overlay's
 * deletion (C5) because the popup (`MarkerPopupContent`) still renders facts, so the two
 * surfaces still exist to disagree; the thresholds and labels now come from one module, which makes
 * this a rendering check rather than a logic one.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import MarkerPopupContent from '../components/MarkerPopupContent.jsx';
import LocationSheetFacts from '../components/map/LocationSheetFacts.jsx';

vi.mock('../components/TideIndicator.jsx', () => ({ default: () => null }));

const ROW = {
  id: 900,
  lowCloud: 10, // inline detail: neither surface fetches
  targetDate: '2026-08-14',
  targetType: 'SUNSET',
  solarEventTime: '2026-08-14T19:41:00',
  nextHighTideTime: '2026-08-14T20:20:00',
  forecastRunAt: '2026-08-14T05:00:00',
  evaluationModel: 'HAIKU',
  aerosolOpticalDepth: 0.5,
  inversionPotential: 'STRONG',
  mistPotential: 'LIKELY',
  bluebellScore: 4,
  bluebellSummary: 'Carpets in the glen',
  seaState: 'Slight',
  significantWaveHeightMetres: 0.6,
  surgeTotalMetres: 0.7,
  surgeRiskLevel: 'HIGH',
  rating: 4,
};

const norm = (el) => el.textContent.replace(/\s+/g, ' ').trim();

const POPUP_ORDER = [
  'dust-badge', 'inversion-badge', 'mist-badge', 'bluebell-badge', 'rising-tide-badge', 'sea-state-badge',
  'surge-badge',
];
const SHEET_ID = {
  'dust-badge': 'dust',
  'inversion-badge': 'inversion',
  'mist-badge': 'mist',
  'bluebell-badge': 'bluebell',
  'rising-tide-badge': 'rising-tide',
  'sea-state-badge': 'sea-state',
  'surge-badge': 'surge',
};

function popupBadges(role) {
  const { container, unmount } = render(
    <MarkerPopupContent
      location={{ name: 'Bamburgh', locationType: ['SEASCAPE'], tideType: ['HIGH'] }}
      forecast={ROW}
      hourlyData={[]}
      eventType="SUNSET"
      isPureWildlife={false}
      role={role}
      date="2026-08-14"
      onTideFetchedAt={() => {}}
      tideFetchedAt={null}
    />,
  );
  const out = POPUP_ORDER
    .map((id) => [id, container.querySelector(`[data-testid="${id}"]`)])
    .filter(([, el]) => el)
    .map(([id, el]) => [id, norm(el)]);
  const footer = [...container.querySelectorAll('div')]
    .map(norm)
    .find((t) => t.startsWith('Forecast generated:') && !t.includes('Dust'));
  unmount();
  return { out, footer };
}

function sheetBadges(isPro) {
  const { unmount } = render(
    <LocationSheetFacts forecast={ROW} eventType="SUNSET" isPro={isPro} triageSuperseded />,
  );
  const out = Object.entries(SHEET_ID)
    .map(([popupId, key]) => [popupId, screen.queryByTestId(`location-sheet-fact-${key}`)])
    .filter(([, el]) => el)
    .map(([id, el]) => [id, norm(el)]);
  const footer = norm(screen.getByTestId('location-sheet-generated'));
  unmount();
  return { out, footer };
}

describe('the sheet\'s facts read as the popup\'s did', () => {
  it.each([
    ['PRO_USER', true],
    ['ADMIN', true],
    ['LITE_USER', false],
  ])('badges (%s): same set, same order, same text', (role, isPro) => {
    const popup = popupBadges(role);
    const sheet = sheetBadges(isPro);
    expect(popup.out.length).toBeGreaterThan(0);
    expect(sheet.out).toEqual(popup.out);
  });

  it('the generated-at footer is the popup\'s sentence', () => {
    // ADMIN's popup draws its footer without expanding, so it is findable in the same render.
    const popup = popupBadges('ADMIN');
    const sheet = sheetBadges(true);
    expect(popup.footer).toBeTruthy();
    expect(sheet.footer).toBe(popup.footer);
  });
});
