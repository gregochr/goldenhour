import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import LocationAlerts from '../components/LocationAlerts.jsx';

vi.mock('../api/forecastApi.js', () => ({
  resetLocationFailures: vi.fn(),
}));

import { resetLocationFailures } from '../api/forecastApi.js';

const AUTO_DISABLED_REASON =
  'Auto-disabled after 3 consecutive failed scheduled runs '
  + '(last 2026-10-02: weather data could not be fetched).';

// The suite runs in UTC; 03:00 UTC on 2 Oct is 04:00 BST, which is what the alert should print.
const AUTO_DISABLED = {
  name: 'Bamburgh Castle',
  consecutiveFailures: 3,
  lastFailureAt: '2026-10-02T03:00:00',
  disabledReason: AUTO_DISABLED_REASON,
  enabled: false,
};

// An auto-disabled place whose counter was reset elsewhere: only the reason marks it.
const AUTO_DISABLED_COUNTER_RESET = {
  name: 'Alnwick Castle',
  consecutiveFailures: 0,
  lastFailureAt: '2026-10-02T03:00:00',
  disabledReason: AUTO_DISABLED_REASON,
  enabled: false,
};

const STILL_ENABLED_TWO_FAILURES = {
  name: 'Keswick',
  consecutiveFailures: 2,
  lastFailureAt: '2026-10-02T03:00:00',
  disabledReason: null,
  enabled: true,
};

const HEALTHY = {
  name: 'Durham',
  consecutiveFailures: 0,
  lastFailureAt: null,
  disabledReason: null,
  enabled: true,
};

describe('LocationAlerts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders nothing when no place has failures or a disabled reason', () => {
    const { container } = render(<LocationAlerts locations={[HEALTHY]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('lists an auto-disabled place whose counter is 0, so a reset counter cannot hide it', () => {
    render(<LocationAlerts locations={[HEALTHY, AUTO_DISABLED_COUNTER_RESET]} />);

    const row = screen.getByTestId('location-alert-Alnwick Castle');
    expect(row).toHaveTextContent(AUTO_DISABLED_REASON);
    expect(screen.queryByTestId('location-alert-Durham')).toBeNull();
  });

  it('prints the stored reason and the time of the last failure in UK time', () => {
    render(<LocationAlerts locations={[AUTO_DISABLED]} />);

    expect(screen.getByTestId('location-alert-reason')).toHaveTextContent(AUTO_DISABLED_REASON);
    expect(screen.getByTestId('location-alert-Bamburgh Castle'))
      .toHaveTextContent('Last failure: 2 Oct 2026, 04:00:00 BST');
  });

  it('offers Re-enable for an auto-disabled place', () => {
    render(<LocationAlerts locations={[AUTO_DISABLED]} />);

    expect(screen.getByTestId('reenable-Bamburgh Castle')).toHaveTextContent('Re-enable');
  });

  it('lists a still-enabled place with failures as a count, with no reason and no Re-enable', () => {
    render(<LocationAlerts locations={[STILL_ENABLED_TWO_FAILURES]} />);

    const row = screen.getByTestId('location-alert-Keswick');
    expect(row).toHaveTextContent('2 consecutive failures');
    expect(screen.queryByTestId('location-alert-reason')).toBeNull();
    expect(screen.queryByTestId('reenable-Keswick')).toBeNull();
  });

  it('clicking Re-enable calls the reset endpoint with the place name, then reports it', async () => {
    resetLocationFailures.mockResolvedValue(undefined);
    const onReenabled = vi.fn();
    render(<LocationAlerts locations={[AUTO_DISABLED]} onReenabledLocation={onReenabled} />);

    fireEvent.click(screen.getByTestId('reenable-Bamburgh Castle'));

    await waitFor(() => expect(onReenabled).toHaveBeenCalledWith('Bamburgh Castle'));
    expect(resetLocationFailures).toHaveBeenCalledTimes(1);
    expect(resetLocationFailures).toHaveBeenCalledWith('Bamburgh Castle');
  });

  it('does not report a re-enable when the request fails, and logs why', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    resetLocationFailures.mockRejectedValue(new Error('403'));
    const onReenabled = vi.fn();
    render(<LocationAlerts locations={[AUTO_DISABLED]} onReenabledLocation={onReenabled} />);

    fireEvent.click(screen.getByTestId('reenable-Bamburgh Castle'));

    await waitFor(() => expect(consoleError).toHaveBeenCalledTimes(1));
    expect(onReenabled).not.toHaveBeenCalled();
  });
});
