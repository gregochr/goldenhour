import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import DailyBriefing from '../components/DailyBriefing.jsx';

// Mock the API module
vi.mock('../api/briefingApi.js', () => ({
  getDailyBriefing: vi.fn(),
}));

import { getDailyBriefing } from '../api/briefingApi.js';

function futureDateStr() {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

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
                verdict: 'STANDDOWN',
                summary: 'Heavy cloud and rain across all 5 locations',
                tideHighlights: ['King tide at Bamburgh'],
                slots: [
                  {
                    locationName: 'Bamburgh',
                    solarEventTime: `${dateStr}T05:47:00`,
                    verdict: 'STANDDOWN',
                    lowCloudPercent: 92,
                    precipitationMm: 4.2,
                    visibilityMetres: 8000,
                    temperatureCelsius: 6.1,
                    windSpeedMs: 8.3,
                    tideState: 'HIGH',
                    tideAligned: true,
                    flags: ['Sun blocked', 'Active rain', 'King tide'],
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

describe('DailyBriefing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  // ────── Rendering ──────

  it('renders nothing when loading', async () => {
    getDailyBriefing.mockReturnValue(new Promise(() => {})); // never resolves
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

  it('renders Daily Briefing label in collapsed state', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => {
      expect(screen.getByTestId('daily-briefing')).toBeInTheDocument();
    });
    expect(screen.getByText(/Daily Briefing/i)).toBeInTheDocument();
  });

  it('shows freshness timestamp', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => {
      expect(screen.getByText(/ago|just now/i)).toBeInTheDocument();
    });
  });

  // ────── Expand/collapse ──────

  it('expands on click to show event sections', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.getByTestId('briefing-expanded')).toBeInTheDocument();
    expect(screen.getByText(/Sunrise/)).toBeInTheDocument();
    expect(screen.getByText(/Sunset/)).toBeInTheDocument();
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

  // ────── Minimise / restore ──────

  it('shows minimise button on the card', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-minimise'));
    expect(screen.getByTestId('briefing-minimise')).toBeInTheDocument();
  });

  it('clicking minimise hides the card and shows the pill', async () => {
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

  it('persists minimised state in localStorage', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-minimise'));

    fireEvent.click(screen.getByTestId('briefing-minimise'));

    expect(localStorage.getItem('briefing-minimised')).toBe('true');
  });

  it('starts minimised when localStorage flag is set', async () => {
    localStorage.setItem('briefing-minimised', 'true');
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-minimised-pill'));

    expect(screen.queryByTestId('daily-briefing')).toBeNull();
    expect(screen.getByTestId('briefing-minimised-pill')).toBeInTheDocument();
  });

  it('clears localStorage flag when restored', async () => {
    localStorage.setItem('briefing-minimised', 'true');
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-minimised-pill'));

    fireEvent.click(screen.getByTestId('briefing-minimised-pill'));

    expect(localStorage.getItem('briefing-minimised')).toBe('false');
  });

  // ────── Expanded view — past event filtering ──────

  it('hides past events when expanded', async () => {
    const todayStr = new Date().toISOString().slice(0, 10);
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: todayStr,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [{ regionName: 'Northumberland', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'Bamburgh', solarEventTime: `${todayStr}T04:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
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

  it('shows future events and hides past events in the same day when expanded', async () => {
    const todayStr = new Date().toISOString().slice(0, 10);
    const tomorrowStr = futureDateStr();
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: todayStr,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [{ regionName: 'Past Region', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'A', solarEventTime: `${todayStr}T04:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
          {
            targetType: 'SUNSET',
            regions: [{ regionName: 'Future Region', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'B', solarEventTime: `${tomorrowStr}T20:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
        ],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.queryByText('Past Region')).toBeNull();
    expect(screen.getByText(/Sunset/)).toBeInTheDocument();
  });

  it('hides day heading when all events for that day have passed', async () => {
    const todayStr = new Date().toISOString().slice(0, 10);
    getDailyBriefing.mockResolvedValue({
      generatedAt: new Date().toISOString().slice(0, 19),
      headline: '',
      days: [{
        date: todayStr,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [{ regionName: 'Northumberland', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'A', solarEventTime: `${todayStr}T04:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
          {
            targetType: 'SUNSET',
            regions: [{ regionName: 'Lake District', verdict: 'GO', summary: '', tideHighlights: [], slots: [{ locationName: 'B', solarEventTime: `${todayStr}T17:00:00`, verdict: 'GO', tideAligned: false, flags: [] }] }],
            unregioned: [],
          },
        ],
      }],
    });
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    // Day heading ("Today — YYYY-MM-DD") should not appear
    expect(screen.queryByText(/Today/)).toBeNull();
  });

  // ────── Region cards ──────

  it('shows region rows with verdict pills', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const regionRows = screen.getAllByTestId('region-row');
    expect(regionRows.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Northumberland')).toBeInTheDocument();
    expect(screen.getByText('Lake District')).toBeInTheDocument();
  });

  it('shows verdict pills with correct text', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    const pills = screen.getAllByTestId('verdict-pill');
    const pillTexts = pills.map((p) => p.textContent);
    expect(pillTexts).toContain('STANDDOWN');
    expect(pillTexts).toContain('GO');
  });

  // ─────��� Region expand with slots ──────

  it('expands region to show location slots', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    // Click the first region row to expand it
    const regionRows = screen.getAllByTestId('region-row');
    fireEvent.click(regionRows[0]);

    const slots = screen.getAllByTestId('region-slots');
    expect(slots.length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Bamburgh')).toBeInTheDocument();
  });

  // ────── Flags ──────

  it('shows flag chips on slots', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    // Expand Northumberland region
    const regionRows = screen.getAllByTestId('region-row');
    fireEvent.click(regionRows[0]);

    expect(screen.getByText('Sun blocked')).toBeInTheDocument();
    expect(screen.getByText('Active rain')).toBeInTheDocument();
    expect(screen.getByText('King tide')).toBeInTheDocument();
  });

  // ────── Tide highlights ──────

  it('shows tide highlight chips on region', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.getByText('King tide at Bamburgh')).toBeInTheDocument();
  });

  // ────── Collapsed event summary rows ──────

  it('shows compact event summary rows for upcoming events in collapsed state', async () => {
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
    const tomorrow = new Date();
    tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
    const tomorrowStr = tomorrow.toISOString().slice(0, 10);

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
    const tomorrow = new Date();
    tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
    const tomorrowStr = tomorrow.toISOString().slice(0, 10);

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

  it('shows tide alignment indicator when a slot is tide-aligned', async () => {
    const tomorrow = new Date();
    tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
    const tomorrowStr = tomorrow.toISOString().slice(0, 10);

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
    const tomorrow = new Date();
    tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
    const tomorrowStr = tomorrow.toISOString().slice(0, 10);

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
    const yesterday = new Date();
    yesterday.setUTCDate(yesterday.getUTCDate() - 1);
    const yesterdayStr = yesterday.toISOString().slice(0, 10);

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

  // ────── Unregioned slots ──────

  it('shows unregioned slots flat at the bottom', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.getByText('Durham')).toBeInTheDocument();
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
              verdict: 'MARGINAL',
              summary: '',
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
      fireEvent.click(screen.getByTestId('region-row'));

      const slots = screen.getAllByTestId('briefing-slot');
      const names = slots.map((s) => s.querySelector('.font-medium').textContent);
      expect(names).toEqual(['Alpha', 'Cedar', 'Beta', 'Zelda']);
    });

    it('sorts unregioned slots GO before STANDDOWN', async () => {
      const dateStr = futureDateStr();
      getDailyBriefing.mockResolvedValue({
        generatedAt: new Date().toISOString().slice(0, 19),
        headline: '',
        days: [{
          date: dateStr,
          eventSummaries: [{
            targetType: 'SUNSET',
            regions: [],
            unregioned: [
              { locationName: 'York',   solarEventTime: `${dateStr}T18:00:00`, verdict: 'STANDDOWN', tideAligned: false, flags: [] },
              { locationName: 'Alnwick', solarEventTime: `${dateStr}T18:00:00`, verdict: 'GO',       tideAligned: false, flags: [] },
            ],
          }],
        }],
      });
      render(<DailyBriefing locations={[]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));

      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const slots = screen.getAllByTestId('briefing-slot');
      const names = slots.map((s) => s.querySelector('.font-medium').textContent);
      expect(names).toEqual(['Alnwick', 'York']);
    });
  });

  // ────── Drive time display ──────

  describe('drive time display', () => {
    it('shows formatted drive time when driveDurationMinutes is in the locations prop', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing locations={[{ name: 'Keswick', driveDurationMinutes: 45 }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));

      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      const lakeDist = regionRows.find((r) => r.textContent.includes('Lake District'));
      fireEvent.click(lakeDist);

      const driveTimes = screen.getAllByTestId('slot-drive-time');
      expect(driveTimes).toHaveLength(1);
      expect(driveTimes[0]).toHaveTextContent('45 min');
    });

    it('does not show drive time when driveDurationMinutes is absent', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing locations={[{ name: 'Keswick' }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));

      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      const lakeDist = regionRows.find((r) => r.textContent.includes('Lake District'));
      fireEvent.click(lakeDist);

      expect(screen.queryByTestId('slot-drive-time')).toBeNull();
    });

    it('formats durations over 1 hour correctly', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing locations={[{ name: 'Keswick', driveDurationMinutes: 90 }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));

      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      const lakeDist = regionRows.find((r) => r.textContent.includes('Lake District'));
      fireEvent.click(lakeDist);

      expect(screen.getByTestId('slot-drive-time')).toHaveTextContent('1h 30min');
    });

    it('formats exact-hour durations without trailing minutes', async () => {
      getDailyBriefing.mockResolvedValue(buildBriefing());
      render(<DailyBriefing locations={[{ name: 'Keswick', driveDurationMinutes: 120 }]} />);
      await waitFor(() => screen.getByTestId('briefing-toggle'));

      fireEvent.click(screen.getByTestId('briefing-toggle'));

      const regionRows = screen.getAllByTestId('region-row');
      const lakeDist = regionRows.find((r) => r.textContent.includes('Lake District'));
      fireEvent.click(lakeDist);

      expect(screen.getByTestId('slot-drive-time')).toHaveTextContent('2h');
    });
  });
});
