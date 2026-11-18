import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../api/warningsAdminApi.js', () => ({
  getWarningsStatus: vi.fn(),
  simulateWarning: vi.fn(),
}));

import { getWarningsStatus, simulateWarning } from '../api/warningsAdminApi.js';
import WarningsAdminPanel from '../components/WarningsAdminPanel.jsx';

const WARNING = {
  warningId: 'W-1',
  warningVersion: 2,
  level: 'Amber',
  weatherTypes: ['WIND', 'RAIN'],
  headline: 'Strong winds',
  validTo: '2026-11-18T12:00:00Z',
  upgradedFrom: 'Yellow',
  locationIds: [1, 2],
  simulated: false,
};

const status = (overrides = {}) => ({
  enabled: true,
  apiKeyConfigured: true,
  feedUrlConfigured: true,
  lastAttempt: {
    at: '2026-11-17T09:00:00Z',
    succeeded: true,
    failureReason: null,
    feedEntries: 4,
    stored: 1,
    alreadyStored: 3,
    goneSkipped: 2,
    malformedSkipped: 1,
  },
  lastSuccessAt: '2026-11-17T09:00:00Z',
  stale: false,
  storedVersions: 12,
  simulatedVersions: 1,
  unknownTypes: {},
  activeWarnings: [],
  ...overrides,
});

const fill = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe('WarningsAdminPanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows Loading until the status arrives', () => {
    getWarningsStatus.mockReturnValue(new Promise(() => {}));
    render(<WarningsAdminPanel />);
    expect(screen.getByTestId('warnings-admin-loading')).toBeInTheDocument();
  });

  it('shows an error with a retry that reloads', async () => {
    getWarningsStatus.mockRejectedValueOnce(new Error('x')).mockResolvedValueOnce(status());
    render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-error')).toHaveTextContent('Failed to load the warnings status');
    fireEvent.click(screen.getByTestId('warnings-admin-retry'));
    expect(await screen.findByTestId('warnings-admin-ingest-list')).toBeInTheDocument();
    expect(getWarningsStatus).toHaveBeenCalledTimes(2);
  });

  it('shows the last poll, last success, stored counts and the skipped counts', async () => {
    getWarningsStatus.mockResolvedValue(status());
    render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-enabled')).toHaveTextContent('On');
    expect(screen.getByTestId('warnings-admin-last-checked')).toHaveTextContent('17 Nov');
    expect(screen.getByTestId('warnings-admin-last-success')).toHaveTextContent('17 Nov');
    expect(screen.getByTestId('warnings-admin-stale')).toHaveTextContent('Fresh');
    expect(screen.getByTestId('warnings-admin-failure')).toHaveTextContent('—');
    expect(screen.getByTestId('warnings-admin-stored')).toHaveTextContent('12');
    expect(screen.getByTestId('warnings-admin-gone')).toHaveTextContent('2');
    expect(screen.getByTestId('warnings-admin-malformed')).toHaveTextContent('1');
  });

  it('says plainly whether warnings are switched off, dormant without a key, or polling', async () => {
    getWarningsStatus.mockResolvedValue(status({ enabled: false, active: false }));
    const off = render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-state')).toHaveTextContent('Switched off');
    off.unmount();

    getWarningsStatus.mockResolvedValue(status({ enabled: true, active: false, apiKeyConfigured: false }));
    const dormant = render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-state')).toHaveTextContent('On but no API key (dormant)');
    dormant.unmount();

    getWarningsStatus.mockResolvedValue(status({ enabled: true, active: true }));
    const live = render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-state')).toHaveTextContent('On and polling');
    live.unmount();

    getWarningsStatus.mockResolvedValue(status({ enabled: true, active: undefined, apiKeyConfigured: false }));
    render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-state')).toHaveTextContent('dormant');
  });

  it('names a recorded failure in words, an unlisted one as recorded, and marks the feed stale', async () => {
    getWarningsStatus.mockResolvedValue(status({
      stale: true,
      lastAttempt: { at: '2026-11-17T09:05:00Z', succeeded: false, failureReason: 'UNAUTHORISED' },
    }));
    const { unmount } = render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-failure')).toHaveTextContent('Unauthorised');
    expect(screen.getByTestId('warnings-admin-stale')).toHaveTextContent('Stale');
    unmount();

    getWarningsStatus.mockResolvedValue(status({
      lastAttempt: { at: '2026-11-17T09:05:00Z', succeeded: false, failureReason: 'HTTP_503' },
    }));
    render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-failure')).toHaveTextContent('HTTP_503');
  });

  it('reads a never-polled status (no attempt, flag off) without crashing', async () => {
    getWarningsStatus.mockResolvedValue(status({
      enabled: false, apiKeyConfigured: false, feedUrlConfigured: false, lastAttempt: null, lastSuccessAt: null,
      stale: true, activeWarnings: undefined, unknownTypes: undefined,
    }));
    render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-enabled')).toHaveTextContent('Off');
    expect(screen.getByTestId('warnings-admin-last-checked')).toHaveTextContent('—');
    expect(screen.getByTestId('warnings-admin-unknown-none')).toBeInTheDocument();
    expect(screen.getByTestId('warnings-admin-active-none')).toBeInTheDocument();
  });

  it('shows an unparseable instant as it was sent', async () => {
    getWarningsStatus.mockResolvedValue(status({ lastSuccessAt: 'not a date' }));
    render(<WarningsAdminPanel />);
    expect(await screen.findByTestId('warnings-admin-last-success')).toHaveTextContent('not a date');
  });

  it('lists unknown weather types with their counts', async () => {
    getWarningsStatus.mockResolvedValue(status({ unknownTypes: { TORNADO: 3 } }));
    render(<WarningsAdminPanel />);
    const list = await screen.findByTestId('warnings-admin-unknown-list');
    expect(list).toHaveTextContent('TORNADO seen 3');
  });

  it('lists warnings in force, flagged and simulated ones included', async () => {
    getWarningsStatus.mockResolvedValue(status({
      activeWarnings: [
        WARNING,
        { ...WARNING, warningId: 'SIM-1', warningVersion: 1, upgradedFrom: null, simulated: true, locationIds: [4],
          weatherTypes: [], headline: null },
      ],
    }));
    render(<WarningsAdminPanel />);
    const first = await screen.findByTestId('warnings-admin-warning-W-1');
    expect(first).toHaveTextContent('Amber');
    expect(first).toHaveTextContent('Upgraded from Yellow');
    expect(first).toHaveTextContent('WIND, RAIN');
    expect(first).toHaveTextContent('Strong winds');
    expect(first).toHaveTextContent('covers 2 enabled places');
    const sim = screen.getByTestId('warnings-admin-warning-SIM-1');
    expect(sim).toHaveTextContent('Simulated');
    expect(sim).toHaveTextContent('covers 1 enabled place');
    expect(within(sim).queryByText(/Upgraded/)).toBeNull();
  });

  it('refreshes on demand', async () => {
    getWarningsStatus.mockResolvedValue(status());
    render(<WarningsAdminPanel />);
    fireEvent.click(await screen.findByTestId('warnings-admin-refresh'));
    await waitFor(() => expect(getWarningsStatus).toHaveBeenCalledTimes(2));
  });

  describe('simulate form', () => {
    beforeEach(() => getWarningsStatus.mockResolvedValue(status()));

    it('refuses a missing or out-of-range latitude, longitude or duration and sends nothing', async () => {
      render(<WarningsAdminPanel />);
      await screen.findByTestId('warnings-simulate-form');
      fireEvent.click(screen.getByTestId('warnings-simulate-submit'));
      expect(screen.getByTestId('warnings-simulate-error')).toHaveTextContent('Latitude');
      fill('Latitude', '95');
      fireEvent.click(screen.getByTestId('warnings-simulate-submit'));
      expect(screen.getByTestId('warnings-simulate-error')).toHaveTextContent('Latitude');
      fill('Latitude', '54.5');
      fireEvent.click(screen.getByTestId('warnings-simulate-submit'));
      expect(screen.getByTestId('warnings-simulate-error')).toHaveTextContent('Longitude');
      fill('Longitude', '-2.2');
      fill(/Valid for/, '0');
      fireEvent.click(screen.getByTestId('warnings-simulate-submit'));
      expect(screen.getByTestId('warnings-simulate-error')).toHaveTextContent('Duration');
      expect(simulateWarning).not.toHaveBeenCalled();
    });

    it('posts the request, names the created warning and reloads the status', async () => {
      simulateWarning.mockResolvedValue({ warningId: 'SIM-abc12345' });
      render(<WarningsAdminPanel />);
      await screen.findByTestId('warnings-simulate-form');
      fireEvent.change(screen.getByLabelText('Level'), { target: { value: 'Red' } });
      fill(/Weather types/, 'snow, ice');
      fill('Latitude', '54.5');
      fill('Longitude', '-2.2');
      fill(/Valid for/, '12');
      fill(/Headline/, '  Heavy snow ');
      fireEvent.click(screen.getByTestId('warnings-simulate-submit'));
      expect(await screen.findByTestId('warnings-simulate-done')).toHaveTextContent('SIM-abc12345');
      expect(simulateWarning).toHaveBeenCalledWith({
        level: 'Red',
        weatherTypes: ['SNOW', 'ICE'],
        headline: 'Heavy snow',
        durationHours: 12,
        latitude: 54.5,
        longitude: -2.2,
      });
      expect(getWarningsStatus).toHaveBeenCalledTimes(2);
    });

    it('defaults an empty types field to WIND and omits a blank headline', async () => {
      simulateWarning.mockResolvedValue({ warningId: 'SIM-1' });
      render(<WarningsAdminPanel />);
      await screen.findByTestId('warnings-simulate-form');
      fill(/Weather types/, ' , ');
      fill('Latitude', '54.5');
      fill('Longitude', '-2.2');
      fireEvent.click(screen.getByTestId('warnings-simulate-submit'));
      await screen.findByTestId('warnings-simulate-done');
      expect(simulateWarning).toHaveBeenCalledWith({
        level: 'Amber',
        weatherTypes: ['WIND'],
        headline: undefined,
        durationHours: 6,
        latitude: 54.5,
        longitude: -2.2,
      });
    });

    it('says so when the server refuses, and clears the previous success', async () => {
      simulateWarning.mockResolvedValueOnce({ warningId: 'SIM-1' }).mockRejectedValueOnce(new Error('403'));
      render(<WarningsAdminPanel />);
      await screen.findByTestId('warnings-simulate-form');
      fill('Latitude', '54.5');
      fill('Longitude', '-2.2');
      fireEvent.click(screen.getByTestId('warnings-simulate-submit'));
      await screen.findByTestId('warnings-simulate-done');
      fireEvent.click(screen.getByTestId('warnings-simulate-submit'));
      expect(await screen.findByTestId('warnings-simulate-error')).toHaveTextContent('could not be simulated');
      expect(screen.queryByTestId('warnings-simulate-done')).toBeNull();
    });

    it('makes the controls 44px tap targets on a phone and never renders a dialog', async () => {
      render(<WarningsAdminPanel />);
      await screen.findByTestId('warnings-simulate-form');
      expect(screen.getByTestId('warnings-simulate-submit')).toHaveClass('max-[639px]:min-h-11');
      expect(screen.getByLabelText('Latitude')).toHaveClass('max-[639px]:min-h-11');
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });
});
