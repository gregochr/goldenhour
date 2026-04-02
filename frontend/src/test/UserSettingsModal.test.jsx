import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import UserSettingsModal from '../components/UserSettingsModal.jsx';

vi.mock('../api/settingsApi', () => ({
  getSettings: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
}));

import { getSettings, lookupPostcode, saveHome, refreshDriveTimes } from '../api/settingsApi';

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
    expect(screen.getByText('Loading...')).toBeInTheDocument();
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

  it('postcode input is disabled for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeDisabled());
  });

  it('lookup button is disabled for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-lookup-btn')).toBeDisabled());
  });

  it('refresh drive times button is disabled for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled());
  });

  it('shows upsell text for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByText('Upgrade to Pro for personalised drive times')).toBeInTheDocument());
  });

  it('home location wrapper is greyed out for LITE user', async () => {
    getSettings.mockResolvedValue(LITE_SETTINGS);
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-postcode-input')).toBeInTheDocument());
    const input = screen.getByTestId('settings-postcode-input');
    const wrapper = input.closest('.opacity-45');
    expect(wrapper).toBeInTheDocument();
    expect(wrapper).toHaveClass('pointer-events-none');
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
    await waitFor(() => expect(screen.getByText('Home Location')).toBeInTheDocument());
    const homeHeader = screen.getByText('Home Location');
    expect(homeHeader.closest('.opacity-45')).toBeNull();
    const driveHeader = screen.getByText('Drive Times');
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
    await waitFor(() => expect(saveHome).toHaveBeenCalledWith('EH1 1BB', 55.95, -3.19));
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
    await waitFor(() => expect(screen.getByText('Calculating drive times...')).toBeInTheDocument());
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

  it('shows "Set a home location first" when no home set', async () => {
    getSettings.mockResolvedValue({ ...PRO_SETTINGS, homePostcode: null });
    renderModal();
    await waitFor(() => expect(screen.getByText('Set a home location first')).toBeInTheDocument());
    expect(screen.getByTestId('settings-refresh-drive-btn')).toBeDisabled();
  });
});
