import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

vi.mock('../api/hotTopicSimulationApi.js', () => ({
  getSimulationState: vi.fn(),
  toggleSimulation: vi.fn(),
  toggleTopicType: vi.fn(),
}));

import { getSimulationState, toggleSimulation, toggleTopicType } from '../api/hotTopicSimulationApi.js';
import HotTopicSimulation from '../components/HotTopicSimulation.jsx';

const state = (enabled, actives) => ({
  enabled,
  types: [
    { type: 'KING_TIDE', label: 'King tide', active: actives[0] },
    { type: 'AURORA', label: 'Aurora', active: actives[1] },
    { type: 'ECLIPSE', label: 'Eclipse', active: actives[2] },
  ],
});

describe('HotTopicSimulation', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows Loading until the state arrives', () => {
    getSimulationState.mockReturnValue(new Promise(() => {}));
    render(<HotTopicSimulation />);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('shows an error when the state cannot be loaded', async () => {
    getSimulationState.mockRejectedValue(new Error('x'));
    render(<HotTopicSimulation />);
    expect(await screen.findByText('Failed to load simulation state')).toBeInTheDocument();
    expect(screen.queryByTestId('simulation-master-toggle')).toBeNull();
  });

  it('when off, says how many topics are selected and hides the tab hint', async () => {
    getSimulationState.mockResolvedValue(state(false, [true, false, true]));
    render(<HotTopicSimulation />);
    expect(await screen.findByTestId('simulation-master-toggle')).toHaveTextContent('OFF');
    expect(screen.getByText('2 of 3 topics selected — toggle ON to activate')).toBeInTheDocument();
    expect(screen.queryByText(/Switch to the At a Glance tab/)).toBeNull();
    expect(screen.getByTestId('simulation-checkbox-KING_TIDE')).toBeChecked();
    expect(screen.getByTestId('simulation-checkbox-AURORA')).not.toBeChecked();
  });

  it('makes the toggle and the checkbox rows 44px tap targets on a phone', async () => {
    getSimulationState.mockResolvedValue(state(true, [true, false, false]));
    render(<HotTopicSimulation />);
    expect(await screen.findByTestId('simulation-master-toggle')).toHaveClass('max-[639px]:min-h-11');
    expect(screen.getByTestId('simulation-type-AURORA')).toHaveClass('max-[639px]:min-h-11');
    expect(screen.getByTestId('simulation-checkbox-AURORA')).toHaveClass('max-[639px]:size-5');
  });

  it('when on, says what is being simulated and shows the tab hint', async () => {
    getSimulationState.mockResolvedValue(state(true, [true, false, false]));
    render(<HotTopicSimulation />);
    expect(await screen.findByTestId('simulation-master-toggle')).toHaveTextContent('ON');
    expect(screen.getByText('Simulating 1 of 3 topics')).toBeInTheDocument();
    expect(screen.getByText('Switch to the At a Glance tab to see the pills')).toBeInTheDocument();
  });

  it('the master toggle adopts the server state it returns', async () => {
    getSimulationState.mockResolvedValue(state(false, [true, true, true]));
    toggleSimulation.mockResolvedValue(state(true, [true, true, true]));
    render(<HotTopicSimulation />);
    fireEvent.click(await screen.findByTestId('simulation-master-toggle'));
    expect(await screen.findByText('Simulating 3 of 3 topics')).toBeInTheDocument();
    expect(toggleSimulation).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('simulation-master-toggle')).toHaveTextContent('ON');
  });

  it('a failed master toggle replaces the panel with an error', async () => {
    getSimulationState.mockResolvedValue(state(false, [true, true, true]));
    toggleSimulation.mockRejectedValue(new Error('x'));
    render(<HotTopicSimulation />);
    fireEvent.click(await screen.findByTestId('simulation-master-toggle'));
    expect(await screen.findByText('Failed to toggle simulation')).toBeInTheDocument();
  });

  it('ticking a type toggles that type and adopts the returned state', async () => {
    getSimulationState.mockResolvedValue(state(true, [true, false, false]));
    toggleTopicType.mockResolvedValue(state(true, [true, true, false]));
    render(<HotTopicSimulation />);
    fireEvent.click(await screen.findByTestId('simulation-checkbox-AURORA'));
    expect(await screen.findByText('Simulating 2 of 3 topics')).toBeInTheDocument();
    expect(toggleTopicType).toHaveBeenCalledWith('AURORA');
    expect(screen.getByTestId('simulation-checkbox-AURORA')).toBeChecked();
  });

  it('a failed type toggle names the type', async () => {
    getSimulationState.mockResolvedValue(state(true, [true, false, false]));
    toggleTopicType.mockRejectedValue(new Error('x'));
    render(<HotTopicSimulation />);
    fireEvent.click(await screen.findByTestId('simulation-checkbox-ECLIPSE'));
    expect(await screen.findByText('Failed to toggle ECLIPSE')).toBeInTheDocument();
  });
});
