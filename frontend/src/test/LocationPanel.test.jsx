import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import LocationManagementView from '../components/LocationManagementView.jsx';

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
  addLocation: vi.fn(),
  updateLocation: vi.fn(),
  setLocationEnabled: vi.fn(),
  geocodePlace: vi.fn(),
  enrichLocation: vi.fn(),
  resetLocationFailures: vi.fn(),
  fetchValleyFloorCandidates: vi.fn(),
  fetchValleyFloorCell: vi.fn(),
}));
vi.mock('../api/regionApi', () => ({ fetchRegions: vi.fn() }));
vi.mock('../api/areaApi', () => ({
  fetchAreas: vi.fn(),
  fetchUncoveredLocations: vi.fn(),
  createArea: vi.fn(),
  updateArea: vi.fn(),
  setAreaLocations: vi.fn(),
  deleteArea: vi.fn(),
  fetchLocationAliases: vi.fn(),
  setLocationAliases: vi.fn(),
}));
vi.mock('../api/tideApi', () => ({ fetchTideStats: vi.fn(), fetchAllTideStats: vi.fn() }));

import {
  fetchLocations, setLocationEnabled, resetLocationFailures,
} from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases } from '../api/areaApi';
import { fetchTideStats } from '../api/tideApi';

const REASON = 'Auto-disabled after 3 consecutive failed scheduled runs (last 2026-10-02: weather data could not be fetched).';
const NORTH = { id: 1, name: 'Northumberland', enabled: true };

const SEASCAPE = {
  id: 1, name: 'Alnmouth', lat: 55.39, lon: -1.613, enabled: true, solarEventType: ['SUNRISE', 'SUNSET'],
  locationType: ['SEASCAPE'], tideType: ['HIGH', 'MID', 'LOW'], region: NORTH, subRegionName: 'Alnwick Coast',
  bortleClass: 3, skyBrightnessSqm: 21.4, elevationMetres: 12, gridLat: 55.375, gridLng: -1.625,
  overlooksWater: true, coastalTidal: true, mistProne: false, consecutiveFailures: 0,
};
const LANDSCAPE = {
  id: 2, name: 'Durham Cathedral', lat: 54.77, lon: -1.58, enabled: true, solarEventType: ['SUNRISE', 'ALLDAY'],
  locationType: ['LANDSCAPE'], tideType: [], region: null, subRegionName: null, bortleClass: null,
  skyBrightnessSqm: null, elevationMetres: null, gridLat: null, gridLng: null, overlooksWater: false,
  coastalTidal: false, mistProne: true, consecutiveFailures: 0,
};
const FAILING = {
  ...LANDSCAPE, id: 3, name: 'Amble', consecutiveFailures: 2, lastFailureAt: '2026-10-02T03:00:00', region: NORTH,
};
const AUTO_DISABLED = {
  ...LANDSCAPE, id: 4, name: 'Bamburgh Castle', enabled: false, consecutiveFailures: 3,
  lastFailureAt: '2026-10-02T03:00:00', disabledReason: REASON, region: NORTH,
};
const MANUAL_OFF = { ...LANDSCAPE, id: 5, name: 'Blea Tarn', enabled: false };

const STATS = {
  avgHighMetres: 2.1, maxHighMetres: 2.62, avgLowMetres: -0.9, minLowMetres: -2.76, dataPoints: 5200, avgRangeMetres: 3.11,
  p75HighMetres: 2.2, p90HighMetres: 2.3, p95HighMetres: 2.27, springTideThreshold: 2.6, springTideCount: 50,
  springTideFrequency: 0.223, kingTideThreshold: 2.27, kingTideCount: 10,
};

async function openPanel(id) {
  fireEvent.click(await screen.findByTestId(`locations-row-${id}`));
  return screen.findByTestId('location-view-panel');
}

function renderView(props = {}) {
  return render(<LocationManagementView onLocationsChanged={() => {}} {...props} />);
}

describe('LocationManagementView — the view panel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    fetchLocations.mockResolvedValue([SEASCAPE, LANDSCAPE, FAILING, AUTO_DISABLED, MANUAL_OFF]);
    fetchRegions.mockResolvedValue([NORTH]);
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
    fetchTideStats.mockResolvedValue(STATS);
  });

  describe('its sections', () => {
    it('draws Shooting, Place, Sky, Tides and Location actions, in that order, for a seascape', async () => {
      renderView();
      const panel = await openPanel(1);

      const labels = Array.from(panel.querySelectorAll('section h3')).map((h) => h.textContent);
      expect(labels).toEqual(['Shooting', 'Place', 'Sky', 'Tides', 'Location actions']);
    });

    it('has no Tides section for a landscape, and does not fetch tide statistics for it', async () => {
      renderView();
      const panel = await openPanel(2);

      const labels = Array.from(panel.querySelectorAll('section h3')).map((h) => h.textContent);
      expect(labels).toEqual(['Shooting', 'Place', 'Sky', 'Location actions']);
      expect(fetchTideStats).not.toHaveBeenCalled();
    });

    it('shows Shooting: type, light, tides and mist-prone', async () => {
      renderView();
      const panel = await openPanel(1);

      const shooting = panel.querySelector('[data-testid="location-shooting"]');
      expect(shooting).toHaveTextContent('Seascape');
      expect(shooting).toHaveTextContent('Sunrise, Sunset');
      expect(shooting).toHaveTextContent('High, Mid, Low');
      expect(screen.getByTestId('location-mist-prone')).toHaveTextContent('No');
    });

    it('reads Not tidal, All day and a mist-prone Yes for the landscape', async () => {
      renderView();
      const panel = await openPanel(2);

      const shooting = panel.querySelector('[data-testid="location-shooting"]');
      expect(shooting).toHaveTextContent('Not tidal');
      expect(shooting).toHaveTextContent('Sunrise, All day');
      expect(screen.getByTestId('location-mist-prone')).toHaveTextContent('Yes');
    });

    it('shows Place: region, sub-region, 3 dp coordinates, elevation and grid cell', async () => {
      renderView();
      const panel = await openPanel(1);

      const place = panel.querySelector('[data-testid="location-place"]');
      expect(place).toHaveTextContent('Northumberland');
      expect(place).toHaveTextContent('Alnwick Coast');
      expect(screen.getByTestId('location-coordinates')).toHaveTextContent('55.390, -1.613');
      expect(place).toHaveTextContent('12 m');
      expect(place).toHaveTextContent('55.375, -1.625');
      expect(screen.queryByTestId('location-valley-floor')).not.toBeInTheDocument();
      expect(screen.queryByTestId('location-tide-point')).not.toBeInTheDocument();
    });

    it('shows the valley floor and the tide point only when they are set', async () => {
      fetchLocations.mockResolvedValue([{
        ...SEASCAPE, valleyFloorMetres: 80, valleyFloorLat: 54.58, valleyFloorLng: -3.12, tideLat: 55.0, tideLng: -1.4,
      }]);
      renderView();
      await openPanel(1);

      expect(screen.getByTestId('location-valley-floor')).toHaveTextContent('80 m at 54.580, -3.120');
      expect(screen.getByTestId('location-tide-point')).toHaveTextContent('55.000, -1.400');
    });

    it('shows a dash for each value the server does not hold', async () => {
      renderView();
      const panel = await openPanel(2);

      const place = panel.querySelector('[data-testid="location-place"]');
      expect(place.querySelectorAll('dd')[0]).toHaveTextContent('—');
      expect(screen.getByTestId('location-bortle')).toHaveTextContent('—');
    });

    it.each([
      [1, 'Excellent dark sky'], [2, 'Excellent dark sky'], [3, 'Moderate, aurora-friendly'],
      [4, 'Moderate, aurora-friendly'], [5, 'Light-polluted'], [9, 'Light-polluted'],
    ])('describes Bortle %i as %s', async (bortle, words) => {
      fetchLocations.mockResolvedValue([{ ...LANDSCAPE, bortleClass: bortle }]);
      renderView();
      await openPanel(2);

      expect(screen.getByTestId('location-bortle')).toHaveTextContent(`${bortle}`);
      expect(screen.getByTestId('location-bortle')).toHaveTextContent(words);
    });

    it('shows Sky: sky brightness, overlooks water and coastal tidal', async () => {
      renderView();
      const panel = await openPanel(1);

      const sky = panel.querySelector('[data-testid="location-sky"]');
      expect(sky).toHaveTextContent('21.4 SQM');
      expect(sky).toHaveTextContent('Overlooks water');
      expect(sky).toHaveTextContent('Coastal tidal');
    });
  });

  describe('the Tides section', () => {
    it('shows the large range bar, its labels, and Avg range, P95 high and Spring', async () => {
      renderView();
      await openPanel(1);

      expect(await screen.findByTestId('location-tide-bar')).toBeInTheDocument();
      expect(screen.getByTestId('location-tide-bar-labels')).toHaveTextContent('−2.76 m');
      expect(screen.getByTestId('location-tide-bar-labels')).toHaveTextContent('+2.62 m');
      expect(screen.getByTestId('location-tide-avg-range')).toHaveTextContent('3.11 m');
      expect(screen.getByTestId('location-tide-p95')).toHaveTextContent('2.27 m');
      expect(screen.getByTestId('location-tide-p95')).toHaveClass('text-verdict-marginal');
      expect(screen.getByTestId('location-tide-spring')).toHaveTextContent('22.3%');
      expect(fetchTideStats).toHaveBeenCalledWith('Alnmouth');
    });

    it('says it is loading, then shows the figures', async () => {
      let land;
      fetchTideStats.mockReturnValue(new Promise((resolve) => { land = resolve; }));
      renderView();
      await openPanel(1);

      expect(screen.getByTestId('location-tides-loading')).toHaveTextContent('Loading tide statistics');
      land(STATS);
      expect(await screen.findByTestId('location-tide-bar')).toBeInTheDocument();
      expect(screen.queryByTestId('location-tides-loading')).not.toBeInTheDocument();
    });

    it("says Couldn't load tide statistics. when the fetch fails, with no link", async () => {
      fetchTideStats.mockRejectedValue(new Error('500'));
      renderView();
      await openPanel(1);

      expect(await screen.findByTestId('location-tides-error')).toHaveTextContent("Couldn't load tide statistics.");
      expect(screen.queryByTestId('location-full-tide-stats')).not.toBeInTheDocument();
    });

    it('says No tide data yet. for a location the server has no tide data for, with no link', async () => {
      fetchTideStats.mockResolvedValue(null);
      renderView();
      await openPanel(1);

      expect(await screen.findByTestId('location-tides-empty')).toHaveTextContent('No tide data yet.');
      expect(screen.queryByTestId('location-full-tide-stats')).not.toBeInTheDocument();
    });

    it('links Full tide stats to the Tides tab when stats came back and the location is on', async () => {
      renderView();
      await openPanel(1);

      expect(await screen.findByTestId('location-full-tide-stats')).toHaveAttribute('href', '#manage/tides/1');
    });

    it('offers no Full tide stats link for a location that is off, because the Tides list holds enabled ones only', async () => {
      fetchLocations.mockResolvedValue([{ ...SEASCAPE, enabled: false }]);
      renderView();
      await openPanel(1);

      expect(await screen.findByTestId('location-tide-bar')).toBeInTheDocument();
      expect(screen.queryByTestId('location-full-tide-stats')).not.toBeInTheDocument();
    });
  });

  describe('the failure box', () => {
    it('is not shown for a healthy location', async () => {
      renderView();
      await openPanel(1);

      expect(screen.queryByTestId('location-failure-box')).not.toBeInTheDocument();
    });

    it('says how many runs failed in a row, when it last failed, and that a third failure turns it off', async () => {
      renderView();
      await openPanel(3);

      expect(screen.getByTestId('location-failure-title')).toHaveTextContent('2 failed runs in a row');
      expect(screen.getByTestId('location-failure-box')).toHaveTextContent('Last failed 2 Oct 2026, 04:00.');
      expect(screen.getByTestId('location-failure-box')).toHaveTextContent('A third failure turns the location off.');
      expect(screen.getByTestId('location-reset-failures')).toHaveTextContent('Reset failure count');
    });

    it('uses the singular for a single failed run, and leaves out the time the server does not hold', async () => {
      fetchLocations.mockResolvedValue([{ ...FAILING, consecutiveFailures: 1, lastFailureAt: null }]);
      renderView();
      await openPanel(3);

      expect(screen.getByTestId('location-failure-title')).toHaveTextContent('1 failed run in a row');
      expect(screen.getByTestId('location-failure-box')).not.toHaveTextContent('Last failed');
    });

    it('Reset failure count calls the reset endpoint by name, then re-reads and tells the app', async () => {
      resetLocationFailures.mockResolvedValue(undefined);
      const onChanged = vi.fn();
      renderView({ onLocationsChanged: onChanged });
      await openPanel(3);
      fetchLocations.mockClear();
      fetchLocations.mockResolvedValue([SEASCAPE, LANDSCAPE, { ...FAILING, consecutiveFailures: 0, lastFailureAt: null }]);

      fireEvent.click(screen.getByTestId('location-reset-failures'));

      await waitFor(() => expect(resetLocationFailures).toHaveBeenCalledWith('Amble'));
      await waitFor(() => expect(onChanged).toHaveBeenCalled());
      await waitFor(() => expect(screen.queryByTestId('location-failure-box')).not.toBeInTheDocument());
      expect(fetchLocations).toHaveBeenCalled();
    });

    it('disables the button while the request is in flight', async () => {
      let finish;
      resetLocationFailures.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
      renderView();
      await openPanel(3);

      fireEvent.click(screen.getByTestId('location-reset-failures'));

      expect(screen.getByTestId('location-reset-failures')).toBeDisabled();
      fireEvent.click(screen.getByTestId('location-reset-failures'));
      expect(resetLocationFailures).toHaveBeenCalledTimes(1);
      finish();
      await waitFor(() => expect(screen.getByTestId('location-reset-failures')).not.toBeDisabled());
    });

    it('shows the server\'s sentence on a refusal, and clears it on the next attempt', async () => {
      resetLocationFailures
        .mockRejectedValueOnce({ response: { status: 409, data: { error: 'Something went wrong server-side.' } } })
        .mockResolvedValueOnce(undefined);
      renderView();
      await openPanel(3);

      fireEvent.click(screen.getByTestId('location-reset-failures'));
      expect(await screen.findByTestId('location-failure-error')).toHaveTextContent('Something went wrong server-side.');

      fireEvent.click(screen.getByTestId('location-reset-failures'));
      await waitFor(() => expect(screen.queryByTestId('location-failure-error')).not.toBeInTheDocument());
    });

    it('falls back to a plain sentence when the server said nothing usable', async () => {
      resetLocationFailures.mockRejectedValue(new Error('Network Error'));
      renderView();
      await openPanel(3);

      fireEvent.click(screen.getByTestId('location-reset-failures'));

      expect(await screen.findByTestId('location-failure-error')).toHaveTextContent('Could not re-enable this location.');
    });

    it('for a place turned off by the failures says so, shows the reason, and offers Turn back on', async () => {
      renderView();
      await openPanel(4);

      expect(screen.getByTestId('location-failure-title')).toHaveTextContent('Turned off after 3 failed runs');
      expect(screen.getByTestId('location-failure-reason')).toHaveTextContent(REASON);
      expect(screen.getByTestId('location-turn-back-on')).toHaveTextContent('Turn back on');
      expect(screen.queryByTestId('location-reset-failures')).not.toBeInTheDocument();
      expect(screen.getByTestId('location-panel-subtitle')).toHaveTextContent('Disabled after failures');
    });

    it('still shows it for a turned-off place whose counter was reset elsewhere', async () => {
      fetchLocations.mockResolvedValue([{ ...AUTO_DISABLED, consecutiveFailures: 0 }]);
      renderView();
      await openPanel(4);

      expect(screen.getByTestId('location-failure-title')).toHaveTextContent('Turned off after failed runs');
      expect(screen.getByTestId('location-turn-back-on')).toBeInTheDocument();
    });

    it('Turn back on calls the same endpoint and puts the place back', async () => {
      fetchLocations
        .mockResolvedValueOnce([AUTO_DISABLED])
        .mockResolvedValue([{ ...AUTO_DISABLED, enabled: true, consecutiveFailures: 0, disabledReason: null, lastFailureAt: null }]);
      resetLocationFailures.mockResolvedValue(undefined);
      renderView();
      await openPanel(4);

      fireEvent.click(screen.getByTestId('location-turn-back-on'));

      await waitFor(() => expect(resetLocationFailures).toHaveBeenCalledWith('Bamburgh Castle'));
      await waitFor(() => expect(screen.queryByTestId('location-failure-box')).not.toBeInTheDocument());
      expect(screen.getByTestId('location-panel-subtitle')).toHaveTextContent('Active');
    });

    it('shows the server\'s sentence when the region is off ("Turn Northumberland on first.")', async () => {
      resetLocationFailures.mockRejectedValue({ response: { status: 409, data: { error: 'Turn Northumberland on first.' } } });
      renderView();
      await openPanel(4);

      fireEvent.click(screen.getByTestId('location-turn-back-on'));

      expect(await screen.findByTestId('location-failure-error')).toHaveTextContent('Turn Northumberland on first.');
      // The place stays turned off and listed.
      expect(screen.getByTestId('location-turn-back-on')).toBeInTheDocument();
    });

    it('shows nothing for a place an admin switched off by hand', async () => {
      renderView();
      await openPanel(5);

      expect(screen.queryByTestId('location-failure-box')).not.toBeInTheDocument();
      expect(screen.getByTestId('location-panel-subtitle')).toHaveTextContent('Disabled');
    });
  });

  describe('Location actions', () => {
    it('offers Turn off with its explanation, and turning off calls the server and tells the app', async () => {
      setLocationEnabled.mockResolvedValue({});
      const onChanged = vi.fn();
      renderView({ onLocationsChanged: onChanged });
      const panel = await openPanel(2);
      expect(panel.querySelector('[data-testid="location-actions"]'))
        .toHaveTextContent('Left out of forecast runs until you turn it back on.');
      fetchLocations.mockResolvedValue([SEASCAPE, { ...LANDSCAPE, enabled: false }, FAILING, AUTO_DISABLED, MANUAL_OFF]);

      fireEvent.click(screen.getByTestId('toggle-enabled-2'));

      await waitFor(() => expect(setLocationEnabled).toHaveBeenCalledWith(2, false));
      await waitFor(() => expect(onChanged).toHaveBeenCalled());
      await waitFor(() => expect(screen.getByTestId('toggle-enabled-2')).toHaveTextContent('Turn on'));
      expect(screen.getByTestId('location-status-2')).toHaveTextContent('Disabled');
    });

    it('turning on a disabled location sends enabled true', async () => {
      setLocationEnabled.mockResolvedValue({});
      renderView();
      await openPanel(5);

      fireEvent.click(screen.getByTestId('toggle-enabled-5'));

      await waitFor(() => expect(setLocationEnabled).toHaveBeenCalledWith(5, true));
    });

    it('flips at once, and reverts with the server\'s sentence when the change is refused', async () => {
      let refuse;
      setLocationEnabled.mockReturnValue(new Promise((_, reject) => { refuse = reject; }));
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      const onChanged = vi.fn();
      renderView({ onLocationsChanged: onChanged });
      await openPanel(5);

      fireEvent.click(screen.getByTestId('toggle-enabled-5'));
      await waitFor(() => expect(screen.getByTestId('toggle-enabled-5')).toHaveTextContent('Turn off'));

      refuse({ response: { status: 409, data: { error: 'Turn Northumberland on first.' } } });

      expect(await screen.findByTestId('location-toggle-error')).toHaveTextContent('Turn Northumberland on first.');
      await waitFor(() => expect(screen.getByTestId('toggle-enabled-5')).toHaveTextContent('Turn on'));
      expect(onChanged).not.toHaveBeenCalled();
      consoleError.mockRestore();
    });

    it('does not show one location\'s refusal under another', async () => {
      setLocationEnabled.mockRejectedValue({ response: { status: 409, data: { error: 'Turn Northumberland on first.' } } });
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      renderView();
      await openPanel(5);
      fireEvent.click(screen.getByTestId('toggle-enabled-5'));
      await screen.findByTestId('location-toggle-error');

      fireEvent.click(screen.getByTestId('locations-row-2'));

      await waitFor(() => expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Durham Cathedral'));
      expect(screen.queryByTestId('location-toggle-error')).not.toBeInTheDocument();
      consoleError.mockRestore();
    });
  });
});
