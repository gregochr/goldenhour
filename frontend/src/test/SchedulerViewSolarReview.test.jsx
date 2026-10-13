import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import { formatUkDateTime } from '../utils/schedulerSolar.js';

vi.mock('../api/schedulerApi', () => ({
  fetchSchedulerJobs: vi.fn(),
  updateJobSchedule: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
  fetchSolarPreview: vi.fn(),
  switchScheduleType: vi.fn(),
}));

import {
  fetchSchedulerJobs,
  updateJobSchedule,
  fetchSolarPreview,
  switchScheduleType,
} from '../api/schedulerApi';

const NIGHTLY = 'near_term_batch_evaluation';
const INTRADAY = 'intraday_forecast_refresh';

const HOUR_MS = 3_600_000;
const inHours = (h) => new Date(Date.now() + h * HOUR_MS).toISOString();

function job(overrides) {
  return {
    id: 1,
    jobKey: NIGHTLY,
    displayName: 'Nightly batch evaluation',
    description: 'The morning forecast cycle',
    scheduleType: 'CRON',
    cronExpression: '0 0 1 * * *',
    solarEvent: '',
    solarOffsetMinutes: 0,
    fixedDelayMs: 0,
    initialDelayMs: 0,
    status: 'ACTIVE',
    lastFireTime: '',
    lastCompletionTime: '',
    nextFireTime: '2026-10-14T00:00:00Z',
    configSource: '',
    updatedAt: '2026-10-13T00:00:00Z',
    ...overrides,
  };
}

function day(overrides) {
  return {
    date: '2026-10-13',
    eventInstant: '2026-10-13T06:40:00Z',
    fireInstant: '2026-10-13T02:25:00Z',
    clamped: false,
    fallback: false,
    fallbackReason: null,
    ...overrides,
  };
}

function previewJob(overrides) {
  return {
    jobKey: NIGHTLY,
    cycleType: 'NIGHTLY',
    scheduleType: 'CRON',
    status: 'ACTIVE',
    storedCron: '0 0 1 * * *',
    solarEvent: 'SUNRISE',
    offsetMinutes: 255,
    ruleSource: 'PROPOSED',
    today: day({ fireInstant: inHours(-1) }),
    tomorrow: day({ date: '2026-10-14', fireInstant: inHours(3) }),
    earliest: { date: '2027-06-18', fireLocal: '00:13', fireInstant: '2027-06-17T23:13:00Z' },
    latest: { date: '2026-12-29', fireLocal: '04:15', fireInstant: '2026-12-29T04:15:00Z' },
    medianDurationSeconds: 5400,
    ...overrides,
  };
}

function preview(jobs) {
  return {
    referencePoint: { lat: 54.776, lon: -1.575 },
    generatedAt: '2026-10-13T12:00:00Z',
    jobs,
  };
}

const INTRADAY_PREVIEW = previewJob({
  jobKey: INTRADAY,
  cycleType: 'INTRADAY',
  solarEvent: 'SUNSET',
  offsetMinutes: 240,
  storedCron: '0 0 14 * * *',
});

const BOTH_PREVIEW = preview([previewJob(), INTRADAY_PREVIEW]);

const SOLAR_JOB = job({
  scheduleType: 'SOLAR_OFFSET',
  solarEvent: 'SUNRISE',
  solarOffsetMinutes: 255,
  nextFireTime: '2026-10-14T02:00:00Z',
});

const SOLAR_JOB_INTRADAY = job({
  id: 2,
  jobKey: INTRADAY,
  displayName: 'Intraday refresh',
  scheduleType: 'SOLAR_OFFSET',
  solarEvent: 'SUNSET',
  solarOffsetMinutes: 240,
});

function mount(jobs, previewData = BOTH_PREVIEW) {
  fetchSchedulerJobs.mockResolvedValue(jobs);
  fetchSolarPreview.mockResolvedValue(previewData);
  return render(<SchedulerView />);
}

/** A promise settled from outside, to hold a request in flight. */
function deferred() {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

const REJECT_400 = (error) => ({ response: { status: 400, data: { error } } });

async function openSwitchDialog(jobKey = NIGHTLY) {
  fireEvent.click(await screen.findByTestId(`switch-btn-${jobKey}`));
  await screen.findByRole('dialog');
}

describe('P2c review - switch dialog wording and after-line', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('switching back says the rule is dropped and a re-switch starts from the proposed rule', async () => {
    mount([SOLAR_JOB]);
    await openSwitchDialog();

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('drops the sun-relative rule');
    expect(dialog).toHaveTextContent("starts from the plan's proposed rule");
    expect(dialog).toHaveTextContent('edit before confirming');
    expect(dialog).not.toHaveTextContent('keeps its sun-relative rule');
    expect(dialog).not.toHaveTextContent('same control');
  });

  it("switching back shows the job's own cron time in UK time, not UTC", async () => {
    mount([SOLAR_JOB]);
    await openSwitchDialog();

    const after = screen.getByTestId('switch-after');
    // cron 0 0 1 * * * is 01:00 UTC: 02:00 BST or 01:00 GMT
    expect(after).toHaveTextContent(/Daily at 0[12]:00 (BST|GMT)/);
    expect(after).toHaveTextContent('the stored cron, UK time');
    expect(after).not.toHaveTextContent('UTC');
  });

  it('changing only the event (offset kept) is not previewed', async () => {
    mount([job()]);
    await openSwitchDialog();

    expect(screen.getByTestId('switch-offset')).toHaveValue('255');
    fireEvent.change(screen.getByTestId('switch-event'), { target: { value: 'SUNSET' } });

    expect(screen.getByTestId('switch-after')).toHaveTextContent('not previewed for an edited rule');
    expect(screen.getByTestId('switch-after')).toHaveTextContent('before sunset');
  });

  it.each([[''], ['abc'], ['9999']])(
    'an invalid offset "%s" asks for a valid one rather than quoting a rule',
    async (bad) => {
      mount([job()]);
      await openSwitchDialog();
      fireEvent.change(screen.getByTestId('switch-offset'), { target: { value: bad } });

      const after = screen.getByTestId('switch-after');
      expect(after).toHaveTextContent('Enter a valid offset (0–720 minutes).');
      expect(after).not.toHaveTextContent('At sunrise');
      expect(after).not.toHaveTextContent('not previewed');
    },
  );

  it('a fallback day today is quoted as the stored cron, not skipped to tomorrow', async () => {
    const tomorrow = inHours(30);
    mount(
      [job()],
      preview([
        previewJob({
          today: day({
            eventInstant: null,
            fireInstant: null,
            fallback: true,
            fallbackReason: 'sunrise did not occur',
          }),
          tomorrow: day({ date: '2026-10-14', fireInstant: tomorrow }),
        }),
        INTRADAY_PREVIEW,
      ]),
    );
    await openSwitchDialog();

    const after = screen.getByTestId('switch-after');
    expect(after).toHaveTextContent(
      'Today falls back to the stored cron (sunrise did not occur): Daily at',
    );
    expect(after).toHaveTextContent(formatUkDateTime(tomorrow));
  });
});

describe('P2c review - switch and edit requests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('a second confirm after a 400 sends again', async () => {
    switchScheduleType.mockRejectedValueOnce(REJECT_400('Stored cron expression is not valid'));
    switchScheduleType.mockResolvedValueOnce({});
    mount([job()]);
    await openSwitchDialog();

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    await screen.findByTestId('switch-error');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('a double-click on confirm sends exactly one request', async () => {
    const hold = deferred();
    switchScheduleType.mockReturnValue(hold.promise);
    mount([job()]);
    await openSwitchDialog();

    const confirm = screen.getByTestId('confirm-dialog-confirm');
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    await act(async () => {
      hold.resolve({});
    });

    expect(switchScheduleType).toHaveBeenCalledTimes(1);
  });

  it('reloads the jobs and the preview after a successful switch and relabels the card', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([job()]);
    await openSwitchDialog();
    expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).toHaveTextContent('Switch to sun-relative');
    fetchSchedulerJobs.mockResolvedValue([SOLAR_JOB]);
    const jobsBefore = fetchSchedulerJobs.mock.calls.length;
    const previewBefore = fetchSolarPreview.mock.calls.length;

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() =>
      expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).toHaveTextContent(
        'Switch back to fixed time',
      ),
    );
    expect(fetchSchedulerJobs.mock.calls.length).toBeGreaterThan(jobsBefore);
    expect(fetchSolarPreview.mock.calls.length).toBeGreaterThan(previewBefore);
    expect(screen.getByTestId(`solar-rule-${NIGHTLY}`)).toHaveTextContent('4 h 15 min before sunrise');
  });

  it('reloads the jobs and the preview after a solar edit is saved', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fetchSchedulerJobs.mockResolvedValue([
      { ...SOLAR_JOB, solarEvent: 'SUNSET', solarOffsetMinutes: 240 },
    ]);
    const jobsBefore = fetchSchedulerJobs.mock.calls.length;
    const previewBefore = fetchSolarPreview.mock.calls.length;

    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });
    fireEvent.change(screen.getByLabelText(/Solar event/), { target: { value: 'SUNSET' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() =>
      expect(screen.getByTestId(`solar-rule-${NIGHTLY}`)).toHaveTextContent('4 h before sunset'),
    );
    expect(fetchSchedulerJobs.mock.calls.length).toBeGreaterThan(jobsBefore);
    expect(fetchSolarPreview.mock.calls.length).toBeGreaterThan(previewBefore);
  });

  it('a rejected cron leaves the rule unsent, shows the server sentence and keeps the form open', async () => {
    updateJobSchedule.mockRejectedValue(REJECT_400('Invalid cron expression'));
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));

    fireEvent.change(screen.getByLabelText(/Fallback cron/), { target: { value: 'nonsense' } });
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    expect(await screen.findByTestId(`solar-edit-${NIGHTLY}-error`)).toHaveTextContent(
      'Invalid cron expression',
    );
    expect(switchScheduleType).not.toHaveBeenCalled();
    expect(screen.getByTestId(`solar-edit-${NIGHTLY}`)).toBeInTheDocument();
  });

  it('an error with no server sentence falls back to a plain one, and a retry sends again', async () => {
    switchScheduleType.mockRejectedValueOnce(new Error('network'));
    switchScheduleType.mockResolvedValueOnce({});
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });

    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));
    expect(await screen.findByTestId(`solar-edit-${NIGHTLY}-error`)).toHaveTextContent(
      `Failed to update ${NIGHTLY}.`,
    );
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledTimes(2));
  });

  it('a double-click on save sends exactly one request', async () => {
    const hold = deferred();
    switchScheduleType.mockReturnValue(hold.promise);
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });

    const save = screen.getByTestId(`save-btn-${NIGHTLY}`);
    fireEvent.click(save);
    fireEvent.click(save);
    await act(async () => {
      hold.resolve({});
    });

    expect(switchScheduleType).toHaveBeenCalledTimes(1);
  });

  it('says the cron WAS saved when only the rule failed, and reloads the jobs', async () => {
    updateJobSchedule.mockResolvedValue({});
    switchScheduleType.mockRejectedValue(REJECT_400('Offset out of range'));
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fetchSchedulerJobs.mockResolvedValue([{ ...SOLAR_JOB, cronExpression: '0 30 1 * * *' }]);
    const jobsBefore = fetchSchedulerJobs.mock.calls.length;

    fireEvent.change(screen.getByLabelText(/Fallback cron/), { target: { value: '0 30 1 * * *' } });
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    const error = await screen.findByTestId(`solar-edit-${NIGHTLY}-error`);
    expect(error).toHaveTextContent('The fallback cron was saved, but the sun-relative rule was not');
    expect(error).toHaveTextContent('Offset out of range');
    await waitFor(() => expect(fetchSchedulerJobs.mock.calls.length).toBeGreaterThan(jobsBefore));
    // the card now carries the saved cron, so a retry does not send the cron a second time
    switchScheduleType.mockResolvedValue({});
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));
    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledTimes(2));
    expect(updateJobSchedule).toHaveBeenCalledTimes(1);
  });

  it('refuses a blank fallback cron with a visible message and sends nothing', async () => {
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));

    fireEvent.change(screen.getByLabelText(/Fallback cron/), { target: { value: '   ' } });
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });

    expect(screen.getByTestId(`solar-edit-${NIGHTLY}-cron-error`)).toHaveTextContent(
      'cannot be blank',
    );
    expect(screen.getByTestId(`save-btn-${NIGHTLY}`)).toBeDisabled();
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));
    expect(updateJobSchedule).not.toHaveBeenCalled();
    expect(switchScheduleType).not.toHaveBeenCalled();
  });
});

describe('P2c review - polling, races and a stale preview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  async function advancePoll() {
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });
  }

  it('polls the preview on the same interval as the jobs', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mount([job()]);
      await screen.findByTestId(`switch-btn-${NIGHTLY}`);
      expect(fetchSolarPreview).toHaveBeenCalledTimes(1);

      await advancePoll();

      await waitFor(() => expect(fetchSolarPreview).toHaveBeenCalledTimes(2));
      expect(fetchSchedulerJobs).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('a slow earlier jobs response cannot overwrite a newer one', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const slow = deferred();
      mount([job({ displayName: 'First load' })]);
      await waitFor(() => expect(screen.getByTestId(`scheduler-job-${NIGHTLY}`)).toHaveTextContent('First load'));
      fetchSchedulerJobs.mockReturnValueOnce(slow.promise);
      fetchSchedulerJobs.mockResolvedValue([job({ displayName: 'Fresh load' })]);

      await advancePoll(); // poll 2: held in flight
      await advancePoll(); // poll 3: answers at once
      await waitFor(() => expect(screen.getByTestId(`scheduler-job-${NIGHTLY}`)).toHaveTextContent('Fresh load'));
      await act(async () => {
        slow.resolve([job({ displayName: 'Stale load' })]);
      });

      const card = screen.getByTestId(`scheduler-job-${NIGHTLY}`);
      expect(card).toHaveTextContent('Fresh load');
      expect(card).not.toHaveTextContent('Stale load');
    } finally {
      vi.useRealTimers();
    }
  });

  it('a slow earlier preview response cannot overwrite a newer one', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const slow = deferred();
      mount([job()]);
      await screen.findByTestId('solar-reference');
      fetchSolarPreview.mockReturnValueOnce(slow.promise);
      fetchSolarPreview.mockResolvedValue({ ...BOTH_PREVIEW, referencePoint: { lat: 55, lon: -2 } });

      await advancePoll();
      await advancePoll();
      await waitFor(() =>
        expect(screen.getByTestId('solar-reference')).toHaveTextContent('55.000 N, 2.000 W'),
      );
      await act(async () => {
        slow.resolve({ ...BOTH_PREVIEW, referencePoint: { lat: 10, lon: -3 } });
      });

      expect(screen.getByTestId('solar-reference')).toHaveTextContent('55.000 N, 2.000 W');
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the held preview but says it is stale, and blocks switching to sun-relative until a refresh works', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mount([job(), SOLAR_JOB_INTRADAY]);
      const button = await screen.findByTestId(`switch-btn-${NIGHTLY}`);
      expect(button).not.toBeDisabled();
      expect(screen.queryByTestId('solar-panel-stale')).not.toBeInTheDocument();

      fetchSolarPreview.mockRejectedValue(new Error('down'));
      await advancePoll();

      const notice = await screen.findByTestId('solar-panel-stale');
      expect(notice).toHaveTextContent("Couldn't refresh the preview");
      expect(notice).toHaveTextContent(formatUkDateTime(BOTH_PREVIEW.generatedAt));
      expect(screen.getByTestId('solar-reference')).toBeInTheDocument();
      expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).toBeDisabled();
      // switching a sun-relative job back to fixed time needs no preview
      expect(screen.getByTestId(`switch-btn-${INTRADAY}`)).not.toBeDisabled();

      fetchSolarPreview.mockResolvedValue(BOTH_PREVIEW);
      await advancePoll();
      await waitFor(() =>
        expect(screen.queryByTestId('solar-panel-stale')).not.toBeInTheDocument(),
      );
      expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).not.toBeDisabled();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('P2c review - focus and layout', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('opening the solar editor focuses its first field, found by its label', async () => {
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));

    expect(screen.getByLabelText(/Minutes before the event/)).toBe(document.activeElement);
    expect(screen.getByLabelText(/Solar event/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Fallback cron/)).toBeInTheDocument();
  });

  it("Cancel returns focus to the row's Edit button", async () => {
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.click(screen.getByTestId(`cancel-btn-${NIGHTLY}`));

    await waitFor(() =>
      expect(screen.getByTestId(`edit-btn-${NIGHTLY}`)).toBe(document.activeElement),
    );
  });

  it("a successful Save returns focus to the row's Edit button", async () => {
    switchScheduleType.mockResolvedValue({});
    mount([SOLAR_JOB]);
    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() =>
      expect(screen.getByTestId(`edit-btn-${NIGHTLY}`)).toBe(document.activeElement),
    );
  });

  it('the long config key in the notes can wrap on a narrow screen', async () => {
    mount([job()]);
    const key = await screen.findByText('photocast.pipeline.safety-timeout');
    expect(key).toHaveClass('break-all');
  });

  it('the panel and the job card use different test ids for their rule', async () => {
    mount([SOLAR_JOB]);
    await screen.findByTestId(`solar-panel-rule-${NIGHTLY}`);
    expect(screen.getAllByTestId(`solar-rule-${NIGHTLY}`)).toHaveLength(1);
  });
});
