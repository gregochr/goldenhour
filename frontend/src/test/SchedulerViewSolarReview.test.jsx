import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import { formatUkDateTime } from '../utils/schedulerSolar.js';
import {
  BOTH_PREVIEW, INTRADAY, INTRADAY_PREVIEW, NIGHTLY, day, inHours, job, preview, previewJob, solarIntradayJob,
  solarJob,
} from './schedulerFixtures.js';

vi.mock('../api/schedulerApi', () => ({
  fetchSchedulerJobs: vi.fn(),
  updateJobSchedule: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
  fetchSolarPreview: vi.fn(),
  switchScheduleType: vi.fn(),
  fetchJobRuns: vi.fn(),
}));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));

import {
  fetchSchedulerJobs,
  updateJobSchedule,
  fetchSolarPreview,
  switchScheduleType,
  fetchJobRuns,
} from '../api/schedulerApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';

function mount(jobs, previewData = BOTH_PREVIEW) {
  fetchSchedulerJobs.mockResolvedValue(jobs);
  fetchSolarPreview.mockResolvedValue(previewData);
  return render(<SchedulerView />);
}

/** Opens a job's panel by pressing its row. */
async function openJob(jobKey = NIGHTLY) {
  fireEvent.click(await screen.findByTestId(`scheduler-row-${jobKey}`));
  return screen.findByTestId('scheduler-panel');
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
  await openJob(jobKey);
  fireEvent.click(await screen.findByTestId(`switch-btn-${jobKey}`));
  await screen.findByRole('dialog');
}

/** Opens the solar job's panel and its editor. */
async function openEditor() {
  await openJob();
  fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
}

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/scheduler';
  useIsSplitLayout.mockReturnValue(true);
  fetchJobRuns.mockResolvedValue([]);
});

describe('P2c review - switch dialog wording and after-line', () => {
  it('switching back says the rule is dropped and a re-switch starts from the proposed rule', async () => {
    mount([solarJob()]);
    await openSwitchDialog();

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('drops the sun-relative rule');
    expect(dialog).toHaveTextContent("starts from the plan's proposed rule");
    expect(dialog).toHaveTextContent('edit before confirming');
    expect(dialog).not.toHaveTextContent('keeps its sun-relative rule');
    expect(dialog).not.toHaveTextContent('same control');
  });

  it('switching back shows the job\'s own cron time in UK time, not UTC', async () => {
    mount([solarJob()]);
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
          today: day({ eventInstant: null, fireInstant: null, fallback: true, fallbackReason: 'sunrise did not occur' }),
          tomorrow: day({ date: '2026-10-14', fireInstant: tomorrow }),
        }),
        INTRADAY_PREVIEW,
      ]),
    );
    await openSwitchDialog();

    const after = screen.getByTestId('switch-after');
    expect(after).toHaveTextContent('Today falls back to the stored cron (sunrise did not occur): Daily at');
    expect(after).toHaveTextContent(formatUkDateTime(tomorrow));
  });
});

describe('P2c review - switch and edit requests', () => {
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

  it('reloads the jobs and the preview after a successful switch and relabels the panel', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([job()]);
    await openJob();
    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).toHaveTextContent('Switch to sun-relative');
    fetchSchedulerJobs.mockResolvedValue([solarJob()]);
    const jobsBefore = fetchSchedulerJobs.mock.calls.length;
    const previewBefore = fetchSolarPreview.mock.calls.length;

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() => expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).toHaveTextContent(
      'Switch back to a fixed time',
    ));
    expect(fetchSchedulerJobs.mock.calls.length).toBeGreaterThan(jobsBefore);
    expect(fetchSolarPreview.mock.calls.length).toBeGreaterThan(previewBefore);
    expect(screen.getByTestId(`solar-rule-${NIGHTLY}`)).toHaveTextContent('4 h 15 min before sunrise');
  });

  it('reloads the jobs and the preview after a solar edit is saved', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([solarJob()]);
    await openEditor();
    fetchSchedulerJobs.mockResolvedValue([{ ...solarJob(), solarEvent: 'SUNSET', solarOffsetMinutes: 240 }]);
    const jobsBefore = fetchSchedulerJobs.mock.calls.length;
    const previewBefore = fetchSolarPreview.mock.calls.length;

    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });
    fireEvent.change(screen.getByLabelText(/Solar event/), { target: { value: 'SUNSET' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() => expect(screen.getByTestId(`solar-rule-${NIGHTLY}`)).toHaveTextContent('4 h before sunset'));
    expect(fetchSchedulerJobs.mock.calls.length).toBeGreaterThan(jobsBefore);
    expect(fetchSolarPreview.mock.calls.length).toBeGreaterThan(previewBefore);
  });

  it('a rejected cron leaves the rule unsent, shows the server sentence and keeps the form open', async () => {
    updateJobSchedule.mockRejectedValue(REJECT_400('Invalid cron expression'));
    mount([solarJob()]);
    await openEditor();

    fireEvent.change(screen.getByLabelText(/Fallback cron/), { target: { value: 'nonsense' } });
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    expect(await screen.findByTestId(`solar-edit-${NIGHTLY}-error`)).toHaveTextContent('Invalid cron expression');
    expect(switchScheduleType).not.toHaveBeenCalled();
    expect(screen.getByTestId(`solar-edit-${NIGHTLY}`)).toBeInTheDocument();
  });

  it('an error with no server sentence falls back to a plain one, and a retry sends again', async () => {
    switchScheduleType.mockRejectedValueOnce(new Error('network'));
    switchScheduleType.mockResolvedValueOnce({});
    mount([solarJob()]);
    await openEditor();
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });

    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));
    expect(await screen.findByTestId(`solar-edit-${NIGHTLY}-error`)).toHaveTextContent(`Failed to update ${NIGHTLY}.`);
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledTimes(2));
  });

  it('a double-click on save sends exactly one request', async () => {
    const hold = deferred();
    switchScheduleType.mockReturnValue(hold.promise);
    mount([solarJob()]);
    await openEditor();
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
    mount([solarJob()]);
    await openEditor();
    fetchSchedulerJobs.mockResolvedValue([{ ...solarJob(), cronExpression: '0 30 1 * * *' }]);
    const jobsBefore = fetchSchedulerJobs.mock.calls.length;

    fireEvent.change(screen.getByLabelText(/Fallback cron/), { target: { value: '0 30 1 * * *' } });
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    const error = await screen.findByTestId(`solar-edit-${NIGHTLY}-error`);
    expect(error).toHaveTextContent('The fallback cron was saved, but the sun-relative rule was not');
    expect(error).toHaveTextContent('Offset out of range');
    await waitFor(() => expect(fetchSchedulerJobs.mock.calls.length).toBeGreaterThan(jobsBefore));
    // the panel now carries the saved cron, so a retry does not send the cron a second time
    switchScheduleType.mockResolvedValue({});
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));
    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledTimes(2));
    expect(updateJobSchedule).toHaveBeenCalledTimes(1);
  });

  it('refuses a blank fallback cron with a visible message and sends nothing', async () => {
    mount([solarJob()]);
    await openEditor();

    fireEvent.change(screen.getByLabelText(/Fallback cron/), { target: { value: '   ' } });
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });

    expect(screen.getByTestId(`solar-edit-${NIGHTLY}-cron-error`)).toHaveTextContent('cannot be blank');
    expect(screen.getByTestId(`save-btn-${NIGHTLY}`)).toBeDisabled();
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));
    expect(updateJobSchedule).not.toHaveBeenCalled();
    expect(switchScheduleType).not.toHaveBeenCalled();
  });
});

describe('P2c review - polling, races and a stale preview', () => {
  async function advancePoll() {
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });
  }

  it('polls the preview on the same interval as the jobs', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mount([job()]);
      await openJob();
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
      await waitFor(() => expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveTextContent('First load'));
      fetchSchedulerJobs.mockReturnValueOnce(slow.promise);
      fetchSchedulerJobs.mockResolvedValue([job({ displayName: 'Fresh load' })]);

      await advancePoll(); // poll 2: held in flight
      await advancePoll(); // poll 3: answers at once
      await waitFor(() => expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveTextContent('Fresh load'));
      await act(async () => {
        slow.resolve([job({ displayName: 'Stale load' })]);
      });

      const row = screen.getByTestId(`scheduler-row-${NIGHTLY}`);
      expect(row).toHaveTextContent('Fresh load');
      expect(row).not.toHaveTextContent('Stale load');
    } finally {
      vi.useRealTimers();
    }
  });

  it('a slow earlier preview response cannot overwrite a newer one', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const slow = deferred();
      mount([solarJob()]);
      await openJob();
      await screen.findByTestId('solar-reference');
      fetchSolarPreview.mockReturnValueOnce(slow.promise);
      fetchSolarPreview.mockResolvedValue({ ...BOTH_PREVIEW, referencePoint: { lat: 55, lon: -2 } });

      await advancePoll();
      await advancePoll();
      await waitFor(() => expect(screen.getByTestId('solar-reference')).toHaveTextContent('55.000 N, 2.000 W'));
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
      mount([job(), solarIntradayJob()]);
      await openJob();
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
      fireEvent.click(screen.getByTestId(`scheduler-row-${INTRADAY}`));
      expect(await screen.findByTestId(`switch-btn-${INTRADAY}`)).not.toBeDisabled();
      fireEvent.click(screen.getByTestId(`scheduler-row-${NIGHTLY}`));
      await screen.findByTestId(`switch-btn-${NIGHTLY}`);

      fetchSolarPreview.mockResolvedValue(BOTH_PREVIEW);
      await advancePoll();
      await waitFor(() => expect(screen.queryByTestId('solar-panel-stale')).not.toBeInTheDocument());
      expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).not.toBeDisabled();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('P2c review - focus and layout', () => {
  it('opening the solar editor focuses its first field, found by its label', async () => {
    mount([solarJob()]);
    await openEditor();

    expect(screen.getByLabelText(/Minutes before the event/)).toBe(document.activeElement);
    expect(screen.getByLabelText(/Solar event/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Fallback cron/)).toBeInTheDocument();
  });

  it("Cancel returns focus to the panel's Edit button", async () => {
    mount([solarJob()]);
    await openEditor();
    fireEvent.click(screen.getByTestId(`cancel-btn-${NIGHTLY}`));

    await waitFor(() => expect(screen.getByTestId(`edit-btn-${NIGHTLY}`)).toBe(document.activeElement));
  });

  it("a successful Save returns focus to the panel's Edit button", async () => {
    switchScheduleType.mockResolvedValue({});
    mount([solarJob()]);
    await openEditor();
    fireEvent.change(screen.getByLabelText(/Minutes before the event/), { target: { value: '240' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() => expect(screen.getByTestId(`edit-btn-${NIGHTLY}`)).toBe(document.activeElement));
  });

  it('Esc inside the solar editor steps back from it, and the next Esc closes the panel', async () => {
    mount([solarJob()]);
    await openEditor();

    fireEvent.keyDown(screen.getByLabelText(/Minutes before the event/), { key: 'Escape' });
    expect(screen.queryByTestId(`solar-edit-${NIGHTLY}`)).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
  });

  it('the Next fires section and the Schedule section use different test ids for their rule', async () => {
    mount([solarJob()]);
    await openJob();
    await screen.findByTestId(`solar-panel-rule-${NIGHTLY}`);
    expect(screen.getAllByTestId(`solar-rule-${NIGHTLY}`)).toHaveLength(1);
  });
});
