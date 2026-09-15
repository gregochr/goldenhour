import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  act, render, screen, fireEvent, waitFor,
} from '@testing-library/react';
import UserSettingsModal from '../components/UserSettingsModal.jsx';

vi.mock('../api/settingsApi', () => ({
  getSettings: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
  saveMapColourPreferences: vi.fn(),
}));

import {
  getSettings, lookupPostcode, saveHome, refreshDriveTimes, saveMapColourPreferences,
} from '../api/settingsApi';

const PRO_SETTINGS = {
  username: 'alice',
  email: 'alice@example.com',
  role: 'PRO_USER',
  homePostcode: 'EH1 1BB',
  homePlaceName: 'Edinburgh',
  driveTimesCalculatedAt: null,
};

const ADMIN_SETTINGS = { ...PRO_SETTINGS, role: 'ADMIN', username: 'admin' };

const LITE_SETTINGS = {
  username: 'bob',
  email: 'bob@example.com',
  role: 'LITE_USER',
  homePostcode: null,
  homePlaceName: null,
  driveTimesCalculatedAt: null,
};

const LITE_WITH_HOME = {
  ...LITE_SETTINGS,
  homePostcode: 'SW1A 1AA',
  homePlaceName: 'Westminster',
};

/** A request the test settles by hand, so a negative can wait for it to have landed. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held save inside an AWAITED act, so its continuation — the only place the
 * dialog reports a save — has run before a "not reported" assertion reads the spy.
 */
async function land(settle) {
  await act(async () => { settle(); });
}

function renderModal(props = {}) {
  const onClose = vi.fn();
  const onDriveTimesRefreshed = vi.fn();
  const result = render(
    <UserSettingsModal onClose={onClose} onDriveTimesRefreshed={onDriveTimesRefreshed} {...props} />,
  );
  return { ...result, onClose, onDriveTimesRefreshed };
}

describe('UserSettingsModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ---------------------------------------------------------------------------
  // Loading & error states
  // ---------------------------------------------------------------------------

  it('shows loading text while fetching settings', () => {
    getSettings.mockReturnValue(new Promise(() => {})); // never resolves
    renderModal();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('shows error message when settings fetch fails', async () => {
    getSettings.mockRejectedValue(new Error('fail'));
    renderModal();
    await waitFor(() => expect(screen.getByText('Failed to load settings.')).toBeInTheDocument());
  });

  // ---------------------------------------------------------------------------
  // Profile section — all roles
  // ---------------------------------------------------------------------------

  it('renders username and role badge for PRO_USER', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());
    expect(screen.getByTestId('settings-role-badge')).toHaveTextContent('Pro');
  });

  it('renders Admin badge for ADMIN', async () => {
    getSettings.mockResolvedValue(ADMIN_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-role-badge')).toHaveTextContent('Admin'));
  });

  it('renders Lite badge for LITE_USER', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-role-badge')).toHaveTextContent('Lite'));
  });

  it('renders email when present', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByText('alice@example.com')).toBeInTheDocument());
  });

  // ---------------------------------------------------------------------------
  // PRO / ADMIN — interactive home location & drive times
  // ---------------------------------------------------------------------------

  it('shows current home location for PRO user', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-home-current')).toHaveTextContent('Edinburgh'));
  });

  it('still autofocuses the postcode field when asked, over the dialog\'s own focus', async () => {
    // The shared Modal focuses its container on open. This consumer's own effect runs after and
    // must win — the whole point of focusing the CONTAINER rather than hunting for a first control
    // was that a consumer stays free to place focus itself.
    renderModal({ focusField: 'postcode' });
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toHaveFocus());
  });

  it('postcode input is enabled for PRO user', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).not.toBeDisabled());
  });

  it('lookup button is enabled for PRO user with postcode', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-lookup-btn')).not.toBeDisabled());
  });

  it('refresh drive times button is enabled for PRO user with home and no prior calculation', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).not.toBeDisabled());
  });

  it('refresh drive times button is disabled when postcode unchanged since last calculation', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, driveTimesCalculatedAt: '2026-04-01T10:00:00Z' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled());
  });

  it('refresh button treats differently-formatted same postcode as unchanged', async () => {
    // Saved as "EH1 1BB" — drive times already calculated; normalised comparison should match
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: 'EH1 1BB', driveTimesCalculatedAt: '2026-04-01T10:00:00Z' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled());
  });

  it('does not show upsell text for PRO user', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByText('alice')).toBeInTheDocument());
    expect(screen.queryByText(/Upgrade to Pro/)).not.toBeInTheDocument();
  });

  it('does not show upsell text for ADMIN', async () => {
    getSettings.mockResolvedValue(ADMIN_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByText('admin')).toBeInTheDocument());
    expect(screen.queryByText(/Upgrade to Pro/)).not.toBeInTheDocument();
  });

  it('home location wrapper has no greyed-out class for PRO user', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeInTheDocument());
    const input = screen.getByTestId('settings-postcode-input');
    // The wrapper div is the grandparent of the input (input → flex div → wrapper div)
    const wrapper = input.closest('.flex.gap-2').parentElement;
    expect(wrapper).not.toHaveClass('opacity-45');
    expect(wrapper).not.toHaveClass('pointer-events-none');
  });

  // ---------------------------------------------------------------------------
  // LITE — greyed out, disabled, upsell
  // ---------------------------------------------------------------------------

  // The postcode is FREE, and these three are the assertions that say so. It was Pro-gated on the
  // reasoning that it exists for the drive times it feeds; it also feeds the masthead's light rule,
  // whose empty state sends the reader here to set one. A nudge landing on a disabled input is a
  // dead end, and the band would stay dim for exactly the accounts the nudge is written for.
  it('lets a LITE user type a postcode — the light rule is free', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeEnabled());
  });

  it('lets a LITE user look up and save a postcode', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    lookupPostcode.mockResolvedValue({
      postcode: 'NE66 1NG', latitude: 55.413, longitude: -1.706, placeName: 'Alnwick',
    });
    saveHome.mockResolvedValue({ ...LITE_SETTINGS, homePostcode: 'NE66 1NG' });
    renderModal();

    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeEnabled());
    fireEvent.change(screen.getByTestId('settings-postcode-input'), { target: { value: 'NE66 1NG' } });
    fireEvent.click(screen.getByTestId('settings-lookup-btn'));

    await waitFor(() => expect(screen.getByTestId('settings-save-home-btn')).toBeEnabled());
    fireEvent.click(screen.getByTestId('settings-save-home-btn'));

    // The whole point of the ungating: the request actually goes out for a LITE account.
    await waitFor(() => expect(saveHome).toHaveBeenCalledWith('NE66 1NG', 55.413, -1.706, null));
  });

  it('keeps the local radius Pro-gated, since it frames Close to home', async () => {
    // The split moved one level down rather than away: light times free, drive times and the
    // radius they are ranked by still Pro.
    getSettings.mockResolvedValue(LITE_WITH_HOME);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-radius-slider')).toBeDisabled());
    expect(screen.getByTestId('settings-local-radius')).toHaveClass('opacity-45');
    expect(screen.getByTestId('settings-local-radius')).toHaveClass('pointer-events-none');
  });

  it('refresh drive times button is disabled for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled());
  });

  it('shows upsell text for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByText(
      'Your postcode sets your light times. Upgrade to Pro for personalised drive times.',
    )).toBeInTheDocument());
  });

  it('does not grey the home location block for LITE user', async () => {
    // The inverse of the assertion this replaced. Stated as "no greyed ancestor" rather than as a
    // class check on one element, because the gate was a WRAPPER — reinstating it anywhere between
    // the input and the section would restore the dead end without failing a narrower test.
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeInTheDocument());
    expect(screen.getByTestId('settings-postcode-input').closest('.opacity-45')).toBeNull();
    expect(screen.getByTestId('settings-lookup-btn').closest('.opacity-45')).toBeNull();
  });

  it('drive times wrapper is greyed out for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeInTheDocument());
    const btn = screen.getByTestId('settings-refresh-drive-btn');
    const wrapper = btn.closest('.opacity-45');
    expect(wrapper).toBeInTheDocument();
    expect(wrapper).toHaveClass('pointer-events-none');
  });

  it('section headers remain visible (not inside greyed wrapper) for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByText('Home location')).toBeInTheDocument());
    const homeHeader = screen.getByText('Home location');
    expect(homeHeader.closest('.opacity-45')).toBeNull();
    const driveHeader = screen.getByText('Drive times');
    expect(driveHeader.closest('.opacity-45')).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // Postcode lookup flow (PRO)
  // ---------------------------------------------------------------------------

  it('calls lookupPostcode on button click', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: null });
    lookupPostcode.mockResolvedValue({ postcode: 'EH1 1BB', placeName: 'Edinburgh', latitude: 55.95, longitude: -3.19 });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeInTheDocument());
    fireEvent.change(screen.getByTestId('settings-postcode-input'), { target: { value: 'EH1 1BB' } });
    fireEvent.click(screen.getByTestId('settings-lookup-btn'));
    await waitFor(() => expect(lookupPostcode).toHaveBeenCalledWith('EH1 1BB'));
    expect(screen.getByTestId('settings-lookup-result')).toBeInTheDocument();
    expect(screen.getByText('Edinburgh')).toBeInTheDocument();
  });

  it('calls lookupPostcode on Enter key', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: null });
    lookupPostcode.mockResolvedValue({ postcode: 'EH1 1BB', placeName: 'Edinburgh', latitude: 55.95, longitude: -3.19 });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeInTheDocument());
    fireEvent.change(screen.getByTestId('settings-postcode-input'), { target: { value: 'EH1 1BB' } });
    fireEvent.keyDown(screen.getByTestId('settings-postcode-input'), { key: 'Enter' });
    await waitFor(() => expect(lookupPostcode).toHaveBeenCalledWith('EH1 1BB'));
  });

  it('shows lookup error on failure', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: null });
    lookupPostcode.mockRejectedValue(new Error('fail'));
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeInTheDocument());
    fireEvent.change(screen.getByTestId('settings-postcode-input'), { target: { value: 'INVALID' } });
    fireEvent.click(screen.getByTestId('settings-lookup-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-lookup-error')).toHaveTextContent('Invalid postcode'));
  });

  // ---------------------------------------------------------------------------
  // Save home flow (PRO)
  // ---------------------------------------------------------------------------

  it('saves home location after lookup', async () => {
    const lookupData = { postcode: 'EH1 1BB', placeName: 'Edinburgh', latitude: 55.95, longitude: -3.19 };
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: null });
    lookupPostcode.mockResolvedValue(lookupData);
    saveHome.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: 'EH1 1BB', homePlaceName: 'Edinburgh' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeInTheDocument());
    fireEvent.change(screen.getByTestId('settings-postcode-input'), { target: { value: 'EH1 1BB' } });
    fireEvent.click(screen.getByTestId('settings-lookup-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-save-home-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-save-home-btn'));
    await waitFor(() => expect(saveHome).toHaveBeenCalledWith('EH1 1BB', 55.95, -3.19, null));
    // After save, lookup result disappears and current home shows
    await waitFor(() => expect(screen.getByTestId('settings-home-current')).toHaveTextContent('Edinburgh'));
  });

  // ---------------------------------------------------------------------------
  // Drive time refresh flow (PRO)
  // ---------------------------------------------------------------------------

  it('shows spinner during drive time refresh', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    refreshDriveTimes.mockReturnValue(new Promise(() => {})); // never resolves
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-refresh-drive-btn'));
    await waitFor(() => expect(screen.getByText('Calculating drive times…')).toBeInTheDocument());
  });

  it('shows success screen after drive time refresh', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    refreshDriveTimes.mockResolvedValue({ locationsUpdated: 12, calculatedAt: '2026-04-02T10:00:00Z' });
    const { onDriveTimesRefreshed } = renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-refresh-drive-btn'));
    await waitFor(() => expect(screen.getByText(/12 locations updated/)).toBeInTheDocument());
    expect(onDriveTimesRefreshed).toHaveBeenCalled();
  });

  it('dismisses success screen and returns to settings', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    refreshDriveTimes.mockResolvedValue({ locationsUpdated: 5, calculatedAt: '2026-04-02T10:00:00Z' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-refresh-drive-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-refresh-dismiss')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-refresh-dismiss'));
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeInTheDocument());
  });

  it('shows refresh error on 429', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    refreshDriveTimes.mockRejectedValue({ response: { status: 429, data: { message: 'Rate limited' } } });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-refresh-drive-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-refresh-error')).toHaveTextContent('Rate limited'));
  });

  it('shows generic refresh error on 500', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    refreshDriveTimes.mockRejectedValue({ response: { status: 500 } });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-refresh-drive-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-refresh-error')).toHaveTextContent('Something went wrong'));
  });

  // ---------------------------------------------------------------------------
  // Close behaviour
  // ---------------------------------------------------------------------------

  it('calls onClose when close button clicked', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    const { onClose } = renderModal();
    await waitFor(() => expect(screen.getByLabelText('Close')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('Close'));
    expect(onClose).toHaveBeenCalled();
  });

  // ---------------------------------------------------------------------------
  // Drive time calc time formatting
  // ---------------------------------------------------------------------------

  it('shows last calculated time', async () => {
    const fiveMinAgo = new Date(Date.now() - 5 * 60000).toISOString();
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, driveTimesCalculatedAt: fiveMinAgo });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-drive-calc-time')).toHaveTextContent('5 min ago'));
  });

  it('refresh button enables after saving a different postcode', async () => {
    // Start with drive times already calculated for EH1 1BB
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, driveTimesCalculatedAt: '2026-04-01T10:00:00Z' });
    lookupPostcode.mockResolvedValue({ postcode: 'NE1 7RU', placeName: 'Newcastle', latitude: 54.97, longitude: -1.61 });
    saveHome.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: 'NE1 7RU', homePlaceName: 'Newcastle', driveTimesCalculatedAt: '2026-04-01T10:00:00Z' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled());
    // Look up and save a different postcode
    fireEvent.change(screen.getByTestId('settings-postcode-input'), { target: { value: 'NE1 7RU' } });
    fireEvent.click(screen.getByTestId('settings-lookup-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-save-home-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-save-home-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).not.toBeDisabled());
  });

  it('refresh button disables again after successful refresh', async () => {
    // No prior calculation — button starts enabled
    getSettings.mockResolvedValue(PRO_SETTINGS);
    refreshDriveTimes.mockResolvedValue({ locationsUpdated: 5, calculatedAt: '2026-04-02T10:00:00Z' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).not.toBeDisabled());
    fireEvent.click(screen.getByTestId('settings-refresh-drive-btn'));
    // Success screen → dismiss
    await waitFor(() => expect(screen.getByTestId('settings-refresh-dismiss')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-refresh-dismiss'));
    // Back to settings — button should now be disabled (postcode matches)
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled());
  });

  it('refresh button enables after first-time postcode save', async () => {
    // First-time user: no home, no drive times calculated
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: null, driveTimesCalculatedAt: null });
    lookupPostcode.mockResolvedValue({ postcode: 'EH1 1BB', placeName: 'Edinburgh', latitude: 55.95, longitude: -3.19 });
    saveHome.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: 'EH1 1BB', homePlaceName: 'Edinburgh' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled());
    // Look up and save a postcode
    fireEvent.change(screen.getByTestId('settings-postcode-input'), { target: { value: 'EH1 1BB' } });
    fireEvent.click(screen.getByTestId('settings-lookup-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-save-home-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-save-home-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).not.toBeDisabled());
  });

  it('refresh button stays disabled for LITE user even if postcode would differ', async () => {
    // LITE user with home but no prior calculation — postcodeChanged would be true for PRO
    getSettings.mockResolvedValue(LITE_WITH_HOME);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled());
  });

  it('refresh button stays enabled after failed refresh (postcode still differs)', async () => {
    // No prior calculation — button starts enabled
    getSettings.mockResolvedValue(PRO_SETTINGS);
    refreshDriveTimes.mockRejectedValue({ response: { status: 429, data: { message: 'Rate limited' } } });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).not.toBeDisabled());
    fireEvent.click(screen.getByTestId('settings-refresh-drive-btn'));
    await waitFor(() => expect(screen.getByTestId('settings-refresh-error')).toBeInTheDocument());
    // Button should still be enabled — drive times were not actually updated
    expect(screen.getByTestId('settings-refresh-drive-btn')).not.toBeDisabled();
  });

  it('shows "Set a home location first." when no home set', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: null });
    renderModal();
    await waitFor(() => expect(screen.getByText('Set a home location first.')).toBeInTheDocument());
    expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled();
  });

  // ---------------------------------------------------------------------------
  // Map Colours (Stage 6) — ungated, new toggle/checkbox pattern
  // ---------------------------------------------------------------------------

  it('renders the Map colours section for a LITE user — reading the map is not a Pro feature', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByText('Map colours')).toBeInTheDocument());
    expect(screen.getByTestId('settings-map-colour-verdict')).not.toBeDisabled();
    expect(screen.getByTestId('settings-map-colour-temp')).not.toBeDisabled();
    // Not inside any greyed-out wrapper — this section has no Pro gate at all.
    expect(screen.getByText('Map colours').closest('.opacity-45')).toBeNull();
  });

  // Stage 7 flipped the default: was 'defaults to the verdict scale when never chosen'.
  it('defaults to the temp scale when never chosen', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, mapColourScale: null });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-map-colour-temp')).toBeChecked());
    expect(screen.getByTestId('settings-map-colour-verdict')).not.toBeChecked();
  });

  it('an explicitly saved verdict scale is not swept up in the flip', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, mapColourScale: 'verdict' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-map-colour-verdict')).toBeChecked());
    expect(screen.getByTestId('settings-map-colour-temp')).not.toBeChecked();
  });

  it('an unrecognised stored value resolves to verdict, not silently to the new default', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, mapColourScale: 'garbled' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-map-colour-verdict')).toBeChecked());
    expect(screen.getByTestId('settings-map-colour-temp')).not.toBeChecked();
  });

  // Trap 2, as far as this component's own loading gate lets it be observed in the DOM: the
  // section holding the radios is gated on `loading`/`settings` and simply does not render before
  // `fetchSettings` resolves (`getSettings` unresolved shows only "Loading…"), so the initial
  // `useState` value it seeds from is never independently visible — only its post-fetch value is.
  // What IS observable, and is the actual risk Trap 2 names, is that the seed and the fetch-driven
  // value come from the SAME call (`resolveMode`) rather than two literals that could drift apart:
  // the never-chosen and unrecognised-value cases above already exercise `resolveMode` end to end,
  // and a `grep` for a bare `'verdict'` default elsewhere in this file is the rest of the guard.
  it('loading gates the colour scale section — no radio renders before settings resolve', async () => {
    getSettings.mockReturnValue(new Promise(() => {})); // never resolves
    renderModal();
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByTestId('settings-map-colour-temp')).toBeNull();
    expect(screen.queryByTestId('settings-map-colour-verdict')).toBeNull();
  });

  it('reflects an explicitly saved temp scale', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, mapColourScale: 'temp' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-map-colour-temp')).toBeChecked());
    expect(screen.getByTestId('settings-map-colour-verdict')).not.toBeChecked();
  });

  it('the colour scale control is native, labelled radio inputs — keyboard-operable by construction', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-map-colour-verdict')).toBeInTheDocument());
    const verdictRadio = screen.getByTestId('settings-map-colour-verdict');
    const tempRadio = screen.getByTestId('settings-map-colour-temp');
    expect(verdictRadio.tagName).toBe('INPUT');
    expect(verdictRadio.type).toBe('radio');
    expect(tempRadio.type).toBe('radio');
    // Each control has an accessible label reachable via getByLabelText — proof it is labelled,
    // not just present. A bare `<div onClick>` would fail this.
    expect(screen.getByLabelText(/Verdict — red means/)).toBe(verdictRadio);
    expect(screen.getByLabelText(/Temperature — cold blue/)).toBe(tempRadio);
  });

  it('round-trips the colour scale choice through settingsApi', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, mapColourScale: 'verdict' });
    saveMapColourPreferences.mockResolvedValue({ ...PRO_SETTINGS, mapColourScale: 'temp' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-map-colour-temp')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('settings-map-colour-temp'));

    await waitFor(() => expect(saveMapColourPreferences).toHaveBeenCalledWith('temp'));
    await waitFor(() => expect(screen.getByTestId('settings-map-colour-temp')).toBeChecked());
  });


  it('shows an error and keeps the section usable when the save fails', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, mapColourScale: 'verdict' });
    saveMapColourPreferences.mockRejectedValue(new Error('fail'));
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-map-colour-temp')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('settings-map-colour-temp'));

    await waitFor(() => expect(screen.getByTestId('settings-colour-error')).toBeInTheDocument());
  });

  // ---------------------------------------------------------------------------
  // The dialog's answers: the page's only news of the reader's settings after mount
  //
  // `App`'s `useReaderSettings` reads the settings once, on mount, and after that takes what this
  // dialog reports: its own read on opening (`onSettingsRead`), the settings a successful save
  // leaves (`onHomeSaved` — a saved postcode, or a recalculation's new stamp) and a saved colour
  // (`onColourSaved`). It compares each with its record and moves a counter only on a real change.
  // So each report must carry the server's answer, arrive when that answer lands rather than when a
  // button is pressed, and not arrive at all for a close or a failed save.
  // ---------------------------------------------------------------------------

  const LOOKUP_MORPETH = {
    postcode: 'NE61 1AA', placeName: 'Morpeth', latitude: 55.17, longitude: -1.69,
  };
  /** A saved home with coordinates, which the radius slider needs before it will save. */
  const PRO_WITH_COORDS = {
    ...PRO_SETTINGS, homeLatitude: 55.95, homeLongitude: -3.19, localRadiusMiles: 22,
  };

  /** Renders the dialog with all three reports spied on. */
  function renderReporting() {
    const onSettingsRead = vi.fn();
    const onHomeSaved = vi.fn();
    const onColourSaved = vi.fn();
    return {
      ...renderModal({ onSettingsRead, onHomeSaved, onColourSaved }),
      onSettingsRead,
      onHomeSaved,
      onColourSaved,
    };
  }

  /** Looks a postcode up and presses Save — the dialog's one home-save path. */
  async function saveNewPostcode() {
    fireEvent.change(await screen.findByTestId('settings-postcode-input'), {
      target: { value: LOOKUP_MORPETH.postcode },
    });
    fireEvent.click(screen.getByTestId('settings-lookup-btn'));
    fireEvent.click(await screen.findByTestId('settings-save-home-btn'));
  }

  describe('onSettingsRead — the dialog\'s own read', () => {
    beforeEach(() => {
      getSettings.mockReset();
    });

    it('reports what it read, once, when the read lands', async () => {
      const read = deferred();
      getSettings.mockReturnValue(read.promise);
      const { onSettingsRead } = renderReporting();
      expect(onSettingsRead).not.toHaveBeenCalled();

      await land(() => read.resolve(PRO_SETTINGS));

      // Control: the form is drawn from it.
      expect(screen.getByTestId('settings-home-current')).toHaveTextContent('Edinburgh');
      expect(onSettingsRead).toHaveBeenCalledTimes(1);
      expect(onSettingsRead).toHaveBeenCalledWith(PRO_SETTINGS);
    });

    it('does not report a read that failed — a failure is no news of the settings', async () => {
      const read = deferred();
      getSettings.mockReturnValue(read.promise);
      const { onSettingsRead } = renderReporting();

      await land(() => read.reject(new Error('502')));

      expect(screen.getByText('Failed to load settings.')).toBeInTheDocument(); // control
      expect(onSettingsRead).not.toHaveBeenCalled();
    });

    it('reports through the newest callback, without reading again when the callback changes', async () => {
      // The read is made once, from a mount effect. Keyed on the callback it would read again on
      // every re-render that handed a fresh one; closed over the first, it would report to a stale one.
      const read = deferred();
      getSettings.mockReturnValue(read.promise);
      const first = vi.fn();
      const second = vi.fn();
      const { rerender } = render(<UserSettingsModal onClose={vi.fn()} onSettingsRead={first} />);
      rerender(<UserSettingsModal onClose={vi.fn()} onSettingsRead={second} />);

      await land(() => read.resolve(PRO_SETTINGS));

      expect(second).toHaveBeenCalledTimes(1);
      expect(first).not.toHaveBeenCalled();
      expect(getSettings).toHaveBeenCalledTimes(1);
    });
  });

  describe('onHomeSaved — the settings a successful save leaves', () => {
    beforeEach(() => {
      // Reset, not cleared: an implementation from another describe must not answer here.
      getSettings.mockReset().mockResolvedValue(PRO_SETTINGS);
      lookupPostcode.mockReset().mockResolvedValue(LOOKUP_MORPETH);
      saveHome.mockReset();
      refreshDriveTimes.mockReset();
      saveMapColourPreferences.mockReset();
    });

    it('reports a saved postcode once — when the save lands, not when Save is pressed', async () => {
      const save = deferred();
      saveHome.mockReturnValue(save.promise);
      const { onHomeSaved } = renderReporting();

      await saveNewPostcode();
      expect(onHomeSaved).not.toHaveBeenCalled();

      await land(() => save.resolve({
        ...PRO_SETTINGS, homePostcode: 'NE61 1AA', homeLatitude: 55.17, homeLongitude: -1.69,
        homePlaceName: null,
      }));

      expect(onHomeSaved).toHaveBeenCalledTimes(1);
      expect(onHomeSaved).toHaveBeenCalledWith(expect.objectContaining({
        homePostcode: 'NE61 1AA', homeLatitude: 55.17, homeLongitude: -1.69,
      }));
    });

    it('names the saved home from the lookup, since the save itself does not geocode', async () => {
      // Without it the page's tick line, and this dialog, read the bare postcode.
      const save = deferred();
      saveHome.mockReturnValue(save.promise);
      const { onHomeSaved } = renderReporting();

      await saveNewPostcode();
      await land(() => save.resolve({
        ...PRO_SETTINGS, homePostcode: 'NE61 1AA', homeLatitude: 55.17, homeLongitude: -1.69,
        homePlaceName: null,
      }));

      expect(onHomeSaved).toHaveBeenCalledWith(expect.objectContaining({ homePlaceName: 'Morpeth' }));
      expect(screen.getByTestId('settings-home-current')).toHaveTextContent('Morpeth');
    });

    it('reports a recalculation once, when it lands, as the same home with the new stamp', async () => {
      const recalc = deferred();
      refreshDriveTimes.mockReturnValue(recalc.promise);
      const { onHomeSaved } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-refresh-drive-btn'));
      expect(onHomeSaved).not.toHaveBeenCalled();

      await land(() => recalc.resolve({ locationsUpdated: 12, calculatedAt: '2026-04-02T10:00:00Z' }));

      expect(screen.getByText(/12 locations updated/)).toBeInTheDocument(); // control
      expect(onHomeSaved).toHaveBeenCalledTimes(1);
      expect(onHomeSaved).toHaveBeenCalledWith(expect.objectContaining({
        homePostcode: 'EH1 1BB', driveTimesCalculatedAt: '2026-04-02T10:00:00Z',
      }));
    });

    it('does not report a close — closing changes nothing', async () => {
      const { onClose, onHomeSaved } = renderReporting();
      await screen.findByTestId('settings-postcode-input');

      fireEvent.click(screen.getByRole('button', { name: 'Close' }));

      expect(onClose).toHaveBeenCalledTimes(1);
      expect(onHomeSaved).not.toHaveBeenCalled();
    });

    it('does not report a radius save — nothing on the page reads the radius', async () => {
      getSettings.mockResolvedValue(PRO_WITH_COORDS);
      const save = deferred();
      saveHome.mockReturnValue(save.promise);
      const { onHomeSaved } = renderReporting();

      fireEvent.mouseUp(await screen.findByTestId('settings-radius-slider'), { target: { value: '30' } });
      // The radius save really went out — without that this passes for a radius never committed.
      expect(saveHome).toHaveBeenCalledWith('EH1 1BB', 55.95, -3.19, 30);
      expect(screen.getByTestId('settings-radius-status')).toHaveTextContent('Saving…');

      await land(() => save.resolve({ ...PRO_WITH_COORDS, localRadiusMiles: 30 }));

      // Control: the save landed — its "Saving…" is gone.
      expect(screen.getByTestId('settings-radius-status').textContent).toBe('');
      expect(onHomeSaved).not.toHaveBeenCalled();
    });

    it('does not report a colour save — that reaches the page as a colour alone', async () => {
      const save = deferred();
      saveMapColourPreferences.mockReturnValue(save.promise);
      const { onHomeSaved, onColourSaved } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-map-colour-verdict'));
      await land(() => save.resolve({ ...PRO_SETTINGS, mapColourScale: 'verdict' }));

      expect(onColourSaved).toHaveBeenCalledTimes(1); // control: it landed and reported
      expect(onHomeSaved).not.toHaveBeenCalled();
    });

    it('does not report a postcode save that failed — nothing changed', async () => {
      const save = deferred();
      saveHome.mockReturnValue(save.promise);
      const { onHomeSaved } = renderReporting();

      await saveNewPostcode();
      expect(screen.getByTestId('settings-save-home-btn')).toHaveTextContent('Saving…');
      await land(() => save.reject(new Error('502')));

      // Control: the failure landed — the button is back from "Saving…" for a retry.
      expect(screen.getByTestId('settings-save-home-btn')).toHaveTextContent('Save');
      expect(screen.getByTestId('settings-save-home-btn')).not.toHaveTextContent('Saving');
      expect(onHomeSaved).not.toHaveBeenCalled();
    });

    it('does not report a recalculation that failed — nothing changed', async () => {
      const recalc = deferred();
      refreshDriveTimes.mockReturnValue(recalc.promise);
      const { onHomeSaved } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-refresh-drive-btn'));
      await land(() => recalc.reject({ response: { status: 500 } }));

      expect(screen.getByText('Something went wrong — please try again.')).toBeInTheDocument();
      expect(onHomeSaved).not.toHaveBeenCalled();
    });

    it('still reports a postcode save that lands after the dialog has closed', async () => {
      // Why the dialog reports from the save and not from the close: the reader can close it while
      // the save is still out, and the save's continuation outlives the dialog.
      const save = deferred();
      saveHome.mockReturnValue(save.promise);
      const { onHomeSaved, unmount } = renderReporting();

      await saveNewPostcode();
      unmount();
      expect(onHomeSaved).not.toHaveBeenCalled();

      await land(() => save.resolve({
        ...PRO_SETTINGS, homePostcode: 'NE61 1AA', homeLatitude: 55.17, homeLongitude: -1.69,
      }));

      expect(onHomeSaved).toHaveBeenCalledTimes(1);
    });
  });

  describe('onColourSaved — the saved scale, from the save\'s own response', () => {
    beforeEach(() => {
      getSettings.mockReset().mockResolvedValue({ ...PRO_SETTINGS, mapColourScale: 'temp' });
      saveMapColourPreferences.mockReset();
      saveHome.mockReset();
      lookupPostcode.mockReset().mockResolvedValue(LOOKUP_MORPETH);
    });

    it('reports the saved scale once — when the save lands, not on the click', async () => {
      const save = deferred();
      saveMapColourPreferences.mockReturnValue(save.promise);
      const { onColourSaved } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-map-colour-verdict'));
      expect(onColourSaved).not.toHaveBeenCalled();

      await land(() => save.resolve({ ...PRO_SETTINGS, mapColourScale: 'verdict' }));

      expect(onColourSaved).toHaveBeenCalledTimes(1);
      expect(onColourSaved).toHaveBeenCalledWith('verdict');
    });

    it('does not report a colour save that failed — nothing changed', async () => {
      const save = deferred();
      saveMapColourPreferences.mockReturnValue(save.promise);
      const { onColourSaved } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-map-colour-verdict'));
      await land(() => save.reject(new Error('502')));

      // The failure landed: the dialog says so.
      expect(screen.getByTestId('settings-colour-error')).toBeInTheDocument();
      expect(onColourSaved).not.toHaveBeenCalled();
    });

    it('does not report a close', async () => {
      const { onClose, onColourSaved } = renderReporting();
      await screen.findByTestId('settings-map-colour-verdict');

      fireEvent.click(screen.getByRole('button', { name: 'Close' }));

      expect(onClose).toHaveBeenCalledTimes(1);
      expect(onColourSaved).not.toHaveBeenCalled();
    });

    it('does not report a postcode save — a home is not a colour answer', async () => {
      const save = deferred();
      saveHome.mockReturnValue(save.promise);
      const { onColourSaved, onHomeSaved } = renderReporting();

      await saveNewPostcode();
      await land(() => save.resolve({
        ...PRO_SETTINGS, homePostcode: 'NE61 1AA', homeLatitude: 55.17, homeLongitude: -1.69,
        mapColourScale: 'verdict',
      }));

      expect(onHomeSaved).toHaveBeenCalledTimes(1); // control: the save landed and reported
      expect(onColourSaved).not.toHaveBeenCalled();
    });

    it('still reports a colour save that lands after the dialog has closed', async () => {
      const save = deferred();
      saveMapColourPreferences.mockReturnValue(save.promise);
      const { onColourSaved, unmount } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-map-colour-verdict'));
      unmount();
      expect(onColourSaved).not.toHaveBeenCalled();

      await land(() => save.resolve({ ...PRO_SETTINGS, mapColourScale: 'verdict' }));

      expect(onColourSaved).toHaveBeenCalledTimes(1);
    });
  });
});
