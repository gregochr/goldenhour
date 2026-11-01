import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import { formatUkDateTime } from '../utils/schedulerSolar.js';
import {
  BOTH_PREVIEW, EVENING, INTRADAY, INTRADAY_PREVIEW, NIGHTLY, day, inHours, job, pollingJob, preview, previewJob,
  solarIntradayJob, solarJob, tideJob,
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

// The sun-relative parts of the Scheduler tab (solar-relative-refresh-plan §5), now in each cycle job's panel: the
// explicit branch per schedule type, the switch between a fixed time and a sun-relative one, and the Next fires section
// that the old Sun-relative forecast cycles card held.

function mount(jobs, previewData = BOTH_PREVIEW) {
  fetchSchedulerJobs.mockResolvedValue(jobs);
  if (previewData === null) {
    fetchSolarPreview.mockRejectedValue(new Error('down'));
  } else {
    fetchSolarPreview.mockResolvedValue(previewData);
  }
  return render(<SchedulerView />);
}

/** Opens a job's panel by pressing its row. */
async function openJob(jobKey = NIGHTLY) {
  fireEvent.click(await screen.findByTestId(`scheduler-row-${jobKey}`));
  return screen.findByTestId('scheduler-panel');
}

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/scheduler';
  useIsSplitLayout.mockReturnValue(true);
  fetchJobRuns.mockResolvedValue([]);
});

describe('SchedulerView - explicit branch per schedule type', () => {
  it('shows each schedule type in its own form', async () => {
    mount([tideJob(), pollingJob({ nextFireTime: inHours(1) }), solarJob()]);

    const cron = await openJob('tide_refresh');
    expect(cron).toHaveTextContent('0 0 2 * * MON');
    fireEvent.click(screen.getByTestId('scheduler-row-aurora_polling'));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Aurora Polling'));
    expect(screen.getByTestId('scheduler-panel')).toHaveTextContent('Every 5 minutes');

    fireEvent.click(screen.getByTestId(`scheduler-row-${NIGHTLY}`));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Nightly batch evaluation'));
    const solar = screen.getByTestId('scheduler-panel');
    expect(within(solar).getByTestId(`solar-rule-${NIGHTLY}`)).toHaveTextContent('4 h 15 min before sunrise');
    expect(within(solar).getByTestId('scheduler-panel-schedule')).toHaveTextContent('never before 00:05 UK');
    // the fallback cron carries its zone label and its reading
    expect(within(solar).getByTestId(`solar-fallback-${NIGHTLY}`)).toHaveTextContent('(UTC)');
    expect(within(solar).getByTestId(`solar-fallback-${NIGHTLY}`)).toHaveTextContent('0 0 1 * * *');
    // 02:00Z on 14 Oct is 03:00 BST; the next fire is in the panel's meta line, not a row of Schedule
    expect(within(solar).getByTestId('scheduler-panel-meta')).toHaveTextContent('03:00');
    expect(within(solar).queryByTestId(`schedule-next-${NIGHTLY}`)).not.toBeInTheDocument();
    expect(solar).not.toHaveTextContent('Every 0');
  });

  it('shows the reference point and when it was computed in a sun-relative job\'s Schedule section', async () => {
    mount([solarJob()]);
    await openJob();
    const reference = screen.getByTestId('solar-reference');
    expect(within(screen.getByTestId('scheduler-panel-schedule')).getByTestId('solar-reference')).toBe(reference);
    expect(reference).toHaveTextContent('54.776 N, 1.575 W');
    expect(reference).toHaveTextContent(`Computed ${formatUkDateTime(BOTH_PREVIEW.generatedAt)}`);
  });

  it('editing a solar rule goes through the schedule-type endpoint and never sends a fixed delay', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([solarJob()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByTestId(`solar-edit-${NIGHTLY}-offset`), { target: { value: '240' } });
    fireEvent.change(screen.getByTestId(`solar-edit-${NIGHTLY}-event`), { target: { value: 'SUNSET' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledWith(NIGHTLY, {
      scheduleType: 'SOLAR_OFFSET',
      solarEvent: 'SUNSET',
      solarOffsetMinutes: 240,
    }));
    expect(updateJobSchedule).not.toHaveBeenCalled();
  });

  it('correcting a solar job\'s fallback cron sends the cron alone', async () => {
    updateJobSchedule.mockResolvedValue({});
    mount([solarJob()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByTestId(`solar-edit-${NIGHTLY}-cron`), { target: { value: '0 30 1 * * *' } });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() => expect(updateJobSchedule).toHaveBeenCalledWith(NIGHTLY, { cronExpression: '0 30 1 * * *' }));
    expect(updateJobSchedule.mock.calls[0][1]).not.toHaveProperty('fixedDelayMs');
    expect(switchScheduleType).not.toHaveBeenCalled();
  });

  it('a solar edit with an invalid offset cannot be saved', async () => {
    mount([solarJob()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByTestId(`solar-edit-${NIGHTLY}-offset`), { target: { value: '9999' } });

    expect(screen.getByTestId(`save-btn-${NIGHTLY}`)).toBeDisabled();
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));
    expect(switchScheduleType).not.toHaveBeenCalled();
    expect(screen.getByTestId(`solar-edit-${NIGHTLY}-offset-error`)).toHaveTextContent('0 and 720');
  });
});

describe('SchedulerView - switching a forecast cycle between fixed and sun-relative', () => {
  it('offers the switch only on the two forecast cycle jobs', async () => {
    mount([tideJob(), job(), job({ id: 2, jobKey: INTRADAY, displayName: 'Intraday refresh' })]);

    await openJob(NIGHTLY);
    expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).toHaveTextContent('Switch to sun-relative…');
    fireEvent.click(screen.getByTestId(`scheduler-row-${INTRADAY}`));
    await screen.findByTestId(`switch-btn-${INTRADAY}`);
    fireEvent.click(screen.getByTestId('scheduler-row-tide_refresh'));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Tide Refresh'));
    expect(screen.queryByTestId('switch-btn-tide_refresh')).not.toBeInTheDocument();
  });

  it('offers no switch on the evening sunrise refresh, but still its timing notes and its Run now words', async () => {
    mount([job({ id: 3, jobKey: EVENING, displayName: 'Evening refresh' })]);
    await openJob(EVENING);

    expect(screen.queryByTestId(`switch-btn-${EVENING}`)).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-panel-next-fires')).not.toBeInTheDocument();
    expect(screen.getByTestId('solar-notes-toggle')).toBeInTheDocument();
    expect(screen.getByTestId(`run-now-text-${EVENING}`)).toHaveTextContent("Starts a cycle straight away. Tonight's still runs.");
  });

  it('asks for confirmation, quotes before and after, and sends nothing until confirmed', async () => {
    mount([job()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));

    const dialog = await screen.findByRole('dialog', { name: 'Switch to a sun-relative time?' });
    expect(switchScheduleType).not.toHaveBeenCalled();
    // before: the job's own next fire (00:00Z 14 Oct = 01:00 BST)
    expect(within(dialog).getByTestId('switch-before')).toHaveTextContent('01:00 UK');
    // after: the preview's next usable fire in the future (tomorrow's, 3 h away)
    expect(within(dialog).getByTestId('switch-after')).toHaveTextContent(
      formatUkDateTime(BOTH_PREVIEW.jobs[0].tomorrow.fireInstant),
    );
    expect(within(dialog).getByTestId('switch-after')).toHaveTextContent('4 h 15 min before sunrise every day');
    expect(dialog).not.toHaveTextContent('same control');
  });

  it('pre-fills the rule from the preview and sends it on confirm (nightly: sunrise 255)', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([job()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    expect(screen.getByTestId('switch-offset')).toHaveValue('255');
    expect(screen.getByTestId('switch-event')).toHaveValue('SUNRISE');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledWith(NIGHTLY, {
      scheduleType: 'SOLAR_OFFSET',
      solarEvent: 'SUNRISE',
      solarOffsetMinutes: 255,
    }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('pre-fills the afternoon cycle from its own proposed rule (sunset 240)', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([job({ id: 2, jobKey: INTRADAY, displayName: 'Intraday refresh', cronExpression: '0 0 14 * * *' })]);
    await openJob(INTRADAY);

    fireEvent.click(await screen.findByTestId(`switch-btn-${INTRADAY}`));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledWith(INTRADAY, {
      scheduleType: 'SOLAR_OFFSET',
      solarEvent: 'SUNSET',
      solarOffsetMinutes: 240,
    }));
  });

  it('cancelling sends nothing', async () => {
    mount([job()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(switchScheduleType).not.toHaveBeenCalled();
  });

  it('switching back to a fixed time sends CRON only, after confirmation', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([solarJob()]);
    await openJob();

    expect(await screen.findByTestId(`switch-btn-${NIGHTLY}`)).toHaveTextContent('Switch back to a fixed time…');
    fireEvent.click(screen.getByTestId(`switch-btn-${NIGHTLY}`));
    const dialog = await screen.findByRole('dialog', { name: 'Switch back to a fixed time?' });
    expect(switchScheduleType).not.toHaveBeenCalled();
    expect(within(dialog).getByTestId('switch-after')).toHaveTextContent('Daily at');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledWith(NIGHTLY, { scheduleType: 'CRON' }));
  });

  it('shows the server\'s sentence on a 400 and keeps the dialog open', async () => {
    switchScheduleType.mockRejectedValue({
      response: { status: 400, data: { error: 'Stored cron expression is not valid' } },
    });
    mount([job()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(await screen.findByTestId('switch-error')).toHaveTextContent('Stored cron expression is not valid');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it.each([['721'], ['-5'], ['abc'], ['4.5'], ['']])(
    'refuses an offset of "%s" client-side and sends nothing',
    async (bad) => {
      mount([job()]);
      await openJob();

      fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
      await screen.findByRole('dialog');
      fireEvent.change(screen.getByTestId('switch-offset'), { target: { value: bad } });

      expect(screen.getByTestId('switch-offset-error')).toBeInTheDocument();
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
      expect(switchScheduleType).not.toHaveBeenCalled();
    },
  );

  it.each([['0'], ['720']])('accepts the boundary offset %s', async (edge) => {
    switchScheduleType.mockResolvedValue({});
    mount([job()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByTestId('switch-offset'), { target: { value: edge } });
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() => expect(switchScheduleType).toHaveBeenCalledWith(
      NIGHTLY,
      expect.objectContaining({ solarOffsetMinutes: Number(edge) }),
    ));
  });

  it('says an edited rule is not previewed rather than quoting the proposed rule\'s time', async () => {
    mount([job()]);
    await openJob();

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByTestId('switch-offset'), { target: { value: '200' } });

    expect(screen.getByTestId('switch-after')).toHaveTextContent('not previewed for an edited rule');
  });

  it('cannot switch to sun-relative while the preview is unavailable, and says why in Next fires', async () => {
    mount([job()], null);
    await openJob();

    const button = await screen.findByTestId(`switch-btn-${NIGHTLY}`);
    await waitFor(() => expect(screen.getByTestId('solar-panel-unavailable')).toHaveTextContent(
      'Solar preview unavailable',
    ));
    expect(button).toBeDisabled();
  });

  it('a solar job can still be switched back when the preview is unavailable', async () => {
    mount([solarJob()], null);
    await openJob();

    await screen.findByTestId('solar-panel-unavailable');
    expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).not.toBeDisabled();
  });

  it('says the preview is loading, not unavailable, until it answers', async () => {
    fetchSchedulerJobs.mockResolvedValue([job()]);
    fetchSolarPreview.mockReturnValue(new Promise(() => {}));
    render(<SchedulerView />);
    await openJob();

    expect(await screen.findByTestId('solar-panel-unavailable')).toHaveTextContent('Loading solar preview\u2026');
    expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).toBeDisabled();
  });
});

describe('Next fires - fed by the preview', () => {
  it('labels a still-fixed job\'s rule as proposed, under that label, and says nothing more for a solar job', async () => {
    mount(
      [job(), solarIntradayJob()],
      preview([previewJob(), { ...INTRADAY_PREVIEW, ruleSource: 'STORED', scheduleType: 'SOLAR_OFFSET' }]),
    );

    await openJob(NIGHTLY);
    const nightly = await screen.findByTestId(`solar-panel-${NIGHTLY}`);
    expect(within(nightly).getByTestId(`solar-proposed-label-${NIGHTLY}`)).toHaveTextContent('Proposed rule');
    expect(within(nightly).getByTestId(`solar-rule-source-${NIGHTLY}`)).toHaveTextContent('Proposed rule');
    expect(within(nightly).getByTestId(`solar-panel-rule-${NIGHTLY}`)).toHaveTextContent('4 h 15 min before sunrise');
    expect(within(nightly).getByTestId(`solar-panel-rule-${NIGHTLY}`)).toHaveTextContent('never before 00:05 UK');
    expect(within(nightly).getByTestId(`solar-rule-source-${NIGHTLY}`)).toHaveTextContent(
      'this job is still on its CRON schedule',
    );

    fireEvent.click(screen.getByTestId(`scheduler-row-${INTRADAY}`));
    const intraday = await screen.findByTestId(`solar-panel-${INTRADAY}`);
    // a sun-relative job's rule is in Schedule above: Next fires repeats it only under the proposed label
    expect(within(intraday).queryByTestId(`solar-proposed-label-${INTRADAY}`)).not.toBeInTheDocument();
    expect(within(intraday).queryByTestId(`solar-rule-source-${INTRADAY}`)).not.toBeInTheDocument();
    expect(within(intraday).queryByTestId(`solar-panel-rule-${INTRADAY}`)).not.toBeInTheDocument();
    expect(screen.getByTestId(`solar-rule-${INTRADAY}`)).toHaveTextContent('4 h before sunset');
  });

  it('shows the figures the preview carries: event and trigger times, finish, year, reference point', async () => {
    mount(
      [job()],
      preview([
        previewJob({
          today: day({ eventInstant: '2026-10-13T06:40:00Z', fireInstant: '2026-10-13T02:25:00Z' }),
          tomorrow: day({
            date: '2026-10-14',
            eventInstant: '2026-10-14T06:42:00Z',
            fireInstant: '2026-10-14T02:27:00Z',
          }),
        }),
      ]),
    );
    await openJob();

    const today = await screen.findByTestId(`solar-today-${NIGHTLY}`);
    // 06:40Z = 07:40 BST sunrise; 02:25Z = 03:25 BST trigger
    expect(today).toHaveTextContent('07:40 UK');
    expect(today).toHaveTextContent('03:25 UK');
    expect(screen.getByTestId(`solar-tomorrow-${NIGHTLY}`)).toHaveTextContent('03:27 UK');
    // finish = trigger + median 90 min = 04:55 BST
    expect(screen.getByTestId(`solar-finish-${NIGHTLY}`)).toHaveTextContent('04:55 UK');
    expect(screen.getByTestId(`solar-finish-${NIGHTLY}`)).toHaveTextContent('1 h 30 min');
    const year = screen.getByTestId(`solar-year-${NIGHTLY}`);
    expect(year).toHaveTextContent('00:13 UK on 18 Jun');
    expect(year).toHaveTextContent('04:15 UK on 29 Dec');
    expect(screen.getByTestId('solar-reference')).toHaveTextContent('54.776 N, 1.575 W');
    expect(screen.getByTestId('solar-reference')).toHaveTextContent(
      `Computed ${formatUkDateTime(BOTH_PREVIEW.generatedAt)}`,
    );
  });

  const PAST_AND_FUTURE = {
    today: day({ fireInstant: inHours(-1) }),
    tomorrow: day({ date: '2026-10-14', fireInstant: inHours(5) }),
  };

  it('marks a past fire "ran" under an active sun-relative job, and leaves a future one bare', async () => {
    mount([solarJob()], preview([previewJob({ ...PAST_AND_FUTURE, ruleSource: 'STORED', scheduleType: 'SOLAR_OFFSET' })]));
    await openJob();

    expect(await screen.findByTestId(`solar-today-${NIGHTLY}-ran`)).toHaveTextContent('ran');
    expect(screen.queryByTestId(`solar-tomorrow-${NIGHTLY}-ran`)).not.toBeInTheDocument();
  });

  it('never says "ran" under a proposed rule: a job still on a fixed time has not fired it', async () => {
    mount([job()], preview([previewJob(PAST_AND_FUTURE)]));
    await openJob();

    await screen.findByTestId(`solar-today-${NIGHTLY}`);
    expect(screen.queryByTestId(`solar-today-${NIGHTLY}-ran`)).not.toBeInTheDocument();
  });

  it('never says "ran" for a paused job, even one that is sun-relative', async () => {
    mount([solarJob({ status: 'PAUSED' })], preview([previewJob({
      ...PAST_AND_FUTURE, ruleSource: 'STORED', scheduleType: 'SOLAR_OFFSET', status: 'PAUSED',
    })]));
    await openJob();

    await screen.findByTestId(`solar-today-${NIGHTLY}`);
    expect(screen.queryByTestId(`solar-today-${NIGHTLY}-ran`)).not.toBeInTheDocument();
  });

  it('says so when there is no median to take a finish time from', async () => {
    mount([job()], preview([previewJob({ medianDurationSeconds: null })]));
    await openJob();

    expect(await screen.findByTestId(`solar-finish-${NIGHTLY}`)).toHaveTextContent('Not available (no completed cycle');
  });

  it('shows the clamped and fallback flags the preview sets, in amber', async () => {
    mount(
      [job()],
      preview([
        previewJob({
          // 00:05 UK on 13 Oct (BST) is 23:05Z on the 12th
          today: day({ clamped: true, fireInstant: '2026-10-12T23:05:00Z' }),
          tomorrow: day({
            date: '2026-10-14',
            eventInstant: null,
            fireInstant: null,
            fallback: true,
            fallbackReason: 'sunrise did not occur',
          }),
        }),
      ]),
    );
    await openJob();

    const today = await screen.findByTestId(`solar-today-${NIGHTLY}`);
    expect(today).toHaveTextContent('clamped to 00:05 UK');
    expect(today).toHaveTextContent('00:05 UK');
    expect(within(today).getByText('(clamped to 00:05 UK)').className).toContain('text-verdict-marginal');
    const tomorrow = screen.getByTestId(`solar-tomorrow-${NIGHTLY}`);
    expect(tomorrow).toHaveTextContent('Stored cron used: sunrise did not occur');
    expect(within(tomorrow).getByText(/Stored cron used/).className).toContain('text-verdict-marginal');
    // the day with no sunrise has no event time to show
    expect(tomorrow).toHaveTextContent('n/a');
  });

  it('keeps the job rows and the panel usable when the preview request fails', async () => {
    mount([job()], null);
    await openJob();

    expect(await screen.findByTestId('solar-panel-unavailable')).toBeInTheDocument();
    expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toBeInTheDocument();
    expect(screen.getByTestId(`trigger-btn-${NIGHTLY}`)).toBeEnabled();
  });

  it('says the preview holds no figures for a job it does not cover', async () => {
    mount([job()], preview([INTRADAY_PREVIEW]));
    await openJob();

    expect(await screen.findByTestId('solar-panel-no-entry')).toBeInTheDocument();
  });
});

describe('How sun-relative timing works', () => {
  async function openNotes() {
    mount([job()]);
    await openJob();
    fireEvent.click(await screen.findByTestId('solar-notes-toggle'));
    return screen.findByTestId('solar-notes');
  }

  it('is a closed disclosure until pressed', async () => {
    mount([job()]);
    await openJob();

    const toggle = await screen.findByTestId('solar-notes-toggle');
    expect(toggle).toHaveTextContent('How sun-relative timing works');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    // always rendered (so aria-controls resolves), hidden until pressed
    expect(toggle).toHaveAttribute('aria-controls', screen.getByTestId('solar-notes-body').id);
    expect(screen.getByTestId('solar-notes-body')).not.toBeVisible();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('solar-notes-body')).toBeVisible();
  });

  it('holds the four notes, word for word: catch-up windows and the safety budget among them', async () => {
    const notes = await openNotes();

    expect(within(notes).getAllByRole('listitem')).toHaveLength(4);
    expect(notes).toHaveTextContent('before 05:00 UK');
    expect(notes).toHaveTextContent('90 minutes before sunset');
    expect(notes).toHaveTextContent('3 h 30 min');
    expect(notes).toHaveTextContent('Accepted: in midsummer the morning cycle fires shortly after midnight UK');
    expect(notes).toHaveTextContent('A falling-back day (shown above) uses the job\'s stored cron');
    // the old last sentence pointed at the job's card, which is gone: it names the link in Schedule instead
    expect(notes).toHaveTextContent('is the \u201cSwitch back to a fixed time\u2026\u201d link in Schedule above');
    expect(notes).not.toHaveTextContent('the job\'s card');
  });

  it('lets the long config keys in the notes wrap on a narrow screen', async () => {
    await openNotes();

    expect(screen.getByText('photocast.pipeline.safety-timeout')).toHaveClass('break-all');
    expect(screen.getByText('photocast.pipeline.evening-safety-timeout')).toHaveClass('break-all');
  });

  it('is on a cycle job and a sun-relative job, and on no other', async () => {
    mount([tideJob(), pollingJob(), job()]);
    await openJob(NIGHTLY);
    expect(await screen.findByTestId('solar-notes-toggle')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('scheduler-row-tide_refresh'));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Tide Refresh'));
    expect(screen.queryByTestId('solar-notes-toggle')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('scheduler-row-aurora_polling'));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Aurora Polling'));
    expect(screen.queryByTestId('solar-notes-toggle')).not.toBeInTheDocument();
  });
});
