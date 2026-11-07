import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ScheduleSwitchDialog, SolarEditForm } from '../components/SolarScheduleControls.jsx';

vi.mock('../api/schedulerApi.js', () => ({
  previewSchedule: vi.fn(() => new Promise(() => {})),
  switchScheduleType: vi.fn(),
  updateJobSchedule: vi.fn(),
}));

import { switchScheduleType, updateJobSchedule } from '../api/schedulerApi.js';

const NOW = new Date('2026-10-02T00:00:00Z');

const baseJob = {
  jobKey: 'near_term_batch_evaluation',
  displayName: 'Near-term batch',
  scheduleType: 'CRON',
  nextFireTime: '2026-10-02T02:00:00Z',
};

const renderDialog = (props = {}) => {
  const handlers = { onCancel: vi.fn(), onSwitched: vi.fn() };
  render(<ScheduleSwitchDialog job={baseJob} previewJob={null} cronWords="03:00 UK" {...handlers} {...props} />);
  return handlers;
};

describe('ScheduleSwitchDialog', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('switching a solar job back quotes the stored cron and sends a CRON switch', async () => {
    switchScheduleType.mockResolvedValue({});
    const handlers = renderDialog({ job: { ...baseJob, scheduleType: 'SOLAR_OFFSET' } });

    expect(screen.getByText('Switch back to a fixed time?')).toBeInTheDocument();
    expect(screen.getByTestId('switch-after')).toHaveTextContent('03:00 UK (the stored cron, UK time)');
    expect(screen.getByTestId('switch-before')).toHaveTextContent('03:00 UK');
    expect(screen.queryByTestId('switch-offset')).toBeNull();

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() => expect(handlers.onSwitched).toHaveBeenCalledTimes(1));
    expect(switchScheduleType).toHaveBeenCalledWith('near_term_batch_evaluation', { scheduleType: 'CRON' });
  });

  it('says the stored cron time when no words are known, and not scheduled when no next fire', () => {
    renderDialog({ job: { ...baseJob, scheduleType: 'SOLAR_OFFSET', nextFireTime: null }, cronWords: '' });

    expect(screen.getByTestId('switch-after')).toHaveTextContent('the stored cron time (the stored cron, UK time)');
    expect(screen.getByTestId('switch-before')).toHaveTextContent('not scheduled');
  });

  it('asks for a valid offset, refuses to send one, and reads the rule once typed', async () => {
    renderDialog();

    expect(screen.getByTestId('switch-after')).toHaveTextContent('Enter a valid offset (0–720 minutes).');
    expect(screen.getByTestId('switch-offset-error')).toHaveTextContent('Enter a whole number of minutes.');
    expect(screen.getByTestId('switch-offset')).toHaveAttribute('aria-invalid', 'true');

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    expect(switchScheduleType).not.toHaveBeenCalled();

    fireEvent.change(screen.getByTestId('switch-offset'), { target: { value: '240' } });
    fireEvent.change(screen.getByTestId('switch-event'), { target: { value: 'SUNSET' } });

    expect(screen.queryByTestId('switch-offset-error')).toBeNull();
    expect(screen.getByTestId('switch-offset')).toHaveAttribute('aria-invalid', 'false');
    expect(screen.getByTestId('switch-after')).toHaveTextContent(
      '4 h before sunset: not previewed for an edited rule',
    );
    expect(screen.getByText('Switch to a sun-relative time?')).toBeInTheDocument();

    switchScheduleType.mockResolvedValue({});
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledWith('near_term_batch_evaluation', {
      scheduleType: 'SOLAR_OFFSET', solarEvent: 'SUNSET', solarOffsetMinutes: 240,
    }));
  });

  it('quotes the preview time when the rule is the preview\'s own', () => {
    renderDialog({
      previewJob: {
        solarEvent: 'SUNRISE',
        offsetMinutes: 240,
        today: { fallback: false, fireInstant: '2026-10-02T03:00:00Z' },
      },
    });

    expect(screen.getByTestId('switch-offset')).toHaveValue('240');
    expect(screen.getByTestId('switch-after')).toHaveTextContent('04:00 UK, then 4 h before sunrise every day');
  });

  it('says the calculation cannot be previewed when neither day offers a future fire', () => {
    renderDialog({
      previewJob: {
        solarEvent: 'SUNRISE',
        offsetMinutes: 240,
        today: { fallback: false, fireInstant: '2026-10-01T03:00:00Z' },
      },
    });

    expect(screen.getByTestId('switch-after')).toHaveTextContent(
      'cannot be previewed (the calculation falls back to the stored cron)',
    );
  });

  it('quotes the stored cron for today with the reason and the next solar fire', () => {
    renderDialog({
      previewJob: {
        solarEvent: 'SUNRISE',
        offsetMinutes: 240,
        today: { fallback: true, fallbackReason: 'polar twilight' },
        tomorrow: { fallback: false, fireInstant: '2026-10-03T03:00:00Z' },
      },
    });

    expect(screen.getByTestId('switch-after')).toHaveTextContent(
      'Today falls back to the stored cron (polar twilight): 03:00 UK; then',
    );
    expect(screen.getByTestId('switch-after')).toHaveTextContent('4 h before sunrise every day');
  });

  it('quotes a fallback today with no reason, no cron words and no later fire', () => {
    renderDialog({
      cronWords: '',
      previewJob: { solarEvent: 'SUNRISE', offsetMinutes: 240, today: { fallback: true } },
    });

    expect(screen.getByTestId('switch-after').textContent).toBe(
      'Today falls back to the stored cron: the stored cron time',
    );
  });

  it('shows the server sentence on a rejected switch and lets the admin retry', async () => {
    switchScheduleType.mockRejectedValueOnce({ response: { data: { error: 'Offset too large.' } } });
    const handlers = renderDialog({
      previewJob: { solarEvent: 'SUNRISE', offsetMinutes: 240, today: {} },
    });

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    expect(await screen.findByTestId('switch-error')).toHaveTextContent('Offset too large.');
    expect(handlers.onSwitched).not.toHaveBeenCalled();

    switchScheduleType.mockRejectedValueOnce(new Error('network'));
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    await waitFor(() => expect(screen.getByTestId('switch-error')).toHaveTextContent(
      'Failed to switch near_term_batch_evaluation.',
    ));
    expect(switchScheduleType).toHaveBeenCalledTimes(2);
  });

  it('sends only one request when confirm is pressed twice while one is in flight', async () => {
    let resolve;
    switchScheduleType.mockReturnValue(new Promise((r) => { resolve = r; }));
    const handlers = renderDialog({
      previewJob: { solarEvent: 'SUNRISE', offsetMinutes: 240, today: {} },
    });

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    resolve({});

    await waitFor(() => expect(handlers.onSwitched).toHaveBeenCalledTimes(1));
    expect(switchScheduleType).toHaveBeenCalledTimes(1);
  });

  it('cancel calls onCancel without sending anything', () => {
    const handlers = renderDialog();

    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));

    expect(handlers.onCancel).toHaveBeenCalledTimes(1);
    expect(switchScheduleType).not.toHaveBeenCalled();
  });
});

const solarJob = {
  jobKey: 'near_term_batch_evaluation',
  solarEvent: 'SUNRISE',
  solarOffsetMinutes: 240,
  cronExpression: '0 3 * * *',
};
const KEY = 'solar-edit-near_term_batch_evaluation';

const renderForm = (job = solarJob) => {
  const handlers = { onCancel: vi.fn(), onSaved: vi.fn(), onPartial: vi.fn() };
  render(<SolarEditForm job={job} {...handlers} />);
  return handlers;
};

describe('SolarEditForm', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('starts with Save disabled and focus in the first field', () => {
    renderForm();

    expect(screen.getByTestId(`save-btn-${solarJob.jobKey}`)).toBeDisabled();
    expect(screen.getByTestId(`${KEY}-offset`)).toHaveFocus();
    expect(screen.queryByTestId(`${KEY}-no-stored`)).toBeNull();
  });

  it('saves a changed fallback cron on its own, trimmed, without touching the rule', async () => {
    updateJobSchedule.mockResolvedValue({});
    const handlers = renderForm();

    fireEvent.change(screen.getByTestId(`${KEY}-cron`), { target: { value: ' 0 4 * * * ' } });
    fireEvent.click(screen.getByTestId(`save-btn-${solarJob.jobKey}`));

    await waitFor(() => expect(handlers.onSaved).toHaveBeenCalledTimes(1));
    expect(updateJobSchedule).toHaveBeenCalledWith(solarJob.jobKey, { cronExpression: '0 4 * * *' });
    expect(switchScheduleType).not.toHaveBeenCalled();
  });

  it('saves a moved slider as a rule switch without the cron', async () => {
    switchScheduleType.mockResolvedValue({});
    const handlers = renderForm();

    fireEvent.change(screen.getByTestId(`${KEY}-offset`), { target: { value: '300' } });
    fireEvent.click(screen.getByTestId(`save-btn-${solarJob.jobKey}`));

    await waitFor(() => expect(handlers.onSaved).toHaveBeenCalledTimes(1));
    expect(switchScheduleType).toHaveBeenCalledWith(solarJob.jobKey, {
      scheduleType: 'SOLAR_OFFSET', solarEvent: 'SUNRISE', solarOffsetMinutes: 300,
    });
    expect(updateJobSchedule).not.toHaveBeenCalled();
  });

  it('changing the event enables Save and sends the new event with the snapped offset', async () => {
    switchScheduleType.mockResolvedValue({});
    renderForm({ ...solarJob, solarOffsetMinutes: 243 });

    fireEvent.change(screen.getByTestId(`${KEY}-event`), { target: { value: 'SUNSET' } });
    fireEvent.click(screen.getByTestId(`save-btn-${solarJob.jobKey}`));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledWith(solarJob.jobKey, {
      scheduleType: 'SOLAR_OFFSET', solarEvent: 'SUNSET', solarOffsetMinutes: 245,
    }));
  });

  it('explains a job with no stored offset or known event and starts the slider at its minimum', () => {
    renderForm({ jobKey: solarJob.jobKey, solarEvent: 'SOMETHING', cronExpression: '0 3 * * *' });

    expect(screen.getByTestId(`${KEY}-no-stored`)).toHaveTextContent('slider starts at');
    expect(screen.getByTestId(`${KEY}-event`)).toHaveValue('SUNRISE');
    expect(screen.getByTestId(`save-btn-${solarJob.jobKey}`)).toBeEnabled();
  });

  it('opens a sunset job on Sunset', () => {
    renderForm({ ...solarJob, solarEvent: 'SUNSET' });

    expect(screen.getByTestId(`${KEY}-event`)).toHaveValue('SUNSET');
  });

  it('treats a job with no stored cron as blank, so Save waits for one', () => {
    renderForm({ jobKey: solarJob.jobKey, solarEvent: 'SUNRISE', solarOffsetMinutes: 240 });

    expect(screen.getByTestId(`${KEY}-cron`)).toHaveValue('');
    expect(screen.getByTestId(`${KEY}-cron-error`)).toBeInTheDocument();
  });

  it('blocks a blank fallback cron with a message and a disabled Save', () => {
    renderForm();

    fireEvent.change(screen.getByTestId(`${KEY}-cron`), { target: { value: '   ' } });

    expect(screen.getByTestId(`${KEY}-cron-error`)).toHaveTextContent('Enter the fallback cron; it cannot be blank.');
    expect(screen.getByTestId(`save-btn-${solarJob.jobKey}`)).toBeDisabled();
  });

  it('blocks a stored offset the backend would reject', () => {
    renderForm({ ...solarJob, solarOffsetMinutes: 900 });

    expect(screen.getByTestId(`${KEY}-offset-error`)).toHaveTextContent('Offset must be between 0 and 720 minutes.');
    expect(screen.getByTestId(`save-btn-${solarJob.jobKey}`)).toBeDisabled();
  });

  it('reports a partial save when the cron went through but the rule was rejected', async () => {
    updateJobSchedule.mockResolvedValue({});
    switchScheduleType.mockRejectedValue({ response: { data: { error: 'Rule refused.' } } });
    const handlers = renderForm();

    fireEvent.change(screen.getByTestId(`${KEY}-cron`), { target: { value: '0 5 * * *' } });
    fireEvent.change(screen.getByTestId(`${KEY}-offset`), { target: { value: '300' } });
    fireEvent.click(screen.getByTestId(`save-btn-${solarJob.jobKey}`));

    expect(await screen.findByTestId(`${KEY}-error`)).toHaveTextContent(
      'The fallback cron was saved, but the sun-relative rule was not: Rule refused.',
    );
    expect(handlers.onPartial).toHaveBeenCalledTimes(1);
    expect(handlers.onSaved).not.toHaveBeenCalled();
  });

  it('shows a plain failure when nothing was saved, and allows another attempt', async () => {
    updateJobSchedule.mockRejectedValueOnce(new Error('network'));
    const handlers = renderForm();

    fireEvent.change(screen.getByTestId(`${KEY}-cron`), { target: { value: '0 5 * * *' } });
    fireEvent.click(screen.getByTestId(`save-btn-${solarJob.jobKey}`));

    expect(await screen.findByTestId(`${KEY}-error`)).toHaveTextContent(`Failed to update ${solarJob.jobKey}.`);
    expect(handlers.onPartial).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByTestId(`save-btn-${solarJob.jobKey}`)).toBeEnabled());
  });

  it('cancel calls onCancel', () => {
    const handlers = renderForm();

    fireEvent.click(screen.getByTestId(`cancel-btn-${solarJob.jobKey}`));

    expect(handlers.onCancel).toHaveBeenCalledTimes(1);
  });
});
