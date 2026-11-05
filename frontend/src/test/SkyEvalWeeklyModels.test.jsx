import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import SkyEvalWeeklyModels from '../components/scheduler/SkyEvalWeeklyModels.jsx';
import { AVAILABLE, SCHEDULE } from './skyEvalFixtures.js';

vi.mock('../api/skyRatingEvalApi', () => ({
  getSkyRatingEvalSchedule: vi.fn(),
  setSkyRatingEvalScheduled: vi.fn(),
}));
vi.mock('../api/modelsApi', () => ({ getAvailableModels: vi.fn() }));

import { getSkyRatingEvalSchedule, setSkyRatingEvalScheduled } from '../api/skyRatingEvalApi';
import { getAvailableModels } from '../api/modelsApi';

// The weekly Sky Eval run's model checkboxes moved here from the Sky Eval page (sky-eval-redesign-plan.md,
// correction 27); these are the page's old schedule tests, ported with the same behaviour and test ids.

beforeEach(() => {
  vi.clearAllMocks();
  getSkyRatingEvalSchedule.mockResolvedValue({ data: SCHEDULE });
  getAvailableModels.mockResolvedValue(AVAILABLE);
});

describe('SkyEvalWeeklyModels', () => {
  it('renders one checkbox per model, ticked from the schedule, under its own label', async () => {
    getSkyRatingEvalSchedule.mockResolvedValue({
      data: SCHEDULE.map((r) => (r.model === 'OPUS' ? { ...r, scheduled: false } : r)),
    });
    render(<SkyEvalWeeklyModels />);

    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-SONNET')).toBeChecked());
    expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeChecked();
    expect(screen.getAllByRole('checkbox')).toHaveLength(5);
    expect(screen.getByRole('group', { name: 'Weekly models' })).toBeInTheDocument();
  });

  it('PUTs the toggled model and value, and applies the response row', async () => {
    setSkyRatingEvalScheduled.mockResolvedValue({ data: { model: 'OPUS', scheduled: false } });
    render(<SkyEvalWeeklyModels />);
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

    fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));

    await waitFor(() => expect(setSkyRatingEvalScheduled).toHaveBeenCalledWith('OPUS', false));
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeChecked());
    expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeDisabled();
  });

  it('disables the checkbox while its request is in flight', async () => {
    let resolve;
    setSkyRatingEvalScheduled.mockReturnValue(new Promise((r) => { resolve = r; }));
    render(<SkyEvalWeeklyModels />);
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

    fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));

    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeDisabled());
    expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeChecked();
    resolve({ data: { model: 'OPUS', scheduled: false } });
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeDisabled());
  });

  it('reverts and shows the banner when the PUT fails', async () => {
    setSkyRatingEvalScheduled.mockRejectedValue(Object.assign(
      new Error('Request failed with status code 500'),
      { response: { status: 500, data: { error: 'Could not save the schedule' } } },
    ));
    render(<SkyEvalWeeklyModels />);
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

    fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));

    expect(await screen.findByTestId('sky-eval-error')).toHaveTextContent('Could not save the schedule');
    expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked();
    expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeDisabled();
  });

  it('applies only the toggled row, so out-of-order responses cannot undo each other', async () => {
    const pending = {};
    setSkyRatingEvalScheduled.mockImplementation((model) => new Promise((r) => { pending[model] = r; }));
    render(<SkyEvalWeeklyModels />);
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

    fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));
    fireEvent.click(screen.getByTestId('sky-eval-schedule-HAIKU'));
    // B (HAIKU) answers first, then A (OPUS). Each response row is correct for its own model; a
    // whole-list replace would have the late OPUS reply carry HAIKU as still ticked.
    pending.HAIKU({ data: { model: 'HAIKU', scheduled: false } });
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-HAIKU')).not.toBeDisabled());
    pending.OPUS({ data: { model: 'OPUS', scheduled: false } });

    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeDisabled());
    expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeChecked();
    expect(screen.getByTestId('sky-eval-schedule-HAIKU')).not.toBeChecked();
    expect(screen.getByTestId('sky-eval-schedule-SONNET')).toBeChecked();
  });

  it('does not render a checkbox for a model the schedule does not list', async () => {
    getSkyRatingEvalSchedule.mockResolvedValue({ data: SCHEDULE.filter((r) => r.model !== 'HAIKU_55') });
    render(<SkyEvalWeeklyModels />);

    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-SONNET')).toBeChecked());
    expect(screen.queryByTestId('sky-eval-schedule-HAIKU_55')).not.toBeInTheDocument();
  });

  it('says the weekly run will be skipped when no model is ticked', async () => {
    getSkyRatingEvalSchedule.mockResolvedValue({ data: SCHEDULE.map((r) => ({ ...r, scheduled: false })) });
    render(<SkyEvalWeeklyModels />);

    expect(await screen.findByTestId('sky-eval-schedule-none')).toHaveTextContent('skipped');
  });

  it('shows the error and disables the row when the schedule fails to load, never ticking by guess', async () => {
    getSkyRatingEvalSchedule.mockRejectedValue(Object.assign(
      new Error('Request failed with status code 500'),
      { response: { status: 500, data: { error: 'Schedule unavailable' } } },
    ));
    render(<SkyEvalWeeklyModels />);

    expect(await screen.findByTestId('sky-eval-schedule-error')).toHaveTextContent('Schedule unavailable');
    const box = await screen.findByTestId('sky-eval-schedule-SONNET');
    expect(box).toBeDisabled();
    expect(box).not.toBeChecked();
    expect(screen.queryByTestId('sky-eval-schedule-none')).not.toBeInTheDocument();
  });

  it('shows just the error when neither the schedule nor the model list loads', async () => {
    getSkyRatingEvalSchedule.mockRejectedValue(new Error('schedule down'));
    getAvailableModels.mockRejectedValue(new Error('models down'));
    render(<SkyEvalWeeklyModels />);

    expect(await screen.findByTestId('sky-eval-schedule-error')).toHaveTextContent('schedule down');
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
  });

  it('clears an earlier error banner once a later toggle succeeds', async () => {
    setSkyRatingEvalScheduled.mockRejectedValueOnce(new Error('first failed'));
    setSkyRatingEvalScheduled.mockResolvedValueOnce({ data: { model: 'HAIKU', scheduled: false } });
    render(<SkyEvalWeeklyModels />);
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

    fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));
    expect(await screen.findByTestId('sky-eval-error')).toHaveTextContent('first failed');
    fireEvent.click(screen.getByTestId('sky-eval-schedule-HAIKU'));

    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-HAIKU')).not.toBeChecked());
    expect(screen.queryByTestId('sky-eval-error')).not.toBeInTheDocument();
  });

  it('renders no boxes and no warning when the schedule comes back empty', async () => {
    getSkyRatingEvalSchedule.mockResolvedValue({ data: null });
    render(<SkyEvalWeeklyModels />);

    expect(await screen.findByRole('group', { name: 'Weekly models' })).toBeInTheDocument();
    await act(async () => {});
    expect(getSkyRatingEvalSchedule).toHaveBeenCalledTimes(1);
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    expect(screen.queryByTestId('sky-eval-schedule-none')).toBeNull();
    expect(screen.queryByTestId('sky-eval-schedule-error')).toBeNull();
  });

  it('uses a stock message when the schedule fails to load with no message at all', async () => {
    getSkyRatingEvalSchedule.mockRejectedValue({});
    render(<SkyEvalWeeklyModels />);

    expect(await screen.findByTestId('sky-eval-schedule-error')).toHaveTextContent(
      'Failed to load the weekly schedule',
    );
  });

  it('lists no fallback boxes when the model list has no available field', async () => {
    getSkyRatingEvalSchedule.mockRejectedValue(new Error('schedule down'));
    getAvailableModels.mockResolvedValue({});
    render(<SkyEvalWeeklyModels />);

    expect(await screen.findByTestId('sky-eval-schedule-error')).toHaveTextContent('schedule down');
    await act(async () => {});
    expect(getAvailableModels).toHaveBeenCalledTimes(1);
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
  });

  it('uses a stock message and reverts the box when a toggle fails with no message', async () => {
    setSkyRatingEvalScheduled.mockRejectedValue({});
    render(<SkyEvalWeeklyModels />);
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

    fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));

    expect(await screen.findByTestId('sky-eval-error')).toHaveTextContent('Failed to change the weekly schedule');
    expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked();
  });

  it('clears an earlier toggle error as soon as the next toggle starts', async () => {
    setSkyRatingEvalScheduled.mockRejectedValueOnce(new Error('first failed'));
    setSkyRatingEvalScheduled.mockReturnValueOnce(new Promise(() => {}));
    render(<SkyEvalWeeklyModels />);
    await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

    fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));
    expect(await screen.findByTestId('sky-eval-error')).toHaveTextContent('first failed');
    fireEvent.click(screen.getByTestId('sky-eval-schedule-HAIKU'));

    await waitFor(() => expect(screen.queryByTestId('sky-eval-error')).not.toBeInTheDocument());
  });
});
