import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import { NIGHTLY, catalogue, job, jobDetail, lastFire, summaryFor, tideJob } from './schedulerFixtures.js';

vi.mock('../api/schedulerApi', () => ({
  fetchSchedulerDay: vi.fn(),
  fetchSchedulerJobs: vi.fn(),
  fetchSchedulerSummary: vi.fn(),
  fetchJobDetail: vi.fn(),
  updateJobSchedule: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
  fetchSolarPreview: vi.fn(),
  switchScheduleType: vi.fn(),
}));
vi.mock('../api/skyRatingEvalApi', () => ({
  getSkyRatingEvalSchedule: vi.fn(),
  setSkyRatingEvalScheduled: vi.fn(),
}));
vi.mock('../api/modelsApi', () => ({ getAvailableModels: vi.fn() }));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));

import {
  fetchSchedulerJobs, fetchSchedulerSummary, fetchJobDetail, fetchSolarPreview,
} from '../api/schedulerApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';
import { useIsMobile } from '../hooks/useIsMobile.js';

// The Scheduler on a phone (639px and under) and the desktop it must leave alone (scheduler-redesign-plan.md S4a).

const JOBS = [
  job({ catalogue: catalogue({ alias: 'The main one', what: 'Sends every sunrise to Claude.', usesClaude: true }), lastFire: lastFire() }),
  tideJob(),
];

function mount() {
  fetchSchedulerJobs.mockResolvedValue(JOBS);
  fetchSchedulerSummary.mockResolvedValue(summaryFor(JOBS));
  return render(<SchedulerView />);
}

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/scheduler';
  // These suites are about the All jobs tab, which the remembered tab opens (the default is Day's jobs).
  try { window.localStorage.setItem('photocast.scheduler.view', 'all'); } catch { /* none */ }
  window.scrollTo = vi.fn();
  useIsSplitLayout.mockReturnValue(true);
  fetchSolarPreview.mockRejectedValue(new Error('not under test'));
  fetchJobDetail.mockResolvedValue(jobDetail());
});

describe('SchedulerView on a phone', () => {
  beforeEach(() => useIsMobile.mockReturnValue(true));

  it('lists the jobs as one column of rows whose card line reads "{schedule} · next {time}"', async () => {
    mount();
    expect(await screen.findByTestId(`scheduler-row-${NIGHTLY}`)).toBeInTheDocument();
    expect(screen.getByTestId(`scheduler-card-line-${NIGHTLY}`).textContent).toMatch(/ · next /);
    expect(screen.getByTestId(`scheduler-card-status-${NIGHTLY}`)).toHaveTextContent('Active');
    // the grid's header hides at the card width by container query, not by removing the element: the card cells exist
    expect(screen.getByTestId(`scheduler-cell-card-summary-${NIGHTLY}`).className).toContain('@max-[540px]:order-3');
  });

  it('opens the panel as a full page under a back bar, with no scrim and no drawer', async () => {
    mount();
    fireEvent.click(await screen.findByTestId(`scheduler-row-${NIGHTLY}`));
    expect(await screen.findByTestId('scheduler-split-backbar')).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-split-scrim')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-split-panel').className).not.toContain('w-[520px]');
    fireEvent.click(screen.getByTestId('scheduler-split-back'));
    expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument();
  });
});

describe('SchedulerView on a desktop', () => {
  beforeEach(() => useIsMobile.mockReturnValue(false));

  it('draws no back bar, and the panel is the drawer beside the full-width list', async () => {
    mount();
    fireEvent.click(await screen.findByTestId(`scheduler-row-${NIGHTLY}`));
    await screen.findByTestId('scheduler-panel');
    expect(screen.queryByTestId('scheduler-split-backbar')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-split-panel').className).toContain('w-[520px]');
    expect(screen.getByTestId('scheduler-split').className).not.toContain('grid-cols');
  });
});
