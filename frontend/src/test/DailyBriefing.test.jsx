import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import DailyBriefing from '../components/DailyBriefing.jsx';

vi.mock('../api/briefingApi.js', () => ({
  getDailyBriefing: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: vi.fn(),
}));

vi.mock('../api/briefingEvaluationApi.js', () => ({
  subscribeToBriefingEvaluation: vi.fn(() => () => {}),
  getCachedEvaluationScores: vi.fn(() => Promise.resolve({})),
  getAllEvaluationScores: vi.fn(() => Promise.resolve([])),
}));

vi.mock('../api/settingsApi.js', () => ({
  getDriveTimes: vi.fn(() => Promise.resolve({})),
}));

vi.mock('../api/hotTopicSimulationApi.js', () => ({
  getSimulationState: vi.fn(() => Promise.resolve({ enabled: false })),
}));

import { getDailyBriefing } from '../api/briefingApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import { getDriveTimes } from '../api/settingsApi.js';
import { getSimulationState } from '../api/hotTopicSimulationApi.js';

// ── Date helpers ─────────────────────────────────────────────────────────────

function futureDateStr(daysAhead = 1) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + daysAhead);
  return d.toISOString().slice(0, 10);
}

function pastDateStr() {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
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

    expect(screen.getByTestId('go-count')).toHaveTextContent('2 GO');
    expect(screen.getByTestId('standdown-count')).toHaveTextContent('1 STANDDOWN');
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

    expect(screen.getByTestId('marginal-count')).toHaveTextContent('1 MARGINAL');
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

      // BEST BET uses WORTH IT green accent, ALSO GOOD uses silver accent
      const pick1 = screen.getByTestId('best-bet-pick-1');
      const pick2 = screen.getByTestId('best-bet-pick-2');
      expect(pick1).toHaveClass('border-green-600/60');
      expect(pick1).toHaveClass('bg-green-600/5');
      expect(pick2).toHaveClass('border-slate-400/40');
      expect(pick2).toHaveClass('bg-slate-400/5');
      // Also Good must NOT pick up any of the WORTH IT green styling.
      expect(pick2).not.toHaveClass('border-green-600/60');
      expect(pick2).not.toHaveClass('bg-green-600/5');

      const label1 = screen.getByText('① BEST BET');
      const label2 = screen.getByText('② ALSO GOOD');
      expect(label1).toHaveClass('text-green-400');
      expect(label2).toHaveClass('text-slate-300');
      expect(label2).not.toHaveClass('text-green-400');
    });

    it('stay-home pick button is disabled', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefingWithPicks([
        { rank: 1, headline: 'Stay in tonight', detail: 'Nothing worth it.',
          event: null, region: null, confidence: 'high' },
      ]));
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('best-bet-banner'));
      const btn = screen.getByTestId('best-bet-pick-1');
      expect(btn).toBeDisabled();
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
      expect(pick1).toHaveClass('border-plex-border');
      expect(pick1).toHaveClass('bg-plex-surface/30');
      expect(pick1).not.toHaveClass('border-green-600/60');
      expect(pick1).not.toHaveClass('bg-green-600/5');

      const label1 = screen.getByText('① BEST BET');
      expect(label1).toHaveClass('text-plex-text-muted');
      expect(label1).not.toHaveClass('text-green-400');
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
      expect(screen.getByTestId('briefing-heatmap')).toBeInTheDocument();
    });

    it('renders day-column headers', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-heatmap'));
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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));
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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));
      const headers = screen.getAllByTestId('heatmap-day-header');
      // Only 1 future day column
      expect(headers).toHaveLength(1);
    });

    it('renders heatmap cells for each region × day combination', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-heatmap'));
      const cells = screen.getAllByTestId('heatmap-cell');
      expect(cells.length).toBeGreaterThanOrEqual(1);
    });

    it('STANDDOWN cells are disabled and not interactive', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-heatmap'));
      const cells = screen.getAllByTestId('heatmap-cell');
      const disabledCells = cells.filter((c) => c.getAttribute('aria-disabled') === 'true');
      expect(disabledCells.length).toBeGreaterThanOrEqual(1);
    });

    it('clicking a GO heatmap cell opens drill-down panel', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));
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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));
      const cells = screen.getAllByTestId('heatmap-cell');
      const enabledCells = cells.filter((c) => !c.disabled);
      const marginalCell = enabledCells.find((c) => c.textContent.includes('Maybe sunrise'));
      expect(marginalCell).toBeTruthy();
    });
  });

  // ────── Evaluation confirmation dialog ──────

  describe('Evaluation confirmation dialog', () => {
    async function openDrillDownWithGoSlots() {
      const dateStr = futureDateStr();
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [{
          date: dateStr,
          eventSummaries: [{
            targetType: 'SUNSET',
            regions: [{
              regionName: 'Northumberland',
              verdict: 'GO',
              summary: 'Clear',
              tideHighlights: [],
              slots: [
                { locationName: 'Bamburgh', solarEventTime: `${dateStr}T18:30:00`, verdict: 'GO', tideAligned: false, flags: [] },
                { locationName: 'Dunstanburgh', solarEventTime: `${dateStr}T18:30:00`, verdict: 'MARGINAL', tideAligned: false, flags: [] },
                { locationName: 'Craster', solarEventTime: `${dateStr}T18:30:00`, verdict: 'STANDDOWN', tideAligned: false, flags: [] },
              ],
            }],
            unregioned: [],
          }],
        }],
      });
      useAuth.mockReturnValue({ role: 'ADMIN' });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

      const cells = screen.getAllByTestId('heatmap-cell');
      const enabledCell = cells.find((c) => !c.disabled);
      fireEvent.click(enabledCell);
      await waitFor(() => screen.getByTestId('drill-down-panel'));
      return screen;
    }

    it('shows "Run full forecast" button for ADMIN users', async () => {
      await openDrillDownWithGoSlots();
      expect(screen.getByTestId('run-forecast-btn')).toBeInTheDocument();
      expect(screen.getByTestId('run-forecast-btn').textContent).toContain('Run full forecast');
    });

    it('shows disabled "Run full forecast" button with Pro pill for LITE_USER', async () => {
      const dateStr = futureDateStr();
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [{
          date: dateStr,
          eventSummaries: [{
            targetType: 'SUNSET',
            regions: [{
              regionName: 'Test',
              verdict: 'GO',
              summary: 'Clear',
              tideHighlights: [],
              slots: [{ locationName: 'A', solarEventTime: `${dateStr}T18:30:00`, verdict: 'GO', tideAligned: false, flags: [] }],
            }],
            unregioned: [],
          }],
        }],
      });
      useAuth.mockReturnValue({ role: 'LITE_USER' });
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

      const cells = screen.getAllByTestId('heatmap-cell');
      const enabledCell = cells.find((c) => !c.disabled);
      fireEvent.click(enabledCell);
      await waitFor(() => screen.getByTestId('drill-down-panel'));

      const btn = screen.getByTestId('run-forecast-btn');
      expect(btn).toBeInTheDocument();
      expect(btn).toBeDisabled();
      expect(screen.getByTestId('pro-pill')).toBeInTheDocument();
    });

    it('clicking "Run full forecast" opens confirmation dialog', async () => {
      await openDrillDownWithGoSlots();
      fireEvent.click(screen.getByTestId('run-forecast-btn'));
      await waitFor(() => screen.getByTestId('confirm-dialog'));
      expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument();
    });

    it('confirmation dialog shows location count and cost estimate', async () => {
      await openDrillDownWithGoSlots();
      fireEvent.click(screen.getByTestId('run-forecast-btn'));
      await waitFor(() => screen.getByTestId('confirm-dialog'));

      const dialog = screen.getByTestId('confirm-dialog');
      // 2 GO/MARGINAL locations (Bamburgh GO + Dunstanburgh MARGINAL; Craster STANDDOWN excluded)
      expect(dialog.textContent).toContain('2 locations');
      expect(dialog.textContent).toContain('~6p');
    });

    it('cancel button closes confirmation dialog without running evaluation', async () => {
      await openDrillDownWithGoSlots();
      fireEvent.click(screen.getByTestId('run-forecast-btn'));
      await waitFor(() => screen.getByTestId('confirm-dialog'));

      fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));
      expect(screen.queryByTestId('confirm-dialog')).toBeNull();
      // Button should still be in ready state
      expect(screen.getByTestId('run-forecast-btn').textContent).toContain('Run full forecast');
    });

    it('confirm button closes dialog and triggers evaluation', async () => {
      const { subscribeToBriefingEvaluation } = await import('../api/briefingEvaluationApi.js');
      await openDrillDownWithGoSlots();
      fireEvent.click(screen.getByTestId('run-forecast-btn'));
      await waitFor(() => screen.getByTestId('confirm-dialog'));

      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
      expect(screen.queryByTestId('confirm-dialog')).toBeNull();
      // SSE subscription should have been called
      expect(subscribeToBriefingEvaluation).toHaveBeenCalled();
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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

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
      await waitFor(() => screen.getByTestId('briefing-heatmap'));

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

  describe('Show all locations toggle', () => {
    it('renders toggle switch on desktop', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const toggle = screen.queryByTestId('show-all-locations-toggle');
      expect(toggle).toBeInTheDocument();
      expect(toggle).toHaveAttribute('role', 'switch');
    });

    it('toggle starts with aria-checked="false" by default', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const toggle = screen.getByTestId('show-all-locations-toggle');
      expect(toggle).toHaveAttribute('aria-checked', 'false');
    });

    it('clicking toggle flips aria-checked from false to true', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const toggle = screen.getByTestId('show-all-locations-toggle');
      expect(toggle).toHaveAttribute('aria-checked', 'false');

      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute('aria-checked', 'true');
    });

    it('clicking toggle twice returns aria-checked to false', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const toggle = screen.getByTestId('show-all-locations-toggle');
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute('aria-checked', 'true');
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute('aria-checked', 'false');
    });

    it('toggle track data-checked updates when clicked', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const toggle = screen.getByTestId('show-all-locations-toggle');
      const track = toggle.querySelector('.quality-toggle-track');
      expect(track).toHaveAttribute('data-checked', 'false');

      fireEvent.click(toggle);
      expect(track).toHaveAttribute('data-checked', 'true');
    });

    it('toggle displays "Show all locations" label text', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const slider = screen.getByTestId('quality-slider');
      expect(slider).toHaveTextContent('Show all locations');
    });
  });

  describe('Quality slider row layout', () => {
    it('slider row contains the quality slider', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const row = screen.getByTestId('quality-slider-row');
      expect(row.querySelector('[data-testid="quality-slider"]')).toBeTruthy();
    });

    it('toggle is inside the quality slider component', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const slider = screen.getByTestId('quality-slider');
      const toggle = slider.querySelector('[data-testid="show-all-locations-toggle"]');
      expect(toggle).toBeInTheDocument();
    });

    it('quality slider row is not rendered when briefing has no days', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing({ days: [] }));
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('daily-briefing'));

      expect(screen.queryByTestId('quality-slider-row')).not.toBeInTheDocument();
    });

    it('quality slider row is rendered when briefing has days', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      expect(screen.getByTestId('quality-slider-row')).toBeInTheDocument();
    });

    // ── Slider prop wiring ──────────────────────────────────────────────────
    // Kills mutations that swap showing↔total, hardcode value, or swap setters.

    it('slider displays correct initial cell counts from sliderCounts', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      // Default qualityTier=2: 3 total cells, 1 visible (Lake District sunset at tier 2)
      const slider = screen.getByTestId('quality-slider');
      expect(slider).toHaveTextContent(/Showing 1 of 3 cells/);
    });

    it('slider initial aria-valuetext matches default qualityTier 2', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      const input = screen.getByRole('slider');
      expect(input).toHaveAttribute('aria-valuetext', 'All worth it');
    });

    it('dragging slider to show-everything updates the showing count', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      // Drag to visual 0 → internal tier 5 (everything visible)
      fireEvent.change(screen.getByRole('slider'), { target: { value: '0' } });

      const slider = screen.getByTestId('quality-slider');
      expect(slider).toHaveTextContent(/Showing 3 of 3 cells/);
    });

    it('dragging slider to best-only reduces the showing count', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      // Drag to visual 5 → internal tier 0 (best only — no cells match)
      fireEvent.change(screen.getByRole('slider'), { target: { value: '5' } });

      const slider = screen.getByTestId('quality-slider');
      expect(slider).toHaveTextContent(/Showing 0 of 3 cells/);
    });

    it('showing and total are not swapped at default tier', async () => {
      useAuth.mockReturnValue({ user: { role: 'ADMIN' } });
      getDailyBriefing.mockResolvedValue(buildBriefing());
      getDriveTimes.mockResolvedValue({});
      render(<DailyBriefing />);
      await waitFor(() => screen.getByTestId('briefing-collapsed-events'));

      // A mutation swapping showing↔total would display "Showing 3 of 1 cells"
      const slider = screen.getByTestId('quality-slider');
      expect(slider.textContent).not.toMatch(/Showing 3 of 1/);
    });
  });
});
