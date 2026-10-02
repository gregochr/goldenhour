import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MarkerPopupContent from '../components/MarkerPopupContent.jsx';
import { runForecast } from '../api/forecastApi.js';

// Only the Run Forecast call is mocked — the same boundary the sibling popup tests mock at.
vi.mock('../api/forecastApi.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, runForecast: vi.fn(), getForecastDetail: vi.fn() };
});

vi.mock('../components/TideIndicator.jsx', () => ({
  default: ({ locationName }) => <div data-testid="tide-indicator">Tides for {locationName}</div>,
}));

const LOCATION = {
  name: 'Gosforth Nature Reserve',
  solarEventType: ['SUNRISE', 'SUNSET'],
  locationType: ['WILDLIFE'],
  tideType: [],
  forecastsByDate: new Map(),
};

/** What axios rejects with for a refused request: a generic message plus the response body. */
function refusal(status, data) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data },
  });
}

async function pressRunForecast() {
  render(
    <MarkerPopupContent
      location={LOCATION}
      forecast={null}
      hourlyData={[]}
      eventType="SUNRISE"
      isPureWildlife={false}
      date="2026-10-03"
      onTideFetchedAt={vi.fn()}
      tideFetchedAt={null}
      role="ADMIN" // eslint-disable-line jsx-a11y/aria-role
    />,
  );
  fireEvent.click(screen.getByTestId('run-forecast-btn'));
}

describe('MarkerPopupContent — a refused Run Forecast', () => {
  beforeEach(() => {
    runForecast.mockReset();
  });

  it("shows the server's sentence from the error key, not axios's generic message", async () => {
    runForecast.mockRejectedValue(refusal(400, {
      error: "'Gosforth Nature Reserve' is not a sky location: it has no sunrise or sunset forecast",
    }));
    await pressRunForecast();

    expect(await screen.findByText(
      "'Gosforth Nature Reserve' is not a sky location: it has no sunrise or sunset forecast",
    )).toBeInTheDocument();
    expect(screen.queryByText(/Request failed with status code 400/)).toBeNull();
  });

  it("falls back to the popup's own sentence, never a proxy's HTML page, when nothing usable came back", async () => {
    runForecast.mockRejectedValue({ response: { status: 502, data: '<html>Bad Gateway</html>' } });
    await pressRunForecast();

    expect(await screen.findByText('Failed to start forecast run')).toBeInTheDocument();
    expect(screen.queryByText(/Bad Gateway/)).toBeNull();
  });

  it("keeps the transport's own message for a failure with no response at all", async () => {
    runForecast.mockRejectedValue(new Error('Network Error'));
    await pressRunForecast();

    expect(await screen.findByText('Network Error')).toBeInTheDocument();
  });
});
