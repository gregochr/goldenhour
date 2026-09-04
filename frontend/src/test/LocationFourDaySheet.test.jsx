import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
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
  {
    locationId: 7, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 3,
    summary: 'High cloud thins after eight.', fierySkyPotential: 62, goldenHourPotential: 58,
    // Phase 2's four boundaries, UTC — the bare shape the backend serialises a LocalDateTime in.
    // Every date in this file is a real BST one and the suite runs on UTC, so a row printed
    // unconverted is an hour out and visibly so.
    goldenHourStart: '2026-08-14T18:57:00', goldenHourEnd: '2026-08-14T19:41:00',
    blueHourStart: '2026-08-14T19:41:00', blueHourEnd: '2026-08-14T20:26:00',
  },
  {
    locationId: 7, locationName: 'Bamburgh', date: '2026-08-15', targetType: 'SUNRISE', rating: 5,
    summary: 'A clear eastern horizon under mid cloud.', fierySkyPotential: 88, goldenHourPotential: 91,
    blueHourStart: '2026-08-15T03:52:00', blueHourEnd: '2026-08-15T04:38:00',
    goldenHourStart: '2026-08-15T04:38:00', goldenHourEnd: '2026-08-15T05:22:00',
  },
  // Rated and explained, but served no boundaries — the silence case the render must respect.
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

  it('says an unscored sunrise or sunset is something different — nothing has looked YET', () => {
    // Collapsing the two onto one word would make a travel day read like a pipeline gap, and a
    // pipeline gap read like a holiday.
    setup({ scoreIndex: null });
    const unscored = within(row('2026-08-14:SUNSET'));
    expect(unscored.getByTestId('location-sheet-state')).toHaveTextContent('Not scored yet');
    expect(unscored.getByTestId('location-sheet-nowhy'))
      .toHaveTextContent('Nothing written about this one yet.');
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
      .toHaveTextContent('No forecast loaded yet.');
    expect(screen.queryByRole('button', { name: /Show on map/ })).toBeNull();
    expect(screen.getByTestId('location-sheet-nomap'))
      .toHaveTextContent('The map opens once the forecast loads.');
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

describe('LocationFourDaySheet — the v3 anatomy (plan-matrix §6 M4.1)', () => {
  it('⚠️ never dims the row the sheet LEADS with, whatever it is rated', () => {
    // `bestKey` is a max over one location's own windows, so a place whose every window is poor
    // still has a best one — and it arrives expanded, under a gold border and an undimmed
    // `◎ best here` tag. Dimming it puts three treatments in contradiction on one row, and the
    // measured cost is the departure line at 4.38:1 where the best row's gold wash meets a hover.
    const twoStarBest = buildScoreIndex([
      { locationId: 7, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 2, summary: 'Low cloud.' },
      { locationId: 7, locationName: 'Bamburgh', date: '2026-08-15', targetType: 'SUNRISE', rating: 1, summary: 'Blanket.' },
    ]);
    setup({ scoreIndex: twoStarBest });
    // The best row IS a 2★ row here — the band edge and the exclusion in one fixture.
    expect(row('2026-08-14:SUNSET')).toHaveAttribute('data-best', 'true');
    expect(row('2026-08-14:SUNSET')).not.toHaveAttribute('data-dim');
    expect(row('2026-08-15:SUNRISE')).toHaveAttribute('data-dim', 'true');
  });

  it('⚠️ dims a 2★ row and leaves an UNRATED one alone', () => {
    // The design dims rows "at 2★ or below". Keyed on a rating that EXISTS, never on the absence of
    // one: an unrated row is one nothing has looked at, which is a different statement from a poor
    // one — the same distinction the badge draws by being omitted rather than greyed. Dimming it
    // would say "poor" in the visual channel above words that say "Not scored yet".
    setup();
    expect(row('2026-08-15:SUNSET')).toHaveAttribute('data-dim', 'true');   // 2★
    expect(row('2026-08-14:SUNSET')).not.toHaveAttribute('data-dim');       // 3★
    expect(row('2026-08-15:SUNRISE')).not.toHaveAttribute('data-dim');      // 5★
    // The away day carries no rating at all — and is the row a naive `rating <= 2` on a null gets
    // wrong, because `null <= 2` is true in JavaScript.
    expect(row('2026-08-16:SUNRISE')).not.toHaveAttribute('data-dim');
  });

  it('⚠️ dims no element that carries its own background — the badge, the date box, and the score track', () => {
    // The load-bearing half of the treatment, and the half no rendered assertion can see: `opacity`
    // on a parent cannot be undone by a child, so the exclusions are OMISSIONS from a selector list
    // and a mutation that adds either class back is invisible to jsdom (`css: false`). Both are
    // excluded for the same measured reason — each paints its own plate, so dimming the group
    // lightens the plate while it darkens the ink and the two converge: the 2★ badge falls to
    // 4.15:1 and `.wf-loc-dow` to 3.20:1, both under AA. The badge is additionally the QUALITY
    // signal, which CLAUDE.md is explicit is never dimmed by another channel.
    // ⚠️ Comments stripped FIRST. A selector capture that runs back through the block comment
    // above the rule picks up every class the comment MENTIONS — including the two this test
    // exists to prove are absent, which made the first cut fail against a correct stylesheet.
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '');
    const dimmed = [...css.matchAll(/([^{}]*)\{[^{}]*opacity[^{}]*\}/g)]
      .map(([, selector]) => selector)
      .filter((selector) => selector.includes("[data-dim='true']"));
    expect(dimmed.length).toBe(1);
    expect(dimmed[0]).toContain('.wf-loc-lv');
    expect(dimmed[0]).not.toContain('.wf-loc-st');
    expect(dimmed[0]).not.toContain('.wf-loc-day');
    // Location-sheet superset plan, Phase 1: the score bars' label row joins this SAME rule (still
    // one block — the selector list widened, not a second rule), while the coloured track
    // (`.wf-peek-bar`, painted with a solid ramp fill since Stage 5b) is excluded for the identical
    // reason the badge is — it carries its own plate, not text.
    expect(dimmed[0]).toContain('.wf-loc-score-label');
    expect(dimmed[0]).not.toContain('.wf-peek-bar');
    // Phase 2's light line joins the same rule for the same reason the prose does — plain text on
    // the body's own ground, no plate of its own to lighten as the ink darkens.
    expect(dimmed[0]).toContain('.wf-loc-light');
    // ⚠️ And its COLOUR, because the rejected alternative is a measured AA failure rather than a
    // matter of taste: `--color-plex-text-muted` is 3.53:1 on this surface and 2.73:1 once this
    // very rule dims it — under the 3:1 large-text floor, let alone AA. Membership in the dim list
    // is exactly what makes the token choice load-bearing, so the two are pinned together.
    const lightRule = css.match(/\.wf-loc-light\s*\{[^}]*\}/)[0];
    expect(lightRule).toContain('var(--color-plex-text-secondary)');
    expect(lightRule).not.toContain('--color-plex-text-muted');
  });

  it('⚠️ keeps the lead line free of a denominator, and free of uppercase TEXT', () => {
    // P8's lesson and plan Rule 5, restated for this phase: `2 windows at 4★+`, never `1 OF 6`. A
    // count of scored evaluation rows is a fact about our database, not about the sky.
    setup();
    const lead = screen.getByTestId('location-sheet-lead');
    expect(lead).toHaveTextContent('The next 3 days here · 1 window at 4★+');
    expect(lead.textContent).not.toMatch(/\bof\b/i);
    // The v3 block's uppercase is a `text-transform`, so the DOM string — and therefore what a
    // screen reader says and what speech input has to match — stays sentence case. A test that
    // accepted either casing would let the copy be shouted into the accessibility tree.
    expect(lead.textContent).toContain('The next');
  });

  it('does not dress the "nothing loaded" line as the lead kicker', () => {
    // They shared `.wf-loc-lead` until M4's restyle turned it into a gold-washed uppercase kicker.
    // A headline treatment on the one line that says nothing has arrived reads as emphasis on an
    // absence — jsdom cannot see the gold, but it can see which class carries it.
    renderSheet({ windows: [] });
    expect(screen.getByTestId('location-sheet-empty')).toHaveClass('wf-loc-note');
    expect(screen.getByTestId('location-sheet-empty')).not.toHaveClass('wf-loc-lead');
  });
});

describe('LocationFourDaySheet — the score bars (location-sheet superset plan, Phase 1)', () => {
  /**
   * The peek one layer up already shows Fiery Sky / Golden Hour bars for a spot; this sheet is the
   * deeper drill-down and must show a SUPERSET of what the peek showed, never less — the plan's
   * whole ask. `LocationFourDaySheet.test.jsx`'s existing fixtures carried no scores before this
   * phase; `SCORES` above now does, for the same two rated windows the rest of this file exercises.
   */
  it('renders both bars, above the prose, with the score row\'s own values', () => {
    setup();
    const best = within(row('2026-08-15:SUNRISE'));
    const fiery = best.getByTestId('location-sheet-fiery');
    const golden = best.getByTestId('location-sheet-golden');
    expect(fiery).toHaveTextContent('Fiery Sky');
    expect(fiery).toHaveTextContent('88');
    expect(golden).toHaveTextContent('Golden Hour');
    expect(golden).toHaveTextContent('91');
    // Order: bars before the prose, matching the peek's own order (bars, then clause).
    const body = best.getByTestId('location-sheet-body');
    const scores = within(body).getByTestId('location-sheet-scores');
    const why = within(body).getByTestId('location-sheet-why');
    expect(scores.compareDocumentPosition(why) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('renders only the bar with a value when the other axis has none', () => {
    // A window scored on one axis and not the other draws one bar, never a bar and a fabricated
    // zero — the same rule the peek's `resolveSpotPeek` already follows.
    setup({
      scoreIndex: buildScoreIndex([
        { locationId: 7, date: '2026-08-14', targetType: 'SUNSET', rating: 3, fierySkyPotential: 40 },
      ]),
    });
    const first = within(row('2026-08-14:SUNSET'));
    expect(first.getByTestId('location-sheet-fiery')).toHaveTextContent('40');
    expect(first.queryByTestId('location-sheet-golden')).toBeNull();
  });

  it('renders no score block at all when neither axis has a value', () => {
    // Silence, never synthesis (plan §3 rule 6): an empty track would assert a measurement nothing
    // produced. This row (2★, Saturday sunset) carries a rating but no bars in `SCORES` above.
    setup();
    const dim = within(row('2026-08-15:SUNSET'));
    fireEvent.click(dim.getByRole('button'));
    expect(dim.queryByTestId('location-sheet-scores')).toBeNull();
    expect(dim.queryByTestId('location-sheet-fiery')).toBeNull();
  });

  it('renders no score block on an away row, even when a stale row is scored', () => {
    // The away-gate already refuses rating and summary; the bars ride the same lookup and must
    // refuse with them — the sheet must not draw a forecast for a night nobody forecast.
    setup({
      scoreIndex: buildScoreIndex([
        { locationId: 7, date: '2026-08-16', targetType: 'SUNRISE', rating: 5, fierySkyPotential: 80, goldenHourPotential: 80 },
      ]),
    });
    const away = within(row('2026-08-16:SUNRISE'));
    fireEvent.click(away.getByRole('button'));
    expect(away.queryByTestId('location-sheet-scores')).toBeNull();
  });

  it('⚠️ keeps the bars mounted on a dimmed ≤2★ row — dimming never hides content', () => {
    // The DOM-visible half of the claim: `data-dim` on the row must not remove or gate the score
    // block, only style it (via CSS this test cannot see — the stylesheet test below owns the
    // selector-membership half: that `.wf-loc-score-label` IS in the dimmed list and the coloured
    // track is NOT). A single-window fixture with a rated window seeds itself open (the "only rated
    // row" fallback), so this row needs no click.
    setup({
      windows: [{ ...WINDOWS[2], key: 'dim-scores:SUNSET' }],
      scoreIndex: buildScoreIndex([
        { locationId: 7, date: '2026-08-15', targetType: 'SUNSET', rating: 2, fierySkyPotential: 30, goldenHourPotential: 20 },
      ]),
    });
    const dimRow = row('dim-scores:SUNSET');
    expect(dimRow).toHaveAttribute('data-dim', 'true');
    expect(within(dimRow).getByTestId('location-sheet-fiery')).toHaveTextContent('30');
    expect(within(dimRow).getByTestId('location-sheet-golden')).toHaveTextContent('20');
  });
});

describe('LocationFourDaySheet — the light line (location-sheet superset plan, Phase 2)', () => {
  /**
   * The gap the owner named from the other side: the map marker's popup has always shown golden
   * and blue hour clock times, and no Plan surface showed them at all — so the deepest Plan surface
   * could tell a reader a window was worth it without telling them when its light actually is.
   */
  it('prints both windows in UK time, ordered by the event side, between the bars and the prose', () => {
    setup();
    const best = within(row('2026-08-15:SUNRISE'));
    const light = best.getByTestId('location-sheet-light');
    // 03:52 UTC → 04:52 BST. A raw print would read 03:52 and the assertion would say so.
    expect(light).toHaveTextContent('blue 04:52–05:38 · golden 05:38–06:22');
    // Sandwiched: bars above (a measurement), prose below (a read of it).
    const body = best.getByTestId('location-sheet-body');
    const scores = within(body).getByTestId('location-sheet-scores');
    const why = within(body).getByTestId('location-sheet-why');
    expect(scores.compareDocumentPosition(light) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(light.compareDocumentPosition(why) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // ⚠️ VISIBLE, not merely mounted. The body is always in the DOM and hidden by attribute, and
    // `getByTestId`/`toHaveTextContent` both ignore `hidden` — so every assertion above would hold
    // on a row that never opens. This row is the sheet's own seeded-open best row.
    expect(light).toBeVisible();
  });

  it('⚠️ hides the line with the body on a collapsed row', () => {
    // The other half of the same claim: `hidden` is what makes the always-mounted accordion body
    // legitimate (it keeps `aria-controls` pointing at something), so the line must go with it.
    setup();
    const first = within(row('2026-08-14:SUNSET'));
    expect(first.getByTestId('location-sheet-light')).not.toBeVisible();
    fireEvent.click(first.getByRole('button'));
    expect(first.getByTestId('location-sheet-light')).toBeVisible();
  });

  it('⚠️ reverses the two for a sunset — golden first, then blue', () => {
    // Not cosmetic. At a sunrise the blue hour comes first (civil dawn to sunrise) and at a sunset
    // it comes last (sunset to civil dusk); a fixed order would be wrong on half the rows, and the
    // map popup — the surface this one is catching up with — already orders them this way.
    setup();
    const first = within(row('2026-08-14:SUNSET'));
    fireEvent.click(first.getByRole('button'));
    expect(first.getByTestId('location-sheet-light'))
      .toHaveTextContent('golden 19:57–20:41 · blue 20:41–21:26');
  });

  it('renders no light line at all for a window served without one', () => {
    // Silence, never synthesis. This row (2★, Saturday sunset) is rated and explained but carries
    // no boundaries — the sheet must not fill the gap with a neighbouring window's almanac.
    setup();
    const dim = within(row('2026-08-15:SUNSET'));
    fireEvent.click(dim.getByRole('button'));
    expect(dim.queryByTestId('location-sheet-light')).toBeNull();
    // And the row is otherwise intact — the missing line takes nothing else with it.
    expect(dim.getByTestId('location-sheet-why')).toHaveTextContent('Blanket low cloud');
  });

  it('renders no light line on an away row, even when a stale row carries one', () => {
    setup({
      scoreIndex: buildScoreIndex([
        {
          locationId: 7, date: '2026-08-16', targetType: 'SUNRISE', rating: 5,
          blueHourStart: '2026-08-16T03:55:00', blueHourEnd: '2026-08-16T04:40:00',
          goldenHourStart: '2026-08-16T04:40:00', goldenHourEnd: '2026-08-16T05:24:00',
        },
      ]),
    });
    const away = within(row('2026-08-16:SUNRISE'));
    fireEvent.click(away.getByRole('button'));
    expect(away.queryByTestId('location-sheet-light')).toBeNull();
  });

  it('⚠️ keeps the line mounted on a dimmed ≤2★ row — dimming never hides content', () => {
    // The DOM half of the claim; the stylesheet test above owns the other half (that
    // `.wf-loc-light` IS in the single dimmed selector list). A one-window sheet with a rated
    // window seeds itself open, so no click is needed.
    setup({
      windows: [{ ...WINDOWS[2], key: 'dim-light:SUNSET' }],
      scoreIndex: buildScoreIndex([
        {
          locationId: 7, date: '2026-08-15', targetType: 'SUNSET', rating: 2,
          goldenHourStart: '2026-08-15T18:55:00', goldenHourEnd: '2026-08-15T19:39:00',
          blueHourStart: '2026-08-15T19:39:00', blueHourEnd: '2026-08-15T20:24:00',
        },
      ]),
    });
    const dimRow = row('dim-light:SUNSET');
    expect(dimRow).toHaveAttribute('data-dim', 'true');
    expect(within(dimRow).getByTestId('location-sheet-light'))
      .toHaveTextContent('golden 19:55–20:39 · blue 20:39–21:24');
  });

  it('⚠️ prints one window rather than half of one when an end is missing', () => {
    // "golden 19:57–" is a claim a reader cannot act on. The blue hour here has no end, so it is
    // dropped whole; the golden one is complete and stands alone, with no separator left behind.
    setup({
      windows: [{ ...WINDOWS[0], key: 'half-light:SUNSET' }],
      scoreIndex: buildScoreIndex([
        {
          locationId: 7, date: '2026-08-14', targetType: 'SUNSET', rating: 3,
          goldenHourStart: '2026-08-14T18:57:00', goldenHourEnd: '2026-08-14T19:41:00',
          blueHourStart: '2026-08-14T19:41:00',
        },
      ]),
    });
    const light = within(row('half-light:SUNSET')).getByTestId('location-sheet-light');
    expect(light).toHaveTextContent('golden 19:57–20:41');
    expect(light.textContent).not.toContain('·');
    expect(light.textContent).not.toContain('blue');
  });
});

describe('LocationFourDaySheet — the Plan-from footer (plan-matrix §6 M4.3, D-4)', () => {
  it('⚠️ calls onClose BEFORE onPlanFrom — the ordering, not the outcome', () => {
    // D-4's whole content. P8 refused this action because moving the origin swaps the reach map and
    // the scope under an open sheet: the drive, the base named beside it, the outside badge and
    // every departure on every row would change while the reader is looking at them. Close-then-move
    // removes the condition instead of the objection — so the ORDER is the requirement, and an edit
    // that merely reached the same end state would silently lose it.
    const onClose = vi.fn();
    const onPlanFrom = vi.fn();
    renderSheet({ planFrom: { name: 'Northumberland' }, onPlanFrom, onClose });
    fireEvent.click(screen.getByRole('button', { name: 'Plan from Northumberland' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onPlanFrom).toHaveBeenCalledTimes(1);
    expect(onClose.mock.invocationCallOrder[0])
      .toBeLessThan(onPlanFrom.mock.invocationCallOrder[0]);
  });

  it('⚠️ keeps a real space between the map button\'s two text nodes', () => {
    // An `aria-hidden` span is removed from the tree entirely, so splitting `◍ Show on map → X`
    // around hidden glyph spans left no element boundary between the surviving TEXT NODES and no
    // engine inserts a space: the name computed as "Show on mapTonight Sunset", one mangled token,
    // which is the only thing a speech-input user has to say (2.5.3). So the label is ONE text
    // node with only the bullseye hidden — that is the glyph VoiceOver reads as a word mid-name; an
    // arrow between two phrases is not. Asked of the tree, exactly, because the `/Show on map/`
    // regex the other tests use passed straight through the defect.
    setup();
    expect(screen.getByTestId('location-sheet-map'))
      .toHaveAccessibleName('Show on map → Tomorrow Sunrise');
  });

  it('keeps the glyphs out of the accessible name, and the words in it in order', () => {
    // `◎` is decorative; VoiceOver says "bullseye" in the middle of the control's name otherwise —
    // the call `◎ best here` already makes one band up. What survives is every visible WORD, in
    // order, which is what WCAG 2.5.3 asks of a name.
    renderSheet({ planFrom: { name: 'Northumberland' }, onPlanFrom: vi.fn() });
    const button = screen.getByTestId('location-sheet-plan');
    expect(button).toHaveAccessibleName('Plan from Northumberland');
    expect(button.textContent).toBe('◎ Plan from Northumberland →');
  });

  it('states the reason rather than rendering a control that does nothing', () => {
    // Plan §3 rule 14. The reason is `originAction`'s, verbatim, so this dialog and the search box
    // that can open it cannot describe one region two ways.
    renderSheet({ planFrom: { name: 'Northumberland', reason: 'This region is switched off' } });
    expect(screen.queryByTestId('location-sheet-plan')).toBeNull();
    expect(screen.getByTestId('location-sheet-plan-note'))
      .toHaveTextContent('This region is switched off');
  });

  it('renders no origin action at all when the caller offers no region', () => {
    // The default, and the state every P8 caller was in — so the footer that shipped is unchanged
    // for anyone who does not opt in.
    setup();
    expect(screen.queryByTestId('location-sheet-plan')).toBeNull();
    expect(screen.queryByTestId('location-sheet-plan-note')).toBeNull();
    expect(screen.getByTestId('location-sheet-map')).toBeInTheDocument();
  });
});

describe('the sheet\'s own stylesheet block names only tokens that exist', () => {
  /**
   * ⚠️ <b>jsdom does not resolve {@code var()}</b>, so no cascade test in this project can ever see
   * an undefined token — an unresolved one renders as inherited bone under a green suite, which is
   * exactly what {@code --color-badge-marginal} did to every "Maybe" badge in M2. The set of
   * DEFINED names is a fact about a file and is readable, so this is the shape that catches it.
   *
   * <p>Sliced to this surface's own rules rather than run over the whole stylesheet: a file-wide
   * check would fail on somebody else's rule and stop being this phase's guard.
   */
  it('⚠️ resolves every var(--…) in the .wf-loc-* and .wf-mchip* rules', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
    const defined = new Set([...css.matchAll(/^\s*(--[a-z0-9-]+)\s*:/gm)].map((m) => m[1]));
    // Selector lists included, so a rule written `.wf-loc-row[data-dim='true'] .wf-loc-lv, …` is
    // caught by its first selector rather than skipped.
    const blocks = [...css.matchAll(/(^|\n)([^{}\n][^{}]*)\{([^{}]*)\}/g)]
      .filter(([, , selector]) => /\.wf-(loc|mchip)/.test(selector))
      .map(([, , , body]) => body);
    const used = new Set(blocks.flatMap(
      (body) => [...body.matchAll(/var\((--[a-z0-9-]+)\)/g)].map((m) => m[1]),
    ));
    // A cut that matched no rules would pass vacuously — the failure mode this whole shape exists
    // to refuse.
    expect(blocks.length).toBeGreaterThan(20);
    expect(used.size).toBeGreaterThan(5);
    expect([...used].filter((name) => !defined.has(name))).toEqual([]);
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
