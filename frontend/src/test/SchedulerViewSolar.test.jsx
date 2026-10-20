import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
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

const TIDE = job({
  id: 9,
  jobKey: 'tide_refresh',
  displayName: 'Tide Refresh',
  cronExpression: '0 0 2 * * MON',
});

const SOLAR_JOB = job({
  scheduleType: 'SOLAR_OFFSET',
  solarEvent: 'SUNRISE',
  solarOffsetMinutes: 255,
  nextFireTime: '2026-10-14T02:00:00Z',
});

function mount(jobs, previewData = BOTH_PREVIEW) {
  fetchSchedulerJobs.mockResolvedValue(jobs);
  if (previewData === null) {
    fetchSolarPreview.mockRejectedValue(new Error('down'));
  } else {
    fetchSolarPreview.mockResolvedValue(previewData);
  }
  return render(<SchedulerView />);
}

describe('SchedulerView - explicit branch per schedule type', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows each schedule type in its own form', async () => {
    mount([
      TIDE,
      job({
        id: 3,
        jobKey: 'aurora_polling',
        displayName: 'Aurora Polling',
        scheduleType: 'FIXED_DELAY',
        cronExpression: '',
        fixedDelayMs: 300000,
      }),
      SOLAR_JOB,
    ]);

    const cron = await screen.findByTestId('scheduler-job-tide_refresh');
    expect(cron).toHaveTextContent('0 0 2 * * MON');
    expect(screen.getByTestId('scheduler-job-aurora_polling')).toHaveTextContent('Every 5 minutes');

    const solar = screen.getByTestId(`scheduler-job-${NIGHTLY}`);
    expect(within(solar).getByTestId(`solar-rule-${NIGHTLY}`)).toHaveTextContent(
      '4 h 15 min before sunrise',
    );
    expect(solar).toHaveTextContent('Fallback cron (UTC)');
    // 02:00Z on 14 Oct is 03:00 BST
    expect(within(solar).getByTestId(`solar-next-${NIGHTLY}`)).toHaveTextContent('03:00 UK');
    expect(solar).not.toHaveTextContent('Every 0');
  });

  it('renders an unknown schedule type as unrecognised, with no edit and no switch', async () => {
    mount([job({ scheduleType: 'WEEKLY_MAGIC', cronExpression: '', nextFireTime: '' })]);

    const card = await screen.findByTestId(`scheduler-job-${NIGHTLY}`);
    expect(within(card).getByTestId(`unrecognised-${NIGHTLY}`)).toHaveTextContent(
      'Unrecognised schedule type: WEEKLY_MAGIC',
    );
    expect(screen.queryByTestId(`edit-btn-${NIGHTLY}`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`switch-btn-${NIGHTLY}`)).not.toBeInTheDocument();
  });

  it('an empty schedule type is also unrecognised, never an empty fixed delay', async () => {
    mount([job({ scheduleType: '', cronExpression: '' })]);

    expect(await screen.findByTestId(`unrecognised-${NIGHTLY}`)).toBeInTheDocument();
    expect(screen.queryByTestId(`edit-btn-${NIGHTLY}`)).not.toBeInTheDocument();
  });

  it('editing a solar rule goes through the schedule-type endpoint and never sends a fixed delay', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([SOLAR_JOB]);

    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByTestId(`solar-edit-${NIGHTLY}-offset`), {
      target: { value: '240' },
    });
    fireEvent.change(screen.getByTestId(`solar-edit-${NIGHTLY}-event`), {
      target: { value: 'SUNSET' },
    });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() =>
      expect(switchScheduleType).toHaveBeenCalledWith(NIGHTLY, {
        scheduleType: 'SOLAR_OFFSET',
        solarEvent: 'SUNSET',
        solarOffsetMinutes: 240,
      }),
    );
    expect(updateJobSchedule).not.toHaveBeenCalled();
  });

  it('correcting a solar row\'s fallback cron sends the cron alone', async () => {
    updateJobSchedule.mockResolvedValue({});
    mount([SOLAR_JOB]);

    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByTestId(`solar-edit-${NIGHTLY}-cron`), {
      target: { value: '0 30 1 * * *' },
    });
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));

    await waitFor(() =>
      expect(updateJobSchedule).toHaveBeenCalledWith(NIGHTLY, { cronExpression: '0 30 1 * * *' }),
    );
    expect(updateJobSchedule.mock.calls[0][1]).not.toHaveProperty('fixedDelayMs');
    expect(switchScheduleType).not.toHaveBeenCalled();
  });

  it('a solar edit with an invalid offset cannot be saved', async () => {
    mount([SOLAR_JOB]);

    fireEvent.click(await screen.findByTestId(`edit-btn-${NIGHTLY}`));
    fireEvent.change(screen.getByTestId(`solar-edit-${NIGHTLY}-offset`), {
      target: { value: '9999' },
    });

    expect(screen.getByTestId(`save-btn-${NIGHTLY}`)).toBeDisabled();
    fireEvent.click(screen.getByTestId(`save-btn-${NIGHTLY}`));
    expect(switchScheduleType).not.toHaveBeenCalled();
    expect(screen.getByTestId(`solar-edit-${NIGHTLY}-offset-error`)).toHaveTextContent('0 and 720');
  });
});

describe('SchedulerView - switching a forecast cycle between fixed and sun-relative', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('offers the switch only on the two forecast cycle jobs', async () => {
    mount([TIDE, job(), job({ id: 2, jobKey: INTRADAY, displayName: 'Intraday refresh' })]);

    await screen.findByTestId(`switch-btn-${NIGHTLY}`);
    expect(screen.getByTestId(`switch-btn-${INTRADAY}`)).toBeInTheDocument();
    expect(screen.queryByTestId('switch-btn-tide_refresh')).not.toBeInTheDocument();
  });

  it('offers no switch on the evening sunrise refresh', async () => {
    mount([job(), job({ id: 3, jobKey: 'evening_sunrise_refresh', displayName: 'Evening refresh' })]);

    await screen.findByTestId(`switch-btn-${NIGHTLY}`);
    expect(screen.getByText('Evening refresh')).toBeInTheDocument();
    expect(screen.queryByTestId('switch-btn-evening_sunrise_refresh')).not.toBeInTheDocument();
  });

  it('asks for confirmation, quotes before and after, and sends nothing until confirmed', async () => {
    mount([job()]);

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));

    const dialog = await screen.findByRole('dialog', { name: 'Switch to a sun-relative time?' });
    expect(switchScheduleType).not.toHaveBeenCalled();
    // before: the job's own next fire (00:00Z 14 Oct = 01:00 BST)
    expect(within(dialog).getByTestId('switch-before')).toHaveTextContent('01:00 UK');
    // after: the preview's next usable fire in the future (tomorrow's, 3 h away)
    expect(within(dialog).getByTestId('switch-after')).toHaveTextContent(
      formatUkDateTime(BOTH_PREVIEW.jobs[0].tomorrow.fireInstant),
    );
    expect(within(dialog).getByTestId('switch-after')).toHaveTextContent(
      '4 h 15 min before sunrise every day',
    );
    expect(dialog).not.toHaveTextContent('same control');
  });

  it('pre-fills the rule from the preview and sends it on confirm (nightly: sunrise 255)', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([job()]);

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    expect(screen.getByTestId('switch-offset')).toHaveValue('255');
    expect(screen.getByTestId('switch-event')).toHaveValue('SUNRISE');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() =>
      expect(switchScheduleType).toHaveBeenCalledWith(NIGHTLY, {
        scheduleType: 'SOLAR_OFFSET',
        solarEvent: 'SUNRISE',
        solarOffsetMinutes: 255,
      }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('pre-fills the afternoon cycle from its own proposed rule (sunset 240)', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([
      job({ id: 2, jobKey: INTRADAY, displayName: 'Intraday refresh', cronExpression: '0 0 14 * * *' }),
    ]);

    fireEvent.click(await screen.findByTestId(`switch-btn-${INTRADAY}`));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() =>
      expect(switchScheduleType).toHaveBeenCalledWith(INTRADAY, {
        scheduleType: 'SOLAR_OFFSET',
        solarEvent: 'SUNSET',
        solarOffsetMinutes: 240,
      }),
    );
  });

  it('cancelling sends nothing', async () => {
    mount([job()]);

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(switchScheduleType).not.toHaveBeenCalled();
  });

  it('switching back to a fixed time sends CRON only, after confirmation', async () => {
    switchScheduleType.mockResolvedValue({});
    mount([SOLAR_JOB]);

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    const dialog = await screen.findByRole('dialog', { name: 'Switch back to a fixed time?' });
    expect(switchScheduleType).not.toHaveBeenCalled();
    expect(within(dialog).getByTestId('switch-after')).toHaveTextContent('Daily at');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() =>
      expect(switchScheduleType).toHaveBeenCalledWith(NIGHTLY, { scheduleType: 'CRON' }),
    );
  });

  it('shows the server\'s sentence on a 400 and keeps the dialog open', async () => {
    switchScheduleType.mockRejectedValue({
      response: { status: 400, data: { error: 'Stored cron expression is not valid' } },
    });
    mount([job()]);

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(await screen.findByTestId('switch-error')).toHaveTextContent(
      'Stored cron expression is not valid',
    );
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it.each([['721'], ['-5'], ['abc'], ['4.5'], ['']])(
    'refuses an offset of "%s" client-side and sends nothing',
    async (bad) => {
      mount([job()]);

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

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByTestId('switch-offset'), { target: { value: edge } });
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    await waitFor(() =>
      expect(switchScheduleType).toHaveBeenCalledWith(
        NIGHTLY,
        expect.objectContaining({ solarOffsetMinutes: Number(edge) }),
      ),
    );
  });

  it('says an edited rule is not previewed rather than quoting the proposed rule\'s time', async () => {
    mount([job()]);

    fireEvent.click(await screen.findByTestId(`switch-btn-${NIGHTLY}`));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByTestId('switch-offset'), { target: { value: '200' } });

    expect(screen.getByTestId('switch-after')).toHaveTextContent('not previewed for an edited rule');
  });

  it('cannot switch to sun-relative while the preview is unavailable', async () => {
    mount([job()], null);

    const button = await screen.findByTestId(`switch-btn-${NIGHTLY}`);
    await waitFor(() => expect(screen.getByTestId('solar-panel-unavailable')).toHaveTextContent(
      'Solar preview unavailable',
    ));
    expect(button).toBeDisabled();
  });

  it('a solar job can still be switched back when the preview is unavailable', async () => {
    mount([SOLAR_JOB], null);

    await screen.findByTestId('solar-panel-unavailable');
    expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).not.toBeDisabled();
  });
});

describe('SolarSchedulePanel - fed by the preview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('labels a still-fixed job\'s rule as proposed and a solar job\'s as current', async () => {
    mount(
      [job(), SOLAR_JOB_INTRADAY()],
      preview([
        previewJob(),
        { ...INTRADAY_PREVIEW, ruleSource: 'STORED', scheduleType: 'SOLAR_OFFSET' },
      ]),
    );

    const nightly = await screen.findByTestId(`solar-panel-${NIGHTLY}`);
    expect(within(nightly).getByTestId(`solar-rule-source-${NIGHTLY}`)).toHaveTextContent(
      'Proposed rule',
    );
    expect(within(nightly).getByTestId(`solar-panel-rule-${NIGHTLY}`)).toHaveTextContent(
      '4 h 15 min before sunrise',
    );
    const intraday = screen.getByTestId(`solar-panel-${INTRADAY}`);
    expect(within(intraday).getByTestId(`solar-rule-source-${INTRADAY}`)).toHaveTextContent(
      'Current rule',
    );
    expect(within(intraday).getByTestId(`solar-panel-rule-${INTRADAY}`)).toHaveTextContent(
      '4 h before sunset',
    );
  });

  it('shows the figures the preview carries: event and trigger times, finish, year, reference point', async () => {
    mount(
      [job()],
      preview([
        previewJob({
          today: day({
            eventInstant: '2026-10-13T06:40:00Z',
            fireInstant: '2026-10-13T02:25:00Z',
          }),
          tomorrow: day({
            date: '2026-10-14',
            eventInstant: '2026-10-14T06:42:00Z',
            fireInstant: '2026-10-14T02:27:00Z',
          }),
        }),
      ]),
    );

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
  });

  it('says so when there is no median to take a finish time from', async () => {
    mount([job()], preview([previewJob({ medianDurationSeconds: null })]));

    expect(await screen.findByTestId(`solar-finish-${NIGHTLY}`)).toHaveTextContent(
      'Expected finish: not available',
    );
  });

  it('shows the clamped and fallback flags the preview sets', async () => {
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

    expect(await screen.findByTestId(`solar-today-${NIGHTLY}`)).toHaveTextContent(
      'clamped to 00:05 UK',
    );
    expect(screen.getByTestId(`solar-tomorrow-${NIGHTLY}`)).toHaveTextContent(
      'Stored cron used: sunrise did not occur',
    );
    // the day with no sunrise has no event time to show
    expect(screen.getByTestId(`solar-tomorrow-${NIGHTLY}`)).toHaveTextContent('n/a');
    expect(screen.getByTestId(`solar-today-${NIGHTLY}`)).toHaveTextContent('00:05 UK');
  });

  it('states the catch-up windows and the safety budget as notes', async () => {
    mount([job()]);

    const notes = await screen.findByTestId('solar-notes');
    expect(notes).toHaveTextContent('before 05:00 UK');
    expect(notes).toHaveTextContent('90 minutes before sunset');
    expect(notes).toHaveTextContent('3 h 30 min');
  });

  it('keeps the job cards usable when the preview request fails', async () => {
    mount([job()], null);

    expect(await screen.findByTestId(`scheduler-job-${NIGHTLY}`)).toBeInTheDocument();
    expect(await screen.findByTestId('solar-panel-unavailable')).toBeInTheDocument();
  });
});

function SOLAR_JOB_INTRADAY() {
  return job({
    id: 2,
    jobKey: INTRADAY,
    displayName: 'Intraday refresh',
    scheduleType: 'SOLAR_OFFSET',
    solarEvent: 'SUNSET',
    solarOffsetMinutes: 240,
  });
}
