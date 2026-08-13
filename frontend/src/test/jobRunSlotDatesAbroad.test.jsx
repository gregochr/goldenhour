/**
 * The admin run dialog's slot list, read from a device that is NOT in the UK.
 *
 * <p>These slots are not labels. The un-ticked ones travel to
 * {@code POST /api/forecast/run/very-short-term} as {@code excludedSlots}, and the backend matches
 * them against dates it derived from {@code ForecastHorizon.today} — the {@code Europe/London}
 * civil date. A date computed on any other calendar is a wrong value on the wire: the slot the
 * admin meant to skip is not excluded and runs anyway, at full Claude cost.
 *
 * <p>⚠️ Pinned to {@code America/New_York} on purpose, and the choice does real work. Two wrong
 * answers exist here and one instant separates the right one from both: at 23:30 UTC in August the
 * UK is already on the next day while UTC (the basis this dialog used) and New York (the basis a
 * well-meaning {@code toLocaleDateString()} rewrite would introduce) are both still on the
 * previous one. Under a {@code Europe/London} pin the browser basis and the UK basis are the same
 * string all year and half of that coverage disappears; under the suite's default UTC pin the
 * device basis and the UTC basis are the same string and the other half does. Do not "harmonise"
 * this file with either.
 *
 * <p>The zone does a second job here. "Has this event already happened?" is answered against real
 * solar event times, which arrive as bare {@code LocalDateTime} strings — and a bare string read as
 * the device's local time is a four-hour error on this zone, far wider than the 25 minutes between
 * the first and last UK location to see the sun set. Under the suite's UTC pin that same misreading
 * is a no-op, so these assertions would pass on the broken form.
 */
process.env.TZ = 'America/New_York';

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import JobRunsMetricsView from '../components/JobRunsMetricsView.jsx';
import { ukDateStr } from '../utils/mapDates.js';

// ── Mocks ────────────────────────────────────────────────────────────────

vi.mock('../api/batchApi', () => ({
  getRegions: vi.fn(),
  submitScheduledBatch: vi.fn(),
  submitJfdiBatch: vi.fn(),
}));

vi.mock('../api/metricsApi', () => ({
  getJobRuns: vi.fn(),
  getApiCalls: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  runVeryShortTermForecast: vi.fn(),
  runShortTermForecast: vi.fn(),
  runLongTermForecast: vi.fn(),
  refreshTideData: vi.fn(),
  backfillTideData: vi.fn(),
  fetchLocations: vi.fn(),
}));

vi.mock('../api/auroraApi', () => ({
  enrichBortle: vi.fn(),
}));

vi.mock('../api/briefingApi.js', () => ({
  runBriefing: vi.fn(),
  getDailyBriefing: vi.fn(),
}));

vi.mock('../api/modelsApi', () => ({
  getAvailableModels: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ isAdmin: true, role: 'ADMIN' }),
}));

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: null, loading: false }),
}));

import { getRegions } from '../api/batchApi';
import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { fetchLocations, runVeryShortTermForecast, runShortTermForecast } from '../api/forecastApi';
import { getAvailableModels } from '../api/modelsApi';
import { getDailyBriefing } from '../api/briefingApi.js';

// ── Instants ─────────────────────────────────────────────────────────────

/**
 * 00:30 on 14 August 2026, UK time (BST). The headline instant: UTC and New York are both still on
 * the 13th, so the UK calendar is the only one of the three that reads 2026-08-14.
 */
const UK_SMALL_HOURS = '2026-08-13T23:30:00Z';

/** 15:00 on 13 August, UK time — an ordinary afternoon, when all three calendars agree. */
const UK_AFTERNOON = '2026-08-13T14:00:00Z';

/** 23:30 UK on the 13th — past every UK sunset that day, but not into the 14th. */
const UK_LATE_EVENING = '2026-08-13T22:30:00Z';

/**
 * A briefing carrying real solar event times for the two days the VST dialog offers.
 *
 * <p>The two regions on the 13th are the point: the Lakes set at 20:30 UK and Ayrshire at 20:55,
 * and the dialog must wait for the **later** one. Times are bare `LocalDateTime` strings because
 * that is what the backend serialises, and reading them as anything but UTC is a four-hour error
 * on this file's zone.
 */
const BRIEFING = {
  days: [
    {
      date: '2026-08-13',
      eventSummaries: [
        {
          targetType: 'SUNRISE',
          regions: [{ slots: [{ solarEventTime: '2026-08-13T04:40:00' }] }],
        },
        {
          targetType: 'SUNSET',
          regions: [
            { slots: [{ solarEventTime: '2026-08-13T19:30:00' }] }, // 20:30 UK — the Lakes
            { slots: [{ solarEventTime: '2026-08-13T19:55:00' }] }, // 20:55 UK — Ayrshire
          ],
        },
      ],
    },
    {
      date: '2026-08-14',
      eventSummaries: [
        { targetType: 'SUNRISE', regions: [{ slots: [{ solarEventTime: '2026-08-14T04:42:00' }] }] },
        { targetType: 'SUNSET', regions: [{ slots: [{ solarEventTime: '2026-08-14T19:53:00' }] }] },
      ],
    },
  ],
};

const noop = () => {};

function freeze(iso) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

function setupDefaultMocks() {
  getRegions.mockResolvedValue([]);
  getJobRuns.mockResolvedValue({ data: { content: [] } });
  getApiCalls.mockResolvedValue({ data: [] });
  fetchLocations.mockResolvedValue([]);
  getAvailableModels.mockResolvedValue({ optimisationStrategies: {} });
  runVeryShortTermForecast.mockResolvedValue({ status: 'Forecast run started', jobRunId: 1 });
  runShortTermForecast.mockResolvedValue({ status: 'Forecast run started', jobRunId: 2 });
  getDailyBriefing.mockResolvedValue(BRIEFING);
}

/**
 * Renders the view and opens a run dialog's slot list.
 *
 * <p>⚠️ Returning here proves the slot list is on screen and NOTHING about the solar times. The
 * dates are pure (`computeSlots`) and render immediately; the past-marking comes from
 * `getDailyBriefing` and lands later. Awaiting the button or the list and then asserting
 * `toBeDisabled` synchronously is the un-gated wait this suite has been bitten by before — it would
 * pass on a quiet machine and fail under load. Every past-marking assertion therefore goes through
 * `waitFor`/`findBy`, and the "nothing is past" cases assert a state that a late briefing could
 * only move AWAY from, so they cannot pass for the wrong reason.
 */
async function openSlots(buttonTestId) {
  render(
    <JobRunsMetricsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} />,
  );
  fireEvent.click(await screen.findByTestId(buttonTestId));
  return screen.findByTestId('confirm-dialog-slots');
}

beforeEach(() => {
  vi.clearAllMocks();
  setupDefaultMocks();
});

afterEach(() => {
  vi.useRealTimers();
});

// ── The fixture itself ───────────────────────────────────────────────────

describe('the zone fixture itself', () => {
  it('is actually pinned to New York, not merely inheriting the suite default', () => {
    // Asserted on the zone rather than on a date, because the headline instant CANNOT tell the two
    // pins apart: at 23:30 UTC New York and UTC are both on the 13th, so every date assertion in
    // this file passes unchanged if `setup.js`'s TZ=UTC ever wins. Without this the file would
    // silently decay into a UTC-pinned duplicate — covering half of what its header claims — which
    // is the exact failure `mapDatesAbroad.test.js` guards against by other means.
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/New_York');
  });

  it('separates the device calendar from UTC as well as from the UK', () => {
    // The other half. This instant is chosen so all three differ in a way no single instant can:
    // New York is still on the 13th while UTC and the UK have both reached the 14th.
    freeze('2026-08-14T02:00:00Z');

    expect(new Date().toLocaleDateString('en-CA')).toBe('2026-08-13'); // the device
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-08-14'); // UTC
    expect(ukDateStr()).toBe('2026-08-14'); // the one the wire is keyed to
  });

  it('really is on an instant where the UK disagrees with both wrong calendars', () => {
    // Guards the guard. If the instant ever stopped separating the three, every assertion below
    // would keep passing while proving nothing, so the disagreement is asserted outright.
    freeze(UK_SMALL_HOURS);

    expect(new Date().toLocaleDateString('en-CA')).toBe('2026-08-13'); // the device
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-08-13'); // UTC
    expect(ukDateStr()).toBe('2026-08-14'); // the one the wire is keyed to
  });
});

// ── Dates ────────────────────────────────────────────────────────────────

describe('slot dates in the hour after UK midnight', () => {
  it('offers the UK day, not the device day and not the UTC day', async () => {
    freeze(UK_SMALL_HOURS);
    const list = await openSlots('run-very-short-term-btn');

    // Asserted as the whole ordered set rather than as three lookups. A single "the 14th is here"
    // check passes on a UTC base date too — the 14th is merely demoted to the second row — so only
    // the set says which day the run STARTS on, which is the thing on the wire.
    expect(within(list).getAllByRole('checkbox').map((c) => c.id)).toEqual([
      'slot-2026-08-14-SUNRISE',
      'slot-2026-08-14-SUNSET',
      'slot-2026-08-15-SUNRISE',
      'slot-2026-08-15-SUNSET',
    ]);
  });

  it('sends the UK date on the wire when a slot is un-ticked', async () => {
    // The whole point of the file. Everything above is a label; this is a value the backend
    // matches against a Europe/London date, and getting it wrong runs the slot the admin skipped.
    freeze(UK_SMALL_HOURS);
    await openSlots('run-very-short-term-btn');

    // Precondition, not decoration: on a UTC base date the 14th still exists as TOMORROW, so
    // un-ticking "the 14th" would produce this exact payload from the wrong row. Pinning the
    // absence of the 13th is what makes the click below unambiguously today's sunrise.
    expect(screen.queryByTestId('slot-2026-08-13-SUNRISE')).toBeNull();

    const sunrise = screen.getByTestId('slot-2026-08-14-SUNRISE');
    expect(sunrise).toHaveAccessibleName('🌅 Sunrise');
    fireEvent.click(sunrise);
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(runVeryShortTermForecast).toHaveBeenCalledWith(
      [{ date: '2026-08-14', targetType: 'SUNRISE' }],
      [],
    );
  });

  it('labels the UK day "Today" and dates the rest from it', async () => {
    // The third row is the discriminator: its label is formatted from the date string rather than
    // from the index, so a UTC base date would print "Sat 15 Aug" here instead.
    freeze(UK_SMALL_HOURS);
    await openSlots('run-short-term-btn');

    expect(screen.getByText('Today')).toBeInTheDocument();
    expect(screen.getByText('Tomorrow')).toBeInTheDocument();
    expect(screen.getByText('Sun 16 Aug')).toBeInTheDocument();
    expect(screen.queryByText('Sat 15 Aug')).toBeNull();
  });

  it('keeps the short-term run three UK days wide', async () => {
    freeze(UK_SMALL_HOURS);
    await openSlots('run-short-term-btn');

    expect(screen.getByTestId('slot-2026-08-16-SUNSET')).toBeInTheDocument();
    expect(screen.queryByTestId('slot-2026-08-17-SUNSET')).toBeNull();
  });
});

// ── The "has it passed?" hour ────────────────────────────────────────────

describe('whether a slot has already happened', () => {
  it('marks nothing past while the solar times are still unknown', async () => {
    // The degrade path, and the reason it is the DEFAULT rather than a fallback: a slot marked
    // past is disabled and unticked, so over-claiming costs an admin a slot they can neither run
    // knowingly nor deselect. Under-claiming costs nothing — the backend's own already-past gate
    // skips a finished event regardless.
    getDailyBriefing.mockResolvedValue(null);
    freeze(UK_AFTERNOON); // 15:00 UK, hours after sunrise
    await openSlots('run-very-short-term-btn');

    expect(await screen.findByTestId('slot-2026-08-13-SUNRISE')).toBeEnabled();
    expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeChecked();
  });

  it('marks a slot past once its event time has gone by', async () => {
    freeze(UK_AFTERNOON); // 15:00 UK; sunrise was 05:40, sunset is not until 20:30
    await openSlots('run-very-short-term-btn');

    await waitFor(() => expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeDisabled());
    expect(screen.getByTestId('slot-2026-08-13-SUNSET')).toBeEnabled();
  });

  it('never marks a later day past, however late today is', async () => {
    freeze(UK_LATE_EVENING); // 23:30 UK — past every UK sunset on the 13th
    await openSlots('run-very-short-term-btn');

    await waitFor(() => expect(screen.getByTestId('slot-2026-08-13-SUNSET')).toBeDisabled());
    expect(screen.getByTestId('slot-2026-08-14-SUNSET')).toBeEnabled();
    expect(screen.getByTestId('slot-2026-08-14-SUNSET')).toBeChecked();
  });

  it('waits for the LATEST location, not the first to set', async () => {
    // The defect a fixed hour could never avoid and the briefing summary field would reintroduce:
    // at 20:35 UK the Lakes have set (20:30) and Ayrshire has not (20:55). Marking the slot past
    // here would strand a run the admin still wanted.
    freeze('2026-08-13T19:35:00Z'); // 20:35 UK
    await openSlots('run-very-short-term-btn');

    // Gated on the sunrise, which IS long past at this hour. Asserting the sunset's enabled state
    // on its own would pass just as well if the briefing never arrived — the absence of a claim
    // and the correct claim look identical, so the wait has to prove the data landed first.
    await waitFor(() => expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeDisabled());
    expect(screen.getByTestId('slot-2026-08-13-SUNSET')).toBeEnabled();
  });

  it('marks it past once even the latest location has set', async () => {
    freeze('2026-08-13T19:56:00Z'); // 20:56 UK, one minute after Ayrshire

    await openSlots('run-very-short-term-btn');

    await waitFor(() => expect(screen.getByTestId('slot-2026-08-13-SUNSET')).toBeDisabled());
  });

  it('marks a slot past when the briefing lands after the dialog was opened', async () => {
    // Why `isPast` is derived at render rather than baked into the slot list: an admin fast enough
    // to click Run before the briefing responds would otherwise get a dialog that never marks
    // anything past, permanently and silently.
    let release;
    getDailyBriefing.mockReturnValue(new Promise((resolve) => { release = resolve; }));
    freeze(UK_AFTERNOON);
    await openSlots('run-very-short-term-btn');
    expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeEnabled();

    release(BRIEFING);

    await waitFor(() => expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeDisabled());
  });

  it('names a past slot as past in its accessible name', async () => {
    freeze(UK_AFTERNOON);
    await openSlots('run-very-short-term-btn');

    const sunrise = await screen.findByTestId('slot-2026-08-13-SUNRISE');
    await waitFor(() => expect(sunrise).toBeDisabled());
    // Run together because the "(past)" marker is an inline sibling with no whitespace between it
    // and the label — the gap on screen is flex `gap-1.5`, which contributes nothing to the name.
    // Pre-existing markup, asserted as it really reads rather than as it looks.
    expect(sunrise).toHaveAccessibleName('🌅 Sunrise(past)');
  });

  it('never puts a past slot on the wire', async () => {
    // A past slot stays `selected` underneath and renders unticked, so `!s.selected` cannot carry
    // it into the exclusions. Confirming with nothing touched must send nothing.
    freeze(UK_AFTERNOON);
    await openSlots('run-very-short-term-btn');
    await waitFor(() => expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeDisabled());

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(runVeryShortTermForecast).toHaveBeenCalledWith([], []);
  });

  it('leaves the new UK day\'s slots selectable at 00:30, when the UTC hour says 23', async () => {
    // The date fix and the past test agreeing: at 00:30 UK the day is the 14th and none of its
    // events have happened, so nothing is past even though the UTC hour is 23.
    freeze(UK_SMALL_HOURS);
    await openSlots('run-very-short-term-btn');

    const sunrise = await screen.findByTestId('slot-2026-08-14-SUNRISE');
    expect(sunrise).toBeEnabled();
    expect(sunrise).toBeChecked();
    expect(screen.getByTestId('slot-2026-08-14-SUNSET')).toBeEnabled();
  });
});

// ── The dialog left open across UK midnight ──────────────────────────────

describe('a dialog whose slots have gone stale', () => {
  /** Opens the dialog just before UK midnight, then moves the clock past it. */
  async function openBeforeMidnightThenRollOver() {
    freeze('2026-08-13T22:55:00Z'); // 23:55 UK on the 13th
    await openSlots('run-very-short-term-btn');
    expect(screen.getByTestId('slot-2026-08-13-SUNSET')).toBeInTheDocument();
    vi.setSystemTime(new Date('2026-08-13T23:05:00Z')); // 00:05 UK on the 14th
  }

  it('submits nothing when the UK day rolled over while it sat open', async () => {
    // The slots are a snapshot from the moment the dialog opened. Sending them after midnight
    // would put 2026-08-13 on the wire against a run the backend builds for 2026-08-14 — the same
    // wrong value the UK calendar fix exists to prevent, reached by dwell time instead of by BST.
    await openBeforeMidnightThenRollOver();

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(runVeryShortTermForecast).not.toHaveBeenCalled();
  });

  it('rebuilds the slots for the new UK day and says so', async () => {
    // Rebuilt rather than remapped: an admin who un-ticked the row labelled "Today" may have meant
    // the day or the date, and the rows carry both kinds of label, so the only honest move is to
    // show the new set and ask again.
    await openBeforeMidnightThenRollOver();

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(await screen.findByTestId('slot-dates-rolled-warning')).toHaveTextContent(
      /past midnight in the UK/,
    );
    expect(screen.getByTestId('slot-2026-08-14-SUNSET')).toBeInTheDocument();
    expect(screen.queryByTestId('slot-2026-08-13-SUNSET')).toBeNull();
  });

  it('submits the new day on a second confirm', async () => {
    await openBeforeMidnightThenRollOver();
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    await screen.findByTestId('slot-dates-rolled-warning');

    fireEvent.click(screen.getByTestId('slot-2026-08-14-SUNRISE'));
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(runVeryShortTermForecast).toHaveBeenCalledWith(
      [{ date: '2026-08-14', targetType: 'SUNRISE' }],
      [],
    );
  });

  it('does not fire the guard on an ordinary same-day confirm', async () => {
    // The guard must be inert in the 99.99% case, or it turns every run into two clicks.
    freeze(UK_AFTERNOON);
    await openSlots('run-very-short-term-btn');

    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(screen.queryByTestId('slot-dates-rolled-warning')).toBeNull();
    expect(runVeryShortTermForecast).toHaveBeenCalledTimes(1);
  });
});
