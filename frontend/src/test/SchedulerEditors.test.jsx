import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import JobSchedule from '../components/scheduler/JobSchedule.jsx';
import { PREVIEW_DEBOUNCE_MS } from '../hooks/useSchedulePreview.js';
import { INTRADAY, pollingJob, schedulePreview, solarIntradayJob, solarJob, tideJob } from './schedulerFixtures.js';

vi.mock('../api/schedulerApi', () => ({
  previewSchedule: vi.fn(),
  updateJobSchedule: vi.fn(),
  switchScheduleType: vi.fn(),
}));

import { previewSchedule } from '../api/schedulerApi';

// The three schedule editors (scheduler-redesign-plan.md S4b), rendered in the Schedule section with the preview
// endpoint mocked at the API module: the sun-relative slider, the cron times editor and the fixed-delay presets.

const noop = () => {};

function renderEditing(job, { onInlineSave = vi.fn().mockResolvedValue(undefined) } = {}) {
  render(
    <JobSchedule
      job={job}
      editing
      onEditStart={noop}
      onEditCancel={noop}
      onSolarSaved={noop}
      onPartial={noop}
      onInlineSave={onInlineSave}
      onSwitch={noop}
    />,
  );
  return { onInlineSave };
}

/** Lets the debounce elapse and the mocked request settle (real timers). */
async function settle() {
  await waitFor(() => expect(previewSchedule).toHaveBeenCalled(), { timeout: 2000 });
}

/** Types new times into the cron editor. */
const typeTimes = (value) => fireEvent.change(screen.getByTestId('cron-times-tide_refresh'), { target: { value } });

const DAILY = (overrides = {}) => tideJob({
  cronExpression: '0 15 6,18 * * *', timeZone: 'Europe/London', ...overrides,
});
const WEEKLY = (overrides = {}) => tideJob({
  cronExpression: '0 0 2 * * MON,WED', timeZone: 'Europe/London', ...overrides,
});

beforeEach(() => {
  vi.clearAllMocks();
  previewSchedule.mockResolvedValue(schedulePreview());
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the sun-relative editor', () => {
  it('reads the slider in words: aria-valuetext and the readout', () => {
    renderEditing(solarJob());
    const slider = screen.getByTestId('solar-edit-near_term_batch_evaluation-offset');
    expect(slider).toHaveAttribute('type', 'range');
    expect(slider).toHaveAttribute('aria-valuetext', '4h 15m before sunrise');
    expect(screen.getByTestId('solar-edit-near_term_batch_evaluation-offset-readout')).toHaveTextContent('4h 15m before sunrise');

    fireEvent.change(slider, { target: { value: '240' } });
    fireEvent.change(screen.getByTestId('solar-edit-near_term_batch_evaluation-event'), { target: { value: 'SUNSET' } });
    expect(slider).toHaveAttribute('aria-valuetext', '4h before sunset');
  });

  it('is 30 minutes to 8 hours in 5-minute steps', () => {
    renderEditing(solarJob());
    const slider = screen.getByTestId('solar-edit-near_term_batch_evaluation-offset');
    expect([slider.min, slider.max, slider.step]).toEqual(['30', '480', '5']);
  });

  it.each([[600, '480', '(stored: 10h)'], [10, '30', '(stored: 10m)'], [243, '245', '(stored: 4h 3m)']])(
    'keeps the grid for a stored %i: the thumb at %s, the stored value beside the readout',
    (stored, thumb, note) => {
      renderEditing(solarJob({ solarOffsetMinutes: stored }));
      const slider = screen.getByTestId('solar-edit-near_term_batch_evaluation-offset');
      expect([slider.min, slider.max, slider.step]).toEqual(['30', '480', '5']);
      expect(slider).toHaveValue(thumb);
      expect(screen.getByTestId('solar-edit-near_term_batch_evaluation-offset-readout')).toHaveTextContent(note);
      expect(slider).toHaveAttribute('aria-valuetext', expect.stringContaining('before sunrise'));
    },
  );

  it('has a visible label, and keeps the readout out of the live regions', () => {
    renderEditing(solarJob());
    expect(screen.getByLabelText('Fire this long before the event')).toBe(
      screen.getByTestId('solar-edit-near_term_batch_evaluation-offset'),
    );
    expect(screen.getByTestId('solar-edit-near_term_batch_evaluation-offset-readout')).toHaveAttribute('aria-live', 'off');
  });

  it('announces only the settled preview: "Checking…" sits outside the live region', async () => {
    renderEditing(solarJob());
    const live = screen.getByTestId('solar-edit-near_term_batch_evaluation-preview');
    expect(live).toHaveAttribute('aria-live', 'polite');
    const checking = screen.getByTestId('solar-edit-near_term_batch_evaluation-checking');
    expect(checking).toHaveAttribute('aria-hidden', 'true');
    expect(live).not.toContainElement(checking);
    expect(live).toBeEmptyDOMElement();
    await waitFor(() => expect(live.textContent).not.toBe(''), { timeout: 2000 });
  });

  it('shows the served preview: today\'s time and the year\'s range in UK time', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-03T10:00:00Z'));
    previewSchedule.mockResolvedValue(schedulePreview({
      cronExpression: null,
      nextFires: ['2026-11-03T14:27:00Z', '2026-11-04T14:25:00Z'],
      yearRange: { earliest: '03:41', latest: '07:12' },
    }));
    renderEditing(solarJob());

    const line = await screen.findByTestId('solar-edit-near_term_batch_evaluation-preview', {}, { timeout: 2000 });
    await waitFor(() => expect(line).toHaveTextContent("Today that's 14:27."), { timeout: 2000 });
    expect(line).toHaveTextContent('Across the year it moves between 03:41 and 07:12 UK time.');
    expect(previewSchedule.mock.calls[0][0]).toBe('near_term_batch_evaluation');
    expect(previewSchedule.mock.calls[0][1]).toEqual({
      scheduleType: 'SOLAR_OFFSET', solarEvent: 'SUNRISE', solarOffsetMinutes: 255,
    });
  });

  it('sends one request after the slider stops moving', async () => {
    vi.useFakeTimers();
    renderEditing(solarJob());
    const slider = screen.getByTestId('solar-edit-near_term_batch_evaluation-offset');
    await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS); });
    previewSchedule.mockClear();

    for (const value of ['260', '265', '270', '275']) {
      fireEvent.change(slider, { target: { value } });
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
    }
    expect(previewSchedule).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS); });

    expect(previewSchedule).toHaveBeenCalledTimes(1);
    expect(previewSchedule.mock.calls[0][1].solarOffsetMinutes).toBe(275);
  });

  it('says so when the preview request fails, and Save still works', async () => {
    previewSchedule.mockRejectedValue(new Error('down'));
    renderEditing(solarJob());
    fireEvent.change(screen.getByTestId('solar-edit-near_term_batch_evaluation-offset'), { target: { value: '240' } });

    expect(await screen.findByText("Couldn't check this schedule.", {}, { timeout: 2000 })).toBeInTheDocument();
    expect(screen.getByTestId('save-btn-near_term_batch_evaluation')).toBeEnabled();
  });

  it('offers the Runs Timing link on the intraday refresh only, as a plain href', () => {
    renderEditing(solarIntradayJob());
    const link = screen.getByTestId(`solar-edit-${INTRADAY}-timing-link`);
    expect(link).toHaveAttribute('href', '#manage/runs?view=timing&type=AFTERNOON');
    expect(link).toHaveTextContent('Choose the offset from real run times (Runs → Timing)');
  });

  it('has no Timing link on the nightly cycle', () => {
    renderEditing(solarJob());
    expect(screen.queryByTestId('solar-edit-near_term_batch_evaluation-timing-link')).not.toBeInTheDocument();
  });
});

describe('the cron times editor', () => {
  it('opens with the stored UK times, and shows no day toggles for a daily job', () => {
    renderEditing(DAILY());
    expect(screen.getByTestId('cron-times-tide_refresh')).toHaveValue('06:15, 18:15');
    expect(screen.getByLabelText('Times (UK time), comma-separated')).toBeInTheDocument();
    expect(screen.queryByTestId('cron-days-tide_refresh')).not.toBeInTheDocument();
    expect(screen.getByTestId('cron-note-tide_refresh')).toHaveTextContent('UK time and follow the clocks');
  });

  it('shows the stored days as pressed toggles for a weekly job', () => {
    renderEditing(WEEKLY());
    const pressed = (code) => screen.getByTestId(`cron-day-tide_refresh-${code}`).getAttribute('aria-pressed');
    expect(['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'].map(pressed))
      .toEqual(['true', 'false', 'true', 'false', 'false', 'false', 'false']);
  });

  it('converts a UTC cron to UK time for display', () => {
    renderEditing(tideJob({
      cronExpression: '0 0 1 * * *', timeZone: 'UTC', nextFires: ['2026-07-01T00:00:00Z'], nextFireTime: '',
    }));
    expect(screen.getByTestId('cron-times-tide_refresh')).toHaveValue('02:00');
  });

  it('asks the server for the cron: the preview body carries times, days and the UK zone', async () => {
    renderEditing(WEEKLY());
    fireEvent.click(screen.getByTestId('cron-day-tide_refresh-fri'));
    fireEvent.change(screen.getByTestId('cron-times-tide_refresh'), { target: { value: '6:15, 18:15' } });
    await waitFor(() => expect(previewSchedule).toHaveBeenLastCalledWith('tide_refresh', {
      scheduleType: 'CRON',
      times: ['06:15', '18:15'],
      timeZone: 'Europe/London',
      daysOfWeek: ['MON', 'WED', 'FRI'],
    }, expect.anything()), { timeout: 2000 });
  });

  it('omits daysOfWeek for a daily job', async () => {
    renderEditing(DAILY());
    await settle();
    expect(previewSchedule.mock.calls[0][1]).toEqual({
      scheduleType: 'CRON', times: ['06:15', '18:15'], timeZone: 'Europe/London',
    });
  });

  it('saves the cron the server built, with Europe/London, never one built here', async () => {
    previewSchedule.mockResolvedValue(schedulePreview({ cronExpression: '0 15 7,18 * * *' }));
    const { onInlineSave } = renderEditing(DAILY());
    typeTimes('07:15, 18:15');
    await waitFor(() => expect(screen.getByTestId('save-btn-tide_refresh')).toBeEnabled(), { timeout: 2000 });
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(onInlineSave).toHaveBeenCalledTimes(1));
    expect(onInlineSave).toHaveBeenCalledWith(
      expect.objectContaining({ jobKey: 'tide_refresh' }), '0 15 7,18 * * *', { timeZone: 'Europe/London' },
    );
  });

  it('keeps Save off until the preview has answered', async () => {
    renderEditing(DAILY());
    typeTimes('07:15, 18:15');
    expect(screen.getByTestId('save-btn-tide_refresh')).toBeDisabled();
    await waitFor(() => expect(screen.getByTestId('save-btn-tide_refresh')).toBeEnabled(), { timeout: 2000 });
  });

  it('shows the server\'s sentence for times with mixed minutes, and keeps Save off', async () => {
    previewSchedule.mockResolvedValue(schedulePreview({
      valid: false,
      error: 'All times must share the same minutes past the hour (e.g. 06:15 and 18:15)',
      cronExpression: null,
    }));
    const { onInlineSave } = renderEditing(DAILY());
    fireEvent.change(screen.getByTestId('cron-times-tide_refresh'), { target: { value: '06:15, 18:45' } });

    expect(await screen.findByText(/All times must share the same minutes/, {}, { timeout: 2000 })).toBeInTheDocument();
    expect(screen.getByTestId('save-btn-tide_refresh')).toBeDisabled();
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));
    expect(onInlineSave).not.toHaveBeenCalled();
  });

  it.each([
    ['', 'Enter at least one time'],
    ['06:15, soon', '"soon" isn\'t a time'],
    ['25:00', 'Times run from 00:00 to 23:59'],
    ['12:75', 'Times run from 00:00 to 23:59'],
  ])('says what is wrong with %j once typing pauses, in a polite status, and sends no request', async (typed, message) => {
    renderEditing(DAILY());
    await settle();
    previewSchedule.mockClear();
    typeTimes(typed);

    // Not while the keys are still going, and Save is already off.
    expect(screen.getByTestId('cron-message-tide_refresh')).toBeEmptyDOMElement();
    expect(screen.getByTestId('save-btn-tide_refresh')).toBeDisabled();
    await waitFor(() => expect(screen.getByTestId('cron-message-tide_refresh')).toHaveTextContent(message), { timeout: 2000 });
    const status = screen.getByTestId('cron-message-tide_refresh');
    expect(status).toHaveAttribute('role', 'status');
    expect(screen.getByTestId('cron-times-tide_refresh').getAttribute('aria-describedby')).toContain(status.id);
    await new Promise((resolve) => { setTimeout(resolve, PREVIEW_DEBOUNCE_MS + 100); });
    expect(previewSchedule).not.toHaveBeenCalled();
  });

  it('shows the format message at once when the field loses focus', async () => {
    renderEditing(DAILY());
    typeTimes('soon');
    fireEvent.blur(screen.getByTestId('cron-times-tide_refresh'));
    expect(screen.getByTestId('cron-message-tide_refresh')).toHaveTextContent("isn't a time");
  });

  it('is named by its visible label only, with no alert role on the message', () => {
    renderEditing(DAILY());
    const input = screen.getByLabelText('Times (UK time), comma-separated');
    expect(input).toBe(screen.getByTestId('cron-times-tide_refresh'));
    expect(input).not.toHaveAttribute('aria-label');
    expect(screen.getByTestId('cron-edit-tide_refresh').querySelector('[role="alert"]')).toBeNull();
  });

  it('needs at least one day when the day toggles are shown', () => {
    renderEditing(WEEKLY());
    fireEvent.click(screen.getByTestId('cron-day-tide_refresh-mon'));
    fireEvent.click(screen.getByTestId('cron-day-tide_refresh-wed'));

    expect(screen.getByTestId('cron-message-tide_refresh')).toHaveTextContent('Choose at least one day.');
    expect(screen.getByTestId('save-btn-tide_refresh')).toBeDisabled();
  });

  it('sends no preview request while no day is chosen', async () => {
    renderEditing(WEEKLY());
    await settle();
    previewSchedule.mockClear();
    fireEvent.click(screen.getByTestId('cron-day-tide_refresh-mon'));
    fireEvent.click(screen.getByTestId('cron-day-tide_refresh-wed'));
    await new Promise((resolve) => { setTimeout(resolve, PREVIEW_DEBOUNCE_MS + 100); });
    expect(previewSchedule).not.toHaveBeenCalled();
  });

  it('lists the neighbours the preview serves', async () => {
    previewSchedule.mockResolvedValue(schedulePreview({
      neighbours: [
        { jobKey: 'a', displayName: 'Tide Refresh', at: '2026-11-04T02:00:00Z', minutesApart: 15 },
        { jobKey: 'b', displayName: 'Aurora Batch', at: '2026-11-04T02:10:00Z', minutesApart: 5 },
      ],
    }));
    renderEditing(DAILY());
    expect(await screen.findByTestId('cron-neighbours-tide_refresh', {}, { timeout: 2000 }))
      .toHaveTextContent('Neighbours: Tide Refresh 02:00 (15 min apart) · Aurora Batch 02:10 (5 min apart)');
  });

  it('says so when nothing is within 30 minutes', async () => {
    renderEditing(DAILY());
    expect(await screen.findByTestId('cron-neighbours-tide_refresh', {}, { timeout: 2000 }))
      .toHaveTextContent('Nothing within 30 minutes.');
  });

  it('says the check failed, keeps Save on, and asks again at Save to get the server\'s cron', async () => {
    previewSchedule.mockRejectedValueOnce(new Error('down'));
    previewSchedule.mockResolvedValue(schedulePreview({ cronExpression: '0 15 6,18 * * *' }));
    const { onInlineSave } = renderEditing(DAILY());
    typeTimes('07:15, 18:15');

    expect(await screen.findByText("Couldn't check this schedule.", {}, { timeout: 2000 })).toBeInTheDocument();
    expect(screen.getByTestId('save-btn-tide_refresh')).toBeEnabled();
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(onInlineSave).toHaveBeenCalledWith(
      expect.objectContaining({ jobKey: 'tide_refresh' }), '0 15 6,18 * * *', { timeZone: 'Europe/London' },
    ));
  });

  it('disables Save for a Europe/London job whose times and days are as stored', async () => {
    renderEditing(WEEKLY());
    await settle();
    await screen.findByTestId('cron-neighbours-tide_refresh', {}, { timeout: 2000 });
    expect(screen.getByTestId('save-btn-tide_refresh')).toBeDisabled();
    expect(screen.queryByTestId('cron-utc-notice-tide_refresh')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('cron-day-tide_refresh-fri'));
    await waitFor(() => expect(screen.getByTestId('save-btn-tide_refresh')).toBeEnabled(), { timeout: 2000 });
    fireEvent.click(screen.getByTestId('cron-day-tide_refresh-fri'));
    expect(screen.getByTestId('save-btn-tide_refresh')).toBeDisabled();
  });

  it('says a UTC job moves to UK time on save, and lets unchanged times save as that conversion', async () => {
    previewSchedule.mockResolvedValue(schedulePreview({ cronExpression: '0 0 2 * * *' }));
    const { onInlineSave } = renderEditing(tideJob({
      cronExpression: '0 0 1 * * *', timeZone: 'UTC', nextFires: ['2026-07-01T00:00:00Z'],
    }));
    expect(screen.getByTestId('cron-utc-notice-tide_refresh')).toHaveTextContent(
      'This job runs on UTC today. Saving moves it to UK time, so its fires follow the clocks.',
    );
    await waitFor(() => expect(screen.getByTestId('save-btn-tide_refresh')).toBeEnabled(), { timeout: 2000 });
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(onInlineSave).toHaveBeenCalledWith(
      expect.objectContaining({ jobKey: 'tide_refresh' }), '0 0 2 * * *', { timeZone: 'Europe/London' },
    ));
    expect(previewSchedule.mock.calls[0][1].timeZone).toBe('Europe/London');
  });

  it('treats a job with no stored zone as UTC', () => {
    renderEditing(tideJob({ cronExpression: '0 0 1 * * *', timeZone: null }));
    expect(screen.getByTestId('cron-utc-notice-tide_refresh')).toBeInTheDocument();
  });

  it('keeps the editor it opened with when the job is re-served mid-edit', async () => {
    const utc = tideJob({ cronExpression: '0 0 1 * * *', timeZone: 'UTC', nextFires: ['2026-07-01T00:00:00Z'] });
    const props = {
      editing: true, onEditStart: noop, onEditCancel: noop, onSolarSaved: noop, onPartial: noop,
      onInlineSave: vi.fn(), onSwitch: noop,
    };
    const { rerender } = render(<JobSchedule job={utc} {...props} />);
    fireEvent.change(screen.getByTestId('cron-times-tide_refresh'), { target: { value: '03:00' } });

    // A poll: the next fire moved into winter, and the cron is now one the times editor would not open for.
    rerender(<JobSchedule job={{ ...utc, nextFires: ['2026-12-01T01:00:00Z'], cronExpression: '0 20 * * * *' }} {...props} />);

    expect(screen.getByTestId('cron-times-tide_refresh')).toHaveValue('03:00');
    expect(screen.queryByTestId('edit-input-tide_refresh')).not.toBeInTheDocument();
  });

  it('makes every field read-only while a save is in flight', async () => {
    let release;
    const onInlineSave = vi.fn(() => new Promise((resolve) => { release = resolve; }));
    renderEditing(WEEKLY(), { onInlineSave });
    typeTimes('07:00');
    await waitFor(() => expect(screen.getByTestId('save-btn-tide_refresh')).toBeEnabled(), { timeout: 2000 });
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(screen.getByTestId('cron-times-tide_refresh')).toBeDisabled());
    expect(screen.getByTestId('cron-day-tide_refresh-mon')).toBeDisabled();
    expect(screen.getByTestId('save-btn-tide_refresh')).toBeDisabled();
    release();
    await waitFor(() => expect(screen.getByTestId('cron-times-tide_refresh')).toBeEnabled());
  });

  it('keeps the editor open and says so when the check fails again at Save', async () => {
    previewSchedule.mockRejectedValue(new Error('down'));
    const { onInlineSave } = renderEditing(DAILY());
    typeTimes('07:15, 18:15');
    await screen.findByText("Couldn't check this schedule.", {}, { timeout: 2000 });
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    expect(await screen.findByTestId('inline-edit-error-tide_refresh')).toHaveTextContent(
      "Couldn't save Tide Refresh's schedule.",
    );
    expect(onInlineSave).not.toHaveBeenCalled();
  });

  it('keeps today\'s failed-save copy when the save itself is refused', async () => {
    const onInlineSave = vi.fn().mockRejectedValue(new Error('400'));
    renderEditing(DAILY(), { onInlineSave });
    typeTimes('07:15, 18:15');
    await waitFor(() => expect(screen.getByTestId('save-btn-tide_refresh')).toBeEnabled(), { timeout: 2000 });
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    expect(await screen.findByTestId('inline-edit-error-tide_refresh')).toHaveTextContent(
      "Couldn't save Tide Refresh's schedule.",
    );
  });

  it('keeps the raw cron field for an hourly cron, which describeCron reads as "Every hour"', () => {
    renderEditing(tideJob({ cronExpression: '0 20 * * * *' }));
    expect(screen.getByTestId('edit-input-tide_refresh')).toHaveValue('0 20 * * * *');
    expect(screen.queryByTestId('cron-times-tide_refresh')).not.toBeInTheDocument();
    expect(previewSchedule).not.toHaveBeenCalled();
  });

  it('keeps the raw cron field for a cron describeCron returns raw', () => {
    renderEditing(tideJob({ cronExpression: '0 0 4 1 * *' }));
    expect(screen.getByTestId('edit-input-tide_refresh')).toBeInTheDocument();
    expect(screen.queryByTestId('cron-times-tide_refresh')).not.toBeInTheDocument();
  });
});

describe('the fixed-delay editor', () => {
  it('offers the presets with the stored one pressed, and Save off until another is picked', () => {
    renderEditing(pollingJob());
    const pressed = (ms) => screen.getByTestId(`fixed-delay-aurora_polling-${ms}`).getAttribute('aria-pressed');
    expect(['60000', '300000', '600000', '1800000', '3600000'].map(pressed))
      .toEqual(['false', 'true', 'false', 'false', 'false']);
    expect(screen.getByTestId('fixed-delay-aurora_polling-60000')).toHaveTextContent('1 min');
    expect(screen.getByTestId('fixed-delay-aurora_polling-3600000')).toHaveTextContent('1 hour');
    expect(screen.getByTestId('save-btn-aurora_polling')).toBeDisabled();
  });

  it('saves the picked preset in milliseconds', async () => {
    const { onInlineSave } = renderEditing(pollingJob());
    fireEvent.click(screen.getByTestId('fixed-delay-aurora_polling-1800000'));
    fireEvent.click(screen.getByTestId('save-btn-aurora_polling'));

    await waitFor(() => expect(onInlineSave).toHaveBeenCalledWith(
      expect.objectContaining({ jobKey: 'aurora_polling' }), '1800000',
    ));
  });

  it('shows a delay that is not a preset as its own selected option, and never changes it unless another is picked', async () => {
    const { onInlineSave } = renderEditing(pollingJob({ fixedDelayMs: 150_000 }));
    const custom = screen.getByTestId('fixed-delay-aurora_polling-150000');
    expect(custom).toHaveTextContent('2 min 30 s');
    expect(custom).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('save-btn-aurora_polling')).toBeDisabled();
    fireEvent.click(screen.getByTestId('save-btn-aurora_polling'));
    expect(onInlineSave).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('fixed-delay-aurora_polling-300000'));
    expect(screen.getByTestId('save-btn-aurora_polling')).toBeEnabled();
  });

  it('focuses the chosen preset, or the first when no delay is stored', () => {
    renderEditing(pollingJob());
    expect(screen.getByTestId('fixed-delay-aurora_polling-300000')).toBe(document.activeElement);
  });

  it.each([null, 0])('focuses the first preset when the stored delay is %s', (stored) => {
    renderEditing(pollingJob({ fixedDelayMs: stored }));
    expect(screen.getByTestId('fixed-delay-aurora_polling-60000')).toBe(document.activeElement);
    expect(screen.getByTestId('save-btn-aurora_polling')).toBeDisabled();
  });

  it('makes the presets read-only while a save is in flight', async () => {
    let release;
    const onInlineSave = vi.fn(() => new Promise((resolve) => { release = resolve; }));
    renderEditing(pollingJob(), { onInlineSave });
    fireEvent.click(screen.getByTestId('fixed-delay-aurora_polling-600000'));
    fireEvent.click(screen.getByTestId('save-btn-aurora_polling'));

    await waitFor(() => expect(screen.getByTestId('fixed-delay-aurora_polling-60000')).toBeDisabled());
    release();
    await waitFor(() => expect(screen.getByTestId('fixed-delay-aurora_polling-60000')).toBeEnabled());
  });

  it('says so, in the job\'s name, when the save fails', async () => {
    const onInlineSave = vi.fn().mockRejectedValue(new Error('400'));
    renderEditing(pollingJob(), { onInlineSave });
    fireEvent.click(screen.getByTestId('fixed-delay-aurora_polling-600000'));
    fireEvent.click(screen.getByTestId('save-btn-aurora_polling'));

    expect(await screen.findByTestId('inline-edit-error-aurora_polling')).toHaveTextContent(
      "Couldn't save Aurora Polling's schedule.",
    );
  });
});

describe('phone and desktop', () => {
  it('gives every control of the editors 44px targets on a phone only, and uses no inline styles', async () => {
    renderEditing(WEEKLY());
    await settle();
    const editor = screen.getByTestId('cron-edit-tide_refresh');
    const phoneOnly = [
      screen.getByTestId('save-btn-tide_refresh'),
      screen.getByTestId('cancel-btn-tide_refresh'),
      screen.getByTestId('cron-day-tide_refresh-mon'),
      screen.getByTestId('cron-times-tide_refresh'),
    ];
    phoneOnly.forEach((el) => expect(el.className).toContain('max-[639px]:min-h-11'));
    // Nothing in the editor sets a minimum height without the phone breakpoint: desktop keeps its sizes.
    editor.querySelectorAll('[class]').forEach((el) => {
      expect(el.className).not.toMatch(/(^|\s)min-h-11/);
    });
    expect(editor.querySelectorAll('[style]')).toHaveLength(0);
    expect(screen.getByTestId('cron-days-tide_refresh').className).toContain('flex-wrap');
  });

  it('wraps the sun-relative controls and sizes their targets for a phone', () => {
    renderEditing(solarJob());
    const form = screen.getByTestId('solar-edit-near_term_batch_evaluation');
    expect(screen.getByTestId('solar-edit-near_term_batch_evaluation-offset').className).toContain('max-[639px]:h-11');
    expect(screen.getByTestId('solar-edit-near_term_batch_evaluation-event').className).toContain('max-[639px]:min-h-11');
    expect(screen.getByTestId('save-btn-near_term_batch_evaluation').className).toContain('max-[639px]:min-h-11');
    expect(form.querySelectorAll('[style]')).toHaveLength(0);
  });

  it('gives the fixed-delay presets and buttons phone targets', () => {
    renderEditing(pollingJob());
    expect(screen.getByTestId('fixed-delay-aurora_polling-60000').className).toContain('max-[639px]:min-h-11');
    expect(screen.getByTestId('save-btn-aurora_polling').className).toContain('max-[639px]:min-h-11');
  });
});
