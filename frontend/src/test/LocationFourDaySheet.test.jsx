import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import LocationFourDaySheet from '../components/LocationFourDaySheet.jsx';
import { buildScoreIndex, buildSlotIndex } from '../utils/locationSheet.js';
import { spotBadgeStyle } from '../utils/windowFirstSpots.js';

/**
 * The four-day location sheet, rendered (plan D10, P8).
 *
 * <p><b>What breaks if these fail:</b> the sheet stops naming the day a wrapped departure falls on;
 * the confidence channel starts dimming the star it is forbidden to touch; the map action goes
 * missing on the emptiest sheets; a travel day reads like a pipeline gap; or our own unfetched
 * ratings request is reported as a complete picture of an empty forecast.
 *
 * <p>The derivation itself is pinned in `locationSheet.test.js` and its day words in
 * `locationSheetAbroad.test.js`; this file asserts what reaches the screen, through role contracts
 * wherever there is one — every row header is a real `aria-expanded` disclosure and the footer
 * action is a real button.
 */

const TODAY = '2026-08-14';
const SPOT = { id: 7, name: 'Bamburgh', regionName: 'Northumberland' };

const WINDOWS = [
  { key: '2026-08-14:SUNSET', date: '2026-08-14', targetType: 'SUNSET', dow: 'Fri', sunrise: false, label: 'Tonight Sunset', time: '20:37', verdictLabel: 'Worth it', confidence: 'high', away: false },
  { key: '2026-08-15:SUNRISE', date: '2026-08-15', targetType: 'SUNRISE', dow: 'Sat', sunrise: true, label: 'Tomorrow Sunrise', time: '05:38', verdictLabel: 'Maybe', confidence: 'high', away: false },
  { key: '2026-08-15:SUNSET', date: '2026-08-15', targetType: 'SUNSET', dow: 'Sat', sunrise: false, label: 'Tomorrow Sunset', time: '20:35', verdictLabel: 'Poor', confidence: 'high', away: false },
  { key: '2026-08-16:SUNRISE', date: '2026-08-16', targetType: 'SUNRISE', dow: 'Sun', sunrise: true, label: 'Sun Sunrise', time: '05:40', verdictLabel: 'Not forecast', confidence: null, away: true },
];

const SCORES = buildScoreIndex([
  { locationId: 7, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 3, summary: 'High cloud thins after eight.' },
  { locationId: 7, locationName: 'Bamburgh', date: '2026-08-15', targetType: 'SUNRISE', rating: 5, summary: 'A clear eastern horizon under mid cloud.' },
  { locationId: 7, locationName: 'Bamburgh', date: '2026-08-15', targetType: 'SUNSET', rating: 2, summary: 'Blanket low cloud to the west.' },
]);

/** Northumberland reads MEDIUM on the Friday and LOW on the Saturday — never the card's `high`. */
const SLOTS = buildSlotIndex([
  {
    date: '2026-08-14',
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: 'Northumberland',
        confidence: 'medium',
        slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-14T19:41:00' }],
      }],
    }],
  },
  {
    date: '2026-08-15',
    eventSummaries: [
      {
        targetType: 'SUNRISE',
        regions: [{
          regionName: 'Northumberland',
          confidence: 'medium',
          slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-15T04:38:00' }],
        }],
      },
      {
        targetType: 'SUNSET',
        regions: [{
          regionName: 'Northumberland',
          confidence: 'low',
          slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-15T19:39:00' }],
        }],
      },
    ],
  },
]);

const setup = (props = {}) => {
  const handlers = { onClose: vi.fn(), onShowOnMap: vi.fn() };
  render(
    <LocationFourDaySheet
      spot={SPOT}
      windows={WINDOWS}
      scoreIndex={SCORES}
      slotIndex={SLOTS}
      scoresKnown
      reachById={new Map([[7, { driveMinutes: 66 }]])}
      scopeRegionNames={['Northumberland']}
      originLabel="Newcastle"
      todayStr={TODAY}
      {...handlers}
      {...props}
    />,
  );
  return handlers;
};

/**
 * The row whose window key is given — rows carry it, so a test never counts positions.
 *
 * <p>Through the rendered test-ids rather than a `container.querySelector`, which the standards ban:
 * a query that reaches into the DOM stops describing the UI and starts describing the markup.
 */
const row = (key) => screen.getAllByTestId('location-sheet-row')
  .find((el) => el.dataset.window === key);

describe('LocationFourDaySheet', () => {
  it('names the place, its region and the journey it measured', () => {
    setup();
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Bamburgh');
    // ⚠️ The origin is NAMED. Under an away origin two bases are in play (the lens bar's and the
    // reader's home), so a bare drive figure is a number they cannot place against it.
    expect(screen.getByTestId('location-sheet-meta'))
      .toHaveTextContent('Northumberland · 1h 6min from Newcastle');
  });

  it('⚠️ counts the windows it actually renders in its accessible name', () => {
    // `heatStripCards` shortens as the day burns down and is uncapped on the degrade path, so "the
    // next six windows" was a number the payload does not guarantee — and it is this dialog's
    // ENTIRE accessible name (an `aria-label` replaces content), invisible to any sighted check.
    setup();
    expect(screen.getByRole('dialog', { name: 'Bamburgh — the next 4 windows' })).toBeInTheDocument();
  });

  it('singularises that name for a one-window sheet', () => {
    setup({ windows: [WINDOWS[0]] });
    expect(screen.getByRole('dialog', { name: 'Bamburgh — the next 1 window' })).toBeInTheDocument();
  });

  it('renders one row per window, naming its day, event and own time', () => {
    setup();
    expect(screen.getAllByTestId('location-sheet-row')).toHaveLength(4);
    const first = within(row('2026-08-14:SUNSET'));
    expect(first.getByTestId('location-sheet-rating')).toHaveTextContent('3★');
    // The DATE as well as the weekday in the accessible name: the date box is `aria-hidden`, and
    // 2.5.3 requires visible label text to be in the name for speech input to match it. And the
    // time is the LOCATION's own 20:41, never the window header's 20:37.
    expect(first.getByRole('button')).toHaveAccessibleName(/Fri 14.*Sunset.*20:41/s);
    expect(first.getByRole('button')).not.toHaveAccessibleName(/20:37/);
  });

  it('spells the rating out for a screen reader rather than leaving a bare glyph', () => {
    // NVDA at its default symbol level does not speak U+2605, so the row's most decision-relevant
    // datum would be announced as a bare integer beside a clock time. `HeatmapGrid` does the same.
    setup();
    expect(within(row('2026-08-14:SUNSET')).getByRole('button'))
      .toHaveAccessibleName(/3 stars/);
  });

  it('counts strong windows with no denominator', () => {
    // ⚠️ Deliberately not "1 of 3 scored windows": §6's sweep bans counts of our own data, and a
    // count of evaluation rows is exactly that.
    setup();
    expect(screen.getByTestId('location-sheet-lead'))
      .toHaveTextContent('The next 3 days here · 1 window at 4★+');
  });

  it('opens on the best window and marks it, with the others closed', () => {
    setup();
    const best = row('2026-08-15:SUNRISE');
    expect(within(best).getByTestId('location-sheet-best')).toHaveTextContent('best here');
    expect(within(best).getByRole('button')).toHaveAttribute('aria-expanded', 'true');
    expect(within(best).getByTestId('location-sheet-why'))
      .toHaveTextContent('A clear eastern horizon under mid cloud.');
    // Closed rows keep their body mounted so `aria-controls` never points at nothing — hidden,
    // not absent.
    const other = row('2026-08-14:SUNSET');
    expect(within(other).getByRole('button')).toHaveAttribute('aria-expanded', 'false');
    expect(within(other).getByTestId('location-sheet-body')).not.toBeVisible();
  });

  it('opens the only rated row when there is no best to name', () => {
    // The fallback arm: fewer than two rated windows means no "best here", and the sheet must still
    // not arrive fully closed while it has something to say.
    setup({
      scoreIndex: buildScoreIndex([
        { locationId: 7, date: '2026-08-15', targetType: 'SUNSET', rating: 2, summary: 'One read.' },
      ]),
    });
    expect(screen.queryByTestId('location-sheet-best')).toBeNull();
    expect(within(row('2026-08-15:SUNSET')).getByRole('button'))
      .toHaveAttribute('aria-expanded', 'true');
  });

  it('arrives fully closed only when nothing is rated at all', () => {
    setup({ scoreIndex: null });
    for (const el of screen.getAllByTestId('location-sheet-row-toggle')) {
      expect(el).toHaveAttribute('aria-expanded', 'false');
    }
  });

  it('⚠️ seeds the open row when the ratings arrive AFTER the sheet mounts', () => {
    // The ratings come over their own fetch, so a sheet opened from a search result that landed
    // first mounts with nothing rated. A bare `useState` initialiser seeded an empty set and never
    // re-ran, leaving a `◎ best here` tag and a lead line above rows that were all still closed.
    const { rerender } = renderSheet({ scoreIndex: null });
    expect(within(row('2026-08-15:SUNRISE')).getByRole('button'))
      .toHaveAttribute('aria-expanded', 'false');
    rerender(sheetElement({ scoreIndex: SCORES }));
    expect(within(row('2026-08-15:SUNRISE')).getByRole('button'))
      .toHaveAttribute('aria-expanded', 'true');
  });

  it('does not re-open a row the reader has closed', () => {
    // The other half of the seeding rule: it happens ONCE. A poll must never reach in and undo a
    // gesture the reader made.
    setup();
    const toggle = within(row('2026-08-15:SUNRISE')).getByRole('button');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(within(row('2026-08-14:SUNSET')).getByRole('button'));
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('toggles a row open and closed', () => {
    setup();
    const toggle = within(row('2026-08-14:SUNSET')).getByRole('button');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(within(row('2026-08-14:SUNSET')).getByTestId('location-sheet-body')).toBeVisible();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('prints a bare clock time for an ordinary departure', () => {
    setup();
    // Bamburgh's own 20:41 BST sunset − 1h6 − 20 min = 19:15, same day, so no day word.
    const leave = within(row('2026-08-14:SUNSET')).getByTestId('location-sheet-leave');
    expect(leave).toHaveTextContent('leave 19:15 · 1h 6min');
    expect(screen.queryAllByTestId('location-sheet-leave-day')).toHaveLength(0);
  });

  it('⚠️ names the DAY when the departure wraps past UK midnight', () => {
    // The rule `leaveBy`'s Javadoc named in P7 as the one this surface reopens: search matches the
    // whole roster, so a base-measured drive can be hours. 05:38 BST − 5h30 − 20 min = 23:48 on the
    // Friday, for a Saturday sunrise. Without the marker the reader sets an alarm for the wrong
    // night.
    setup({ reachById: new Map([[7, { driveMinutes: 330 }]]) });
    const leave = within(row('2026-08-15:SUNRISE')).getByTestId('location-sheet-leave');
    expect(within(leave).getByTestId('location-sheet-leave-day')).toHaveTextContent('Fri');
    expect(leave).toHaveTextContent('leave Fri 23:48');
    // Only the wrapping row is marked — the Friday sunset's own departure stays on its own day.
    expect(screen.getAllByTestId('location-sheet-leave-day')).toHaveLength(1);
  });

  it('prints no departure line at all when the drive is unknown', () => {
    // Plan §2.5: absence means "unknown", never "out of reach" — the normal state for a reader with
    // no home postcode, and never a guess.
    setup({ reachById: new Map() });
    expect(screen.queryAllByTestId('location-sheet-leave')).toHaveLength(0);
    expect(screen.getByTestId('location-sheet-meta')).toHaveTextContent('Northumberland');
    expect(screen.getByTestId('location-sheet-meta')).not.toHaveTextContent('from Newcastle');
  });

  it('says an away day was never forecast, and says it impersonally', () => {
    // ⚠️ No "you". A travel day is the OPERATOR'S, not the reader's, and the arm is scrupulously
    // impersonal about it everywhere else. A pilot reader sitting at home on that Sunday would
    // otherwise conclude the app holds a travel calendar of theirs.
    setup();
    const away = within(row('2026-08-16:SUNRISE'));
    expect(away.getByTestId('location-sheet-state')).toHaveTextContent('Not forecast');
    expect(away.getByTestId('location-sheet-nowhy'))
      .toHaveTextContent('Nothing was forecast for this day — away.');
    expect(away.getByTestId('location-sheet-nowhy')).not.toHaveTextContent('you');
    expect(away.queryByTestId('location-sheet-leave')).toBeNull();
  });

  it('says an unscored forecast window is something different — nothing has looked YET', () => {
    // Collapsing the two onto one word would make a travel day read like a pipeline gap, and a
    // pipeline gap read like a holiday.
    setup({ scoreIndex: null });
    const unscored = within(row('2026-08-14:SUNSET'));
    expect(unscored.getByTestId('location-sheet-state')).toHaveTextContent('Not scored yet');
    expect(unscored.getByTestId('location-sheet-nowhy'))
      .toHaveTextContent('No read for this window yet.');
  });

  it('⚠️ claims NOTHING about the pipeline while the ratings are unknown', () => {
    // An in-flight or failed request is not evidence that nothing was rated — `scoresLoaded`'s own
    // rule. Without this the sheet reports our own network failure as a confident, complete picture
    // of an empty forecast, permanently for the session if the request 500s.
    setup({ scoreIndex: null, scoresKnown: false });
    const row0 = within(row('2026-08-14:SUNSET'));
    expect(row0.getByTestId('location-sheet-state')).toHaveTextContent('Loading ratings…');
    expect(row0.getByTestId('location-sheet-nowhy')).toHaveTextContent('Ratings are still loading.');
    expect(screen.queryByTestId('location-sheet-lead')).toBeNull();
  });

  it('⚠️ marks low confidence and leaves the star exactly as the ramp painted it', () => {
    // CLAUDE.md: the confidence channel NEVER touches the quality signal. Asserted against
    // `spotBadgeStyle`'s own answer rather than "background is not empty", which passes for any
    // non-empty fill — including a greyed or desaturated one.
    setup();
    const low = within(row('2026-08-15:SUNSET'));
    expect(low.getByTestId('provisional-mark'))
      .toHaveAttribute('aria-label', 'Low confidence · provisional');
    const badge = low.getByTestId('location-sheet-rating');
    expect(badge).toHaveTextContent('2★');
    expect(badge).toHaveStyle({
      background: spotBadgeStyle(2).background, color: spotBadgeStyle(2).color,
    });
    // A Tailwind class is how a dimming regression would most plausibly arrive, and jsdom applies
    // no Tailwind — so the class list is asserted too.
    expect(badge.className).toBe('wf-loc-st font-mono');
  });

  it('⚠️ takes each row\'s confidence from the LOCATION\'S region, not the window\'s', () => {
    // Every `WINDOWS` entry says `high`; Northumberland's own is medium on the Friday and low on
    // the Saturday sunset. Reading the card's would leave the low row unmarked.
    expect(WINDOWS[2].confidence).toBe('high');
    setup();
    expect(within(row('2026-08-15:SUNSET')).getByTestId('provisional-mark')).toBeInTheDocument();
    expect(within(row('2026-08-14:SUNSET')).queryByTestId('provisional-mark')).toBeNull();
    // Medium is silent too — one quiet channel, not a per-row percentage (D3 rejects `◐ 88%`).
    expect(within(row('2026-08-15:SUNRISE')).queryByTestId('provisional-mark')).toBeNull();
  });

  it('marks no confidence on a row with no rating', () => {
    // The channel qualifies a forecast; "Not forecast · provisional" qualifies an absence.
    setup({ scoreIndex: null, windows: [{ ...WINDOWS[0], date: '2026-08-25', key: 'far:SUNSET' }] });
    expect(screen.queryByTestId('provisional-mark')).toBeNull();
  });

  it('shows no outside badge for a place inside the scope', () => {
    setup();
    expect(screen.queryByTestId('location-sheet-outside')).toBeNull();
  });

  it('⚠️ NAMES the scope the place is outside of', () => {
    // A bare "outside your plan" means two different things and only one is about distance, so a
    // near spot in another region wore the badge directly above its own short drive.
    setup({ scopeRegionNames: ['Lake District'], origin: { id: 3, name: 'Lake District' } });
    expect(screen.getByTestId('location-sheet-outside')).toHaveTextContent('outside Lake District');
  });

  it('offers the map and names the window in the strip\'s own vocabulary', () => {
    const { onShowOnMap } = setup();
    const map = screen.getByRole('button', { name: /Show on map/ });
    // "Tomorrow Sunrise", not "Sat sunrise": the strip behind the dialog says the former, and a
    // second vocabulary for one window would make the reader translate.
    expect(map).toHaveTextContent('◍ Show on map → Tomorrow Sunrise');
    fireEvent.click(map);
    expect(onShowOnMap).toHaveBeenCalledWith('2026-08-15', 'SUNRISE', 'Bamburgh');
  });

  it('still offers the map when nothing is scored', () => {
    // "the map is one tap further, never lost" — a footer that disappeared exactly when the rest of
    // the card is emptiest would be the worst moment to withhold it.
    setup({ scoreIndex: null });
    expect(screen.getByRole('button', { name: /Show on map/ })).toHaveTextContent('Tonight Sunset');
  });

  it('⚠️ says why there is no map action when there are no windows at all', () => {
    // Reachable: the roster and the briefing arrive over two independent fetches and search reads
    // the roster, so a sheet can open before there are windows. The first cut rendered a title over
    // an empty card with no footer content and no explanation.
    setup({ windows: [] });
    expect(screen.getByTestId('location-sheet-empty'))
      .toHaveTextContent('No forecast windows are loaded yet.');
    expect(screen.queryByRole('button', { name: /Show on map/ })).toBeNull();
    expect(screen.getByTestId('location-sheet-nomap'))
      .toHaveTextContent('The map opens once a forecast window loads.');
  });

  it('closes on the button and on Escape', () => {
    const { onClose } = setup();
    fireEvent.click(screen.getByTestId('location-sheet-close'));
    expect(onClose).toHaveBeenCalled();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('renders a location with no scores, no times and no reach without throwing', () => {
    // The first-run state for a reader with no postcode against a briefing still awaiting
    // evaluation — every degrade at once.
    setup({
      scoreIndex: null, slotIndex: null, reachById: null, scopeRegionNames: null, originLabel: null,
    });
    expect(screen.getAllByTestId('location-sheet-row')).toHaveLength(4);
    expect(screen.queryByTestId('location-sheet-best')).toBeNull();
    expect(screen.getByTestId('location-sheet-meta')).toHaveTextContent('Northumberland');
    // The window header's time is the fallback when the briefing carries no slot for the row.
    expect(within(row('2026-08-14:SUNSET')).getByRole('button')).toHaveAccessibleName(/20:37/);
  });
});

/** The element under test, so a re-render can hand it different props. */
function sheetElement(props = {}) {
  return (
    <LocationFourDaySheet
      spot={SPOT}
      windows={WINDOWS}
      scoreIndex={SCORES}
      slotIndex={SLOTS}
      scoresKnown
      reachById={new Map([[7, { driveMinutes: 66 }]])}
      scopeRegionNames={['Northumberland']}
      originLabel="Newcastle"
      todayStr={TODAY}
      onClose={vi.fn()}
      onShowOnMap={vi.fn()}
      {...props}
    />
  );
}

function renderSheet(props = {}) {
  return render(sheetElement(props));
}
