import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import ConfirmDialog from '../components/shared/ConfirmDialog.jsx';
import AuroraForecastModal from '../components/AuroraForecastModal.jsx';
import AuroraSimulateModal from '../components/AuroraSimulateModal.jsx';
import LiteLimitButton from '../components/users/LiteLimitButton.jsx';
import { ScheduleSwitchDialog } from '../components/SolarScheduleControls.jsx';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

vi.mock('../api/auroraApi.js', () => ({
  getAuroraForecastPreview: vi.fn(),
  runAuroraForecast: vi.fn(),
  simulateAurora: vi.fn(),
  clearSimulation: vi.fn(),
}));
vi.mock('../api/schedulerApi.js', () => ({
  previewSchedule: vi.fn(() => new Promise(() => {})),
  switchScheduleType: vi.fn(),
  updateJobSchedule: vi.fn(),
}));
vi.mock('../api/subRegionPolicyApi.js', () => ({
  getSubRegionPolicy: vi.fn(),
  saveSubRegionPolicy: vi.fn(),
}));

import {
  getAuroraForecastPreview, runAuroraForecast, simulateAurora, clearSimulation,
} from '../api/auroraApi.js';
import { switchScheduleType } from '../api/schedulerApi.js';
import { getSubRegionPolicy } from '../api/subRegionPolicyApi.js';

/**
 * Back on the cancel set (back-closes-dialogs-plan.md, PR 2a): a confirm dialog and the aurora modals have
 * no Escape, so Back runs their Cancel path; while their own Cancel is disabled (a run, a submit, a switch in
 * flight) Back is absorbed instead, and goes back to cancelling in place when that clears.
 */

let b;

const NIGHTS = {
  nights: [{
    date: '2026-03-21', label: 'Tonight', maxKp: 6, gScale: 'G2', recommended: true, summary: 'Kp 6', eligibleLocations: 3,
  }],
};

beforeEach(() => {
  vi.clearAllMocks();
  b = installBackBrowser();
  getAuroraForecastPreview.mockResolvedValue(NIGHTS);
  getSubRegionPolicy.mockResolvedValue({
    liteLimit: 3, maxLimit: 24, liteUsersOverLimit: 0, candidateLimit: 3, updatedAt: null, updatedBy: null,
  });
});

afterEach(() => {
  uninstallBackBrowser();
});

describe('ConfirmDialog', () => {
  const renderDialog = (props = {}) => {
    const onCancel = vi.fn();
    const onConfirm = vi.fn();
    const view = render(
      <ConfirmDialog title="Sure?" message="Really" confirmLabel="Yes" onConfirm={onConfirm} onCancel={onCancel} {...props} />,
    );
    return { onCancel, onConfirm, ...view };
  };

  it('Back runs its Cancel path and never its Confirm', async () => {
    const { onCancel, onConfirm } = renderDialog();
    await b.settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);

    await b.back();

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('Back is absorbed while busy, and cancels again once the work clears', async () => {
    const { onCancel, rerender } = renderDialog({ busy: true });
    await b.settle();

    await b.back();
    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument();
    expect(__inspectForTests().layers).toBe(1);

    rerender(<ConfirmDialog title="Sure?" message="Really" confirmLabel="Yes" onConfirm={vi.fn()} onCancel={onCancel} />);
    await b.settle();
    // The layer was updated in place, never unregistered: still the one entry.
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });

    await b.back();
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});

describe('ScheduleSwitchDialog', () => {
  const job = {
    jobKey: 'near_term_batch_evaluation', displayName: 'Near-term', scheduleType: 'SOLAR_OFFSET', nextFireTime: null,
  };

  it('Back cancels; while the switch is in flight Back is absorbed, and a failure frees it again', async () => {
    let reject;
    switchScheduleType.mockReturnValue(new Promise((_res, rej) => { reject = rej; }));
    const onCancel = vi.fn();
    render(<ScheduleSwitchDialog job={job} previewJob={null} cronWords="03:00" onCancel={onCancel} onSwitched={vi.fn()} />);
    await b.settle();

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    await b.settle();
    await b.back();
    expect(onCancel).not.toHaveBeenCalled();

    await act(async () => { reject(new Error('nope')); });
    await b.settle();
    expect(await screen.findByTestId('switch-error')).toBeInTheDocument();

    await b.back();
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});

describe('AuroraForecastModal', () => {
  it('Back cancels it, as Cancel does', async () => {
    const onClose = vi.fn();
    render(<AuroraForecastModal onClose={onClose} onComplete={vi.fn()} />);
    await screen.findByText(/Tonight/);
    await b.settle();

    await b.back();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('absorbs Back while the run is in flight, and cancels again after a failed run', async () => {
    let reject;
    runAuroraForecast.mockReturnValue(new Promise((_res, rej) => { reject = rej; }));
    const onClose = vi.fn();
    render(<AuroraForecastModal onClose={onClose} onComplete={vi.fn()} />);
    await screen.findByText(/Tonight/);
    fireEvent.click(screen.getByRole('button', { name: /Run Forecast/ }));
    await b.settle();

    await b.back();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('aurora-forecast-modal')).toBeInTheDocument();

    await act(async () => { reject(new Error('boom')); });
    await b.settle();
    await b.back();
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('AuroraSimulateModal', () => {
  it('Back cancels it, as Cancel does', async () => {
    const onClose = vi.fn();
    render(<AuroraSimulateModal isActive={false} onClose={onClose} onSuccess={vi.fn()} />);
    await b.settle();

    await b.back();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('absorbs Back while submitting, and while clearing', async () => {
    let rejectSimulate;
    simulateAurora.mockReturnValue(new Promise((_res, rej) => { rejectSimulate = rej; }));
    clearSimulation.mockReturnValue(new Promise(() => {}));
    const onClose = vi.fn();
    render(<AuroraSimulateModal isActive onClose={onClose} onSuccess={vi.fn()} />);
    await b.settle();

    fireEvent.click(screen.getByTestId('simulate-submit-btn'));
    await b.settle();
    await b.back();
    expect(onClose).not.toHaveBeenCalled();

    await act(async () => { rejectSimulate(new Error('x')); });
    await b.settle();
    fireEvent.click(screen.getByTestId('simulate-clear-btn'));
    await b.settle();
    await b.back();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('aurora-simulate-modal')).toBeInTheDocument();
  });
});

describe('LiteLimitButton', () => {
  it('Back closes the dialog, as Escape does', async () => {
    render(<LiteLimitButton />);
    fireEvent.click(await screen.findByTestId('lite-limit-btn'));
    await screen.findByTestId('lite-limit-dialog');
    await b.settle();

    await b.back();

    expect(screen.queryByTestId('lite-limit-dialog')).toBeNull();
  });
});
