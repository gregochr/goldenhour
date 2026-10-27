import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import RegisterPage from '../components/RegisterPage.jsx';
import { AuthProvider, useAuth } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';
import * as SettingsApi from '../api/settingsApi.js';

vi.mock('../api/authApi.js');
vi.mock('../api/settingsApi.js');
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn().mockResolvedValue({ enforced: false, limit: 3, picks: [], options: [], suggested: [], status: 'FIRST_CHOICE' }), saveSubRegions: vi.fn() }));

/**
 * R4b (login-registration-plan.md §2 #2, #6, §3 R4b): the `HOME` step between Set password and the
 * Welcome. The account is signed in by then, the postcode is optional, and "Skip for now" is the
 * reader's dismissal of the sign-in prompt. These render the page alone; that `AuthGate` KEEPS it
 * mounted through the step is the gate's own test, in `App.test.jsx`.
 */

const SESSION = {
  accessToken: 'new-access', refreshToken: 'new-refresh', role: 'LITE_USER', username: 'newuser',
};
const MORPETH = { postcode: 'NE61 1AA', placeName: 'Morpeth, Northumberland', latitude: 55.17, longitude: -1.69 };

/** Reads the auth context's registration flags next to the page. */
function Probe() {
  const { registrationPending, postcodePromptSuppressed, token } = useAuth();
  return (
    <div>
      <span data-testid="probe-pending">{String(registrationPending)}</span>
      <span data-testid="probe-suppressed">{String(postcodePromptSuppressed)}</span>
      <span data-testid="probe-token">{token ?? 'none'}</span>
    </div>
  );
}

const renderWithProbe = () => render(
  <AuthProvider>
    <Probe />
    <RegisterPage verifyToken="tok" onBackToLogin={vi.fn()} />
  </AuthProvider>,
);

/** Walks the verified link through Set password and waits for the HOME step. */
const toHome = async (user) => {
  vi.spyOn(AuthApi, 'verifyEmail').mockResolvedValue({ userId: 7, verified: true, setupToken: 'setup-tok' });
  vi.spyOn(AuthApi, 'setPasswordForNewUser').mockResolvedValue(SESSION);
  renderWithProbe();
  await screen.findByTestId('set-password-form');
  await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
  await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');
  await user.click(screen.getByTestId('sp-submit'));
  expect(AuthApi.setPasswordForNewUser).toHaveBeenCalledWith('setup-tok', 'Abcdef1!');
  return screen.findByTestId('home-step');
};

/** Looks the postcode up and waits for the confirmation row. */
const lookUp = async (user) => {
  await user.type(screen.getByTestId('home-input'), 'NE61 1AA');
  await user.click(screen.getByTestId('home-lookup'));
  await screen.findByTestId('home-found');
};

describe('RegisterPage HOME step (R4b)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    SettingsApi.markPostcodePromptDismissed.mockResolvedValue({});
  });

  afterEach(() => {
    localStorage.clear();
  });

  it("follows Set password with Where's home?, signed in and registration pending, not the Welcome", async () => {
    const user = userEvent.setup();

    const step = await toHome(user);

    expect(step).toHaveClass('card');
    expect(screen.getByRole('heading', { level: 2, name: "Where's home?" })).toBeInTheDocument();
    expect(screen.getByText(
      'Your postcode sets the sunrise and sunset times shown at the top of the app.',
    )).toBeInTheDocument();
    expect(screen.getByTestId('home-primary')).toHaveTextContent('Save and continue');
    expect(screen.getByTestId('home-secondary')).toHaveTextContent('Skip for now');
    expect(screen.getByTestId('pro-tag')).toBeInTheDocument();
    expect(screen.queryByTestId('success-step')).toBeNull();
    expect(screen.queryByTestId('set-password-form')).toBeNull();
    expect(screen.getByTestId('probe-token')).toHaveTextContent('new-access');
    expect(screen.getByTestId('probe-pending')).toHaveTextContent('true');
    expect(localStorage.getItem('goldenhour_token')).toBe('new-access');
  });

  it('keeps the primary disabled until a lookup finds a postcode', async () => {
    const user = userEvent.setup();
    SettingsApi.lookupPostcode.mockResolvedValue(MORPETH);
    await toHome(user);
    expect(screen.getByTestId('home-primary')).toBeDisabled();

    await user.type(screen.getByTestId('home-input'), 'NE61 1AA');
    expect(screen.getByTestId('home-primary')).toBeDisabled();

    await user.click(screen.getByTestId('home-lookup'));
    await screen.findByTestId('home-found');

    expect(screen.getByTestId('home-primary')).toBeEnabled();
  });

  it('lookup, confirmation, Change restores the field with its value, then look up again', async () => {
    const user = userEvent.setup();
    SettingsApi.lookupPostcode.mockResolvedValue(MORPETH);
    await toHome(user);

    await lookUp(user);
    expect(screen.getByTestId('home-place')).toHaveTextContent('Morpeth, Northumberland');
    expect(screen.getByTestId('home-found-postcode')).toHaveTextContent('NE61 1AA');

    await user.click(screen.getByTestId('home-change'));
    expect(screen.getByTestId('home-input')).toHaveValue('NE61 1AA');
    expect(screen.queryByTestId('home-found')).toBeNull();

    await user.click(screen.getByTestId('home-lookup'));
    await screen.findByTestId('home-found');
    expect(SettingsApi.lookupPostcode).toHaveBeenCalledTimes(2);
    expect(SettingsApi.saveHome).not.toHaveBeenCalled();
  });

  it('shows Invalid postcode for a postcode the lookup refuses', async () => {
    const user = userEvent.setup();
    SettingsApi.lookupPostcode.mockRejectedValue({ response: { status: 400 } });
    await toHome(user);

    await user.type(screen.getByTestId('home-input'), 'NE61 9ZZ');
    await user.click(screen.getByTestId('home-lookup'));

    expect(await screen.findByTestId('home-error')).toHaveTextContent('Invalid postcode');
    expect(screen.getByTestId('home-input')).toHaveClass('field-error');
  });

  it('Save calls saveHome(postcode, lat, lon, null) exactly once, then shows the Welcome; no dismissal', async () => {
    const user = userEvent.setup();
    SettingsApi.lookupPostcode.mockResolvedValue(MORPETH);
    SettingsApi.saveHome.mockResolvedValue({ homePostcode: 'NE61 1AA', postcodePromptDue: false });
    await toHome(user);
    await lookUp(user);

    await user.click(screen.getByTestId('home-primary'));

    expect(await screen.findByTestId('success-step')).toBeInTheDocument();
    expect(SettingsApi.saveHome).toHaveBeenCalledTimes(1);
    expect(SettingsApi.saveHome).toHaveBeenCalledWith('NE61 1AA', 55.17, -1.69, null);
    expect(SettingsApi.markPostcodePromptDismissed).not.toHaveBeenCalled();
    expect(screen.getByTestId('probe-suppressed')).toHaveTextContent('false');
    expect(screen.queryByTestId('home-step')).toBeNull();
  });

  it('a failed save keeps the step and says so; the Welcome waits for a save or a skip', async () => {
    const user = userEvent.setup();
    SettingsApi.lookupPostcode.mockResolvedValue(MORPETH);
    SettingsApi.saveHome.mockRejectedValue(new Error('503'));
    await toHome(user);
    await lookUp(user);

    await user.click(screen.getByTestId('home-primary'));

    expect(await screen.findByTestId('home-save-error')).toHaveTextContent("Couldn't save. Try again.");
    expect(screen.getByTestId('home-step')).toBeInTheDocument();
    expect(screen.queryByTestId('success-step')).toBeNull();
  });

  it('Skip for now records the dismissal once, saves nothing, suppresses the prompt, and shows the Welcome', async () => {
    const user = userEvent.setup();
    await toHome(user);

    await user.click(screen.getByTestId('home-secondary'));

    expect(await screen.findByTestId('success-step')).toBeInTheDocument();
    expect(SettingsApi.markPostcodePromptDismissed).toHaveBeenCalledTimes(1);
    expect(SettingsApi.markPostcodePromptDismissed).toHaveBeenCalledWith();
    expect(SettingsApi.saveHome).not.toHaveBeenCalled();
    expect(SettingsApi.lookupPostcode).not.toHaveBeenCalled();
    expect(screen.getByTestId('probe-suppressed')).toHaveTextContent('true');
  });

  it('Skip suppresses the prompt even when the dismissal FAILS, and still shows the Welcome', async () => {
    const user = userEvent.setup();
    SettingsApi.markPostcodePromptDismissed.mockRejectedValue(new Error('503'));
    await toHome(user);

    await user.click(screen.getByTestId('home-secondary'));

    expect(await screen.findByTestId('success-step')).toBeInTheDocument();
    expect(screen.getByTestId('probe-suppressed')).toHaveTextContent('true');
    expect(SettingsApi.markPostcodePromptDismissed).toHaveBeenCalledTimes(1);
  });

  it('suppresses BEFORE the dismissal returns, so a slow PUT cannot let the prompt in', async () => {
    const user = userEvent.setup();
    let release;
    SettingsApi.markPostcodePromptDismissed.mockReturnValue(new Promise((resolve) => { release = resolve; }));
    await toHome(user);

    await user.click(screen.getByTestId('home-secondary'));

    expect(screen.getByTestId('probe-suppressed')).toHaveTextContent('true');
    expect(screen.queryByTestId('success-step')).toBeNull();
    await act(async () => { release({}); });
    expect(await screen.findByTestId('success-step')).toBeInTheDocument();
  });

  it('a double press on Skip while the dismissal is out records one dismissal', async () => {
    const user = userEvent.setup();
    let release;
    SettingsApi.markPostcodePromptDismissed.mockReturnValue(new Promise((resolve) => { release = resolve; }));
    await toHome(user);

    await user.dblClick(screen.getByTestId('home-secondary'));

    expect(SettingsApi.markPostcodePromptDismissed).toHaveBeenCalledTimes(1);
    await act(async () => { release({}); });
    expect(await screen.findByTestId('success-step')).toBeInTheDocument();
    expect(SettingsApi.markPostcodePromptDismissed).toHaveBeenCalledTimes(1);
  });

  it('the Welcome ends the pending registration after two seconds, and signs nobody out', async () => {
    const user = userEvent.setup();
    await toHome(user);
    const timers = vi.spyOn(globalThis, 'setTimeout');
    await user.click(screen.getByTestId('home-secondary'));
    await screen.findByTestId('success-step');
    expect(screen.getByTestId('probe-pending')).toHaveTextContent('true');

    const welcome = timers.mock.calls.find(([, ms]) => ms === 2000);
    expect(welcome).toBeDefined();
    act(() => { welcome[0](); });

    expect(screen.getByTestId('probe-pending')).toHaveTextContent('false');
    expect(screen.getByTestId('probe-token')).toHaveTextContent('new-access');
    timers.mockRestore();
  });
});
