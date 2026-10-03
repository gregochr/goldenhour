import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import LocationAlerts from '../components/LocationAlerts.jsx';

vi.mock('../api/forecastApi.js', () => ({
  resetLocationFailures: vi.fn(),
}));

import { resetLocationFailures } from '../api/forecastApi.js';

const AUTO_DISABLED_REASON =
  'Auto-disabled after 3 consecutive failed scheduled runs '
  + '(last 2026-10-02: data could not be collected).';

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

const AUTO_DISABLED_NO_TIME = { ...AUTO_DISABLED, name: 'Seaton Delaval', lastFailureAt: null };

const STILL_ENABLED_TWO_FAILURES = {
  name: 'Keswick',
  consecutiveFailures: 2,
  lastFailureAt: '2026-10-02T03:00:00',
  disabledReason: null,
  enabled: true,
};

const STILL_ENABLED_ONE_FAILURE_NO_TIME = {
  name: 'Ambleside',
  consecutiveFailures: 1,
  lastFailureAt: null,
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

function reenableButton(name = 'Bamburgh Castle') {
  return screen.getByRole('button', { name: `Re-enable ${name}` });
}

describe('LocationAlerts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when no place has failures or a disabled reason', () => {
    const { container } = render(<LocationAlerts locations={[HEALTHY]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('lists an auto-disabled place whose counter is 0, so a reset counter cannot hide it', () => {
    render(<LocationAlerts locations={[HEALTHY, AUTO_DISABLED_COUNTER_RESET]} />);

    expect(screen.getByTestId('location-alert-Alnwick Castle')).toHaveTextContent(AUTO_DISABLED_REASON);
    expect(screen.queryByTestId('location-alert-Durham')).toBeNull();
  });

  it('prints the stored reason and the time of the last failure in UK time', () => {
    render(<LocationAlerts locations={[AUTO_DISABLED]} />);

    expect(screen.getByTestId('location-alert-reason')).toHaveTextContent(AUTO_DISABLED_REASON);
    expect(screen.getByTestId('location-alert-Bamburgh Castle'))
      .toHaveTextContent('Last failure: 2 Oct 2026, 04:00:00 BST');
  });

  it('omits the last-failure line for an auto-disabled place that has no failure time', () => {
    render(<LocationAlerts locations={[AUTO_DISABLED_NO_TIME]} />);

    const row = screen.getByTestId('location-alert-Seaton Delaval');
    expect(row).toHaveTextContent(AUTO_DISABLED_REASON);
    expect(row).not.toHaveTextContent('Last failure');
  });

  it('offers Re-enable for an auto-disabled place', () => {
    render(<LocationAlerts locations={[AUTO_DISABLED]} />);

    expect(reenableButton()).toBeEnabled();
  });

  it('lists a still-enabled place with failures as a count with its time, no reason and no Re-enable', () => {
    render(<LocationAlerts locations={[STILL_ENABLED_TWO_FAILURES]} />);

    const row = screen.getByTestId('location-alert-Keswick');
    expect(row).toHaveTextContent('2 consecutive failures (2 Oct 2026, 04:00:00 BST)');
    expect(screen.queryByTestId('location-alert-reason')).toBeNull();
    expect(screen.queryByRole('button', { name: /Re-enable/ })).toBeNull();
  });

  it('says "1 consecutive failure" in the singular and omits the time when there is none', () => {
    render(<LocationAlerts locations={[STILL_ENABLED_ONE_FAILURE_NO_TIME]} />);

    const text = screen.getByTestId('location-alert-Ambleside').textContent;
    expect(text).toContain('1 consecutive failure');
    expect(text).not.toContain('failures');
    expect(text).not.toContain('(');
  });

  it('clicking Re-enable calls the reset endpoint with the place name, then reports it', async () => {
    resetLocationFailures.mockResolvedValue(undefined);
    const onReenabled = vi.fn();
    render(<LocationAlerts locations={[AUTO_DISABLED]} onReenabledLocation={onReenabled} />);

    fireEvent.click(reenableButton());

    await waitFor(() => expect(onReenabled).toHaveBeenCalledWith('Bamburgh Castle'));
    expect(resetLocationFailures).toHaveBeenCalledTimes(1);
    expect(resetLocationFailures).toHaveBeenCalledWith('Bamburgh Castle');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('disables the button while the request is in flight, so a second press cannot be sent', async () => {
    let finish;
    resetLocationFailures.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    render(<LocationAlerts locations={[AUTO_DISABLED]} />);

    const button = reenableButton();
    fireEvent.click(button);

    await waitFor(() => expect(button).toBeDisabled());
    fireEvent.click(button);
    expect(resetLocationFailures).toHaveBeenCalledTimes(1);
    finish();
    await waitFor(() => expect(button).toBeEnabled());
  });

  it('shows the server\'s sentence on the row when Re-enable is refused, and lets the admin retry', async () => {
    resetLocationFailures.mockRejectedValue({
      response: { data: { error: 'Location not found: Bamburgh Castle' } },
    });
    const onReenabled = vi.fn();
    render(<LocationAlerts locations={[AUTO_DISABLED]} onReenabledLocation={onReenabled} />);

    fireEvent.click(reenableButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('Location not found: Bamburgh Castle');
    expect(reenableButton()).toBeEnabled();
    expect(onReenabled).not.toHaveBeenCalled();
  });

  it('falls back to a plain sentence when the failure carries no server message', async () => {
    resetLocationFailures.mockRejectedValue(new Error('Network Error'));
    render(<LocationAlerts locations={[AUTO_DISABLED]} />);

    fireEvent.click(reenableButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not re-enable this location.');
  });

  it('clears the error line when the next attempt starts', async () => {
    resetLocationFailures
      .mockRejectedValueOnce({ response: { data: { error: 'Location not found: Bamburgh Castle' } } })
      .mockResolvedValueOnce(undefined);
    render(<LocationAlerts locations={[AUTO_DISABLED]} />);
    fireEvent.click(reenableButton());
    await screen.findByRole('alert');

    fireEvent.click(reenableButton());

    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });
});
