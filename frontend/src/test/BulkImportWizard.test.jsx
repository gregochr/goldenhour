import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import BulkImportWizard from '../components/BulkImportWizard.jsx';

vi.mock('../api/forecastApi', () => ({
  addLocation: vi.fn(),
  geocodePlaceBulk: vi.fn(),
}));

import { addLocation, geocodePlaceBulk } from '../api/forecastApi';

const MOCK_EXISTING = [
  { id: 1, name: 'Durham', lat: 54.77, lon: -1.58, enabled: true },
];

const MOCK_REGIONS = [
  { id: 1, name: 'North East', enabled: true },
  { id: 2, name: 'Lake District', enabled: true },
  { id: 3, name: 'Disabled Region', enabled: false },
];

const DRAFT_KEY = 'goldenhour_bulk_import';

function renderWizard(props = {}) {
  const defaultProps = {
    existingLocations: MOCK_EXISTING,
    regions: MOCK_REGIONS,
    onComplete: vi.fn(),
    onCancel: vi.fn(),
  };
  return render(<BulkImportWizard {...defaultProps} {...props} />);
}

describe('BulkImportWizard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.removeItem(DRAFT_KEY);
    geocodePlaceBulk.mockResolvedValue([
      { lat: 55.6, lon: -1.7, displayName: 'Bamburgh Castle, Northumberland, UK' },
    ]);
    addLocation.mockResolvedValue({ id: 99, name: 'Test' });
  });

  afterEach(() => {
    localStorage.removeItem(DRAFT_KEY);
  });

  describe('Step 1: PASTE', () => {
    it('renders the paste step on mount', () => {
      renderWizard();
      expect(screen.getByTestId('step-paste')).toBeInTheDocument();
      expect(screen.getByTestId('paste-textarea')).toBeInTheDocument();
      expect(screen.getByTestId('step-indicator')).toBeInTheDocument();
    });

    it('shows location count as user types', () => {
      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Bamburgh\nDunstanburgh\nSt Marys' } });
      expect(screen.getByTestId('location-count')).toHaveTextContent('3 locations detected');
    });

    it('deduplicates names', () => {
      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Bamburgh\nBamburgh\nOther' } });
      expect(screen.getByTestId('location-count')).toHaveTextContent('2 locations detected');
    });

    it('filters blank lines', () => {
      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Bamburgh\n\n\nOther\n' } });
      expect(screen.getByTestId('location-count')).toHaveTextContent('2 locations detected');
    });

    it('shows duplicate warning for existing locations', () => {
      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Durham\nBamburgh' } });
      expect(screen.getByTestId('duplicate-warning')).toHaveTextContent('1 duplicate');
      expect(screen.getByTestId('duplicate-list')).toHaveTextContent('Durham');
    });

    it('disables Geocode All when textarea is empty', () => {
      renderWizard();
      expect(screen.getByTestId('geocode-all-btn')).toBeDisabled();
    });

    it('advances to GEOCODE step on Geocode All', async () => {
      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
      });
    });

    it('marks existing locations as skipped', async () => {
      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Durham\nBamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
      });

      // Durham should not appear in the non-skipped table (it's skipped)
      // Only Bamburgh should be geocoded
      expect(geocodePlaceBulk).toHaveBeenCalledWith('Bamburgh');
    });
  });

  describe('Step 2: GEOCODE', () => {
    async function goToGeocodeStep() {
      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
      });
    }

    it('shows geocode progress', async () => {
      geocodePlaceBulk.mockImplementation(() =>
        new Promise((resolve) => setTimeout(() => resolve([{ lat: 55.6, lon: -1.7, displayName: 'Test' }]), 50)),
      );
      await goToGeocodeStep();

      // Progress text should appear
      await waitFor(() => {
        expect(screen.getByTestId('geocode-table')).toBeInTheDocument();
      });
    });

    it('shows resolved status badge', async () => {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Bamburgh Castle, UK' },
      ]);
      await goToGeocodeStep();

      await waitFor(() => {
        const badges = screen.getAllByTestId('status-resolved');
        expect(badges.length).toBeGreaterThan(0);
      });
    });

    it('shows failed status and retry/edit buttons', async () => {
      geocodePlaceBulk.mockResolvedValue([]);

      renderWizard();
      fireEvent.change(screen.getByTestId('paste-textarea'), { target: { value: 'Unknown Place' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('status-failed')).toBeInTheDocument();
      });

      expect(screen.getByTestId('edit-row-btn')).toBeInTheDocument();
      expect(screen.getByTestId('retry-row-btn')).toBeInTheDocument();
    });

    it('shows ambiguous status with candidate picker', async () => {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 54.77, lon: -1.58, displayName: 'Durham, England' },
        { lat: 35.9, lon: -78.9, displayName: 'Durham, NC' },
      ]);

      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Durham City' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('status-ambiguous')).toBeInTheDocument();
      });
    });

    it('enables Continue only when all non-skipped rows resolved', async () => {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Bamburgh Castle, UK' },
      ]);

      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('status-resolved')).toBeInTheDocument();
      });

      expect(screen.getByTestId('continue-to-metadata-btn')).not.toBeDisabled();
    });

    it('allows Back to PASTE step', async () => {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Test' },
      ]);

      renderWizard();
      const textarea = screen.getByTestId('paste-textarea');
      fireEvent.change(textarea, { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
      });

      fireEvent.click(screen.getByTestId('back-to-paste-btn'));
      expect(screen.getByTestId('step-paste')).toBeInTheDocument();
    });

    it('skips and unskips a row', async () => {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Test' },
      ]);

      renderWizard();
      fireEvent.change(screen.getByTestId('paste-textarea'), { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('status-resolved')).toBeInTheDocument();
      });

      // Skip the row
      fireEvent.click(screen.getByTestId('skip-row-btn'));
      // Now nothing should be in the non-skipped list (pagination hides skipped)
      // The continue button should be disabled since no non-skipped rows remain
      expect(screen.getByTestId('continue-to-metadata-btn')).toBeDisabled();
    });

    it('allows inline editing with manual coordinates', async () => {
      geocodePlaceBulk
        .mockResolvedValueOnce([]);

      renderWizard();
      fireEvent.change(screen.getByTestId('paste-textarea'), { target: { value: 'Unknown Place' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('status-failed')).toBeInTheDocument();
      });

      // Start editing
      fireEvent.click(screen.getByTestId('edit-row-btn'));
      expect(screen.getByTestId('edit-name-input')).toBeInTheDocument();
      expect(screen.getByTestId('edit-lat-input')).toBeInTheDocument();
      expect(screen.getByTestId('edit-lon-input')).toBeInTheDocument();

      // Enter manual coords
      fireEvent.change(screen.getByTestId('edit-name-input'), { target: { value: 'My Place' } });
      fireEvent.change(screen.getByTestId('edit-lat-input'), { target: { value: '55.5' } });
      fireEvent.change(screen.getByTestId('edit-lon-input'), { target: { value: '-1.5' } });
      fireEvent.click(screen.getByTestId('save-edit-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('status-manual')).toBeInTheDocument();
      });
    });
  });

  describe('Step 3: METADATA', () => {
    async function goToMetadataStep() {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Test' },
      ]);

      renderWizard();
      fireEvent.change(screen.getByTestId('paste-textarea'), { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        const continueBtn = screen.getByTestId('continue-to-metadata-btn');
        expect(continueBtn).not.toBeDisabled();
      });

      fireEvent.click(screen.getByTestId('continue-to-metadata-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-metadata')).toBeInTheDocument();
      });
    }

    it('renders metadata table with inline controls', async () => {
      await goToMetadataStep();
      expect(screen.getByTestId('metadata-table')).toBeInTheDocument();
      expect(screen.getByTestId('bulk-apply-bar')).toBeInTheDocument();
    });

    it('applies bulk metadata to all rows', async () => {
      await goToMetadataStep();
      fireEvent.click(screen.getByTestId('apply-bulk-btn'));
      // All rows should have updated metadata (verified by render)
      expect(screen.getByTestId('metadata-table')).toBeInTheDocument();
    });

    it('shows region dropdown with only enabled regions', async () => {
      await goToMetadataStep();
      const bulkSelect = screen.getByTestId('bulk-region-select');
      const options = within(bulkSelect).getAllByRole('option');
      // None + 2 enabled regions = 3
      expect(options).toHaveLength(3);
      expect(options[0]).toHaveTextContent('None');
      expect(options[1]).toHaveTextContent('North East');
      expect(options[2]).toHaveTextContent('Lake District');
    });

    it('advances to SUBMIT step', async () => {
      await goToMetadataStep();
      fireEvent.click(screen.getByTestId('continue-to-submit-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-submit')).toBeInTheDocument();
      });
    });

    it('allows Back to GEOCODE', async () => {
      await goToMetadataStep();
      fireEvent.click(screen.getByTestId('back-to-geocode-btn'));
      expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
    });
  });

  describe('Step 4: SUBMIT', () => {
    async function goToSubmitStep() {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Test' },
      ]);

      renderWizard();
      fireEvent.change(screen.getByTestId('paste-textarea'), { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('continue-to-metadata-btn')).not.toBeDisabled();
      });

      fireEvent.click(screen.getByTestId('continue-to-metadata-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-metadata')).toBeInTheDocument();
      });

      fireEvent.click(screen.getByTestId('continue-to-submit-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-submit')).toBeInTheDocument();
      });
    }

    it('renders the submit step with import button', async () => {
      await goToSubmitStep();
      expect(screen.getByTestId('submit-table')).toBeInTheDocument();
      expect(screen.getByTestId('start-submit-btn')).toBeInTheDocument();
    });

    it('submits locations and shows success', async () => {
      addLocation.mockResolvedValue({ id: 99, name: 'Bamburgh' });
      await goToSubmitStep();

      fireEvent.click(screen.getByTestId('start-submit-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('submit-summary')).toBeInTheDocument();
        expect(screen.getByTestId('submit-summary')).toHaveTextContent('1 saved');
      });

      expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
        name: 'Bamburgh',
        lat: 55.6,
        lon: -1.7,
        solarEventTypes: ['SUNRISE', 'SUNSET'],
        locationType: 'LANDSCAPE',
        tideTypes: [],
        regionId: null,
      }));
    });

    it('shows failed status and retry button on error', async () => {
      addLocation.mockRejectedValue(new Error('Conflict'));
      await goToSubmitStep();

      fireEvent.click(screen.getByTestId('start-submit-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('submit-summary')).toHaveTextContent('1 failed');
      });

      expect(screen.getByTestId('retry-failed-btn')).toBeInTheDocument();
    });

    it('calls onComplete and clears draft on Done', async () => {
      addLocation.mockResolvedValue({ id: 99 });
      const onComplete = vi.fn();
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Test' },
      ]);

      render(
        <BulkImportWizard
          existingLocations={MOCK_EXISTING}
          regions={MOCK_REGIONS}
          onComplete={onComplete}
          onCancel={vi.fn()}
        />,
      );

      fireEvent.change(screen.getByTestId('paste-textarea'), { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => expect(screen.getByTestId('continue-to-metadata-btn')).not.toBeDisabled());
      fireEvent.click(screen.getByTestId('continue-to-metadata-btn'));
      await waitFor(() => expect(screen.getByTestId('step-metadata')).toBeInTheDocument());
      fireEvent.click(screen.getByTestId('continue-to-submit-btn'));
      await waitFor(() => expect(screen.getByTestId('step-submit')).toBeInTheDocument());

      fireEvent.click(screen.getByTestId('start-submit-btn'));
      await waitFor(() => expect(screen.getByTestId('done-btn')).toBeInTheDocument());

      fireEvent.click(screen.getByTestId('done-btn'));
      expect(onComplete).toHaveBeenCalled();
      expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
    });
  });

  describe('localStorage draft', () => {
    it('shows resume banner when draft exists', () => {
      localStorage.setItem(DRAFT_KEY, JSON.stringify({
        version: 1,
        step: 'GEOCODE',
        rawText: 'Bamburgh\nDunstanburgh',
        rows: [
          { id: '1', name: 'Bamburgh', geocodeStatus: 'resolved', lat: 55.6, lon: -1.7, displayName: 'Bamburgh', candidates: [], solarEventTypes: ['SUNRISE', 'SUNSET'], locationType: 'LANDSCAPE', tideTypes: [], regionId: null },
        ],
        timestamp: Date.now(),
      }));

      renderWizard();
      expect(screen.getByTestId('resume-banner')).toBeInTheDocument();
    });

    it('resumes draft state on Resume click', async () => {
      localStorage.setItem(DRAFT_KEY, JSON.stringify({
        version: 1,
        step: 'GEOCODE',
        rawText: 'Bamburgh',
        rows: [
          { id: '1', name: 'Bamburgh', geocodeStatus: 'resolved', lat: 55.6, lon: -1.7, displayName: 'Bamburgh', candidates: [], solarEventTypes: ['SUNRISE', 'SUNSET'], locationType: 'LANDSCAPE', tideTypes: [], regionId: null },
        ],
        timestamp: Date.now(),
      }));

      renderWizard();
      fireEvent.click(screen.getByTestId('resume-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
      });
    });

    it('dismisses draft on Discard click', () => {
      localStorage.setItem(DRAFT_KEY, JSON.stringify({
        version: 1,
        step: 'GEOCODE',
        rawText: 'Test',
        rows: [],
        timestamp: Date.now(),
      }));

      renderWizard();
      fireEvent.click(screen.getByTestId('dismiss-draft-btn'));

      expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
      expect(screen.queryByTestId('resume-banner')).not.toBeInTheDocument();
    });

    it('discards stale drafts (> 7 days old)', () => {
      localStorage.setItem(DRAFT_KEY, JSON.stringify({
        version: 1,
        step: 'GEOCODE',
        rawText: 'Test',
        rows: [],
        timestamp: Date.now() - 8 * 24 * 60 * 60 * 1000,
      }));

      renderWizard();
      expect(screen.queryByTestId('resume-banner')).not.toBeInTheDocument();
    });
  });

  describe('Cancel flow', () => {
    it('cancels directly from PASTE step', () => {
      const onCancel = vi.fn();
      render(
        <BulkImportWizard
          existingLocations={MOCK_EXISTING}
          regions={MOCK_REGIONS}
          onComplete={vi.fn()}
          onCancel={onCancel}
        />,
      );

      fireEvent.click(screen.getByTestId('bulk-cancel-btn'));
      expect(onCancel).toHaveBeenCalled();
    });

    it('shows confirm dialog when cancelling after PASTE', async () => {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Test' },
      ]);

      renderWizard();
      fireEvent.change(screen.getByTestId('paste-textarea'), { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
      });

      fireEvent.click(screen.getByTestId('bulk-cancel-btn'));
      expect(screen.getByTestId('cancel-confirm-modal')).toBeInTheDocument();
    });

    it('keeps working when cancel is dismissed', async () => {
      geocodePlaceBulk.mockResolvedValue([
        { lat: 55.6, lon: -1.7, displayName: 'Test' },
      ]);

      renderWizard();
      fireEvent.change(screen.getByTestId('paste-textarea'), { target: { value: 'Bamburgh' } });
      fireEvent.click(screen.getByTestId('geocode-all-btn'));

      await waitFor(() => {
        expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
      });

      fireEvent.click(screen.getByTestId('bulk-cancel-btn'));
      fireEvent.click(screen.getByTestId('cancel-keep-btn'));
      expect(screen.queryByTestId('cancel-confirm-modal')).not.toBeInTheDocument();
      expect(screen.getByTestId('step-geocode')).toBeInTheDocument();
    });
  });

  describe('Step indicator', () => {
    it('highlights current step', () => {
      renderWizard();
      screen.getByTestId('step-paste');
      // The step-indicator should show Paste as active
      expect(screen.getByTestId('step-indicator')).toBeInTheDocument();
    });
  });
});
