import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ModelSelectionView from '../components/ModelSelectionView.jsx';

// Mock the API modules
vi.mock('../api/modelsApi', () => ({
  getAvailableModels: vi.fn(),
  setActiveModel: vi.fn(),
  updateOptimisationStrategy: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: vi.fn(),
}));

import { getAvailableModels, setActiveModel, updateOptimisationStrategy } from '../api/modelsApi';
import { fetchLocations } from '../api/forecastApi';
import { useAuth } from '../context/AuthContext.jsx';

const MOCK_LOCATIONS = [
  { id: 1, name: 'Durham', enabled: true, locationType: ['LANDSCAPE'] },
  { id: 2, name: 'Bamburgh', enabled: true, locationType: ['SEASCAPE'] },
  { id: 3, name: 'Farne Islands', enabled: true, locationType: ['WILDLIFE'] },
  { id: 4, name: 'Disabled', enabled: false, locationType: ['LANDSCAPE'] },
];

const MOCK_DATA = {
  available: ['HAIKU', 'SONNET', 'OPUS'],
  configs: {
    VERY_SHORT_TERM: 'HAIKU',
    SHORT_TERM: 'HAIKU',
    LONG_TERM: 'HAIKU',
    BRIEFING_BEST_BET: 'HAIKU',
    AURORA_EVALUATION: 'HAIKU',
    BATCH_NEAR_TERM: 'SONNET',
    BATCH_FAR_TERM: 'HAIKU',
  },
  optimisationStrategies: {
    VERY_SHORT_TERM: [
      { strategyType: 'SKIP_LOW_RATED', enabled: true, paramValue: 3 },

      { strategyType: 'SKIP_EXISTING', enabled: false, paramValue: null },
      { strategyType: 'FORCE_IMMINENT', enabled: false, paramValue: null },
      { strategyType: 'FORCE_STALE', enabled: false, paramValue: null },
      { strategyType: 'EVALUATE_ALL', enabled: false, paramValue: null },
      { strategyType: 'NEXT_EVENT_ONLY', enabled: false, paramValue: null },
    ],
    SHORT_TERM: [
      { strategyType: 'SKIP_LOW_RATED', enabled: false, paramValue: 3 },

      { strategyType: 'SKIP_EXISTING', enabled: false, paramValue: null },
      { strategyType: 'FORCE_IMMINENT', enabled: false, paramValue: null },
      { strategyType: 'FORCE_STALE', enabled: false, paramValue: null },
      { strategyType: 'EVALUATE_ALL', enabled: false, paramValue: null },
      { strategyType: 'NEXT_EVENT_ONLY', enabled: false, paramValue: null },
    ],
    LONG_TERM: [
      { strategyType: 'SKIP_LOW_RATED', enabled: false, paramValue: 3 },

      { strategyType: 'SKIP_EXISTING', enabled: true, paramValue: null },
      { strategyType: 'FORCE_IMMINENT', enabled: false, paramValue: null },
      { strategyType: 'FORCE_STALE', enabled: false, paramValue: null },
      { strategyType: 'EVALUATE_ALL', enabled: false, paramValue: null },
      { strategyType: 'NEXT_EVENT_ONLY', enabled: false, paramValue: null },
    ],
  },
};

describe('ModelSelectionView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuth.mockReturnValue({ isAdmin: true });
    getAvailableModels.mockResolvedValue(MOCK_DATA);
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);
  });

  it('renders model cards and strategy toggles', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    expect(screen.getByText('Cost Optimisation')).toBeInTheDocument();
    expect(screen.getByTestId('cost-estimate-table')).toBeInTheDocument();
    // 2 enabled non-wildlife locations × 2 days × 2 targets = 8 calls
    expect(screen.getByText(/8 Claude calls per run/)).toBeInTheDocument();
  });

  it('renders config tabs', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-VERY_SHORT_TERM')).toBeInTheDocument();
    });

    expect(screen.getByTestId('config-tab-SHORT_TERM')).toBeInTheDocument();
    expect(screen.getByTestId('config-tab-LONG_TERM')).toBeInTheDocument();
    expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
  });

  it('shows strategy toggle buttons', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('strategy-toggle-SKIP_LOW_RATED')).toBeInTheDocument();
    });

    expect(screen.getByTestId('strategy-toggle-SKIP_EXISTING')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-toggle-EVALUATE_ALL')).toBeInTheDocument();
  });

  it('shows ON for enabled strategies', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('strategy-toggle-SKIP_LOW_RATED')).toHaveTextContent('ON');
    });

    expect(screen.getByTestId('strategy-toggle-SKIP_EXISTING')).toHaveTextContent('OFF');
  });

  it('shows conflict text for mutually exclusive strategies', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('strategy-row-SKIP_EXISTING')).toBeInTheDocument();
    });

    // SKIP_EXISTING should show conflict because SKIP_LOW_RATED is ON
    const skipExistingRow = screen.getByTestId('strategy-row-SKIP_EXISTING');
    expect(skipExistingRow).toHaveTextContent('Conflicts with');
  });

  it('calls API when strategy is toggled', async () => {
    updateOptimisationStrategy.mockResolvedValue({
      strategyType: 'FORCE_IMMINENT',
      enabled: true,
      paramValue: null,
    });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('strategy-toggle-FORCE_IMMINENT')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('strategy-toggle-FORCE_IMMINENT'));

    await waitFor(() => {
      expect(updateOptimisationStrategy).toHaveBeenCalledWith(
        'VERY_SHORT_TERM', 'FORCE_IMMINENT', true, null
      );
    });
  });

  it('shows parameter buttons for SKIP_LOW_RATED when enabled', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('param-SKIP_LOW_RATED-3')).toBeInTheDocument();
    });

    // Should show all 5 parameter buttons
    for (let i = 1; i <= 5; i++) {
      expect(screen.getByTestId(`param-SKIP_LOW_RATED-${i}`)).toBeInTheDocument();
    }
  });

  it('shows error on API failure', async () => {
    getAvailableModels.mockRejectedValue(new Error('Network error'));

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Error loading models')).toBeInTheDocument();
    });
  });

  it('shows loading state initially', () => {
    getAvailableModels.mockReturnValue(new Promise(() => {})); // never resolves
    render(<ModelSelectionView />);
    expect(screen.getByText('Loading models...')).toBeInTheDocument();
  });

  it('switches to Briefing tab and shows custom cost estimate', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BRIEFING_BEST_BET'));

    await waitFor(() => {
      expect(screen.getByText(/12 Claude calls per run/)).toBeInTheDocument();
    });

    // Briefing tab should not show strategy toggles (no strategies configured)
    expect(screen.queryByText('Cost Optimisation')).not.toBeInTheDocument();
  });

  it('switches to Aurora tab and shows custom cost estimate', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getByText(/35 Claude calls per run/)).toBeInTheDocument();
    });

    // Aurora tab should not show strategy toggles
    expect(screen.queryByText('Cost Optimisation')).not.toBeInTheDocument();
  });

  it('shows tab-specific description for Briefing model cards', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BRIEFING_BEST_BET'));

    await waitFor(() => {
      // Each model card shows the same tab-specific description (3 cards)
      expect(screen.getAllByText(/region-level triage data/)).toHaveLength(3);
    });
  });

  it('shows tab-specific description for Aurora model cards', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getAllByText(/aurora visibility conditions/)).toHaveLength(3);
    });
  });

  it('calls setActiveModel API when switching model on Briefing tab', async () => {
    setActiveModel.mockResolvedValue({ runType: 'BRIEFING_BEST_BET', active: 'OPUS' });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BRIEFING_BEST_BET'));

    await waitFor(() => {
      expect(screen.getByTestId('switch-BRIEFING_BEST_BET-OPUS')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('switch-BRIEFING_BEST_BET-OPUS'));

    await waitFor(() => {
      expect(setActiveModel).toHaveBeenCalledWith('BRIEFING_BEST_BET', 'OPUS');
    });
  });

  it('calls setActiveModel API when switching model on Aurora tab', async () => {
    setActiveModel.mockResolvedValue({ runType: 'AURORA_EVALUATION', active: 'SONNET' });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getByTestId('switch-AURORA_EVALUATION-SONNET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('switch-AURORA_EVALUATION-SONNET'));

    await waitFor(() => {
      expect(setActiveModel).toHaveBeenCalledWith('AURORA_EVALUATION', 'SONNET');
    });
  });

  // ── Scheduled Batch tab ──────────────────────────────────────────────────────

  it('Scheduled Batch tab is visible for ADMIN users', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });
  });

  it('Scheduled Batch tab is NOT visible for non-ADMIN users', async () => {
    useAuth.mockReturnValue({ isAdmin: false });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('config-tab-BATCH_NEAR_TERM')).not.toBeInTheDocument();
  });

  it('Scheduled Batch tab label shows active model in brackets', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    // MOCK_DATA.configs.BATCH_NEAR_TERM = 'SONNET'
    expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toHaveTextContent('(Sonnet)');
  });

  it('Scheduled Batch tab shows three distinct per-model descriptions', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));

    await waitFor(() => {
      expect(screen.getByText(/cost-efficient model for overnight batch runs/)).toBeInTheDocument();
    });

    expect(screen.getByText(/Recommended for scheduled batch runs/)).toBeInTheDocument();
    expect(screen.getByText(/Best reserved for key seasonal events only/)).toBeInTheDocument();

    // Each description appears exactly once — not the same text repeated across all three cards
    expect(screen.getAllByText(/cost-efficient model for overnight batch runs/)).toHaveLength(1);
    expect(screen.getAllByText(/Recommended for scheduled batch runs/)).toHaveLength(1);
  });

  it('Scheduled Batch tab shows informational panel', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));

    await waitFor(() => {
      expect(screen.getByTestId('batch-info-panel')).toBeInTheDocument();
    });

    expect(screen.getByText('How scheduled batch runs work')).toBeInTheDocument();
    expect(screen.getByText('SETTLED')).toBeInTheDocument();
    expect(screen.getByText('TRANSITIONAL')).toBeInTheDocument();
    expect(screen.getByText('UNSETTLED')).toBeInTheDocument();
  });

  it('informational panel is absent on non-batch tabs', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getByText(/35 Claude calls per run/)).toBeInTheDocument();
    });

    expect(screen.queryByTestId('batch-info-panel')).not.toBeInTheDocument();
  });

  it('Scheduled Batch tab shows batch cost table and no standard cost-estimate-table', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));

    await waitFor(() => {
      expect(screen.getByTestId('batch-cost-table')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('cost-estimate-table')).not.toBeInTheDocument();
    // Spot-check cost ranges are present
    expect(screen.getByText('~£0.0002')).toBeInTheDocument();
    expect(screen.getByText('~£0.02 – £0.08')).toBeInTheDocument();
  });

  it('batch cost table is absent on non-batch tabs', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    // Default tab is VERY_SHORT_TERM
    expect(screen.queryByTestId('batch-cost-table')).not.toBeInTheDocument();
  });

  it('model switch on Batch Near-Term tab sends BATCH_NEAR_TERM as runType', async () => {
    setActiveModel.mockResolvedValue({ runType: 'BATCH_NEAR_TERM', active: 'HAIKU' });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));

    await waitFor(() => {
      expect(screen.getByTestId('switch-BATCH_NEAR_TERM-HAIKU')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('switch-BATCH_NEAR_TERM-HAIKU'));

    await waitFor(() => {
      expect(setActiveModel).toHaveBeenCalledWith('BATCH_NEAR_TERM', 'HAIKU');
    });
  });
});
