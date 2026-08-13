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
 * <p>The hour is pinned for the same reason as the date. It answers "has today's event already
 * happened?", so it has to be read on the same clock as the date it is asked about — and the
 * thresholds below are therefore UK wall-clock hours, which through BST is not what the UTC hour
 * says.
 */
process.env.TZ = 'America/New_York';

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
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

// ── Instants ─────────────────────────────────────────────────────────────

/**
 * 00:30 on 14 August 2026, UK time (BST). The headline instant: UTC and New York are both still on
 * the 13th, so the UK calendar is the only one of the three that reads 2026-08-14.
 */
const UK_SMALL_HOURS = '2026-08-13T23:30:00Z';

/** 15:00 on 13 August, UK time — an ordinary afternoon, when all three calendars agree. */
const UK_AFTERNOON = '2026-08-13T14:00:00Z';

/** 11:59 UK, BST — one minute below the sunrise threshold. */
const UK_BST_1159 = '2026-08-13T10:59:00Z';

/** 12:00 UK, BST — the sunrise threshold exactly. */
const UK_BST_1200 = '2026-08-13T11:00:00Z';

/** 21:59 UK, BST — one minute below the sunset threshold. */
const UK_BST_2159 = '2026-08-13T20:59:00Z';

/** 22:00 UK, BST — the sunset threshold exactly, and the same instant 21:00 UTC always named. */
const UK_BST_2200 = '2026-08-13T21:00:00Z';

/** 21:30 UK in January, where GMT makes the UK hour and the UTC hour identical. */
const UK_GMT_2130 = '2026-01-15T21:30:00Z';

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
}

/**
 * Renders the view and opens a run dialog's slot list.
 *
 * <p>Waiting on the run button would be the mistake `JobRunsMetricsViewBatch.test.jsx` documents —
 * it is static markup and says nothing about any fetch. It is honest here only because the slot
 * list is not gated on a fetch either: `computeSlots` is pure, so `confirm-dialog-slots` is the
 * thing under test and awaiting it is awaiting the render that produced it.
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

describe('the past-event hour, read on the UK clock', () => {
  it('leaves the new UK day\'s sunrise selectable at 00:30, when the UTC hour says 23', async () => {
    // The mirror image of the date bug, and the reason the hour could not stay on UTC while the
    // date moved: marked past, the checkbox is disabled AND filtered out of `excluded`, so the
    // admin could neither run it knowingly nor skip it.
    freeze(UK_SMALL_HOURS);
    await openSlots('run-very-short-term-btn');

    const sunrise = screen.getByTestId('slot-2026-08-14-SUNRISE');
    expect(sunrise).toBeEnabled();
    expect(sunrise).toBeChecked();
    expect(screen.getByTestId('slot-2026-08-14-SUNSET')).toBeEnabled();
  });

  it('leaves sunrise selectable at 11:59 UK', async () => {
    freeze(UK_BST_1159);
    await openSlots('run-very-short-term-btn');

    expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeEnabled();
  });

  it('marks only TODAY\'s sunrise past at 12:00 UK, never a later day\'s', async () => {
    // The `isToday &&` term, which nothing else in this file exercises: without it every day's
    // sunrise goes past at noon, and a past slot is disabled AND dropped from `excluded`, so the
    // admin can neither run tomorrow's knowingly nor skip it. Both halves are asserted here
    // because the disabled one alone passes with the guard deleted.
    freeze(UK_BST_1200);
    await openSlots('run-very-short-term-btn');

    expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeDisabled();
    expect(screen.getByTestId('slot-2026-08-14-SUNRISE')).toBeEnabled();
    expect(screen.getByTestId('slot-2026-08-14-SUNRISE')).toBeChecked();
  });

  it('marks only TODAY\'s sunset past at 22:00 UK, never a later day\'s', async () => {
    // 22:00 UK is the same instant 21:00 UTC always named, through BST — so the threshold change
    // is inert here and this is the `isToday` guard again, on the sunset line.
    freeze(UK_BST_2200);
    await openSlots('run-very-short-term-btn');

    expect(screen.getByTestId('slot-2026-08-13-SUNSET')).toBeDisabled();
    expect(screen.getByTestId('slot-2026-08-14-SUNSET')).toBeEnabled();
    expect(screen.getByTestId('slot-2026-08-14-SUNSET')).toBeChecked();
  });

  it('names a past slot as past in its accessible name', async () => {
    freeze(UK_BST_1200);
    await openSlots('run-very-short-term-btn');

    const sunrise = screen.getByTestId('slot-2026-08-13-SUNRISE');
    expect(sunrise).toBeDisabled();
    // Run together because the "(past)" marker is an inline sibling with no whitespace between it
    // and the label — the gap on screen is flex `gap-1.5`, which contributes nothing to the name.
    // Pre-existing markup, asserted as it really reads rather than as it looks.
    expect(sunrise).toHaveAccessibleName('🌅 Sunrise(past)');
  });

  it('leaves sunset selectable at 21:59 UK, an hour a UK summer sun can still be up', async () => {
    // The threshold moved from 21 UTC to 22 UK precisely so this hour survives BST: at these
    // latitudes the sun does not set until ~21:55 in midsummer, and marking a slot past before its
    // event makes it impossible to deselect.
    freeze(UK_BST_2159);
    await openSlots('run-very-short-term-btn');

    expect(screen.getByTestId('slot-2026-08-13-SUNSET')).toBeEnabled();
  });

  it('leaves sunset selectable at 21:30 in GMT, where the old UTC threshold had already fired', async () => {
    // The one place the new threshold is deliberately later than the old one. Erring late costs
    // nothing — the slot stays selectable and the backend's own already-past gate skips a finished
    // event regardless.
    freeze(UK_GMT_2130);
    await openSlots('run-very-short-term-btn');

    expect(screen.getByTestId('slot-2026-01-15-SUNSET')).toBeEnabled();
  });

  it('never puts a past slot on the wire, so an early threshold cannot be skipped around', async () => {
    // Why the asymmetry above matters: `excluded` drops isPast slots, so a slot wrongly marked
    // past is one the admin cannot exclude. Confirming with nothing touched must send nothing.
    freeze(UK_AFTERNOON);
    await openSlots('run-very-short-term-btn');

    expect(screen.getByTestId('slot-2026-08-13-SUNRISE')).toBeDisabled();
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));

    expect(runVeryShortTermForecast).toHaveBeenCalledWith([], []);
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
