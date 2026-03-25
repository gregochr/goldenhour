import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import DailyBriefing from '../components/DailyBriefing.jsx';

// Mock the API module
vi.mock('../api/briefingApi.js', () => ({
  getDailyBriefing: vi.fn(),
}));

import { getDailyBriefing } from '../api/briefingApi.js';

function buildBriefing(overrides = {}) {
  return {
    generatedAt: '2026-03-25T14:00:00',
    headline: 'Tomorrow sunset looks promising in the Lake District',
    days: [
      {
        date: new Date().toISOString().slice(0, 10),
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
                    solarEventTime: '2026-03-25T05:47:00',
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
                    solarEventTime: '2026-03-25T18:30:00',
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
                solarEventTime: '2026-03-25T18:28:00',
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

  it('renders headline in collapsed state', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => {
      expect(screen.getByTestId('daily-briefing')).toBeInTheDocument();
    });
    expect(screen.getByText(/Tomorrow sunset looks promising/)).toBeInTheDocument();
  });

  it('shows freshness timestamp', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => {
      expect(screen.getByText(/Briefing as of/)).toBeInTheDocument();
    });
  });

  // ────── Expand/collapse ──────

  it('expands on click to show event sections', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.getByTestId('briefing-expanded')).toBeInTheDocument();
    expect(screen.getByText('Sunrise')).toBeInTheDocument();
    expect(screen.getByText('Sunset')).toBeInTheDocument();
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

  // ────���─ Region cards ──────

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

  // ────── Unregioned slots ──────

  it('shows unregioned slots flat at the bottom', async () => {
    getDailyBriefing.mockResolvedValue(buildBriefing());
    render(<DailyBriefing />);
    await waitFor(() => screen.getByTestId('briefing-toggle'));

    fireEvent.click(screen.getByTestId('briefing-toggle'));

    expect(screen.getByText('Durham')).toBeInTheDocument();
  });
});
