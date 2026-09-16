import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, fireEvent, waitFor,
} from '@testing-library/react';
import UserSettingsModal from '../components/UserSettingsModal.jsx';
import { createColourSaveQueue, saveColourInTurn } from '../utils/colourSaveQueue.js';

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
    // was that a consumer stays free to place focus itself. Found by its name, because the name is
    // what a screen reader announces on landing — not the heading above the field. No postcode
    // saved: the only state in which the routes that ask for this field exist.
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal({ focusField: 'postcode' });
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Home location' })).toHaveFocus());
  });

  it('selects a postcode the read brings back, so typing replaces it rather than adding to it', async () => {
    // The routes that ask for this field exist only while the page knows of no saved postcode, but
    // the dialog reads the server afresh on opening — so a postcode saved since, on another device,
    // arrives already in the field.
    getSettings.mockResolvedValue(PRO_SETTINGS);
    renderModal({ focusField: 'postcode' });
    const field = await screen.findByRole('textbox', { name: 'Home location' });

    await waitFor(() => expect(field).toHaveFocus());
    expect(field).toHaveValue('EH1 1BB');
    expect(field.selectionStart).toBe(0);
    expect(field.selectionEnd).toBe('EH1 1BB'.length);
  });

  // ---------------------------------------------------------------------------
  // Accessible names
  //
  // The postcode field's only name was its placeholder: the name of last resort, an instruction
  // rather than a name, and out of sight whenever the field holds a value. It is the one control
  // the "set a postcode" nudge and the map's ⌂ exist to land a reader on, so the heading above it
  // names it. The radius slider had a real label, but its hint ran into it — "Local radiusHow far
  // counts as close to home." — so the label's first span names it and the hint describes it.
  // ---------------------------------------------------------------------------

  describe('accessible names — the postcode field and the radius slider', () => {
    beforeEach(() => {
      // Reset, not cleared: an implementation from another test must not answer here.
      getSettings.mockReset();
    });

    it('names the empty postcode field by its section heading, not by its placeholder', async () => {
      // Empty is the state the nudge and the ⌂ land on — they exist only with no postcode saved.
      getSettings.mockResolvedValue(LITE_SETTINGS);
      renderModal();

      const field = await screen.findByRole('textbox', { name: 'Home location' });

      expect(field).toHaveValue('');
      // The visible hint is untouched — and no longer the name.
      expect(field).toHaveAttribute('placeholder', 'Enter UK postcode');
      expect(screen.queryByRole('textbox', { name: 'Enter UK postcode' })).toBeNull();
    });

    it('keeps that name while the field holds a saved postcode, where the placeholder is hidden', async () => {
      getSettings.mockResolvedValue(PRO_SETTINGS);
      renderModal();

      expect(await screen.findByRole('textbox', { name: 'Home location' })).toHaveValue('EH1 1BB');
    });

    it('names the radius slider by its label alone, and describes it with the hint', async () => {
      getSettings.mockResolvedValue(PRO_SETTINGS);
      renderModal();

      const slider = await screen.findByRole('slider', { name: 'Local radius' });

      expect(slider).toHaveAccessibleDescription('How far counts as close to home.');
    });
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
  // The dialog's answers: how the page hears of the reader's settings after mount
  //
  // `App`'s `useReaderSettings` reads the settings once, on mount, and after that takes what this
  // dialog reports: its own read on opening (`startSettingsRead`, which numbers the read as it is
  // asked and returns what its answer is reported through), a saved postcode (`onHomeSaved`), a
  // recalculation's new stamp (`onDriveTimesRecalculated`) and a saved colour (`onColourSaved`). It
  // compares each with its record and moves a counter only on a real change. So each report must
  // carry the server's answer, arrive when that answer lands rather than when a button is pressed,
  // and not arrive at all for a close or a failed save.
  // ---------------------------------------------------------------------------

  const LOOKUP_MORPETH = {
    postcode: 'NE61 1AA', placeName: 'Morpeth', latitude: 55.17, longitude: -1.69,
  };
  /** A saved home with coordinates, which the radius slider needs before it will save. */
  const PRO_WITH_COORDS = {
    ...PRO_SETTINGS, homeLatitude: 55.95, homeLongitude: -3.19, localRadiusMiles: 22,
  };

  /** Renders the dialog with every report spied on; `reportRead` is what the read reports through. */
  function renderReporting() {
    const reportRead = vi.fn();
    const startSettingsRead = vi.fn(() => reportRead);
    const onHomeSaved = vi.fn();
    const onDriveTimesRecalculated = vi.fn();
    const onColourSaved = vi.fn();
    return {
      ...renderModal({
        startSettingsRead, onHomeSaved, onDriveTimesRecalculated, onColourSaved,
      }),
      startSettingsRead,
      reportRead,
      onHomeSaved,
      onDriveTimesRecalculated,
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

  describe('startSettingsRead — the dialog\'s own read', () => {
    beforeEach(() => {
      getSettings.mockReset();
    });

    it('starts its read as it is asked, and reports what it read once it lands', async () => {
      // Started before the answer, so the page can number the read by when it was ASKED.
      const read = deferred();
      getSettings.mockReturnValue(read.promise);
      const { startSettingsRead, reportRead } = renderReporting();
      expect(startSettingsRead).toHaveBeenCalledTimes(1);
      expect(reportRead).not.toHaveBeenCalled();

      await land(() => read.resolve(PRO_SETTINGS));

      // Control: the form is drawn from it.
      expect(screen.getByTestId('settings-home-current')).toHaveTextContent('Edinburgh');
      expect(reportRead).toHaveBeenCalledTimes(1);
      expect(reportRead).toHaveBeenCalledWith(PRO_SETTINGS);
      expect(startSettingsRead).toHaveBeenCalledTimes(1);
    });

    it('does not report a read that failed — a failure is no news of the settings', async () => {
      const read = deferred();
      getSettings.mockReturnValue(read.promise);
      const { reportRead } = renderReporting();

      await land(() => read.reject(new Error('502')));

      expect(screen.getByText('Failed to load settings.')).toBeInTheDocument(); // control
      expect(reportRead).not.toHaveBeenCalled();
    });

    it('reads once however often the callback changes', async () => {
      // The read is made once, from a mount effect. Keyed on the callback, it would read again on
      // every re-render that handed a fresh one — each read a new, and newer, answer to the page.
      const read = deferred();
      getSettings.mockReturnValue(read.promise);
      const report = vi.fn();
      const first = vi.fn(() => report);
      const second = vi.fn(() => vi.fn());
      const { rerender } = render(<UserSettingsModal onClose={vi.fn()} startSettingsRead={first} />);
      rerender(<UserSettingsModal onClose={vi.fn()} startSettingsRead={second} />);

      await land(() => read.resolve(PRO_SETTINGS));

      expect(getSettings).toHaveBeenCalledTimes(1);
      expect(first).toHaveBeenCalledTimes(1);
      expect(second).not.toHaveBeenCalled();
      expect(report).toHaveBeenCalledWith(PRO_SETTINGS);
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

    it('does not report a recalculation — that is a new stamp, not a home', async () => {
      // Reported as the dialog's copy of the home, it put back a home a save landing under the
      // spinner had just replaced.
      const recalc = deferred();
      refreshDriveTimes.mockReturnValue(recalc.promise);
      const { onHomeSaved, onDriveTimesRecalculated } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-refresh-drive-btn'));
      await land(() => recalc.resolve({ locationsUpdated: 12, calculatedAt: '2026-04-02T10:00:00Z' }));

      expect(onDriveTimesRecalculated).toHaveBeenCalledTimes(1); // control: it landed and reported
      expect(onHomeSaved).not.toHaveBeenCalled();
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

  describe('onDriveTimesRecalculated — a recalculation\'s new stamp', () => {
    beforeEach(() => {
      getSettings.mockReset().mockResolvedValue(PRO_SETTINGS);
      lookupPostcode.mockReset().mockResolvedValue(LOOKUP_MORPETH);
      saveHome.mockReset();
      refreshDriveTimes.mockReset();
    });

    it('reports the new stamp once — when the recalculation lands, not when it is pressed', async () => {
      const recalc = deferred();
      refreshDriveTimes.mockReturnValue(recalc.promise);
      const { onDriveTimesRecalculated } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-refresh-drive-btn'));
      expect(onDriveTimesRecalculated).not.toHaveBeenCalled();

      await land(() => recalc.resolve({ locationsUpdated: 12, calculatedAt: '2026-04-02T10:00:00Z' }));

      expect(screen.getByText(/12 locations updated/)).toBeInTheDocument(); // control
      expect(onDriveTimesRecalculated).toHaveBeenCalledTimes(1);
      expect(onDriveTimesRecalculated).toHaveBeenCalledWith('2026-04-02T10:00:00Z');
    });

    it('does not report a recalculation that failed — nothing changed', async () => {
      const recalc = deferred();
      refreshDriveTimes.mockReturnValue(recalc.promise);
      const { onDriveTimesRecalculated } = renderReporting();

      fireEvent.click(await screen.findByTestId('settings-refresh-drive-btn'));
      await land(() => recalc.reject({ response: { status: 500 } }));

      expect(screen.getByText('Something went wrong — please try again.')).toBeInTheDocument();
      expect(onDriveTimesRecalculated).not.toHaveBeenCalled();
    });

    it('does not report a postcode save — a move is not a recalculation', async () => {
      const save = deferred();
      saveHome.mockReturnValue(save.promise);
      const { onDriveTimesRecalculated, onHomeSaved } = renderReporting();

      await saveNewPostcode();
      await land(() => save.resolve({
        ...PRO_SETTINGS, homePostcode: 'NE61 1AA', homeLatitude: 55.17, homeLongitude: -1.69,
      }));

      expect(onHomeSaved).toHaveBeenCalledTimes(1); // control: the save landed and reported
      expect(onDriveTimesRecalculated).not.toHaveBeenCalled();
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

/**
 * Where focus goes when a step of the dialog takes away the control the reader pressed.
 *
 * <p>From an accessibility review of #842: a save removed its own Save button, the drive-time
 * refresh swapped the whole body for a spinner and back, and the colour radios sat in a fieldset
 * disabled while saving — each dropped the reader on `<body>` inside an open dialog, so the next
 * Tab walked the page behind the backdrop.
 *
 * <p>⚠️ <b>Two assertions per busy control, and the second is the one that bites.</b> Measured:
 * jsdom does NOT move focus off a control that becomes `disabled` (the browsers all do — Chromium
 * at once, WebKit and Firefox within a few frames), so `toHaveFocus()` alone passes against the
 * old `disabled={saving}`. `not.toBeDisabled()` is what fails there.
 *
 * <p>Every request is held by hand and settled inside an AWAITED `act`, so each landing has run
 * and committed before its assertion (`frontend-test-standards.md`, "A late response is only
 * dropped once it has landed"); the dialog's own mount frame is spent first, so no pending
 * `useDialogFocus` frame can move focus mid-test.
 */
describe('UserSettingsModal — where focus goes when a step removes what the reader pressed', () => {
  /** A promise the test settles by hand. */
  const deferred = () => {
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
    // The component awaits every one it is handed; this only keeps the teardown's rejection of one
    // a test never handed over from reading as an unhandled error.
    promise.catch(() => {});
    return { promise, resolve, reject };
  };
  /** Settles a held request and lets its continuation commit. */
  const land = async (fn) => { await act(async () => { fn(); }); };
  /**
   * Presses a control the way a keyboard reader does — focus first, then activate. `fireEvent.click`
   * alone never moves focus in jsdom, so a test that skipped this would start from `<body>` and
   * prove nothing about where a press leaves the reader.
   */
  const press = (el) => { el.focus(); fireEvent.click(el); };
  // Requests a test leaves open are settled when it ends, so none can land in the next one — by
  // REJECTING them, the one outcome every handler here takes without reading a payload.
  let open = [];
  const hold = () => { const d = deferred(); open.push(d); return d; };
  let elsewhere = null;

  beforeEach(() => {
    // Reset, not cleared: `mockClear` keeps implementations — `…Once` queues included — so a
    // queue one test left unconsumed would answer the next test's first request.
    [getSettings, lookupPostcode, saveHome, refreshDriveTimes, saveMapColourPreferences]
      .forEach((fn) => fn.mockReset());
    open = [];
  });
  afterEach(async () => {
    await act(async () => { open.forEach((d) => d.reject(new Error('test over'))); });
    elsewhere?.remove();
    elsewhere = null;
  });

  /** Renders the dialog on `settings` and spends its mount frame. */
  const openDialog = async (settings, props = {}) => {
    getSettings.mockResolvedValue(settings);
    renderModal(props);
    const dialog = screen.getByRole('dialog', { name: 'Settings' });
    // The mount frame focuses the root once — spent here so it cannot fire mid-test and supply a
    // position the code under test did not.
    await waitFor(() => expect(dialog).toHaveFocus());
    return dialog;
  };

  const LOOKUP = { postcode: 'NE1 7RU', placeName: 'Newcastle', latitude: 54.97, longitude: -1.61 };

  /** Opens on a PRO account with no home and gets as far as the lookup result's Save button. */
  const toSaveButton = async () => {
    await openDialog({ ...PRO_SETTINGS, homePostcode: null, homePlaceName: null });
    lookupPostcode.mockResolvedValue(LOOKUP);
    fireEvent.change(await screen.findByTestId('settings-postcode-input'), { target: { value: 'NE1 7RU' } });
    fireEvent.click(screen.getByRole('button', { name: 'Look up' }));
    return screen.findByRole('button', { name: 'Save' });
  };

  describe('saving a home', () => {
    it('⚠️ lands the reader on the line naming the new home, not on <body>', async () => {
      const save = await toSaveButton();
      const request = hold();
      saveHome.mockReturnValue(request.promise);
      press(save);

      await land(() => request.resolve({ ...PRO_SETTINGS, homePostcode: 'NE1 7RU', homePlaceName: 'Newcastle' }));

      expect(save.isConnected, 'precondition: the Save button went with the lookup result').toBe(false);
      const home = screen.getByTestId('settings-home-current');
      expect(home).toHaveTextContent('Newcastle');
      expect(home).toHaveFocus();
      // A place focus is PUT, never a tab stop.
      expect(home).toHaveAttribute('tabindex', '-1');
      // The indicator is an OUTLINE, which forced-colours mode keeps and a box-shadow ring loses.
      // jsdom paints nothing (`css: false`), so the classes are what can be pinned here; how they
      // render — in forced colours too — was measured in a browser.
      expect(home).toHaveClass('focus-visible:outline-2', 'focus-visible:outline-plex-gold');
      expect(home).not.toHaveClass('focus-visible:ring-2');
    });

    it('⚠️ lands the reader in the same commit that removes the Save button — not a task later', async () => {
      // Layout, not passive: in between, focus sits on <body> and a keypress lands there. A parent's
      // layout effect runs after its child's in the same commit, so it sees where that commit left
      // focus: the home line with a layout effect, <body> with a passive one.
      const seen = [];
      function Page() {
        const [saves, setSaves] = React.useState(0);
        React.useLayoutEffect(() => { if (saves > 0) seen.push(document.activeElement); }, [saves]);
        return <UserSettingsModal onClose={() => {}} onHomeSaved={() => setSaves((n) => n + 1)} />;
      }
      getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: null, homePlaceName: null });
      lookupPostcode.mockResolvedValue(LOOKUP);
      render(<Page />);
      const dialog = screen.getByRole('dialog', { name: 'Settings' });
      await waitFor(() => expect(dialog).toHaveFocus());
      fireEvent.change(await screen.findByTestId('settings-postcode-input'), { target: { value: 'NE1 7RU' } });
      fireEvent.click(screen.getByRole('button', { name: 'Look up' }));
      const save = await screen.findByRole('button', { name: 'Save' });
      const request = hold();
      saveHome.mockReturnValue(request.promise);
      press(save);

      await land(() => request.resolve({ ...PRO_SETTINGS, homePostcode: 'NE1 7RU', homePlaceName: 'Newcastle' }));

      expect(seen, 'control: the save landed, in one commit').toHaveLength(1);
      expect(seen[0]).toBe(screen.getByTestId('settings-home-current'));
    });

    it('⚠️ keeps the Save button live — aria-disabled, never disabled — while the save is out', async () => {
      const save = await toSaveButton();
      saveHome.mockReturnValue(hold().promise);
      press(save);

      expect(save).toHaveTextContent('Saving…');
      expect(save).not.toBeDisabled();
      // Busy LOOKS as `disabled` did (outside forced colours) — the dim and the cursor.
      expect(save).toHaveClass('aria-disabled:opacity-40', 'aria-disabled:cursor-not-allowed');
      expect(save).toHaveAttribute('aria-disabled', 'true');
      expect(save).toHaveFocus();
    });

    it('sends nothing for a second press while the save is out', async () => {
      const save = await toSaveButton();
      saveHome.mockReturnValue(hold().promise);
      press(save);
      fireEvent.click(save);

      expect(saveHome).toHaveBeenCalledTimes(1);
    });

    it('leaves the reader on a live Save button when the save fails, ready to try again', async () => {
      const save = await toSaveButton();
      const request = hold();
      saveHome.mockReturnValue(request.promise);
      press(save);

      await land(() => request.reject(new Error('fail')));

      expect(save).toBeInTheDocument();
      expect(save).toHaveFocus();
      // Both, because jsdom keeps focus on a control that becomes disabled and the browsers do not.
      expect(save).not.toBeDisabled();
      expect(save).not.toHaveAttribute('aria-disabled');
      expect(save).toHaveTextContent('Save');
    });

    it('⚠️ does not move a reader who has gone elsewhere in the dialog while it was saving', async () => {
      const save = await toSaveButton();
      const request = hold();
      saveHome.mockReturnValue(request.promise);
      press(save);
      const field = screen.getByTestId('settings-postcode-input');
      field.focus();              // Tabbed back to the field while the save was out — a choice

      await land(() => request.resolve({ ...PRO_SETTINGS, homePostcode: 'NE1 7RU', homePlaceName: 'Newcastle' }));

      expect(field).toHaveFocus();
    });

    it('lands the reader on the postcode field when what came back names no home to draw', async () => {
      // The fallback in the list. No line renders without a saved postcode, so the field they typed
      // it into is the next nearest thing to what they just did.
      const save = await toSaveButton();
      const request = hold();
      saveHome.mockReturnValue(request.promise);
      press(save);

      await land(() => request.resolve({ ...PRO_SETTINGS, homePostcode: null, homePlaceName: null }));

      expect(screen.queryByTestId('settings-home-current')).toBeNull();
      expect(screen.getByTestId('settings-postcode-input')).toHaveFocus();
    });
  });

  describe('looking a postcode up', () => {
    it('⚠️ keeps the Look up button live while the lookup is out, and a second press starts nothing', async () => {
      await openDialog({ ...PRO_SETTINGS, homePostcode: null, homePlaceName: null });
      fireEvent.change(await screen.findByTestId('settings-postcode-input'), { target: { value: 'NE1 7RU' } });
      const request = hold();
      lookupPostcode.mockReturnValue(request.promise);
      const lookUp = screen.getByRole('button', { name: 'Look up' });
      press(lookUp);

      expect(lookUp).toHaveTextContent('Looking up…');
      expect(lookUp).not.toBeDisabled();
      expect(lookUp).toHaveAttribute('aria-disabled', 'true');
      expect(lookUp).toHaveClass('aria-disabled:opacity-40', 'aria-disabled:cursor-not-allowed');
      expect(lookUp).toHaveFocus();
      fireEvent.click(lookUp);
      expect(lookupPostcode).toHaveBeenCalledTimes(1);

      await land(() => request.resolve(LOOKUP));

      expect(lookUp).toHaveFocus();
      // Both, because jsdom keeps focus on a control that becomes disabled and the browsers do not.
      expect(lookUp).not.toBeDisabled();
      expect(lookUp).not.toHaveAttribute('aria-disabled');
    });

    it('starts no second lookup for Enter pressed again in the field while one is out', async () => {
      // The field was never disabled, so repeated Enters used to race two lookups for one slot.
      await openDialog({ ...PRO_SETTINGS, homePostcode: null, homePlaceName: null });
      const field = await screen.findByTestId('settings-postcode-input');
      fireEvent.change(field, { target: { value: 'NE1 7RU' } });
      lookupPostcode.mockReturnValue(hold().promise);

      fireEvent.keyDown(field, { key: 'Enter' });
      fireEvent.keyDown(field, { key: 'Enter' });

      expect(lookupPostcode).toHaveBeenCalledTimes(1);
    });

    it('still refuses a press while the field is empty — the one state it is truly disabled for', async () => {
      await openDialog({ ...PRO_SETTINGS, homePostcode: null, homePlaceName: null });
      expect(await screen.findByRole('button', { name: 'Look up' })).toBeDisabled();
    });
  });

  describe('refreshing the drive times', () => {
    const DONE = { locationsUpdated: 12, calculatedAt: '2026-04-02T10:00:00Z' };

    /** Presses Refresh with its request held, and returns the handle. */
    const pressRefresh = async () => {
      await openDialog(PRO_SETTINGS);
      const request = hold();
      refreshDriveTimes.mockReturnValue(request.promise);
      const refresh = await screen.findByRole('button', { name: 'Refresh drive times' });
      press(refresh);
      return { request, refresh };
    };

    it('⚠️ lands the reader on the spinner\'s status line, not on <body>, for the whole wait', async () => {
      const { refresh } = await pressRefresh();

      expect(refresh.isConnected, 'precondition: the spinner replaced the whole body').toBe(false);
      const status = screen.getByTestId('settings-refresh-status');
      expect(status).toHaveTextContent('Calculating drive times…');
      expect(status).toHaveFocus();
      expect(status).toHaveAttribute('tabindex', '-1');
    });

    it('lands a POINTER press on the status line too — macOS Safari leaves focus on the dialog root', async () => {
      // Safari does not focus a button on click, so a mouse press leaves focus where the dialog's own
      // open put it: the root. The root survives the swap, so this is not `<body>` — but it is not a
      // place the reader chose either, and the status line is what they should hear.
      const dialog = await openDialog(PRO_SETTINGS);
      refreshDriveTimes.mockReturnValue(hold().promise);

      fireEvent.click(await screen.findByRole('button', { name: 'Refresh drive times' }));

      expect(dialog, 'precondition: the root survives the swap — same dialog, new content')
        .toBeInTheDocument();
      expect(screen.getByTestId('settings-refresh-status')).toHaveFocus();
    });

    it('⚠️ lands them on Back to settings when it finishes, described by the count it reports', async () => {
      const { request } = await pressRefresh();

      await land(() => request.resolve(DONE));

      const back = screen.getByRole('button', { name: 'Back to settings' });
      expect(back).toHaveFocus();
      expect(back).toHaveAccessibleDescription('Done — 12 locations updated');
    });

    it('⚠️ lands them on the "Last calculated" line when they go back — the line the refresh changed', async () => {
      const { request } = await pressRefresh();
      await land(() => request.resolve(DONE));
      const back = screen.getByRole('button', { name: 'Back to settings' });

      press(back);

      const calc = screen.getByTestId('settings-drive-calc-time');
      expect(calc).toHaveFocus();
      expect(calc).toHaveAttribute('tabindex', '-1');
      // Why not the button they started from: it is disabled now, the drive times being current.
      expect(screen.getByRole('button', { name: 'Refresh drive times' })).toBeDisabled();
    });

    it('lands them on the dialog itself when no line can be drawn and the button is disabled', async () => {
      // The last resort. A result with no timestamp draws no "Last calculated" line, and Refresh is
      // disabled once the drive times match the postcode — so neither listed target can take focus,
      // and the dialog root, where an open would have put them, does.
      const { request } = await pressRefresh();
      await land(() => request.resolve({ locationsUpdated: 3, calculatedAt: null }));

      press(screen.getByRole('button', { name: 'Back to settings' }));

      expect(screen.queryByTestId('settings-drive-calc-time')).toBeNull();
      expect(screen.getByRole('dialog', { name: 'Settings' })).toHaveFocus();
    });

    it('lands them on the same line when the result\'s backdrop is what takes them back', async () => {
      const { request } = await pressRefresh();
      await land(() => request.resolve(DONE));

      fireEvent.click(screen.getByTestId('settings-modal-backdrop'));

      expect(screen.getByTestId('settings-drive-calc-time')).toHaveFocus();
    });

    it('⚠️ lands them back on Refresh drive times when it fails, described by the error', async () => {
      const { request } = await pressRefresh();

      await land(() => request.reject({ response: { status: 429, data: { message: 'Rate limited' } } }));

      const refresh = screen.getByRole('button', { name: 'Refresh drive times' });
      expect(refresh).toHaveFocus();
      expect(refresh).toHaveAccessibleDescription('Rate limited');
    });

    it('does not pull back a reader who has left the dialog while it was calculating', async () => {
      // Not a trap — Tabbing out is supported — so a reader outside is where they chose to be.
      const { request } = await pressRefresh();
      elsewhere = document.createElement('button');
      document.body.appendChild(elsewhere);
      elsewhere.focus();

      await land(() => request.resolve(DONE));

      expect(elsewhere).toHaveFocus();
    });
  });

  describe('choosing a map colour', () => {
    /** The two radios, on an account whose saved scale is verdict. */
    const openOnVerdict = async () => {
      await openDialog({ ...PRO_SETTINGS, mapColourScale: 'verdict' });
      return {
        verdict: await screen.findByRole('radio', { name: /Verdict/ }),
        temp: screen.getByRole('radio', { name: /Temperature/ }),
      };
    };

    it('⚠️ keeps both radios live while a save is out, so the one being arrowed keeps focus', async () => {
      const { verdict, temp } = await openOnVerdict();
      saveMapColourPreferences.mockReturnValue(hold().promise);

      press(temp);

      expect(screen.getByTestId('settings-colour-status')).toHaveTextContent('Saving…');
      expect(temp).toBeEnabled();
      expect(verdict).toBeEnabled();
      expect(temp).toHaveFocus();
    });

    it('⚠️ sends a choice made mid-save AFTER the save in flight, never beside it', async () => {
      // Two requests out at once can commit in either order, and the server could end on the scale
      // the reader moved AWAY from.
      const { verdict, temp } = await openOnVerdict();
      const first = hold();
      const second = hold();
      saveMapColourPreferences
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise);

      press(temp);
      press(verdict);

      expect(saveMapColourPreferences).toHaveBeenCalledTimes(1);
      expect(verdict, 'the newest choice shows at once, whatever is still saving').toBeChecked();

      await land(() => first.resolve({ mapColourScale: 'temp' }));

      expect(saveMapColourPreferences).toHaveBeenCalledTimes(2);
      expect(saveMapColourPreferences).toHaveBeenLastCalledWith('verdict');
    });

    it('reports every save that lands to the page, one at a time, in the order the choices were made', async () => {
      // `onColourSaved` is how the map's ramp learns the scale. Each landed save names what the
      // server holds from that moment, and the queue is what keeps two reports from crossing.
      const onColourSaved = vi.fn();
      await openDialog({ ...PRO_SETTINGS, mapColourScale: 'verdict' }, { onColourSaved });
      const verdict = await screen.findByRole('radio', { name: /Verdict/ });
      const temp = screen.getByRole('radio', { name: /Temperature/ });
      const first = hold();
      const second = hold();
      saveMapColourPreferences
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise);
      press(temp);
      press(verdict);

      await land(() => first.resolve({ mapColourScale: 'temp' }));
      expect(onColourSaved.mock.calls).toEqual([['temp']]);

      await land(() => second.resolve({ mapColourScale: 'verdict' }));
      expect(onColourSaved.mock.calls).toEqual([['temp'], ['verdict']]);
    });

    it('collapses every choice made during one save into the newest', async () => {
      const { verdict, temp } = await openOnVerdict();
      const first = hold();
      saveMapColourPreferences.mockReturnValueOnce(first.promise).mockResolvedValue({});

      press(temp);
      press(verdict);
      press(temp);
      press(verdict);
      await land(() => first.resolve({}));

      expect(saveMapColourPreferences.mock.calls.map(([scale]) => scale)).toEqual(['temp', 'verdict']);
    });

    it('⚠️ writes the NEWEST of several mid-save choices last, not the first one queued', async () => {
      // temp is out; verdict, then temp again, arrive behind it. The first queued (verdict) is not
      // what the reader wants any more — with it written last, the server ends on the wrong scale.
      const { verdict, temp } = await openOnVerdict();
      const first = hold();
      saveMapColourPreferences.mockReturnValueOnce(first.promise).mockResolvedValue({});

      press(temp);
      press(verdict);
      press(temp);
      await land(() => first.resolve({}));

      expect(saveMapColourPreferences.mock.calls.map(([scale]) => scale)).toEqual(['temp', 'temp']);
      expect(temp).toBeChecked();
    });

    it('⚠️ sends a new choice once the save before it has landed — the line is free again', async () => {
      const { verdict, temp } = await openOnVerdict();
      const first = hold();
      const second = hold();
      saveMapColourPreferences
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise);
      press(temp);
      await land(() => first.resolve({}));
      expect(screen.getByTestId('settings-colour-status'), 'control: the first save landed')
        .toHaveTextContent('');

      press(verdict);

      expect(saveMapColourPreferences).toHaveBeenCalledTimes(2);
      expect(saveMapColourPreferences).toHaveBeenLastCalledWith('verdict');
      expect(screen.getByTestId('settings-colour-status')).toHaveTextContent('Saving…');
    });

    it('sends a retry after a failed save', async () => {
      const { verdict, temp } = await openOnVerdict();
      const first = hold();
      saveMapColourPreferences.mockReturnValueOnce(first.promise).mockResolvedValue({});
      press(temp);
      await land(() => first.reject(new Error('502')));
      expect(screen.getByTestId('settings-colour-error'), 'control: the failure landed').toBeInTheDocument();

      press(verdict);
      await land(() => {});

      expect(saveMapColourPreferences).toHaveBeenCalledTimes(2);
      expect(screen.queryByTestId('settings-colour-error')).toBeNull();
    });

    it('reports a waiting save\'s OWN scale when its response names none', async () => {
      const onColourSaved = vi.fn();
      await openDialog({ ...PRO_SETTINGS, mapColourScale: 'verdict' }, { onColourSaved });
      const verdict = await screen.findByRole('radio', { name: /Verdict/ });
      const temp = screen.getByRole('radio', { name: /Temperature/ });
      const first = hold();
      const second = hold();
      saveMapColourPreferences
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise);
      press(temp);
      press(verdict);

      await land(() => first.resolve({}));
      await land(() => second.resolve({}));

      expect(onColourSaved.mock.calls).toEqual([['temp'], ['verdict']]);
    });

    it('opens on a choice still in the page\'s line, not on the server\'s older answer', async () => {
      // A dialog closed with a choice still waiting, then reopened: the server has not been told yet
      // and answers with the old scale, which the line is about to overwrite.
      const colourSaveQueue = createColourSaveQueue();
      const earlier = hold();
      saveColourInTurn(colourSaveQueue, 'temp', { save: () => earlier.promise });

      await openDialog({ ...PRO_SETTINGS, mapColourScale: 'verdict' }, { colourSaveQueue });

      expect(await screen.findByRole('radio', { name: /Temperature/ })).toBeChecked();
      expect(screen.getByRole('radio', { name: /Verdict/ })).not.toBeChecked();
    });

    /** A line holding a choice an earlier opening made — temp, still saving. */
    const lineWithCarriedTemp = () => {
      const colourSaveQueue = createColourSaveQueue();
      const earlier = hold();
      saveColourInTurn(colourSaveQueue, 'temp', { save: () => earlier.promise });
      return { colourSaveQueue, earlier };
    };

    it('⚠️ says "Saving…" for a choice an earlier opening left in the line, until it lands', async () => {
      // This opening shows that choice, so it says how the choice is going, as the opening it was
      // made in would have.
      const { colourSaveQueue, earlier } = lineWithCarriedTemp();
      await openDialog({ ...PRO_SETTINGS, mapColourScale: 'verdict' }, { colourSaveQueue });
      const status = await screen.findByTestId('settings-colour-status');
      expect(status).toHaveTextContent('Saving…');

      await land(() => earlier.resolve({}));

      expect(status).toHaveTextContent('');
      expect(screen.queryByTestId('settings-colour-error')).toBeNull();
    });

    it('shows the error when a choice an earlier opening left in the line fails', async () => {
      const { colourSaveQueue, earlier } = lineWithCarriedTemp();
      await openDialog({ ...PRO_SETTINGS, mapColourScale: 'verdict' }, { colourSaveQueue });
      const status = await screen.findByTestId('settings-colour-status');
      expect(status, 'control: the carried choice is followed').toHaveTextContent('Saving…');

      await land(() => earlier.reject(new Error('502')));

      expect(status).toHaveTextContent('');
      expect(screen.getByTestId('settings-colour-error')).toBeInTheDocument();
    });

    it('⚠️ hands the status to a choice made here, whatever the carried one does after', async () => {
      const { colourSaveQueue, earlier } = lineWithCarriedTemp();
      await openDialog({ ...PRO_SETTINGS, mapColourScale: 'verdict' }, { colourSaveQueue });
      const verdict = await screen.findByRole('radio', { name: /Verdict/ });
      const mine = hold();
      saveMapColourPreferences.mockReturnValueOnce(mine.promise);
      press(verdict);

      await land(() => earlier.reject(new Error('502')));

      expect(saveMapColourPreferences, 'control: the choice made here went out behind it')
        .toHaveBeenCalledWith('verdict');
      expect(screen.getByTestId('settings-colour-status'), 'this opening\'s own save is still out')
        .toHaveTextContent('Saving…');
      expect(screen.queryByTestId('settings-colour-error'), 'the carried failure is not this opening\'s to show')
        .toBeNull();

      await land(() => mine.resolve({}));

      expect(screen.getByTestId('settings-colour-status')).toHaveTextContent('');
    });

    it('⚠️ shows a save that landed while its read was out, not the read\'s older answer', async () => {
      // The likelier order on a reopen: the server geocodes on every GET, so the save lands first,
      // and a read taken before the save committed answers with the scale it replaced.
      const { colourSaveQueue, earlier } = lineWithCarriedTemp();
      const read = hold();
      getSettings.mockReturnValue(read.promise);
      renderModal({ colourSaveQueue });
      await land(() => earlier.resolve({}));
      expect(colourSaveQueue.pending, 'precondition: nothing is left in the line').toBeNull();

      await land(() => read.resolve({ ...PRO_SETTINGS, mapColourScale: 'verdict' }));

      expect(await screen.findByRole('radio', { name: /Temperature/ })).toBeChecked();
      expect(screen.getByRole('radio', { name: /Verdict/ })).not.toBeChecked();
      expect(screen.getByTestId('settings-colour-status'), 'nothing left to follow').toHaveTextContent('');
    });

    it('but lets a read asked after the save landed answer for itself — it is the newer word', async () => {
      const { colourSaveQueue, earlier } = lineWithCarriedTemp();
      await land(() => earlier.resolve({}));

      await openDialog({ ...PRO_SETTINGS, mapColourScale: 'verdict' }, { colourSaveQueue });

      expect(await screen.findByRole('radio', { name: /Verdict/ })).toBeChecked();
    });

    it('says "Saving…" until the queued save has landed, not just the first', async () => {
      const { verdict, temp } = await openOnVerdict();
      const first = hold();
      const second = hold();
      saveMapColourPreferences
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise);
      const status = screen.getByTestId('settings-colour-status');
      press(temp);
      press(verdict);

      await land(() => first.resolve({}));
      expect(status).toHaveTextContent('Saving…');

      await land(() => second.resolve({}));
      expect(status).toHaveTextContent('');
    });

    it('shows no error when a failed save is superseded by one that lands', async () => {
      const { verdict, temp } = await openOnVerdict();
      const first = hold();
      const second = hold();
      saveMapColourPreferences
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise);
      press(temp);
      press(verdict);

      await land(() => first.reject(new Error('fail')));
      await land(() => second.resolve({}));

      expect(screen.queryByTestId('settings-colour-error')).toBeNull();
    });

    it('shows the error when the newest save is the one that fails', async () => {
      const { verdict, temp } = await openOnVerdict();
      const first = hold();
      const second = hold();
      saveMapColourPreferences
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise);
      press(temp);
      press(verdict);

      await land(() => first.resolve({}));
      await land(() => second.reject(new Error('fail')));

      expect(screen.getByTestId('settings-colour-error')).toBeInTheDocument();
    });
  });
});
