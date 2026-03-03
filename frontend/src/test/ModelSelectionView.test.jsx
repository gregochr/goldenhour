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

import { getAvailableModels, updateOptimisationStrategy } from '../api/modelsApi';
import { fetchLocations } from '../api/forecastApi';

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
  },
  optimisationStrategies: {
    VERY_SHORT_TERM: [
      { strategyType: 'SKIP_LOW_RATED', enabled: true, paramValue: 3 },
      { strategyType: 'REQUIRE_PRIOR', enabled: true, paramValue: null },
      { strategyType: 'SKIP_EXISTING', enabled: false, paramValue: null },
      { strategyType: 'FORCE_IMMINENT', enabled: false, paramValue: null },
      { strategyType: 'FORCE_STALE', enabled: false, paramValue: null },
      { strategyType: 'EVALUATE_ALL', enabled: false, paramValue: null },
    ],
    SHORT_TERM: [
      { strategyType: 'SKIP_LOW_RATED', enabled: false, paramValue: 3 },
      { strategyType: 'REQUIRE_PRIOR', enabled: false, paramValue: null },
      { strategyType: 'SKIP_EXISTING', enabled: false, paramValue: null },
      { strategyType: 'FORCE_IMMINENT', enabled: false, paramValue: null },
      { strategyType: 'FORCE_STALE', enabled: false, paramValue: null },
      { strategyType: 'EVALUATE_ALL', enabled: false, paramValue: null },
    ],
    LONG_TERM: [
      { strategyType: 'SKIP_LOW_RATED', enabled: false, paramValue: 3 },
      { strategyType: 'REQUIRE_PRIOR', enabled: false, paramValue: null },
      { strategyType: 'SKIP_EXISTING', enabled: true, paramValue: null },
      { strategyType: 'FORCE_IMMINENT', enabled: false, paramValue: null },
      { strategyType: 'FORCE_STALE', enabled: false, paramValue: null },
      { strategyType: 'EVALUATE_ALL', enabled: false, paramValue: null },
    ],
  },
};

describe('ModelSelectionView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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
  });

  it('shows strategy toggle buttons', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('strategy-toggle-SKIP_LOW_RATED')).toBeInTheDocument();
    });

    expect(screen.getByTestId('strategy-toggle-REQUIRE_PRIOR')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-toggle-SKIP_EXISTING')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-toggle-EVALUATE_ALL')).toBeInTheDocument();
  });

  it('shows ON for enabled strategies', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('strategy-toggle-SKIP_LOW_RATED')).toHaveTextContent('ON');
    });

    expect(screen.getByTestId('strategy-toggle-REQUIRE_PRIOR')).toHaveTextContent('ON');
    expect(screen.getByTestId('strategy-toggle-SKIP_EXISTING')).toHaveTextContent('OFF');
  });

  it('shows conflict text for mutually exclusive strategies', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('strategy-row-SKIP_EXISTING')).toBeInTheDocument();
    });

    // SKIP_EXISTING should show conflict because SKIP_LOW_RATED and REQUIRE_PRIOR are ON
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
});
