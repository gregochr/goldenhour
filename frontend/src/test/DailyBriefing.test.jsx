import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import DailyBriefing, { bestConfidence } from '../components/DailyBriefing.jsx';

vi.mock('../api/briefingApi.js', () => ({
  getDailyBriefing: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: vi.fn(),
}));

vi.mock('../api/briefingEvaluationApi.js', () => ({
  getAllEvaluationScores: vi.fn(() => Promise.resolve([])),
}));

vi.mock('../api/settingsApi.js', () => ({
  getDriveTimes: vi.fn(() => Promise.resolve({})),
}));

vi.mock('../api/travelDayApi.js', () => ({
  fetchTravelDayRanges: vi.fn(() => Promise.resolve([])),
}));

/**
 * The full briefing grid is collapsed by default — open it before asserting grid content.
 */
async function openFullGrid() {
  const expander = await screen.findByTestId('grid-expander');
  fireEvent.click(expander);
  await waitFor(() => screen.getByTestId('briefing-heatmap'));
}

vi.mock('../api/hotTopicSimulationApi.js', () => ({
  getSimulationState: vi.fn(() => Promise.resolve({ enabled: false })),
}));

import { getDailyBriefing } from '../api/briefingApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import { getDriveTimes } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getSimulationState } from '../api/hotTopicSimulationApi.js';

// ── Date helpers ─────────────────────────────────────────────────────────────

// Mirror DailyBriefing's own today/tomorrow derivation exactly: local-calendar day
// arithmetic, formatted in Europe/London. Using UTC here diverges from the component in
// the 23:00–24:00 UTC window under BST (UTC-tomorrow collapses to London-today), which
// flips the first heatmap column from "Tomorrow" to "Today" and fails the header tests.
function londonDateStr(offsetDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(d);
}

function futureDateStr(daysAhead = 1) {
  return londonDateStr(daysAhead);
}

function pastDateStr() {
  return londonDateStr(-1);
}

// ── Standard fixture ─────────────────────────────────────────────────────────
//
//  Day 1 (tomorrow):
//    SUNRISE:  Northumberland (MARGINAL) — Bamburgh slot with flags + king tide
//              Coastal North (STANDDOWN)  — Seaham slot
//    SUNSET:   Lake District (GO)         — Keswick slot
//              unregioned: Durham (MARGINAL)
//
//  Region sort order (GO first):
//    0 → Lake District  (GO via sunset)
//    1 → Northumberland (MARGINAL via sunrise)
//    2 → Coastal North  (STANDDOWN via sunrise)

function buildBriefing(overrides = {}) {
  const dateStr = futureDateStr();
  return {
    generatedAt: new Date().toISOString().slice(0, 19),
    headline: 'Tomorrow sunset looks promising in the Lake District',
    days: [
      {
        date: dateStr,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [
              {
                regionName: 'Northumberland',
                verdict: 'MARGINAL',
                summary: 'Some cloud at 3 locations',
                tideHighlights: ['King Tide at 1 coastal spot'],
                slots: [
                  {
                    locationName: 'Bamburgh',
                    solarEventTime: `${dateStr}T05:47:00`,
                    verdict: 'MARGINAL',
                    lowCloudPercent: 55,
                    precipitationMm: 0.2,
                    visibilityMetres: 12000,
                    temperatureCelsius: 6.1,
                    windSpeedMs: 8.3,
                    tideState: 'HIGH',
                    tideAligned: true,
                    flags: ['Sun blocked', 'Active rain', 'King tide'],
                  },
                ],
              },
              {
                regionName: 'Coastal North',
                verdict: 'STANDDOWN',
                summary: 'Heavy cloud and rain',
                tideHighlights: [],
                slots: [
                  {
                    locationName: 'Seaham',
                    solarEventTime: `${dateStr}T05:50:00`,
                    verdict: 'STANDDOWN',
                    lowCloudPercent: 92,
                    precipitationMm: 4.2,
                    visibilityMetres: 8000,
                    temperatureCelsius: 6.1,
                    windSpeedMs: 8.3,
                    tideState: null,
                    tideAligned: false,
                    flags: [],
                  },
                ],
              },
            ],
            unregioned: [],
          },
          {
            targetType: 'SUNSET',
            regions: [
              {
                regionName: 'Lake District',
                verdict: 'GO',
                summary: 'Clear at 3 of 4 locations',
                tideHighlights: [],
                slots: [
                  {
                    locationName: 'Keswick',
                    solarEventTime: `${dateStr}T18:30:00`,
                    verdict: 'GO',
                    lowCloudPercent: 15,
                    precipitationMm: 0,
                    visibilityMetres: 20000,
                    temperatureCelsius: 10.5,
                    windSpeedMs: 3.2,
                    tideState: null,
                    tideAligned: false,
                    flags: [],
                  },
                ],
              },
            ],
            unregioned: [
              {
                locationName: 'Durham',
                solarEventTime: `${dateStr}T18:28:00`,
                verdict: 'MARGINAL',
                lowCloudPercent: 60,
                precipitationMm: 0.8,
                visibilityMetres: 12000,
                temperatureCelsius: 9.0,
                windSpeedMs: 4.1,
                tideState: null,
                tideAligned: false,
                flags: ['Partial cloud', 'Light rain'],
              },
            ],
          },
        ],
      },
    ],
    ...overrides,
  };
}

// ── Test setup ───────────────────────────────────────────────────────────────

describe('DailyBriefing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    useAuth.mockReturnValue({ role: 'ADMIN' });
    // Default: not away. clearAllMocks leaves implementations intact, so re-assert the
    // baseline here to keep a per-test travel-range override from leaking.
    fetchTravelDayRanges.mockResolvedValue([]);
  });

  // ────── Rendering ──────

  it('renders nothing when loading', async () => {
    getDailyBriefing.mockReturnValue(new Promise(() => {}));
    const { container } = render(<DailyBriefing />);
    expect(container.querySelector('[data-testid="daily-briefing"]')).toBeNull();
  });

  it('renders nothing when briefing is null', async () => {
    getDailyBriefing.mockResolvedValue(null);
    const { container } = render(<DailyBriefing />);
    await waitFor(() => {
      expect(container.querySelector('[data-testid="daily-briefing"]')).toBeNull();
    });
  });

  it('renders PhotoCast Planner label', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => {
      expect(screen.getByTestId('daily-briefing')).toBeInTheDocument();
    });
    expect(screen.getByText(/PhotoCast Planner/i)).toBeInTheDocument();
  });

  it('shows freshness timestamp', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => {
      expect(screen.getByText(/ago|just now/i)).toBeInTheDocument();
    });
  });

  // ────── Expand/collapse ──────

  it('expands on click to show briefing-expanded', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.getByTestId('briefing-expanded')).toBeInTheDocument();
  });

  it('shows Sunrise and Sunset text after expanding (from compact rows)', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    // Multiple "Sunrise"/"Sunset" elements exist: compact rows + mobile sub-section headers
    expect(screen.getAllByText(/Sunrise/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/Sunset/).length).toBeGreaterThanOrEqual(1);
  });

  it('collapses on second click', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));
    expect(screen.getByTestId('briefing-expanded')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('briefing-toggle'));
    expect(screen.queryByTestId('briefing-expanded')).toBeNull();
  });

  // ────── Dismiss / restore (session-scoped) ──────

  it('shows dismiss button on the card', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-minimise'));
    expect(screen.getByTestId('briefing-minimise')).toBeInTheDocument();
  });

  it('clicking × hides the card and shows the pill', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-minimise'));

    fireEvent.click(screen.getByTestId('briefing-minimise'));

    expect(screen.queryByTestId('daily-briefing')).toBeNull();
    expect(screen.getByTestId('briefing-minimised-pill')).toBeInTheDocument();
  });

  it('clicking the pill restores the card', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-minimise'));

    fireEvent.click(screen.getByTestId('briefing-minimise'));
    fireEvent.click(screen.getByTestId('briefing-minimised-pill'));

    expect(screen.getByTestId('daily-briefing')).toBeInTheDocument();
    expect(screen.queryByTestId('briefing-minimised-pill')).toBeNull();
  });

  it('persists dismissed generatedAt in sessionStorage', async () => {
    const briefing = buildBriefing();
    getDailyBriefing.mockResolvedValue(briefing);
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-minimise'));

    fireEvent.click(screen.getByTestId('briefing-minimise'));

    expect(sessionStorage.getItem('briefing-dismissed-at')).toBe(briefing.generatedAt);
  });

  it('shows pill on refresh when sessionStorage has same generatedAt', async () => {
    const briefing = buildBriefing();
    sessionStorage.setItem('briefing-dismissed-at', briefing.generatedAt);
    getDailyBriefing.mockResolvedValue(briefing);
    render(<DailyBriefing />);

    await waitFor(() => screen.getByTestId('briefing-minimised-pill'));

    expect(screen.queryByTestId('daily-briefing')).toBeNull();
    expect(screen.getByTestId('briefing-minimised-pill')).toBeInTheDocument();
  });

  it('auto-shows the card when a newer briefing is generated after dismissal', async () => {
    const futureDateS = futureDateStr();
    const oldBriefing = { ...buildBriefing(), generatedAt: `${futureDateS}T10:00:00` };
    const newBriefing = { ...buildBriefing(), generatedAt: `${futureDateS}T12:00:00` };

    sessionStorage.setItem('briefing-dismissed-at', oldBriefing.generatedAt);
    getDailyBriefing.mockResolvedValue(newBriefing);
    render(<DailyBriefing />);

    await waitFor(() => screen.getByTestId('daily-briefing'));
    expect(screen.queryByTestId('briefing-minimised-pill')).toBeNull();
  });

  it('shows the card when sessionStorage flag is absent (new session)', async () => {
    sessionStorage.removeItem('briefing-dismissed-at');
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('daily-briefing'));

    expect(screen.getByTestId('daily-briefing')).toBeInTheDocument();
  });

  // ────── Past event filtering ──────

  it('hides past events in compact rows and day-card view', async () => {
    const pastStr = pastDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: pastStr,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [{ regionName: 'Northumberland', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'Bamburgh', solarEventTime: `${pastStr}T04:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
        ],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.queryByText(/Sunrise/)).toBeNull();
    expect(screen.queryByText('Northumberland')).toBeNull();
  });

  it('shows future events and hides past events in the same day', async () => {
    const pastStr = pastDateStr();
    const futureStr = futureDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: pastStr,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [{ regionName: 'Past Region', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'A', solarEventTime: `${pastStr}T04:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
          {
            targetType: 'SUNSET',
            regions: [{ regionName: 'Future Region', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'B', solarEventTime: `${futureStr}T20:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
        ],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.queryByText('Past Region')).toBeNull();
    // "Sunset" appears in the compact summary row and/or mobile sub-section header
    expect(screen.getAllByText(/Sunset/).length).toBeGreaterThanOrEqual(1);
  });

  it('hides "Today" when all events for today have passed', async () => {
    const pastStr = pastDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: pastStr,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [{ regionName: 'Northumberland', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'A', solarEventTime: `${pastStr}T04:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
          {
            targetType: 'SUNSET',
            regions: [{ regionName: 'Lake District', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'B', solarEventTime: `${pastStr}T17:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
        ],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.queryByText(/^Today$/)).toBeNull();
  });

  // ────── Mobile region cards ──────

  it('shows region cards with verdict pills after expanding', async () => {
    // Set quality tier to "All" so MARGINAL and STANDDOWN cards are visible
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    expect(regionRows.length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('Northumberland').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Lake District').length).toBeGreaterThanOrEqual(1);
  });

  it('shows Worth it and Stand down verdict pills', async () => {
    // Set quality tier to "All" so all verdict types are visible on mobile
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const pills = screen.getAllByTestId('verdict-pill');
    const pillTexts = pills.map((p) => p.textContent);
    expect(pillTexts).toContain('Stand down');
    expect(pillTexts).toContain('Worth it');
  });

  it('marks a low-confidence region card pill as provisional (MobileRegionCard wiring)', async () => {
    // Wiring guard for DailyBriefing's region-level pill: a low-confidence Worth-it region card
    // carries the shared provisional marker (bestRegion.confidence -> VerdictPill).
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    const briefing = buildBriefing();
    briefing.days.forEach((d) => d.eventSummaries.forEach((es) => es.regions.forEach((r) => {
      if (r.verdict === 'GO') r.confidence = 'low';
    })));
    getDailyBriefing.mockResolvedValue(briefing);
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const worthItRow = screen.getAllByTestId('region-row').find(
      (row) => row.querySelector('[data-testid="verdict-pill"]')?.textContent === 'Worth it');
    expect(worthItRow).toBeTruthy();
    expect(worthItRow.querySelector('[data-testid="provisional-mark"]')).not.toBeNull();
  });

  it('shows Maybe verdict pill for MARGINAL region', async () => {
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const pills = screen.getAllByTestId('verdict-pill');
    const pillTexts = pills.map((p) => p.textContent);
    expect(pillTexts).toContain('Maybe');
  });

  // ── Gate 2 honesty patch: verdictLabel override ──

  it('verdictLabel override replaces the default STAND_DOWN pill label', async () => {
    // Region returned from the API in its post-honesty-filter shape:
    // displayVerdict=STAND_DOWN with a custom verdictLabel. The pill must
    // render the override text, not the default 'Stand down'.
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    const dateStr = futureDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: dateStr,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{
            regionName: 'The Lake District',
            verdict: 'GO',
            displayVerdict: 'STAND_DOWN',
            verdictLabel: 'Too unsettled to forecast',
            summary: 'No per-location forecast — conditions too unsettled to evaluate',
            tideHighlights: [],
            slots: [],
          }],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const pills = screen.getAllByTestId('verdict-pill');
    const pillTexts = pills.map((p) => p.textContent);
    expect(pillTexts).toContain('Too unsettled to forecast');
    expect(pillTexts).not.toContain('Stand down');
  });

  it('without verdictLabel, STAND_DOWN pill falls back to the default "Stand down" label', async () => {
    // Mirrors the override test but with no verdictLabel — the default label survives.
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    const dateStr = futureDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: dateStr,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{
            regionName: 'Coastal North',
            verdict: 'STANDDOWN',
            displayVerdict: 'STAND_DOWN',
            summary: 'Heavy cloud and rain across all 3 locations',
            tideHighlights: [],
            slots: [],
          }],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const pills = screen.getAllByTestId('verdict-pill');
    const pillTexts = pills.map((p) => p.textContent);
    expect(pillTexts).toContain('Stand down');
  });

  it('mobile region card shows Worth it sunset for GO sunset region', async () => {
    const dateStr = futureDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: dateStr,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{ regionName: 'Lake District', verdict: 'GO', summary: 'Clear', tideHighlights: [], slots: [{ locationName: 'Keswick', solarEventTime: `${dateStr}T18:30:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    const goRow = regionRows.find((r) => r.textContent.includes('Lake District'));
    expect(goRow.textContent).toContain('Worth it sunset');
  });

  it('mobile region card shows Maybe sunrise for MARGINAL sunrise region', async () => {
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    const dateStr = futureDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: dateStr,
        eventSummaries: [{
          targetType: 'SUNRISE',
          regions: [{ regionName: 'Northumberland', verdict: 'MARGINAL', summary: 'Cloud', tideHighlights: [], slots: [{ locationName: 'Bamburgh', solarEventTime: `${dateStr}T05:47:00`, verdict: 'MARGINAL', tideAligned: false, flags: [] }] }],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    const marginalRow = regionRows.find((r) => r.textContent.includes('Northumberland'));
    expect(marginalRow.textContent).toContain('Maybe sunrise');
  });

  it('mobile region card hides verdictLabel for STANDDOWN', async () => {
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    const standdownRow = regionRows.find((r) => r.textContent.includes('Coastal North'));
    expect(standdownRow.textContent).not.toContain('Worth it');
    expect(standdownRow.textContent).not.toContain('Maybe');
    expect(standdownRow.textContent).not.toContain('Poor');
  });

  it('regions sorted GO first, then MARGINAL, then STANDDOWN', async () => {
    // Set quality tier to "All" so MARGINAL/STANDDOWN cards are visible on mobile
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    // Mobile layout groups by sunrise/sunset sections:
    // Sunrise section: Northumberland (MARGINAL), Coastal North (STANDDOWN)
    // Sunset section: Lake District (GO)
    // All three should be visible at tier=5
    const rowTexts = regionRows.map((r) => r.textContent);
    expect(rowTexts.some((t) => t.includes('Lake District'))).toBe(true);
    expect(rowTexts.some((t) => t.includes('Northumberland'))).toBe(true);
  });

  it('STANDDOWN region card is not tappable', async () => {
    // Set quality tier to "All" so STANDDOWN cards are visible on mobile
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    const standdownCard = regionRows.find((r) => r.textContent.includes('Coastal North'));
    expect(standdownCard).toBeTruthy();
    expect(standdownCard.disabled).toBe(true);
  });

  // ────── Drill-down: event list → location slots ──────

  it('clicking a GO region card opens drill-down event list', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    const lakeDist = regionRows.find((r) => r.textContent.includes('Lake District'));
    fireEvent.click(lakeDist);

    expect(screen.getAllByTestId('drill-down-event-row').length).toBeGreaterThanOrEqual(1);
  });

  it('clicking a GO event row in drill-down shows location slots', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    // Open Lake District card
    const regionRows = screen.getAllByTestId('region-row');
    fireEvent.click(regionRows.find((r) => r.textContent.includes('Lake District')));

    // Click GO event row → location slots
    const eventRows = screen.getAllByTestId('drill-down-event-row');
    const goEvent = eventRows.find((r) => !r.disabled && r.getAttribute('role') === 'button');
    fireEvent.click(goEvent);

    expect(screen.getByTestId('region-slots')).toBeInTheDocument();
    expect(screen.getByText('Keswick')).toBeInTheDocument();
  });

  it('clicking a MARGINAL event row shows its location slots', async () => {
    // Set quality tier to "All" so MARGINAL cards are visible on mobile
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    // Open Northumberland card (MARGINAL)
    const regionRows = screen.getAllByTestId('region-row');
    fireEvent.click(regionRows.find((r) => r.textContent.includes('Northumberland')));

    const eventRows = screen.getAllByTestId('drill-down-event-row');
    const marginalEvent = eventRows.find((r) => r.getAttribute('role') === 'button');
    fireEvent.click(marginalEvent);

    expect(screen.getByTestId('region-slots')).toBeInTheDocument();
    expect(screen.getByText('Bamburgh')).toBeInTheDocument();
  });

  it('STANDDOWN events in drill-down are greyed out and not tappable', async () => {
    const dateStr = futureDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: dateStr,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{
            regionName: 'Coastal',
            verdict: 'GO',
            summary: 'Clear',
            tideHighlights: [],
            slots: [
              { locationName: 'A', solarEventTime: `${dateStr}T18:30:00`, verdict: 'GO', tideAligned: false, flags: [] },
            ],
          }],
          unregioned: [],
        }, {
          targetType: 'SUNRISE',
          regions: [{
            regionName: 'Coastal',
            verdict: 'STANDDOWN',
            summary: 'Heavy cloud',
            tideHighlights: [],
            slots: [
              { locationName: 'A', solarEventTime: `${dateStr}T05:30:00`, verdict: 'STANDDOWN', tideAligned: false, flags: [] },
            ],
          }],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    fireEvent.click(regionRows[0]);

    const eventRows = screen.getAllByTestId('drill-down-event-row');
    const standdownRow = eventRows.find((r) => r.getAttribute('role') !== 'button');
    expect(standdownRow).toBeTruthy();
  });

  // ────── Show on map handoff ──────

  it('calls onShowOnMap with correct date and event type when map button is clicked', async () => {
    const onShowOnMap = vi.fn();
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing onShowOnMap={onShowOnMap} />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    // Open Lake District card (GO sunset)
    const regionRows = screen.getAllByTestId('region-row');
    fireEvent.click(regionRows.find((r) => r.textContent.includes('Lake District')));

    // Map button should be visible on the GO event row (not expanded yet)
    const mapButtons = screen.getAllByTestId('show-on-map-btn');
    expect(mapButtons.length).toBeGreaterThan(0);
    fireEvent.click(mapButtons[0]);

    expect(onShowOnMap).toHaveBeenCalledWith(
      expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
      'SUNSET',
    );
  });

  it('does not show map button when onShowOnMap is not provided', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    fireEvent.click(regionRows.find((r) => r.textContent.includes('Lake District')));

    expect(screen.queryByTestId('show-on-map-btn')).toBeNull();
  });

  // ────── Flags ──────

  it('shows flag chips in location slot list', async () => {
    // Set quality tier to "All" so MARGINAL Northumberland card is visible on mobile
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    // Northumberland → MARGINAL sunrise → Bamburgh with flags
    const regionRows = screen.getAllByTestId('region-row');
    fireEvent.click(regionRows.find((r) => r.textContent.includes('Northumberland')));

    const eventRows = screen.getAllByTestId('drill-down-event-row');
    fireEvent.click(eventRows.find((r) => r.getAttribute('role') === 'button'));

    expect(screen.getByText('Sun blocked')).toBeInTheDocument();
    expect(screen.getByText('Active rain')).toBeInTheDocument();
    expect(screen.getAllByText('King tide').length).toBeGreaterThan(0);
  });

  // ────── Tide highlights ──────

  it('shows tide highlight chips on region card', async () => {
    // Set quality tier to "All" so MARGINAL Northumberland card (with king tide) is visible
    localStorage.setItem('plannerQualityTier', JSON.stringify(5));
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.getAllByText('1 king tide').length).toBeGreaterThan(0);
  });

  // ────── Collapsed event summary rows ──────

  it('shows compact event summary rows for upcoming events (always visible)', async () => {
    const tomorrow = new Date();
    tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
    const tomorrowStr = tomorrow.toISOString().slice(0, 10);

    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '🌅 Tomorrow sunrise looking excellent — 6 regions GO',
      days: [{
        date: tomorrowStr,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [{ regionName: 'Northumberland', verdict: 'GO', summary: 'Clear', tideHighlights: [], slots: [{ locationName: 'A', solarEventTime: `${tomorrowStr}T05:47:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
          {
            targetType: 'SUNSET',
            regions: [{ regionName: 'Lake District', verdict: 'STANDDOWN', summary: 'Rain', tideHighlights: [], slots: [{ locationName: 'B', solarEventTime: `${tomorrowStr}T20:15:00`, verdict: 'STANDDOWN', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
        ],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

    const rows = screen.getAllByTestId('event-summary-row');
    expect(rows).toHaveLength(2);
  });

  it('shows GO and STANDDOWN counts in collapsed event row', async () => {
    const tomorrowStr = futureDateStr();

    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: tomorrowStr,
        eventSummaries: [{
          targetType: 'SUNRISE',
          regions: [
            { regionName: 'Northumberland', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'A', solarEventTime: `${tomorrowStr}T06:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] },
            { regionName: 'Lake District', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'B', solarEventTime: `${tomorrowStr}T06:05:00`, verdict: 'GO', tideAligned: false, flags: [] }] },
            { regionName: 'Coastal', verdict: 'STANDDOWN', summary: '', tideHighlights: [], slots: [{ locationName: 'C', solarEventTime: `${tomorrowStr}T06:03:00`, verdict: 'STANDDOWN', tideAligned: false, flags: [] }] },
          ],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

    expect(screen.getByTestId('go-count')).toHaveTextContent('2 go');
    expect(screen.getByTestId('standdown-count')).toHaveTextContent('1 poor');
  });

  it('shows MARGINAL count when present', async () => {
    const tomorrowStr = futureDateStr();

    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: tomorrowStr,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [
            { regionName: 'A', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'X', solarEventTime: `${tomorrowStr}T20:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] },
            { regionName: 'B', verdict: 'MARGINAL', summary: '', tideHighlights: [], slots: [{ locationName: 'Y', solarEventTime: `${tomorrowStr}T20:05:00`, verdict: 'MARGINAL', tideAligned: false, flags: [] }] },
          ],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

    expect(screen.getByTestId('marginal-count')).toHaveTextContent('1 maybe');
  });

  it('shows tide alignment indicator in compact row when a slot is tide-aligned', async () => {
    const tomorrowStr = futureDateStr();

    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: tomorrowStr,
        eventSummaries: [{
          targetType: 'SUNRISE',
          regions: [{ regionName: 'Northumberland', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'Bamburgh', solarEventTime: `${tomorrowStr}T05:47:00`, verdict: 'GO', tideAligned: true, flags: [] }] }],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

    expect(screen.getByTitle('Tide-aligned location in this event')).toBeInTheDocument();
  });

  it('does not show tide alignment when no slots are tide-aligned', async () => {
    const tomorrowStr = futureDateStr();

    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: tomorrowStr,
        eventSummaries: [{
          targetType: 'SUNRISE',
          regions: [{ regionName: 'Northumberland', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'Bamburgh', solarEventTime: `${tomorrowStr}T05:47:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

    expect(screen.queryByTitle('Tide-aligned location in this event')).toBeNull();
  });

  it('shows no-upcoming message when all events have passed', async () => {
    const yesterdayStr = pastDateStr();

    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: yesterdayStr,
        eventSummaries: [{
          targetType: 'SUNRISE',
          regions: [{ regionName: 'Northumberland', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'Bamburgh', solarEventTime: `${yesterdayStr}T05:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
          unregioned: [],
        }],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

    expect(screen.queryAllByTestId('event-summary-row')).toHaveLength(0);
    expect(screen.getByText(/No upcoming events/)).toBeInTheDocument();
  });

  // ────── Slot sort order ──────

  describe('slot sort order within a region', () => {
    it('renders GO before MARGINAL before STANDDOWN, then A-Z within each group', async () => {
      const dateStr = futureDateStr();
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [{
          date: dateStr,
          eventSummaries: [{
            targetType: 'SUNSET',
            regions: [{
              regionName: 'TestRegion',
              verdict: 'GO',
              summary: 'Mixed',
              tideHighlights: [],
              slots: [
                { locationName: 'Zelda',  solarEventTime: `${dateStr}T18:00:00`, verdict: 'STANDDOWN', tideAligned: false, flags: [] },
                { locationName: 'Alpha',  solarEventTime: `${dateStr}T18:00:00`, verdict: 'GO',        tideAligned: false, flags: [] },
                { locationName: 'Beta',   solarEventTime: `${dateStr}T18:00:00`, verdict: 'MARGINAL',  tideAligned: false, flags: [] },
                { locationName: 'Cedar',  solarEventTime: `${dateStr}T18:00:00`, verdict: 'GO',        tideAligned: false, flags: [] },
              ],
            }],
            unregioned: [],
          }],
        }],
      });
      render(<DailyBriefing locations={[]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      // Open the region card
      fireEvent.click(screen.getByTestId('region-row'));

      // Click the GO event row
      const eventRows = screen.getAllByTestId('drill-down-event-row');
      fireEvent.click(eventRows.find((r) => r.getAttribute('role') === 'button'));

      const slots = screen.getAllByTestId('briefing-slot');
      const names = slots.map((s) => s.querySelector('.font-medium').textContent.trim());
      // GO slots: Alpha, Cedar; MARGINAL: Beta (STANDDOWN hidden)
      expect(names).toEqual(['Alpha', 'Cedar', 'Beta']);
    });
  });

  // ────── Drive time display ──────

  describe('drive time display', () => {
    it('shows formatted drive time when driveDurationMinutes is in the locations prop', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({ 1: 45 });
      render(<DailyBriefing locations={[{ id: 1, name: 'Keswick' }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      fireEvent.click(regionRows.find((r) => r.textContent.includes('Lake District')));

      const eventRows = screen.getAllByTestId('drill-down-event-row');
      fireEvent.click(eventRows.find((r) => r.getAttribute('role') === 'button'));

      const driveTimes = screen.getAllByTestId('slot-drive-time');
      expect(driveTimes).toHaveLength(1);
      expect(driveTimes[0]).toHaveTextContent('45 min');
    });

    it('does not show drive time when driveDurationMinutes is absent', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing locations={[{ id: 1, name: 'Keswick' }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      fireEvent.click(regionRows.find((r) => r.textContent.includes('Lake District')));

      const eventRows = screen.getAllByTestId('drill-down-event-row');
      fireEvent.click(eventRows.find((r) => r.getAttribute('role') === 'button'));

      expect(screen.queryByTestId('slot-drive-time')).toBeNull();
    });

    it('formats durations over 1 hour correctly', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({ 1: 90 });
      render(<DailyBriefing locations={[{ id: 1, name: 'Keswick' }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      fireEvent.click(regionRows.find((r) => r.textContent.includes('Lake District')));

      const eventRows = screen.getAllByTestId('drill-down-event-row');
      fireEvent.click(eventRows.find((r) => r.getAttribute('role') === 'button'));

      expect(screen.getByTestId('slot-drive-time')).toHaveTextContent('1h 30min');
    });

    it('formats exact-hour durations without trailing minutes', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({ 1: 120 });
      render(<DailyBriefing locations={[{ id: 1, name: 'Keswick' }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      fireEvent.click(regionRows.find((r) => r.textContent.includes('Lake District')));

      const eventRows = screen.getAllByTestId('drill-down-event-row');
      fireEvent.click(eventRows.find((r) => r.getAttribute('role') === 'button'));

      expect(screen.getByTestId('slot-drive-time')).toHaveTextContent('2h');
    });
  });

  // ────── Location type icons ──────

  describe('location type icons', () => {
    async function openLakeDistrictSlots(locationType) {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing locations={[{ name: 'Keswick', locationType }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      fireEvent.click(regionRows.find((r) => r.textContent.includes('Lake District')));

      const eventRows = screen.getAllByTestId('drill-down-event-row');
      fireEvent.click(eventRows.find((r) => r.getAttribute('role') === 'button'));
    }

    it('shows landscape icon for LANDSCAPE locations', async () => {
      await openLakeDistrictSlots('LANDSCAPE');
      const icons = screen.getAllByTestId('slot-type-icon');
      expect(icons[0]).toHaveTextContent('🏔️');
    });

    it('shows seascape icon for SEASCAPE locations', async () => {
      await openLakeDistrictSlots('SEASCAPE');
      const icons = screen.getAllByTestId('slot-type-icon');
      expect(icons[0]).toHaveTextContent('🌊');
    });

    it('shows no type icon when locationType is absent', async () => {
      await openLakeDistrictSlots(undefined);
      expect(screen.queryByTestId('slot-type-icon')).toBeNull();
    });
  });

  // ────── Region comfort forecast ──────

  describe('region comfort forecast', () => {
    function buildBriefingWithComfort() {
      const dateStr = futureDateStr();
      return {
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: 'Looking good',
        days: [{
          date: dateStr,
          eventSummaries: [{
            targetType: 'SUNSET',
            regions: [{
              regionName: 'Lake District',
              verdict: 'GO',
              summary: 'Clear at 1 of 1 location',
              tideHighlights: [],
              slots: [{
                locationName: 'Keswick',
                solarEventTime: `${dateStr}T18:30:00`,
                verdict: 'GO',
                tideAligned: false,
                flags: [],
              }],
              regionTemperatureCelsius: 7.4,
              regionApparentTemperatureCelsius: 3.2,
              regionWindSpeedMs: 5.5,
              regionWeatherCode: 1,
            }],
            unregioned: [],
          }],
        }],
      };
    }

    it('shows temperature on region card when comfort data is present', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithComfort());
      render(<DailyBriefing locations={[]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const comfort = screen.getByTestId('region-comfort');
      expect(comfort).toHaveTextContent('7°C');
    });

    it('shows feels-like temperature in brackets', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithComfort());
      render(<DailyBriefing locations={[]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const comfort = screen.getByTestId('region-comfort');
      expect(comfort).toHaveTextContent('(3°C)');
    });

    it('shows wind speed in mph on region card', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithComfort());
      render(<DailyBriefing locations={[]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const comfort = screen.getByTestId('region-comfort');
      // 5.5 m/s * 2.237 = 12.3 → 12 mph
      expect(comfort).toHaveTextContent('12mph');
    });

    it('does not show comfort strip when regionTemperatureCelsius is null', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing locations={[]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      expect(screen.queryByTestId('region-comfort')).toBeNull();
    });
  });

  // ────── Best bet banner ──────

  describe('Best bet banner', () => {
    function buildBriefingWithPicks(picks) {
      return { ...buildBriefing(), bestBets: picks };
    }

    it('renders empty state when bestBets is absent', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('best-bet-banner')).toBeNull();
      expect(screen.getByTestId('best-bet-empty')).toBeInTheDocument();
      expect(screen.getByText(/No standout recommendations/)).toBeInTheDocument();
    });

    it('renders empty state when bestBets is empty', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('best-bet-banner')).toBeNull();
      expect(screen.getByTestId('best-bet-empty')).toBeInTheDocument();
    });

    it('empty state says "away", not "conditions similar", when the whole window is a travel period', async () => {
      // Every upcoming day is a travel day → no forecasts were generated, so the honest reason
      // is "you're away", not "conditions are similar across all regions".
      fetchTravelDayRanges.mockResolvedValue([{ startDate: pastDateStr(), endDate: futureDateStr(7) }]);
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      const empty = await screen.findByTestId('best-bet-empty');
      await waitFor(() => expect(empty).toHaveAttribute('data-variant', 'away'));
      expect(empty.textContent).toContain("away for the whole forecast window");
      expect(empty.textContent).not.toContain('conditions are similar');
    });

    it('empty state keeps the "conditions similar" copy when the operator is not away', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      const empty = screen.getByTestId('best-bet-empty');
      expect(empty).toHaveAttribute('data-variant', 'similar');
      expect(empty.textContent).toContain('conditions are similar');
    });

    describe('"No Also Good" note — a single pick must not read as a silent failure', () => {
      it('shows the note when a fresh cycle produced only a Best Bet', async () => {
        useAuth.mockReturnValue({ role: 'PRO_USER' });
        getDailyBriefing.mockResolvedValue({
          ...buildBriefing(),
          bestBetStatus: 'SUCCESS_WITH_PICKS',
          bestBets: [
            { rank: 1, headline: 'Only pick', detail: 'Clear.',
              event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
          ],
        });
        render(<DailyBriefing />);
        await waitFor(() => screen.getByTestId('best-bet-banner'));
        const note = screen.getByTestId('best-bet-no-second-pick');
        expect(note.textContent).toContain('NO ALSO GOOD');
        expect(note.textContent).toContain('not a missing recommendation');
      });

      it('hides the note when both picks are present', async () => {
        useAuth.mockReturnValue({ role: 'PRO_USER' });
        getDailyBriefing.mockResolvedValue({
          ...buildBriefing(),
          bestBetStatus: 'SUCCESS_WITH_PICKS',
          bestBets: [
            { rank: 1, headline: 'Best', detail: 'Clear.',
              event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
            { rank: 2, headline: 'Also', detail: 'Also clear.',
              event: 'tomorrow_sunrise', region: 'Durham', confidence: 'medium',
              relationship: 'DIFFERENT_SLOT' },
          ],
        });
        render(<DailyBriefing />);
        await waitFor(() => screen.getByTestId('best-bet-banner'));
        expect(screen.queryByTestId('best-bet-no-second-pick')).toBeNull();
      });

      it('hides the note on a stale fallback — one surviving pick is not honest silence', async () => {
        useAuth.mockReturnValue({ role: 'PRO_USER' });
        getDailyBriefing.mockResolvedValue({
          ...buildBriefing(),
          bestBetStatus: 'FAILED',
          bestBets: [
            { rank: 1, headline: 'Stale pick', detail: 'From earlier.',
              event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
          ],
        });
        render(<DailyBriefing />);
        await waitFor(() => screen.getByTestId('best-bet-banner'));
        expect(screen.getByTestId('best-bet-stale')).toBeInTheDocument();
        expect(screen.queryByTestId('best-bet-no-second-pick')).toBeNull();
      });

      it('hides the note on a stay-home pick — that pick is already the message', async () => {
        useAuth.mockReturnValue({ role: 'PRO_USER' });
        getDailyBriefing.mockResolvedValue({
          ...buildBriefing(),
          bestBetStatus: 'SUCCESS_WITH_PICKS',
          bestBets: [
            { rank: 1, headline: "Stay home, edit last weekend's shots",
              detail: 'Flat week.', event: null, region: null, confidence: 'high' },
          ],
        });
        render(<DailyBriefing />);
        await waitFor(() => screen.getByTestId('best-bet-banner'));
        expect(screen.queryByTestId('best-bet-no-second-pick')).toBeNull();
      });
    });

    it('FAILED status with fallback picks → banner WITH stale chip', async () => {
      useAuth.mockReturnValue({ role: 'PRO_USER' });
      getDailyBriefing.mockResolvedValue({
        ...buildBriefing(),
        bestBetStatus: 'FAILED',
        bestBets: [
          { rank: 1, headline: 'Last good pick', detail: 'From earlier.',
            event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
        ],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByTestId('best-bet-stale')).toBeInTheDocument();
      expect(screen.queryByTestId('best-bet-empty')).toBeNull();
    });

    it('SUCCESS_WITH_PICKS → banner WITHOUT stale chip', async () => {
      useAuth.mockReturnValue({ role: 'PRO_USER' });
      getDailyBriefing.mockResolvedValue({
        ...buildBriefing(),
        bestBetStatus: 'SUCCESS_WITH_PICKS',
        bestBets: [
          { rank: 1, headline: 'Fresh pick', detail: 'Clear.',
            event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
        ],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.queryByTestId('best-bet-stale')).toBeNull();
    });

    it('FAILED status with no fallback picks → honest empty state (no stale chip)', async () => {
      useAuth.mockReturnValue({ role: 'PRO_USER' });
      getDailyBriefing.mockResolvedValue({
        ...buildBriefing(),
        bestBetStatus: 'FAILED',
        bestBets: [],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('best-bet-banner')).toBeNull();
      expect(screen.queryByTestId('best-bet-stale')).toBeNull();
      expect(screen.getByTestId('best-bet-empty')).toBeInTheDocument();
    });

    it('SUCCESS_NO_PICKS → honest empty state', async () => {
      useAuth.mockReturnValue({ role: 'PRO_USER' });
      getDailyBriefing.mockResolvedValue({
        ...buildBriefing(),
        bestBetStatus: 'SUCCESS_NO_PICKS',
        bestBets: [],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.getByTestId('best-bet-empty')).toBeInTheDocument();
    });

    it('does not render empty state for LITE_USER when bestBets is absent', async () => {
      useAuth.mockReturnValue({ role: 'LITE_USER' });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('best-bet-empty')).toBeNull();
      expect(screen.queryByTestId('best-bet-placeholder')).toBeNull();
    });

    it('does not render empty state for LITE_USER when bestBets is empty', async () => {
      useAuth.mockReturnValue({ role: 'LITE_USER' });
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('best-bet-empty')).toBeNull();
      expect(screen.queryByTestId('best-bet-placeholder')).toBeNull();
    });

    it('renders placeholder (not real banner) for LITE_USER when picks exist', async () => {
      useAuth.mockReturnValue({ role: 'LITE_USER' });
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Go shoot', detail: 'Clear.', event: 'tomorrow_sunset',
          region: 'Northumberland', confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('best-bet-banner')).toBeNull();
      expect(screen.getByTestId('best-bet-placeholder')).toBeInTheDocument();
      expect(screen.getByText('Upgrade to Pro')).toBeInTheDocument();
    });

    it('renders banner for PRO_USER', async () => {
      useAuth.mockReturnValue({ role: 'PRO_USER' });
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Go shoot', detail: 'Clear.', event: 'tomorrow_sunset',
          region: 'Northumberland', confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByTestId('best-bet-pick-1')).toBeInTheDocument();
    });

    it('renders banner with one pick', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'King tide at Northumberland', detail: 'Rare king tide.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByTestId('best-bet-pick-1')).toBeInTheDocument();
      expect(screen.getByText('King tide at Northumberland')).toBeInTheDocument();
      expect(screen.getByText('Rare king tide.')).toBeInTheDocument();
    });

    it('marks a low-confidence pick as provisional on the shared channel', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Maybe worth a look', detail: 'The models disagree.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'low' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByTestId('provisional-mark')).toBeInTheDocument();
    });

    it('does not mark a high-confidence pick as provisional', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Solid tonight', detail: 'Clear and settled.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.queryByTestId('provisional-mark')).toBeNull();
    });

    it('renders the full detail sentence with no clamp or read-more affordance', async () => {
      // The headline recommendation is the single most important element on the screen —
      // it renders complete, never clipped mid-word behind a "Read more" toggle.
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Go to Lake District', detail: 'A long detailed analysis.',
          event: 'tomorrow_sunset', region: 'Lake District', confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));

      expect(screen.getByTestId('best-bet-detail')).toHaveTextContent('A long detailed analysis.');
      expect(screen.queryByTestId('best-bet-read-more')).toBeNull();
    });

    it('View on map hands off the bet region, date and event', async () => {
      const onShowOnMap = vi.fn();
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Go shoot', detail: 'Clear.', event: 'tomorrow_sunset',
          region: 'Northumberland', confidence: 'high' },
      ]));
      render(<DailyBriefing onShowOnMap={onShowOnMap} />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));

      fireEvent.click(screen.getByTestId('best-bet-view-on-map'));

      const tomorrow = new Date();
      tomorrow.setDate(tomorrow.getDate() + 1);
      const tomorrowStr = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(tomorrow);
      expect(onShowOnMap).toHaveBeenCalledWith({
        region: 'Northumberland', date: tomorrowStr, eventType: 'SUNSET',
      });
    });

    it('renders two picks with different visual weight', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'First pick', detail: 'Detail 1.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
        { rank: 2, headline: 'Second pick', detail: 'Detail 2.',
          event: 'tomorrow_sunset', region: 'Lake District', confidence: 'medium' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByTestId('best-bet-pick-1')).toBeInTheDocument();
      expect(screen.getByTestId('best-bet-pick-2')).toBeInTheDocument();
      expect(screen.getByText('① BEST BET')).toBeInTheDocument();
      expect(screen.getByText('② ALSO GOOD')).toBeInTheDocument();

      // BEST BET (primary, high-conf) gets the verdict-go green left accent; ALSO GOOD (secondary)
      // gets the periwinkle pick-also accent — the two picks read as a matched pair, not one green
      // and one neutral. The same two colours carry down into the strip chips (keyed by region).
      const pick1 = screen.getByTestId('best-bet-pick-1');
      const pick2 = screen.getByTestId('best-bet-pick-2');
      expect(pick1.style.borderLeft).toBe('3px solid var(--color-verdict-go)');
      expect(pick2.style.borderLeft).toBe('3px solid var(--color-pick-also)');
      // Also Good must NOT pick up the verdict-go accent — it owns its own periwinkle.
      expect(pick2.style.borderLeft).not.toContain('var(--color-verdict-go)');

      const label1 = screen.getByText('① BEST BET');
      const label2 = screen.getByText('② ALSO GOOD');
      expect(label1.style.color).toBe('var(--color-verdict-go)');
      expect(label2.style.color).toBe('var(--color-pick-also)');
      expect(label2.style.color).not.toBe('var(--color-verdict-go)');
    });

    it('stay-home pick is not navigable (no role/click handler)', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Stay in tonight', detail: 'Nothing worth it.',
          event: null, region: null, confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      const pick = screen.getByTestId('best-bet-pick-1');
      // Non-navigable: no role="button", and clicking does not expand the briefing.
      expect(pick).not.toHaveAttribute('role', 'button');
      fireEvent.click(pick);
      expect(screen.queryByTestId('briefing-expanded')).toBeNull();
    });

    it('clicking a pick expands the briefing-expanded section', async () => {
      const dateStr = futureDateStr();
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        bestBets: [
          { rank: 1, headline: 'Go to Lake District', detail: 'Clear skies.',
            event: 'tomorrow_sunset', region: 'Lake District', confidence: 'high' },
        ],
        days: [{
          date: dateStr,
          eventSummaries: [{
            targetType: 'SUNSET',
            regions: [{ regionName: 'Lake District', verdict: 'GO', summary: 'Clear',
              tideHighlights: [], slots: [{
                locationName: 'Keswick', solarEventTime: `${dateStr}T19:00:00`,
                verdict: 'GO', tideAligned: false, flags: [],
              }],
            }],
            unregioned: [],
          }],
        }],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));

      expect(screen.queryByTestId('briefing-expanded')).toBeNull();
      fireEvent.click(screen.getByTestId('best-bet-pick-1'));

      await waitFor(() => expect(screen.getByTestId('briefing-expanded')).toBeInTheDocument());
    });

    it('low-confidence pick shows low-confidence label', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Marginal at best', detail: 'Patchy cloud.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'low' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByText(/low confidence/i)).toBeInTheDocument();
    });

    it('low-confidence pick 1 falls back to muted styling, not WORTH IT green', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Marginal at best', detail: 'Patchy cloud.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'low' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      const pick1 = screen.getByTestId('best-bet-pick-1');
      // Low-conf primary uses the muted bone accent, not the verdict-go green.
      expect(pick1.style.borderLeft).toBe('3px solid var(--color-plex-border-light)');
      expect(pick1.style.borderLeft).not.toContain('var(--color-verdict-go)');

      const label1 = screen.getByText('① BEST BET');
      expect(label1.style.color).toBe('var(--color-plex-text-muted)');
      expect(label1.style.color).not.toBe('var(--color-verdict-go)');
    });

    it('renders structured header with day, event type, time, and drive', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Best overall light', detail: 'Clear skies.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high',
          dayName: 'Tomorrow', eventType: 'sunset', eventTime: '19:48',
          nearestDriveMinutes: 37 },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByText(/Tomorrow sunset/)).toBeInTheDocument();
      expect(screen.getByText(/19:48/)).toBeInTheDocument();
      expect(screen.getByText(/37 min drive/)).toBeInTheDocument();
    });

    it('structured header omits drive time when not available', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Best light conditions', detail: 'Clear.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high',
          dayName: 'Tomorrow', eventType: 'sunset', eventTime: '19:48' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByText(/Tomorrow sunset/)).toBeInTheDocument();
      expect(screen.queryByText(/min drive/)).toBeNull();
    });

    it('stay-home pick has no structured header', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Stay in tonight', detail: 'Nothing worth it.',
          event: null, region: null, confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.queryByText(/min drive/)).toBeNull();
      expect(screen.queryByText(/Today sunset|Tomorrow sunrise/)).toBeNull();
    });

    it('does not show empty state while briefing is still loading', async () => {
      getDailyBriefing.mockReturnValue(new Promise(() => {}));
      render(<DailyBriefing />);
      // Briefing hasn't resolved — neither banner nor empty state should appear
      expect(screen.queryByTestId('best-bet-banner')).toBeNull();
      expect(screen.queryByTestId('best-bet-empty')).toBeNull();
    });

    it('hides empty state when picks are present', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Go shoot', detail: 'Clear.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.queryByTestId('best-bet-empty')).toBeNull();
    });

    it('renders ALSO GOOD label for SAME_SLOT pick on same event', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Best light', detail: 'Clear.',
          event: 'tomorrow_sunset', region: 'Lake District', confidence: 'high',
          dayName: 'Tomorrow', eventType: 'sunset', eventTime: '20:28' },
        { rank: 2, headline: 'Strong backup', detail: 'Also clear.',
          event: 'tomorrow_sunset', region: 'Yorkshire Dales', confidence: 'high',
          dayName: 'Tomorrow', eventType: 'sunset', eventTime: '20:28',
          relationship: 'SAME_SLOT', differsBy: [] },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByText('② ALSO GOOD')).toBeInTheDocument();
      // Both cards show same event
      const headers = screen.getAllByText(/Tomorrow sunset/);
      expect(headers).toHaveLength(2);
    });

    it('renders ALSO GOOD label for DIFFERENT_SLOT pick on different event', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Best light', detail: 'Clear.',
          event: 'tomorrow_sunset', region: 'Lake District', confidence: 'high',
          dayName: 'Tomorrow', eventType: 'sunset', eventTime: '20:28' },
        { rank: 2, headline: 'A second strong window', detail: 'Good Friday morning.',
          event: 'tomorrow_sunrise', region: 'Lake District', confidence: 'medium',
          dayName: 'Tomorrow', eventType: 'sunrise', eventTime: '05:49',
          relationship: 'DIFFERENT_SLOT', differsBy: ['EVENT'] },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByText('② ALSO GOOD')).toBeInTheDocument();
      // Pick 2 shows its own event type
      expect(screen.getByText(/Tomorrow sunrise/)).toBeInTheDocument();
      expect(screen.getByText(/05:49/)).toBeInTheDocument();
    });

    it('single pick renders only Best Bet — no Also Good card', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Only pick', detail: 'Nothing else clears threshold.',
          event: 'tomorrow_sunset', region: 'Northumberland', confidence: 'high',
          dayName: 'Tomorrow', eventType: 'sunset', eventTime: '20:28' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      expect(screen.getByTestId('best-bet-pick-1')).toBeInTheDocument();
      expect(screen.queryByTestId('best-bet-pick-2')).toBeNull();
    });
  });

  // ────── Hot Topic empty states ──────

  describe('Hot Topic empty states', () => {
    it('shows nothing when hotTopics is empty and simulation is off', async () => {
      getSimulationState.mockResolvedValue({ enabled: false });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('hot-topic-sim-hint')).toBeNull();
    });

    it('shows nothing when hotTopics is empty even when simulation is on', async () => {
      getSimulationState.mockResolvedValue({ enabled: true });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('hot-topic-sim-hint')).toBeNull();
      expect(screen.queryByTestId('hot-topic-strip')).toBeNull();
    });

    it('shows pills instead of hint when hotTopics has items and simulation is on', async () => {
      getSimulationState.mockResolvedValue({ enabled: true });
      getDailyBriefing.mockResolvedValue({
        ...buildBriefing(),
        hotTopics: [
          { type: 'BLUEBELL', label: 'Bluebell conditions', detail: 'Misty and still',
            date: futureDateStr(), priority: 1, filterAction: 'BLUEBELL',
            regions: ['Northumberland'], description: null },
        ],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.getByText('Bluebell conditions')).toBeInTheDocument();
      expect(screen.queryByTestId('hot-topic-sim-hint')).toBeNull();
    });
  });

  // ────── Aurora data passed to HotTopicStrip ──────

  describe('Aurora data forwarded to HotTopicStrip', () => {
    it('does not render aurora heatmap cells after column removal', async () => {
      getDailyBriefing.mockResolvedValue({
        ...buildBriefing(),
        auroraTonight: { alertLevel: 'MODERATE', kp: 5.3, regions: [] },
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.queryByTestId('aurora-heatmap-cell')).toBeNull();
    });

    it('renders AURORA hot topic pill when aurora data and hotTopics are present', async () => {
      getDailyBriefing.mockResolvedValue({
        ...buildBriefing(),
        auroraTonight: { alertLevel: 'MODERATE', kp: 5.3, regions: [] },
        hotTopics: [
          { type: 'AURORA', label: 'Aurora tonight', detail: 'Moderate activity tonight',
            date: futureDateStr(), priority: 1, filterAction: null, regions: ['Northumberland'] },
        ],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      expect(screen.getByTestId('hot-topic-pill-AURORA')).toBeInTheDocument();
    });
  });

  // ────── Desktop heatmap grid ──────

  describe('Desktop heatmap grid', () => {
    it('renders heatmap grid container when there are upcoming events', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      await openFullGrid();
      expect(screen.getByTestId('briefing-heatmap')).toBeInTheDocument();
    });

    it('is collapsed by default, and once opened persists across a remount (B5)', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      const first = render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      // Collapsed by default — no grid until the expander is used.
      expect(screen.queryByTestId('briefing-heatmap')).toBeNull();
      await openFullGrid();
      // Remount (as happens on a round-trip to the full Map tab and back).
      first.unmount();
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));
      // The grid is open again without touching the expander.
      expect(screen.getByTestId('briefing-heatmap')).toBeInTheDocument();
    });

    it('renders day-column headers', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await openFullGrid();
      const headers = screen.getAllByTestId('heatmap-day-header');
      expect(headers.length).toBeGreaterThanOrEqual(1);
      // First header shows "Tomorrow" for the standard fixture
      expect(headers[0].textContent).toMatch(/Tomorrow/);
    });

    it('shows 3 day-columns when 3 days of data are available', async () => {
      const day1 = futureDateStr(1);
      const day2 = futureDateStr(2);
      const day3 = futureDateStr(3);
      const makeDay = (date) => ({
        date,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{ regionName: 'North', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'A', solarEventTime: `${date}T18:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
          unregioned: [],
        }],
      });
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [makeDay(day1), makeDay(day2), makeDay(day3)],
      });
      render(<DailyBriefing />);
      await openFullGrid();
      const headers = screen.getAllByTestId('heatmap-day-header');
      expect(headers).toHaveLength(3);
      // Tomorrow label on first
      expect(headers[0].textContent).toMatch(/Tomorrow/);
    });

    it('past-only days are excluded from grid columns', async () => {
      const pastStr = pastDateStr();
      const futureStr = futureDateStr();
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [
          {
            date: pastStr,
            eventSummaries: [{ targetType: 'SUNRISE', regions: [{ regionName: 'Old', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'X', solarEventTime: `${pastStr}T04:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }], unregioned: [] }],
          },
          {
            date: futureStr,
            eventSummaries: [{ targetType: 'SUNSET', regions: [{ regionName: 'North', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'Y', solarEventTime: `${futureStr}T18:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }], unregioned: [] }],
          },
        ],
      });
      render(<DailyBriefing />);
      await openFullGrid();
      const headers = screen.getAllByTestId('heatmap-day-header');
      // Only 1 future day column
      expect(headers).toHaveLength(1);
    });

    it('renders heatmap cells for each region × day combination', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await openFullGrid();
      const cells = screen.getAllByTestId('heatmap-cell');
      expect(cells.length).toBeGreaterThanOrEqual(1);
    });

    it('STANDDOWN cells are disabled and not interactive', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await openFullGrid();
      // Poor-only regions are pooled behind a reveal (A3a); open it to reach the STANDDOWN cells.
      fireEvent.click(screen.getByTestId('heatmap-poor-toggle'));
      const cells = screen.getAllByTestId('heatmap-cell');
      const disabledCells = cells.filter((c) => c.getAttribute('aria-disabled') === 'true');
      expect(disabledCells.length).toBeGreaterThanOrEqual(1);
    });

    it('clicking a GO heatmap cell opens drill-down panel', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await openFullGrid();

      const cells = screen.getAllByTestId('heatmap-cell');
      const enabledCell = cells.find((c) => !c.disabled);
      if (enabledCell) {
        fireEvent.click(enabledCell);
        await waitFor(() => screen.getByTestId('drill-down-panel'));
        expect(screen.getByTestId('drill-down-panel')).toBeInTheDocument();
      }
    });

    it('clicking a second cell closes the first drill-down and opens the new one', async () => {
      const dateStr = futureDateStr();
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [{
          date: dateStr,
          eventSummaries: [{
            targetType: 'SUNSET',
            regions: [
              { regionName: 'Alpha Region', verdict: 'GO', summary: 'Clear', tideHighlights: [], slots: [{ locationName: 'A1', solarEventTime: `${dateStr}T18:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] },
              { regionName: 'Beta Region', verdict: 'GO', summary: 'Clear', tideHighlights: [], slots: [{ locationName: 'B1', solarEventTime: `${dateStr}T18:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] },
            ],
            unregioned: [],
          }],
        }],
      });
      render(<DailyBriefing />);
      await openFullGrid();

      const cells = screen.getAllByTestId('heatmap-cell');
      const enabled = cells.filter((c) => !c.disabled);
      if (enabled.length >= 2) {
        fireEvent.click(enabled[0]);
        await waitFor(() => screen.getByTestId('drill-down-panel'));

        fireEvent.click(enabled[1]);
        // Still exactly one drill-down panel
        const panels = screen.getAllByTestId('drill-down-panel');
        expect(panels).toHaveLength(1);
      }
    });

    it('drill-down shows event rows for the selected region × day', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await openFullGrid();

      const cells = screen.getAllByTestId('heatmap-cell');
      const enabledCell = cells.find((c) => !c.disabled);
      if (enabledCell) {
        fireEvent.click(enabledCell);
        await waitFor(() => screen.getByTestId('drill-down-panel'));
        expect(screen.getAllByTestId('drill-down-event-row').length).toBeGreaterThanOrEqual(1);
      }
    });

    it('clicking a GO event in heatmap drill-down shows location slots', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await openFullGrid();

      const cells = screen.getAllByTestId('heatmap-cell');
      // Find the Lake District cell (GO sunset) by clicking the first enabled cell
      const enabledCell = cells.find((c) => !c.disabled);
      if (enabledCell) {
        fireEvent.click(enabledCell);
        await waitFor(() => screen.getByTestId('drill-down-panel'));

        const eventRows = screen.getAllByTestId('drill-down-event-row');
        const tappable = eventRows.find((r) => r.getAttribute('role') === 'button');
        if (tappable) {
          fireEvent.click(tappable);
          expect(screen.getByTestId('region-slots')).toBeInTheDocument();
        }
      }
    });

    it('GO cells in the grid show Worth it sunset label', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await openFullGrid();
      const cells = screen.getAllByTestId('heatmap-cell');
      const enabledCells = cells.filter((c) => !c.disabled);
      expect(enabledCells.length).toBeGreaterThanOrEqual(1);
      const goCell = enabledCells.find((c) => c.textContent.includes('Worth it sunset'));
      expect(goCell).toBeTruthy();
    });

    it('MARGINAL cells in the grid show Maybe sunrise label', async () => {
      localStorage.setItem('plannerQualityTier', JSON.stringify(5));
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await openFullGrid();
      const cells = screen.getAllByTestId('heatmap-cell');
      const enabledCells = cells.filter((c) => !c.disabled);
      const marginalCell = enabledCells.find((c) => c.textContent.includes('Maybe sunrise'));
      expect(marginalCell).toBeTruthy();
    });
  });


  // ────── Mobile other-day pills ──────

  describe('Mobile other-day pills', () => {
    it('shows other-day pills when more than 1 day is available', async () => {
      const day1 = futureDateStr(1);
      const day2 = futureDateStr(2);
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [
          {
            date: day1,
            eventSummaries: [{ targetType: 'SUNSET', regions: [{ regionName: 'A', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'X', solarEventTime: `${day1}T18:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }], unregioned: [] }],
          },
          {
            date: day2,
            eventSummaries: [{ targetType: 'SUNSET', regions: [{ regionName: 'A', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'X', solarEventTime: `${day2}T18:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }], unregioned: [] }],
          },
        ],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      await waitFor(() => screen.getByTestId('briefing-expanded'));
      const pills = screen.getAllByTestId('mobile-other-day-pill');
      expect(pills.length).toBeGreaterThanOrEqual(1);
    });

    it('does not show other-day pills when only 1 day available', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      await waitFor(() => screen.getByTestId('briefing-expanded'));
      expect(screen.queryByTestId('mobile-other-day-pill')).toBeNull();
    });

    it('clicking an other-day pill swaps the main region list', async () => {
      const day1 = futureDateStr(1);
      const day2 = futureDateStr(2);
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [
          {
            date: day1,
            eventSummaries: [{ targetType: 'SUNSET', regions: [{ regionName: 'Alpha', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'X', solarEventTime: `${day1}T18:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }], unregioned: [] }],
          },
          {
            date: day2,
            eventSummaries: [{ targetType: 'SUNSET', regions: [{ regionName: 'Beta', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'Y', solarEventTime: `${day2}T18:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }], unregioned: [] }],
          },
        ],
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));
      fireEvent.click(screen.getByTestId('briefing-toggle'));

      await waitFor(() => screen.getByTestId('briefing-expanded'));
      // Initially shows day1 region (Alpha)
      expect(screen.getAllByText('Alpha').length).toBeGreaterThanOrEqual(1);

      // Click pill for day2
      const pill = screen.getByTestId('mobile-other-day-pill');
      fireEvent.click(pill);

      // Now shows day2 region (Beta)
      await waitFor(() =>
        expect(screen.getAllByText('Beta').length).toBeGreaterThanOrEqual(1),
      );
    });
  });

  // ────── Event-based columns (next 6 solar events) ──────

  describe('Event-based columns', () => {
    function makeEventDay(date, types) {
      return {
        date,
        eventSummaries: types.map((tt) => ({
          targetType: tt,
          regions: [{
            regionName: 'North',
            verdict: 'GO',
            summary: 'Clear',
            tideHighlights: [],
            slots: [{
              locationName: 'A',
              solarEventTime: `${date}T${tt === 'SUNRISE' ? '06:00:00' : '18:00:00'}`,
              verdict: 'GO',
              tideAligned: false,
              flags: [],
            }],
          }],
          unregioned: [],
        })),
      };
    }

    it('shows up to 6 event columns across 4 days', async () => {
      const day1 = futureDateStr(1);
      const day2 = futureDateStr(2);
      const day3 = futureDateStr(3);
      const day4 = futureDateStr(4);
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [
          makeEventDay(day1, ['SUNRISE', 'SUNSET']),
          makeEventDay(day2, ['SUNRISE', 'SUNSET']),
          makeEventDay(day3, ['SUNRISE', 'SUNSET']),
          makeEventDay(day4, ['SUNRISE', 'SUNSET']),
        ],
      });
      render(<DailyBriefing />);
      await openFullGrid();

      // 6 event columns = 6 heatmap cells (1 region)
      const cells = screen.getAllByTestId('heatmap-cell');
      expect(cells).toHaveLength(6);

      // 3 day headers (first 3 days cover 6 events)
      const headers = screen.getAllByTestId('heatmap-day-header');
      expect(headers).toHaveLength(3);
    });

    it('shows dynamic day header spanning when today has only 1 event left', async () => {
      const day1 = futureDateStr(1); // has both sunrise + sunset
      const day2 = futureDateStr(2);
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [
          makeEventDay(day1, ['SUNSET']),      // 1 event
          makeEventDay(day2, ['SUNRISE', 'SUNSET']),  // 2 events
        ],
      });
      render(<DailyBriefing />);
      await openFullGrid();

      const headers = screen.getAllByTestId('heatmap-day-header');
      expect(headers).toHaveLength(2);
      // First header spans 1 column (only sunset), second spans 2
      expect(headers[0].style.gridColumn).toBe('span 1');
      expect(headers[1].style.gridColumn).toBe('span 2');
    });

    it('limits to 6 events even when more are available', async () => {
      const days = [];
      for (let i = 1; i <= 5; i++) {
        days.push(makeEventDay(futureDateStr(i), ['SUNRISE', 'SUNSET']));
      }
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days,
      });
      render(<DailyBriefing />);
      await openFullGrid();

      const cells = screen.getAllByTestId('heatmap-cell');
      expect(cells).toHaveLength(6);
    });

    it('mobile compact rows show all upcoming events (not limited to 6)', async () => {
      const days = [];
      for (let i = 1; i <= 5; i++) {
        days.push(makeEventDay(futureDateStr(i), ['SUNRISE', 'SUNSET']));
      }
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days,
      });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      // Mobile rows are derived from upcomingEvents (max 6)
      const rows = screen.getAllByTestId('event-summary-row');
      expect(rows).toHaveLength(6);
    });
  });

});

// ── Lightly-evaluated framing (mobile/shared drill list) ───────────────────────

describe('DailyBriefing — lightly-evaluated framing', () => {
  function buildDalesBriefing({ lightlyEvaluated = true, allScored = false } = {}) {
    const dateStr = futureDateStr();
    const scored = (name, rating) => ({
      locationName: name, solarEventTime: `${dateStr}T18:00:00`, verdict: 'GO',
      displayVerdict: 'WORTH_IT', tideAligned: false, flags: [],
      claudeRating: rating, claudeSummary: 'Lovely clean horizon.',
    });
    const unscored = (name) => ({
      locationName: name, solarEventTime: `${dateStr}T18:00:00`, verdict: 'GO',
      displayVerdict: 'WORTH_IT', tideAligned: false, flags: [],
    });
    const slots = allScored
      ? [scored('Alpha', 4), scored('Beta', 5), scored('Cedar', 4)]
      : [scored('Alpha', 4), unscored('Beta'), unscored('Cedar')];
    return {
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: dateStr,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{
            regionName: 'TestRegion', verdict: 'GO', displayVerdict: 'WORTH_IT',
            summary: 'Clear at 3 of 3 locations', tideHighlights: [],
            lightlyEvaluated, scoredLocationCount: allScored ? 3 : 1, slots,
          }],
          unregioned: [],
        }],
      }],
    };
  }

  async function openSlots(briefing) {
    getDailyBriefing.mockResolvedValue(briefing);
    render(<DailyBriefing locations={[]} />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('briefing-toggle'));
    fireEvent.click(screen.getByTestId('region-row'));
    const eventRows = screen.getAllByTestId('drill-down-event-row');
    fireEvent.click(eventRows.find((r) => r.getAttribute('role') === 'button'));
  }

  it('scope-marks the header and renders ghost pills for unscored slots', async () => {
    await openSlots(buildDalesBriefing());

    expect(screen.getByTestId('coverage-note').textContent.replace(/\s+/g, ' '))
      .toContain('1 of 3 evaluated');

    const slotRows = within(screen.getByTestId('region-slots')).getAllByTestId('briefing-slot');
    expect(slotRows).toHaveLength(3);
    // Scored slot (Alpha): solid verdict pill, not a ghost.
    expect(within(slotRows[0]).queryByTestId('unscored-pill')).toBeNull();
    expect(within(slotRows[0]).getByTestId('verdict-pill')).toBeTruthy();
    // Unscored slots: ghost "not scored" pill.
    expect(within(slotRows[1]).getByTestId('unscored-pill').textContent).toContain('not scored');
    expect(within(slotRows[2]).getByTestId('unscored-pill').textContent).toContain('not scored');
  });

  it('omits the coverage note when not lightly evaluated', async () => {
    await openSlots(buildDalesBriefing({ lightlyEvaluated: false }));
    expect(screen.queryByTestId('coverage-note')).toBeNull();
  });

  it('fully-covered region: no ghost pills and no coverage note', async () => {
    await openSlots(buildDalesBriefing({ lightlyEvaluated: false, allScored: true }));
    expect(screen.queryByTestId('coverage-note')).toBeNull();
    expect(screen.queryAllByTestId('unscored-pill')).toHaveLength(0);
  });
});

describe('DailyBriefing — summary strip', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    useAuth.mockReturnValue({ role: 'ADMIN' });
    // Default: not away. clearAllMocks leaves implementations intact, so re-assert the
    // baseline here to keep a per-test travel-range override from leaking.
    fetchTravelDayRanges.mockResolvedValue([]);
  });

  function stripBriefing(date, sunsetRegions, sunriseRegions = []) {
    return {
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date,
        eventSummaries: [
          { targetType: 'SUNRISE', regions: sunriseRegions, unregioned: [] },
          { targetType: 'SUNSET', regions: sunsetRegions, unregioned: [] },
        ],
      }],
    };
  }

  const region = (regionName, verdict, extra = {}) => ({
    regionName,
    verdict,
    summary: `${regionName} gloss`,
    tideHighlights: [],
    regionWeatherCode: 3,
    regionTemperatureCelsius: 18,
    regionWindSpeedMs: 4.47,
    slots: [{ locationName: `${regionName} spot`, solarEventTime: `${extra.date}T21:00:00`, verdict }],
    ...extra,
  });

  it('names the rated event and renders a chip per rated region (not a bare count)', async () => {
    const d = futureDateStr(1);
    getDailyBriefing.mockResolvedValue(stripBriefing(d, [
      region('The North Yorkshire Coast', 'GO', { date: d }),
      region('Tyne and Wear', 'GO', { date: d }),
    ]));
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-summary-strip'));
    expect(screen.getAllByTestId('summary-pill-peak')[0].textContent).toBe('\u25ce Worth it \u00b7 sunset');
    const chips = screen.getAllByTestId('summary-region-chip');
    expect(chips).toHaveLength(2);
    expect(chips[0].textContent).toContain('N. Yorks Coast');
    expect(chips[1].textContent).toContain('Tyne & Wear');
    expect(screen.getAllByTestId('summary-pill-detail')[0].textContent).not.toContain('regions rated');
  });

  it('shows "sunrise/sunset" when both events have rated regions', async () => {
    const d = futureDateStr(1);
    getDailyBriefing.mockResolvedValue(stripBriefing(
      d,
      [region('Tyne and Wear', 'GO', { date: d })],
      [region('Northumberland', 'GO', { date: d })],
    ));
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-summary-strip'));
    expect(screen.getAllByTestId('summary-pill-peak')[0].textContent).toBe('\u25ce Worth it \u00b7 sunrise/sunset');
  });

  it('renders every rated region as its own hoverable chip', async () => {
    const d = futureDateStr(1);
    getDailyBriefing.mockResolvedValue(stripBriefing(d, [
      region('Tyne and Wear', 'GO', { date: d }),
      region('Teesdale', 'GO', { date: d }),
      region('Northumberland', 'GO', { date: d }),
      region('The Lake District', 'GO', { date: d }),
    ]));
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-summary-strip'));
    expect(screen.getAllByTestId('summary-region-chip')).toHaveLength(4);
  });

  it("a chip reveals that region's verdict, weather and gloss", async () => {
    const d = futureDateStr(1);
    getDailyBriefing.mockResolvedValue(stripBriefing(d, [
      region('Tyne and Wear', 'MARGINAL', { date: d, summary: 'Broken mid-cloud keeps it marginal.' }),
    ]));
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-summary-strip'));
    const chip = screen.getByTestId('summary-region-chip');
    // Hovering reveals the tooltip, which is portalled to <body> (so the plan card can't clip it);
    // it carries the verdict + wx header and the gloss body.
    fireEvent.mouseEnter(chip);
    const tip = screen.getByRole('tooltip');
    expect(tip.textContent).toContain('Maybe sunset');
    expect(tip.textContent).toContain('18\u00b0C');
    expect(tip.textContent).toContain('10mph'); // 4.47 m/s -> 10mph
    expect(tip.textContent).toContain('Broken mid-cloud keeps it marginal.');
  });

  it('clicking a region chip opens the map for that region', async () => {
    const onShowOnMap = vi.fn();
    const d = futureDateStr(1);
    getDailyBriefing.mockResolvedValue(stripBriefing(d, [
      region('Tyne and Wear', 'GO', { date: d }),
    ]));
    render(<DailyBriefing onShowOnMap={onShowOnMap} />);
    await waitFor(() => screen.getByTestId('briefing-summary-strip'));
    fireEvent.click(screen.getByTestId('summary-region-chip'));
    expect(onShowOnMap).toHaveBeenCalledWith({ region: 'Tyne and Wear', date: d, eventType: 'SUNSET' });
  });

  it('colour-matches the Best bet / Also good chips and floats them to the front, marker and all', async () => {
    const d = futureDateStr(1);
    getDailyBriefing.mockResolvedValue({
      ...stripBriefing(d, [
        region('Teesdale', 'GO', { date: d }),
        region('The North Yorkshire Coast', 'GO', { date: d }),
        region('Tyne and Wear', 'GO', { date: d }),
      ]),
      // Match is by region name (the stable id shared with the grid roll-up), independent of event.
      bestBets: [
        { rank: 1, headline: 'Best', detail: '', event: 'tomorrow_sunset', region: 'Tyne and Wear', confidence: 'high' },
        { rank: 2, headline: 'Also', detail: '', event: 'tomorrow_sunset', region: 'The North Yorkshire Coast', confidence: 'medium' },
      ],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-summary-strip'));
    const chips = screen.getAllByTestId('summary-region-chip');
    // Best floated first, Also second, then the remaining (unpicked) region in roll-up order.
    expect(chips[0]).toHaveAttribute('data-pick', 'best');
    expect(chips[0].textContent).toContain('Tyne & Wear');
    expect(chips[1]).toHaveAttribute('data-pick', 'also');
    expect(chips[1].textContent).toContain('N. Yorks Coast');
    expect(chips[2]).not.toHaveAttribute('data-pick');
    expect(chips[2].textContent).toContain('Teesdale');
    // \u25ce marker rides the picks only.
    expect(chips[0].querySelector('.rn-mark')).not.toBeNull();
    expect(chips[1].querySelector('.rn-mark')).not.toBeNull();
    expect(chips[2].querySelector('.rn-mark')).toBeNull();
  });

  it('does not colour-match strip chips for LITE users \u2014 they see a redacted banner, not the cards', async () => {
    useAuth.mockReturnValue({ role: 'LITE_USER' });
    const d = futureDateStr(1);
    getDailyBriefing.mockResolvedValue({
      ...stripBriefing(d, [region('Tyne and Wear', 'GO', { date: d })]),
      bestBets: [
        { rank: 1, headline: 'Best', detail: '', event: 'tomorrow_sunset', region: 'Tyne and Wear', confidence: 'high' },
      ],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-summary-strip'));
    const chip = screen.getByTestId('summary-region-chip');
    expect(chip).not.toHaveAttribute('data-pick');
    expect(chip.querySelector('.rn-mark')).toBeNull();
  });

  it('rolls up the grid display verdict, not the raw verdict \u2014 no strip/grid drift', async () => {
    const d = futureDateStr(1);
    // Raw verdict says STANDDOWN, but the serve-time re-derivation lifts the display to WORTH_IT.
    getDailyBriefing.mockResolvedValue(stripBriefing(d, [
      region('The Lake District', 'STANDDOWN', { date: d, displayVerdict: 'WORTH_IT' }),
    ]));
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-summary-strip'));
    const peak = screen.getAllByTestId('summary-pill-peak')[0];
    expect(peak.textContent).toContain('\u25ce Worth it');
    expect(peak.textContent).not.toContain('All poor');
    const chips = screen.getAllByTestId('summary-region-chip');
    expect(chips).toHaveLength(1);
    expect(chips[0].textContent).toContain('Lake District');
  });
});

describe('bestConfidence (day-pill confidence aggregation)', () => {
  const entry = (confidence) => ({ region: { confidence } });

  it('returns the most-confident tier regardless of region order', () => {
    // The day-pill points at the day's best pick, so it reads provisional only when even that
    // best pick is — the result must not depend on which region is iterated first.
    expect(bestConfidence([entry('low'), entry('high')], null)).toBe('high');
    expect(bestConfidence([entry('high'), entry('low')], null)).toBe('high');
    expect(bestConfidence([entry('low'), entry('medium')], null)).toBe('medium');
  });

  it('falls back to the horizon when a region has no backend confidence', () => {
    // No supplied confidence + far horizon → low; the most-confident of two far regions is low.
    expect(bestConfidence([entry(undefined), entry(undefined)], 5)).toBe('low');
    // A same-day region with no field falls back to high.
    expect(bestConfidence([entry(undefined)], 0)).toBe('high');
  });

  it('returns null for an empty set (no reduce-without-seed throw)', () => {
    expect(bestConfidence([], 0)).toBeNull();
    expect(bestConfidence(null, 0)).toBeNull();
  });
});
